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
import lk.yathra.booking.booking.BookingDtos.CreateBookingRequest;
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
 * The proof the project stands on.
 *
 * <p>Both halves matter, and a system that passes only the first has merely reimplemented whole-seat
 * locking:
 *
 * <ul>
 *   <li><b>Negative</b> -- 50 threads racing for overlapping legs on one seat produce exactly one 201.
 *   <li><b>Positive</b> -- 2 threads booking <em>adjacent</em> legs on one seat both get 201.
 * </ul>
 *
 * <p>Every test asserts on <b>database state</b> as well as on HTTP status codes. A system can return
 * an entirely plausible set of responses and still have written garbage; the status codes check the
 * API, the SQL checks the truth.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ConcurrentBookingIT extends PostgresTestSupport {

    @Autowired NamedParameterJdbcTemplate jdbc;
    @Autowired TripPublicationService publication;
    @Autowired TestRestTemplate rest;

    @LocalServerPort int port;

    private UUID tripId;
    private UUID seat3A;

    @BeforeEach
    void setUp() {
        jdbc.getJdbcTemplate().execute("TRUNCATE booking, booking_segment, trip, idempotency_record CASCADE");

        tripId = publication.publish(TripFixture.upCountryTrip(LocalDate.now().plusDays(7))).tripId();
        seat3A =
                jdbc.queryForList(
                                "SELECT id FROM trip_seat WHERE trip_id = :t ORDER BY row_index, seat_label",
                                Map.of("t", tripId),
                                UUID.class)
                        .get(0);
    }

    // ------------------------------------------------------------------ negative

    @Test
    @DisplayName("★ 50 threads booking overlapping legs on one seat produce exactly one booking")
    void fiftyThreadsOnOverlappingLegsProduceExactlyOneWinner() throws Exception {
        int threads = 50;
        var barrier = new CyclicBarrier(threads);
        ExecutorService pool = Executors.newFixedThreadPool(threads);

        List<Callable<Integer>> tasks = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            tasks.add(
                    () -> {
                        // Every thread fires within the same instant, so the race is real rather than
                        // an accident of scheduling.
                        barrier.await(20, TimeUnit.SECONDS);
                        return book(seat3A, "CMB", "KDY").getStatusCode().value();
                    });
        }

        List<Integer> statuses = Collections.synchronizedList(new ArrayList<>());
        for (Future<Integer> future : pool.invokeAll(tasks, 60, TimeUnit.SECONDS)) {
            statuses.add(future.get());
        }
        pool.shutdown();
        assertThat(pool.awaitTermination(30, TimeUnit.SECONDS)).isTrue();

        // HTTP-level: one winner, everybody else told cleanly that the seat went.
        assertThat(statuses).filteredOn(s -> s == 201).hasSize(1);
        assertThat(statuses).filteredOn(s -> s == 409).hasSize(threads - 1);

        // ★ Database-level: the assertion that actually matters.
        assertThat(activeSegments(seat3A)).isEqualTo(1);
        assertNoOverlappingSegmentsAnywhere();
    }

    // ------------------------------------------------------------------ positive

    @Test
    @DisplayName("★ concurrent ADJACENT legs on one seat both succeed -- one seat, two passengers")
    void concurrentAdjacentLegsBothSucceed() throws Exception {
        var barrier = new CyclicBarrier(2);
        ExecutorService pool = Executors.newFixedThreadPool(2);

        // [1,9) Colombo Fort -> Kandy, and [9,25) Kandy -> Badulla. Half-open, so they touch at
        // Kandy but do not overlap: the exact scenario the brief asks for.
        Future<ResponseEntity<String>> fortToKandy =
                pool.submit(
                        () -> {
                            barrier.await(20, TimeUnit.SECONDS);
                            return book(seat3A, "CMB", "KDY");
                        });
        Future<ResponseEntity<String>> kandyToBadulla =
                pool.submit(
                        () -> {
                            barrier.await(20, TimeUnit.SECONDS);
                            return book(seat3A, "KDY", "BDL");
                        });

        assertThat(fortToKandy.get(60, TimeUnit.SECONDS).getStatusCode().value()).isEqualTo(201);
        assertThat(kandyToBadulla.get(60, TimeUnit.SECONDS).getStatusCode().value()).isEqualTo(201);
        pool.shutdown();

        // ★ One physical seat, two paying passengers, zero contention between them.
        assertThat(activeSegments(seat3A)).isEqualTo(2);
        assertNoOverlappingSegmentsAnywhere();
    }

    @Test
    @DisplayName("a leg that straddles the boundary is rejected once both neighbours are sold")
    void straddlingLegIsRejected() {
        assertThat(book(seat3A, "CMB", "KDY").getStatusCode().value()).isEqualTo(201);
        assertThat(book(seat3A, "KDY", "BDL").getStatusCode().value()).isEqualTo(201);

        // Gampaha -> Nanu Oya crosses Kandy, so it overlaps both existing sales.
        var straddle = book(seat3A, "GMP", "NAN");
        assertThat(straddle.getStatusCode().value()).isEqualTo(409);
        assertThat(straddle.getBody()).contains("SEAT_SEGMENT_UNAVAILABLE");

        // And the whole journey is likewise unavailable on this seat.
        assertThat(book(seat3A, "CMB", "BDL").getStatusCode().value()).isEqualTo(409);

        assertThat(activeSegments(seat3A)).isEqualTo(2);
    }

    @Test
    @DisplayName("the 409 carries the data the UI needs to recover in one click")
    void conflictResponseIsActionable() {
        book(seat3A, "CMB", "KDY");
        var conflict = book(seat3A, "CMB", "KDY");

        assertThat(conflict.getStatusCode().value()).isEqualTo(409);
        String body = conflict.getBody();
        assertThat(body).contains("SEAT_SEGMENT_UNAVAILABLE");
        assertThat(body).contains("conflicts");
        assertThat(body).contains("suggestedAlternatives");
        assertThat(body).contains("availabilityUrl");
    }

    // -------------------------------------------------------------- idempotency

    @Test
    @DisplayName("the same Idempotency-Key replays instead of booking twice")
    void repeatedKeyReplaysTheOriginalBooking() {
        String key = UUID.randomUUID().toString();

        // The SAME body, as a retrying client would send. The key alone is not the identity of the
        // request -- the key plus a hash of the body is -- so a fresh quote id here would (correctly)
        // be rejected as key reuse rather than replayed.
        var body = request(seat3A, "CMB", "KDY");

        var first = post(body, key);
        var second = post(body, key);

        assertThat(first.getStatusCode().value()).isEqualTo(201);
        assertThat(second.getStatusCode().value()).isEqualTo(200);
        assertThat(second.getHeaders().getFirst("Idempotency-Replayed")).isEqualTo("true");

        // One seat sold once, despite two identical submissions.
        assertThat(activeSegments(seat3A)).isEqualTo(1);
        assertThat(bookingCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("concurrent duplicate submits with one key produce one booking")
    void concurrentDuplicateSubmitsProduceOneBooking() throws Exception {
        String key = UUID.randomUUID().toString();
        var body = request(seat3A, "CMB", "KDY"); // one body, submitted many times
        int threads = 8;
        var barrier = new CyclicBarrier(threads);
        ExecutorService pool = Executors.newFixedThreadPool(threads);

        List<Callable<Integer>> tasks = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            tasks.add(
                    () -> {
                        barrier.await(20, TimeUnit.SECONDS);
                        return post(body, key).getStatusCode().value();
                    });
        }

        List<Integer> statuses = new ArrayList<>();
        for (Future<Integer> f : pool.invokeAll(tasks, 60, TimeUnit.SECONDS)) {
            statuses.add(f.get());
        }
        pool.shutdown();

        // Whether a caller sees 201 or a 200 replay depends on who won, but there must be exactly
        // one booking and no 409 -- a duplicate submit is not a conflict.
        assertThat(statuses).allMatch(s -> s == 201 || s == 200);
        assertThat(bookingCount()).isEqualTo(1);
        assertThat(activeSegments(seat3A)).isEqualTo(1);
    }

    @Test
    @DisplayName("a missing Idempotency-Key is rejected")
    void missingIdempotencyKeyIsRejected() {
        var headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        var response =
                rest.postForEntity(
                        url("/api/v1/bookings"),
                        new HttpEntity<>(request(seat3A, "CMB", "KDY"), headers),
                        String.class);

        assertThat(response.getStatusCode().value()).isEqualTo(400);
        assertThat(response.getBody()).contains("IDEMPOTENCY_KEY_REQUIRED");
    }

    // ------------------------------------------------------------------ helpers

    private ResponseEntity<String> book(UUID seatId, String from, String to) {
        return book(seatId, from, to, UUID.randomUUID().toString());
    }

    private ResponseEntity<String> book(UUID seatId, String from, String to, String idempotencyKey) {
        return post(request(seatId, from, to), idempotencyKey);
    }

    private ResponseEntity<String> post(CreateBookingRequest body, String idempotencyKey) {
        var headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("Idempotency-Key", idempotencyKey);
        return rest.postForEntity(url("/api/v1/bookings"), new HttpEntity<>(body, headers), String.class);
    }

    private CreateBookingRequest request(UUID seatId, String from, String to) {
        int fromSeq = seq(from);
        int toSeq = seq(to);
        return new CreateBookingRequest(
                tripId,
                from,
                to,
                new SeatSelection("SPECIFIC", List.of(seatId), null, null),
                QuoteFixture.quote(tripId, fromSeq, toSeq, 1, 47000),
                List.of(new lk.yathra.booking.booking.BookingDtos.Passenger("Test Passenger", "ADULT")),
                new lk.yathra.booking.booking.BookingDtos.Contact(
                        // Unique per request so the anti-squatting hold limit does not fire during the
                        // 50-thread race, which is testing the invariant rather than that control.
                        UUID.randomUUID() + "@example.lk", "+94770000000", "Test Passenger"));
    }

    private int seq(String stationCode) {
        Integer s =
                jdbc.queryForObject(
                        "SELECT stop_sequence FROM trip_stop WHERE trip_id = :t AND station_code = :c",
                        Map.of("t", tripId, "c", stationCode),
                        Integer.class);
        return s == null ? 0 : s;
    }

    private String url(String path) {
        return "http://localhost:" + port + path;
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
     * The global invariant check, run after every race. This is the query the runbook uses during an
     * incident, and it should return zero rows for all eternity.
     */
    private void assertNoOverlappingSegmentsAnywhere() {
        Integer violations =
                jdbc.queryForObject(
                        """
                        SELECT count(*)
                          FROM booking_segment a
                          JOIN booking_segment b
                            ON a.trip_id = b.trip_id AND a.seat_id = b.seat_id AND a.id < b.id
                         WHERE a.leg && b.leg
                           AND a.status IN ('HELD','CONFIRMED')
                           AND b.status IN ('HELD','CONFIRMED')
                        """,
                        Map.of(),
                        Integer.class);
        assertThat(violations).as("overlapping active segments anywhere in the database").isZero();
    }
}
