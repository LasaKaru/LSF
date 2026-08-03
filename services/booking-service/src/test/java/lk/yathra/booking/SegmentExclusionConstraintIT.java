package lk.yathra.booking;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lk.yathra.booking.booking.SqlStates;
import lk.yathra.booking.support.PostgresTestSupport;
import lk.yathra.booking.support.TripFixture;
import lk.yathra.booking.trip.TripPublicationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

/**
 * Tests the exclusion constraint directly, against real PostgreSQL and the real migrations.
 *
 * <p>These assertions are about the <em>database</em>, not about the service layer. If the service
 * were deleted entirely, the guarantees checked here would still hold -- which is the whole point of
 * putting the invariant in DDL.
 */
@SpringBootTest
class SegmentExclusionConstraintIT extends PostgresTestSupport {

    @Autowired NamedParameterJdbcTemplate jdbc;
    @Autowired TripPublicationService publication;

    private UUID tripId;
    private UUID seat3A;
    private UUID seat3B;

    @BeforeEach
    void setUp() {
        jdbc.getJdbcTemplate().execute("TRUNCATE booking, booking_segment, trip CASCADE");

        var published = publication.publish(TripFixture.upCountryTrip(LocalDate.now().plusDays(7)));
        tripId = published.tripId();

        var seats =
                jdbc.queryForList(
                        "SELECT id FROM trip_seat WHERE trip_id = :t ORDER BY row_index, seat_label",
                        Map.of("t", tripId),
                        UUID.class);
        seat3A = seats.get(0);
        seat3B = seats.get(1);
    }

    @Test
    @DisplayName("the constraint exists with the expected definition")
    void constraintExists() {
        // If a future migration drops or weakens this, the build fails loudly here rather than
        // quietly in production. Runbook R1 names this test as the gap-closer.
        String definition =
                jdbc.queryForObject(
                        """
                        SELECT pg_get_constraintdef(oid) FROM pg_constraint
                         WHERE conname = 'no_overlapping_active_segments'
                        """,
                        Map.of(),
                        String.class);

        assertThat(definition).isNotNull();
        assertThat(definition).contains("EXCLUDE USING gist");
        assertThat(definition).contains("trip_id WITH =");
        assertThat(definition).contains("seat_id WITH =");
        assertThat(definition).contains("leg WITH &&");
        assertThat(definition).contains("HELD");
        assertThat(definition).contains("CONFIRMED");
    }

    @Test
    @DisplayName("★ adjacent legs on one seat are allowed -- the brief's headline scenario")
    void adjacentSegmentsOnSameSeatAreAllowed() {
        insertSegment(seat3A, 1, 9, "CONFIRMED"); // Colombo Fort -> Kandy

        assertThatCode(() -> insertSegment(seat3A, 9, 25, "CONFIRMED")) // Kandy -> Badulla
                .doesNotThrowAnyException();

        assertThat(activeSegmentCount(seat3A)).isEqualTo(2);
    }

    @Test
    @DisplayName("overlapping legs on one seat are rejected with SQLSTATE 23P01")
    void overlappingSegmentsOnSameSeatAreRejected() {
        insertSegment(seat3A, 1, 9, "CONFIRMED");

        assertThatThrownBy(() -> insertSegment(seat3A, 5, 15, "CONFIRMED"))
                .isInstanceOf(DataIntegrityViolationException.class)
                .satisfies(e -> assertThat(SqlStates.of(e)).isEqualTo("23P01"))
                .satisfies(e -> assertThat(SqlStates.isSegmentOverlap(e)).isTrue());

        assertThat(activeSegmentCount(seat3A)).isEqualTo(1);
    }

    @Test
    @DisplayName("a whole-journey booking blocks every sub-leg on that seat")
    void containmentIsRejected() {
        insertSegment(seat3A, 1, 25, "CONFIRMED");

        assertThatThrownBy(() -> insertSegment(seat3A, 9, 11, "CONFIRMED"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("the same leg on a different seat is unaffected")
    void differentSeatsDoNotConflict() {
        insertSegment(seat3A, 1, 9, "CONFIRMED");

        assertThatCode(() -> insertSegment(seat3B, 1, 9, "CONFIRMED")).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("cancelled and expired segments hold no inventory -- the WHERE predicate works")
    void terminalSegmentsDoNotBlock() {
        UUID bookingId = insertSegment(seat3A, 1, 9, "HELD");

        // Cancelling the booking propagates to its segments via trigger, freeing the stretch.
        jdbc.update(
                "UPDATE booking SET status = 'CANCELLED', cancelled_at = now() WHERE id = :id",
                Map.of("id", bookingId));

        assertThat(activeSegmentCount(seat3A)).isZero();
        assertThatCode(() -> insertSegment(seat3A, 1, 9, "CONFIRMED")).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("a held segment does reserve inventory")
    void heldSegmentsBlock() {
        insertSegment(seat3A, 1, 9, "HELD");

        assertThatThrownBy(() -> insertSegment(seat3A, 1, 9, "CONFIRMED"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("booking status changes propagate to segment status")
    void statusStaysInStep() {
        UUID bookingId = insertSegment(seat3A, 1, 9, "HELD");

        jdbc.update(
                "UPDATE booking SET status = 'CONFIRMED', confirmed_at = now() WHERE id = :id",
                Map.of("id", bookingId));

        List<String> statuses =
                jdbc.queryForList(
                        "SELECT status::text FROM booking_segment WHERE booking_id = :id",
                        Map.of("id", bookingId),
                        String.class);
        assertThat(statuses).containsExactly("CONFIRMED");
    }

    @Test
    @DisplayName("degenerate and inverted legs are rejected before the exclusion constraint is reached")
    void degenerateAndInvertedLegsRejected() {
        // A degenerate leg [9,9) is a perfectly legal (empty) int4range, so the generated column
        // computes fine and the CHECK constraint is what stops it: 23514 check_violation.
        assertThatThrownBy(() -> insertSegment(seat3A, 9, 9, "CONFIRMED"))
                .isInstanceOf(DataIntegrityViolationException.class)
                .satisfies(e -> assertThat(SqlStates.of(e)).isEqualTo(SqlStates.CHECK_VIOLATION));

        // An inverted leg [9,1) is rejected one layer earlier, by int4range() itself while evaluating
        // the generated column -- "range lower bound must be less than or equal to range upper bound",
        // SQLSTATE 22000 data_exception. The CHECK never gets the chance to fire.
        //
        // Worth pinning down rather than glossing over: both are rejected, which is the property that
        // matters, but they surface as different SQLSTATEs. Anything mapping database errors to HTTP
        // responses has to know that. In practice the API cannot reach either case, because Leg.of()
        // and JourneyResolver reject both before a statement is ever built -- these two rows are the
        // last line of defence, not the first.
        assertThatThrownBy(() -> insertSegment(seat3A, 9, 1, "CONFIRMED"))
                .isInstanceOf(org.springframework.dao.DataAccessException.class)
                .satisfies(e -> assertThat(SqlStates.of(e)).isEqualTo("22000"));
    }

    // ------------------------------------------------------------------ helpers

    private UUID insertSegment(UUID seatId, int fromSeq, int toSeq, String status) {
        UUID bookingId = UUID.randomUUID();
        jdbc.update(
                """
                INSERT INTO booking (id, reference, trip_id, status, total_fare_minor, expires_at, confirmed_at)
                VALUES (:id, :ref, :tripId, CAST(:status AS booking_status), 47000,
                        CASE WHEN :status = 'HELD' THEN now() + interval '10 minutes' END,
                        CASE WHEN :status = 'CONFIRMED' THEN now() END)
                """,
                new MapSqlParameterSource()
                        .addValue("id", bookingId)
                        .addValue("ref", "YTH-" + UUID.randomUUID().toString().substring(0, 6).toUpperCase())
                        .addValue("tripId", tripId)
                        .addValue("status", status));

        jdbc.update(
                """
                INSERT INTO booking_segment (booking_id, trip_id, seat_id, from_seq, to_seq,
                                             from_station_code, to_station_code, distance_km,
                                             fare_minor, status)
                VALUES (:bookingId, :tripId, :seatId, :fromSeq, :toSeq, 'AAA', 'BBB', 100.0, 47000,
                        CAST(:status AS booking_status))
                """,
                new MapSqlParameterSource()
                        .addValue("bookingId", bookingId)
                        .addValue("tripId", tripId)
                        .addValue("seatId", seatId)
                        .addValue("fromSeq", fromSeq)
                        .addValue("toSeq", toSeq)
                        .addValue("status", status));
        return bookingId;
    }

    private int activeSegmentCount(UUID seatId) {
        Integer n =
                jdbc.queryForObject(
                        """
                        SELECT count(*) FROM booking_segment
                         WHERE seat_id = :seatId AND status IN ('HELD','CONFIRMED')
                        """,
                        Map.of("seatId", seatId),
                        Integer.class);
        return n == null ? 0 : n;
    }
}
