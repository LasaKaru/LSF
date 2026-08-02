package lk.yathra.booking.booking;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import lk.yathra.booking.booking.BookingDtos.ConflictDetail;
import lk.yathra.common.domain.Leg;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class BookingRepository {

    private final NamedParameterJdbcTemplate jdbc;

    public BookingRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public record BookingRow(
            UUID id,
            String reference,
            UUID tripId,
            String status,
            String contactEmail,
            long totalFareMinor,
            String currency,
            Instant expiresAt,
            Instant confirmedAt,
            Instant createdAt) {}

    public record SegmentRow(
            UUID id,
            UUID tripId,
            UUID seatId,
            String seatLabel,
            String coachNumber,
            int fromSeq,
            int toSeq,
            String fromCode,
            String toCode,
            BigDecimal distanceKm,
            long fareMinor,
            String status) {}

    // ------------------------------------------------------------ hold expiry

    /**
     * Expires only the holds that are actually in this booking's way, inside this booking's
     * transaction.
     *
     * <p>This is the "lazy" half of the layered hold-expiry answer (docs/04 §6.3). The exclusion
     * constraint's predicate must be immutable, so it cannot reference {@code now()} and cannot tell a
     * live hold from a dead one -- meaning an abandoned checkout keeps blocking a legitimate booking
     * until something changes its status.
     *
     * <p>Doing it here rather than only in the background sweeper makes it race-free: the expiry and
     * the insert share one transaction, so there is no window between "I expired it" and "I took it".
     * The booking's status is updated and a trigger propagates to its segments, so the two can never
     * disagree.
     */
    public int expireConflictingHolds(UUID tripId, List<UUID> seatIds, Leg leg) {
        if (seatIds.isEmpty()) {
            return 0;
        }
        return jdbc.update(
                """
                UPDATE booking b
                   SET status = 'EXPIRED'
                 WHERE b.status = 'HELD'
                   AND b.expires_at < now()
                   AND EXISTS (
                        SELECT 1 FROM booking_segment bs
                         WHERE bs.booking_id = b.id
                           AND bs.trip_id = :tripId
                           AND bs.seat_id IN (:seatIds)
                           AND bs.leg && int4range(:fromSeq, :toSeq, '[)'))
                """,
                new MapSqlParameterSource()
                        .addValue("tripId", tripId)
                        .addValue("seatIds", seatIds)
                        .addValue("fromSeq", leg.fromSeq())
                        .addValue("toSeq", leg.toSeq()));
    }

    /** The eager half: batched, ShedLock-guarded background sweep. Returns the released segments. */
    public List<UUID> expireDueHolds(int batchSize) {
        return jdbc.queryForList(
                """
                WITH due AS (
                    SELECT id FROM booking
                     WHERE status = 'HELD' AND expires_at < now()
                     ORDER BY expires_at
                     LIMIT :batchSize
                     FOR UPDATE SKIP LOCKED
                )
                UPDATE booking b SET status = 'EXPIRED'
                  FROM due WHERE b.id = due.id
                RETURNING b.id
                """,
                new MapSqlParameterSource().addValue("batchSize", batchSize),
                UUID.class);
    }

    // ---------------------------------------------------------------- writes

    public void insertBooking(
            UUID id,
            String reference,
            UUID tripId,
            String contactName,
            String contactEmail,
            String contactPhone,
            int passengerCount,
            UUID quoteId,
            String ruleSetVersion,
            long totalFareMinor,
            String currency,
            Instant expiresAt) {
        jdbc.update(
                """
                INSERT INTO booking (id, reference, trip_id, status, channel, contact_name, contact_email,
                                     contact_phone, passenger_count, quote_id, fare_rule_version,
                                     total_fare_minor, currency, expires_at)
                VALUES (:id, :reference, :tripId, 'HELD', 'WEB', :contactName, :contactEmail,
                        :contactPhone, :passengerCount, :quoteId, :ruleSetVersion,
                        :totalFareMinor, :currency, :expiresAt)
                """,
                new MapSqlParameterSource()
                        .addValue("id", id)
                        .addValue("reference", reference)
                        .addValue("tripId", tripId)
                        .addValue("contactName", contactName)
                        .addValue("contactEmail", contactEmail)
                        .addValue("contactPhone", contactPhone)
                        .addValue("passengerCount", passengerCount)
                        .addValue("quoteId", quoteId)
                        .addValue("ruleSetVersion", ruleSetVersion)
                        .addValue("totalFareMinor", totalFareMinor)
                        .addValue("currency", currency)
                        .addValue("expiresAt", Timestamp.from(expiresAt)));
    }

    /**
     * The insert the exclusion constraint guards. If an overlapping active segment exists this throws
     * with SQLSTATE 23P01; if a conflicting one is merely uncommitted, this call <em>blocks</em> until
     * that transaction resolves.
     */
    public void insertSegment(
            UUID bookingId,
            UUID tripId,
            UUID seatId,
            Leg leg,
            String fromCode,
            String toCode,
            BigDecimal distanceKm,
            long fareMinor) {
        jdbc.update(
                """
                INSERT INTO booking_segment (booking_id, trip_id, seat_id, from_seq, to_seq,
                                             from_station_code, to_station_code, distance_km,
                                             fare_minor, status)
                VALUES (:bookingId, :tripId, :seatId, :fromSeq, :toSeq, :fromCode, :toCode,
                        :distanceKm, :fareMinor, 'HELD')
                """,
                new MapSqlParameterSource()
                        .addValue("bookingId", bookingId)
                        .addValue("tripId", tripId)
                        .addValue("seatId", seatId)
                        .addValue("fromSeq", leg.fromSeq())
                        .addValue("toSeq", leg.toSeq())
                        .addValue("fromCode", fromCode)
                        .addValue("toCode", toCode)
                        .addValue("distanceKm", distanceKm)
                        .addValue("fareMinor", fareMinor));
    }

    public int updateStatus(UUID bookingId, String newStatus, String expectedCurrentStatus) {
        String extra =
                switch (newStatus) {
                    case "CONFIRMED" -> ", confirmed_at = now(), expires_at = NULL";
                    case "CANCELLED" -> ", cancelled_at = now()";
                    default -> "";
                };
        return jdbc.update(
                """
                UPDATE booking SET status = CAST(:newStatus AS booking_status)%s
                 WHERE id = :id AND status = CAST(:expected AS booking_status)
                """
                        .formatted(extra),
                new MapSqlParameterSource()
                        .addValue("id", bookingId)
                        .addValue("newStatus", newStatus)
                        .addValue("expected", expectedCurrentStatus));
    }

    // ---------------------------------------------------------------- reads

    public Optional<BookingRow> findById(UUID id) {
        return one("SELECT * FROM booking WHERE id = :key", Map.of("key", id));
    }

    public Optional<BookingRow> findByReference(String reference) {
        return one("SELECT * FROM booking WHERE reference = :key", Map.of("key", reference));
    }

    private Optional<BookingRow> one(String sql, Map<String, ?> params) {
        return jdbc
                .query(
                        sql,
                        params,
                        (rs, i) ->
                                new BookingRow(
                                        rs.getObject("id", UUID.class),
                                        rs.getString("reference"),
                                        rs.getObject("trip_id", UUID.class),
                                        rs.getString("status"),
                                        rs.getString("contact_email"),
                                        rs.getLong("total_fare_minor"),
                                        rs.getString("currency"),
                                        rs.getTimestamp("expires_at") == null
                                                ? null
                                                : rs.getTimestamp("expires_at").toInstant(),
                                        rs.getTimestamp("confirmed_at") == null
                                                ? null
                                                : rs.getTimestamp("confirmed_at").toInstant(),
                                        rs.getTimestamp("created_at").toInstant()))
                .stream()
                .findFirst();
    }

    public List<SegmentRow> findSegments(UUID bookingId) {
        return jdbc.query(
                """
                SELECT bs.id, bs.trip_id, bs.seat_id, s.seat_label, c.coach_number, bs.from_seq, bs.to_seq,
                       bs.from_station_code, bs.to_station_code, bs.distance_km, bs.fare_minor,
                       bs.status::text AS status
                  FROM booking_segment bs
                  JOIN trip_seat s  ON s.id = bs.seat_id
                  JOIN trip_coach c ON c.id = s.trip_coach_id
                 WHERE bs.booking_id = :bookingId
                 ORDER BY c.position_index, s.row_index, s.seat_label
                """,
                Map.of("bookingId", bookingId),
                (rs, i) ->
                        new SegmentRow(
                                rs.getObject("id", UUID.class),
                                rs.getObject("trip_id", UUID.class),
                                rs.getObject("seat_id", UUID.class),
                                rs.getString("seat_label"),
                                rs.getString("coach_number"),
                                rs.getInt("from_seq"),
                                rs.getInt("to_seq"),
                                rs.getString("from_station_code"),
                                rs.getString("to_station_code"),
                                rs.getBigDecimal("distance_km"),
                                rs.getLong("fare_minor"),
                                rs.getString("status")));
    }

    /** Anti-squatting: how much inventory this contact currently has frozen but unpaid. */
    public int countActiveHolds(String contactEmail) {
        if (contactEmail == null || contactEmail.isBlank()) {
            return 0;
        }
        Integer n =
                jdbc.queryForObject(
                        """
                        SELECT count(*) FROM booking
                         WHERE contact_email = :email AND status = 'HELD' AND expires_at > now()
                        """,
                        Map.of("email", contactEmail),
                        Integer.class);
        return n == null ? 0 : n;
    }

    /** The seats that actually blocked us, so the 409 can name them instead of being generic. */
    public List<ConflictDetail> findConflicts(UUID tripId, List<UUID> seatIds, Leg leg) {
        if (seatIds.isEmpty()) {
            return List.of();
        }
        return jdbc.query(
                """
                SELECT bs.seat_id, s.seat_label, bs.from_seq, bs.to_seq
                  FROM booking_segment bs
                  JOIN booking b ON b.id = bs.booking_id
                  JOIN trip_seat s ON s.id = bs.seat_id
                 WHERE bs.trip_id = :tripId
                   AND bs.seat_id IN (:seatIds)
                   AND bs.leg && int4range(:fromSeq, :toSeq, '[)')
                   AND ( bs.status = 'CONFIRMED'
                      OR (bs.status = 'HELD' AND b.expires_at > now()) )
                 ORDER BY s.seat_label, bs.from_seq
                """,
                new MapSqlParameterSource()
                        .addValue("tripId", tripId)
                        .addValue("seatIds", seatIds)
                        .addValue("fromSeq", leg.fromSeq())
                        .addValue("toSeq", leg.toSeq()),
                (rs, i) ->
                        new ConflictDetail(
                                rs.getObject("seat_id", UUID.class),
                                rs.getString("seat_label"),
                                new int[] {rs.getInt("from_seq"), rs.getInt("to_seq")}));
    }

    /** Seat metadata for the seats being booked, used to build the response and the outbox event. */
    public List<SeatMeta> findSeatMeta(List<UUID> seatIds) {
        if (seatIds.isEmpty()) {
            return List.of();
        }
        return jdbc.query(
                """
                SELECT s.id, s.seat_label, c.coach_number, c.class_code::text AS class_code,
                       c.coach_type::text AS coach_type, s.is_window, c.is_reservable, s.is_bookable
                  FROM trip_seat s JOIN trip_coach c ON c.id = s.trip_coach_id
                 WHERE s.id IN (:seatIds)
                """,
                new MapSqlParameterSource().addValue("seatIds", seatIds),
                (rs, i) ->
                        new SeatMeta(
                                rs.getObject("id", UUID.class),
                                rs.getString("seat_label"),
                                rs.getString("coach_number"),
                                rs.getString("class_code"),
                                rs.getString("coach_type"),
                                rs.getBoolean("is_window"),
                                rs.getBoolean("is_reservable"),
                                rs.getBoolean("is_bookable")));
    }

    public record SeatMeta(
            UUID id,
            String label,
            String coachNumber,
            String classCode,
            String coachType,
            boolean window,
            boolean reservable,
            boolean bookable) {}

    /** Occupancy per seat, for the fragmentation-minimising strategy. */
    public Map<UUID, List<int[]>> occupancyFor(UUID tripId, List<UUID> seatIds) {
        Map<UUID, List<int[]>> result = new java.util.LinkedHashMap<>();
        if (seatIds.isEmpty()) {
            return result;
        }
        jdbc.query(
                """
                SELECT bs.seat_id, bs.from_seq, bs.to_seq
                  FROM booking_segment bs
                  JOIN booking b ON b.id = bs.booking_id
                 WHERE bs.trip_id = :tripId
                   AND bs.seat_id IN (:seatIds)
                   AND ( bs.status = 'CONFIRMED'
                      OR (bs.status = 'HELD' AND b.expires_at > now()) )
                 ORDER BY bs.seat_id, bs.from_seq
                """,
                new MapSqlParameterSource().addValue("tripId", tripId).addValue("seatIds", seatIds),
                // Braces matter: an expression lambda here is both value- and void-compatible, so it
                // is ambiguous between ResultSetExtractor and RowCallbackHandler.
                (org.springframework.jdbc.core.RowCallbackHandler)
                        rs -> {
                            result
                                    .computeIfAbsent(rs.getObject("seat_id", UUID.class), k -> new ArrayList<>())
                                    .add(new int[] {rs.getInt("from_seq"), rs.getInt("to_seq")});
                        });
        return result;
    }
}
