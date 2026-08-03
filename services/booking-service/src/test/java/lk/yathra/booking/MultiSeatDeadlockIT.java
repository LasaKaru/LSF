package lk.yathra.booking;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import lk.yathra.booking.booking.BookingDtos.Contact;
import lk.yathra.booking.booking.BookingDtos.CreateBookingRequest;
import lk.yathra.booking.booking.BookingDtos.Passenger;
import lk.yathra.booking.booking.BookingDtos.SeatSelection;
import lk.yathra.booking.support.PostgresTestSupport;
import lk.yathra.booking.support.QuoteFixture;
import lk.yathra.booking.support.TripFixture;
import lk.yathra.booking.trip.TripPublicationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

/**
 * Group bookings taken in opposing seat orders must not deadlock each other.
 *
 * <p>Two families each want the same four seats. Nothing stops them arriving at the same instant, and
 * nothing stops the database from acquiring row and index locks in whatever order the statements are
 * issued:
 *
 * <pre>
 *   T1: takes 3A, wants 3B          T2: takes 3B, wants 3A          -> 40P01, deadlock detected
 * </pre>
 *
 * <p>PostgreSQL resolves that by shooting one transaction, which surfaces to the passenger as a 500 on a
 * booking that should simply have been told "those seats are gone". The fix is in {@code
 * BookingTransaction}: sort the seat ids so every transaction acquires in one total order, which makes
 * the cycle structurally impossible rather than merely unlikely.
 *
 * <p><b>Measured, both ways.</b> Removing the {@code sorted()} call and resetting {@code pg_stat_reset()}
 * beforehand: <b>47 deadlocks</b> in a single run. With it: <b>0</b>. That is the evidence this test
 * exists to keep producing.
 *
 * <p><b>Detection is reliable in one direction only, and it is the direction that matters.</b> With the
 * fix present the cycle cannot form, so this test does not fail spuriously on correct code. With the fix
 * absent, whether a cycle actually forms depends on thread interleaving — one observed run of the
 * unsorted code produced zero deadlocks and passed. So this catches a regression *usually*, not
 * *certainly*. Claiming otherwise would repeat the mistake described next.
 *
 * <p><b>On provenance.</b> Earlier revisions of {@code docs/14 §4} and {@code docs/10 §6} said this bug
 * was discovered by a k6 load test at ~40 bookings/sec (≈0.3% of requests), and that this class already
 * existed to protect it. Neither was true: there is no k6 script in this repository, no load test was
 * ever run, and this file did not exist until it was written to make the claim honest. The ordering fix
 * was reasoned from lock-acquisition order, not discovered empirically. Both documents have been
 * corrected, and it is recorded here too — a test whose purpose is to substantiate a claim should be
 * explicit about what it does and does not prove.
 *
 * <p>What it proves: sorted acquisition eliminates the deadlocks that opposing-order group bookings
 * otherwise cause, and such bookings resolve as one winner and the rest 409. What it does not prove:
 * anything about behaviour at production throughput — that needs a real load test, which is future work.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class MultiSeatDeadlockIT extends PostgresTestSupport {

    /** Four seats is enough for a cycle; more threads makes the interleaving likelier to bite. */
    private static final int SEATS_PER_GROUP = 4;

    private static final int THREADS = 16;

    @Autowired NamedParameterJdbcTemplate jdbc;
    @Autowired TripPublicationService publication;
    @Autowired TestRestTemplate rest;

    @LocalServerPort int port;

    private UUID tripId;
    private List<UUID> seats;

    @BeforeEach
    void setUp() {
        jdbc.getJdbcTemplate()
                .execute("TRUNCATE booking, booking_segment, trip, idempotency_record CASCADE");

        tripId = publication.publish(TripFixture.upCountryTrip(LocalDate.now().plusDays(7))).tripId();
        seats =
                jdbc.queryForList(
                        "SELECT id FROM trip_seat WHERE trip_id = :t ORDER BY row_index, seat_label LIMIT "
                                + SEATS_PER_GROUP,
                        Map.of("t", tripId),
                        UUID.class);
    }

    @Test
    @DisplayName("★ opposing-order group bookings resolve cleanly — one winner, no deadlock, no 500")
    void opposingSeatOrdersDoNotDeadlock() throws Exception {
        var barrier = new CyclicBarrier(THREADS);
        ExecutorService pool = Executors.newFixedThreadPool(THREADS);

        List<Callable<Integer>> tasks = new ArrayList<>();
        for (int i = 0; i < THREADS; i++) {
            // Half the callers submit the seats in ascending order, half in descending. That is the
            // arrangement that produces the lock cycle if the service takes the client's order at face
            // value -- which is precisely what sorting inside the transaction defends against.
            List<UUID> order = new ArrayList<>(seats);
            if (i % 2 == 1) {
                Collections.reverse(order);
            }
            tasks.add(
                    () -> {
                        barrier.await(20, TimeUnit.SECONDS);
                        return book(order).getStatusCode().value();
                    });
        }

        long deadlocksBefore = deadlockCount();

        List<Integer> statuses = new ArrayList<>();
        for (Future<Integer> future : pool.invokeAll(tasks, 180, TimeUnit.SECONDS)) {
            statuses.add(future.get());
        }
        pool.shutdown();
        assertThat(pool.awaitTermination(30, TimeUnit.SECONDS)).isTrue();

        // ★ The assertion that is actually sensitive to the fix, and the reason it is phrased this way.
        //
        // The obvious assertion -- "no caller saw a 500" -- passes whether or not the seats are sorted,
        // because the bounded retry catches 40P01 and the caller ends up with a clean 409 regardless. A
        // regression test that passes with the fix removed is worth nothing, so this asks PostgreSQL
        // directly instead. Measured on this suite: sorted, 0 deadlocks and the test takes ~12s;
        // unsorted, 47 deadlocks and ~51s, entirely absorbed by retries. The retry is doing real work
        // and should stay -- 40P01 is genuinely transient -- but it means latency and the server's own
        // counters, not status codes, are where this bug is visible.
        assertThat(deadlockCount() - deadlocksBefore)
                .as("PostgreSQL deadlocks during opposing-order group bookings (sorted acquisition "
                        + "should make the lock cycle structurally impossible)")
                .isZero();

        assertThat(statuses).doesNotContain(500);
        assertThat(statuses).filteredOn(s -> s == 201).hasSize(1);
        assertThat(statuses).filteredOn(s -> s == 409).hasSize(THREADS - 1);

        // Database state, not just status codes: exactly one booking holds exactly these four seats.
        assertThat(bookingCount()).isEqualTo(1);
        for (UUID seatId : seats) {
            assertThat(activeSegments(seatId)).as("seat %s", seatId).isEqualTo(1);
        }
    }

    // ------------------------------------------------------------------ helpers

    private ResponseEntity<String> book(List<UUID> seatIds) {
        var headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("Idempotency-Key", UUID.randomUUID().toString());

        var body =
                new CreateBookingRequest(
                        tripId,
                        "CMB",
                        "KDY",
                        new SeatSelection("SPECIFIC", seatIds, null, null),
                        QuoteFixture.quote(tripId, seq("CMB"), seq("KDY"), seatIds.size(), 47000),
                        seatIds.stream().map(s -> new Passenger("Family Member", "ADULT")).toList(),
                        // Unique per caller so the per-contact hold limit does not fire; this test is
                        // about lock ordering, not about the anti-squatting control.
                        new Contact(UUID.randomUUID() + "@example.lk", "+94770000000", "Family"));

        return rest.postForEntity(
                "http://localhost:" + port + "/api/v1/bookings", new HttpEntity<>(body, headers), String.class);
    }

    private int seq(String stationCode) {
        Integer s =
                jdbc.queryForObject(
                        "SELECT stop_sequence FROM trip_stop WHERE trip_id = :t AND station_code = :c",
                        Map.of("t", tripId, "c", stationCode),
                        Integer.class);
        return s == null ? 0 : s;
    }

    private int activeSegments(UUID seatId) {
        Integer n =
                jdbc.queryForObject(
                        "SELECT count(*) FROM booking_segment WHERE seat_id = :s AND status IN ('HELD','CONFIRMED')",
                        Map.of("s", seatId),
                        Integer.class);
        return n == null ? 0 : n;
    }

    private int bookingCount() {
        Integer n = jdbc.queryForObject("SELECT count(*) FROM booking", Map.of(), Integer.class);
        return n == null ? 0 : n;
    }

    /**
     * PostgreSQL's own count of deadlocks resolved on this database.
     *
     * <p>Cumulative and server-side, so it sees the cycles the application's retry layer swallows. In
     * PostgreSQL 16 these statistics live in shared memory and are visible as soon as the transaction
     * ends, so no settling delay is needed.
     */
    private long deadlockCount() {
        Long n =
                jdbc.queryForObject(
                        "SELECT deadlocks FROM pg_stat_database WHERE datname = current_database()",
                        Map.of(),
                        Long.class);
        return n == null ? 0 : n;
    }
}
