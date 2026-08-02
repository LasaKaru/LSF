package lk.yathra.booking.waitlist;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import lk.yathra.common.domain.Leg;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class WaitlistRepository {

    private final NamedParameterJdbcTemplate jdbc;

    public WaitlistRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public record EntryRow(
            UUID id,
            UUID tripId,
            int fromSeq,
            int toSeq,
            String fromCode,
            String toCode,
            String classCode,
            String contactName,
            String contactEmail,
            String contactPhone,
            int passengers,
            long fareMinor,
            String currency,
            String status,
            UUID offeredBookingId,
            Instant offerExpiresAt,
            Instant createdAt) {}

    private static final String COLUMNS =
            """
            id, trip_id, from_seq, to_seq, from_station_code, to_station_code,
            class_code::text AS class_code, contact_name, contact_email, contact_phone,
            passengers, fare_minor, currency, status::text AS status,
            offered_booking_id, offer_expires_at, created_at
            """;

    private static EntryRow map(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new EntryRow(
                rs.getObject("id", UUID.class),
                rs.getObject("trip_id", UUID.class),
                rs.getInt("from_seq"),
                rs.getInt("to_seq"),
                rs.getString("from_station_code"),
                rs.getString("to_station_code"),
                rs.getString("class_code"),
                rs.getString("contact_name"),
                rs.getString("contact_email"),
                rs.getString("contact_phone"),
                rs.getInt("passengers"),
                rs.getLong("fare_minor"),
                rs.getString("currency"),
                rs.getString("status"),
                rs.getObject("offered_booking_id", UUID.class),
                rs.getTimestamp("offer_expires_at") == null
                        ? null
                        : rs.getTimestamp("offer_expires_at").toInstant(),
                rs.getTimestamp("created_at").toInstant());
    }

    public UUID insert(
            UUID tripId, Leg leg, String fromCode, String toCode, String classCode,
            String name, String email, String phone, int passengers, long fareMinor, String currency) {

        UUID id = UUID.randomUUID();
        jdbc.update(
                """
                INSERT INTO waitlist_entry (id, trip_id, from_seq, to_seq, from_station_code,
                                            to_station_code, class_code, contact_name, contact_email,
                                            contact_phone, passengers, fare_minor, currency)
                VALUES (:id, :tripId, :fromSeq, :toSeq, :fromCode, :toCode,
                        CAST(:classCode AS class_enum), :name, :email, :phone, :passengers,
                        :fareMinor, :currency)
                """,
                new MapSqlParameterSource()
                        .addValue("id", id)
                        .addValue("tripId", tripId)
                        .addValue("fromSeq", leg.fromSeq())
                        .addValue("toSeq", leg.toSeq())
                        .addValue("fromCode", fromCode)
                        .addValue("toCode", toCode)
                        .addValue("classCode", classCode)
                        .addValue("name", name)
                        .addValue("email", email)
                        .addValue("phone", phone)
                        .addValue("passengers", passengers)
                        .addValue("fareMinor", fareMinor)
                        .addValue("currency", currency));
        return id;
    }

    public Optional<EntryRow> findById(UUID id) {
        return jdbc
                .query("SELECT " + COLUMNS + " FROM waitlist_entry WHERE id = :id",
                        Map.of("id", id), (rs, i) -> map(rs))
                .stream()
                .findFirst();
    }

    /**
     * Queue position: how many entries on the same trip and class are ahead of this one.
     *
     * <p>Counts {@code OFFERED} entries too — someone holding a live offer is still ahead of you, and
     * reporting otherwise would show a position that jumps backwards when their offer lapses.
     */
    public int positionOf(EntryRow entry) {
        Integer ahead =
                jdbc.queryForObject(
                        """
                        SELECT count(*) FROM waitlist_entry
                         WHERE trip_id = :tripId
                           AND class_code = CAST(:classCode AS class_enum)
                           AND status IN ('WAITING','OFFERED')
                           AND created_at < :createdAt
                        """,
                        new MapSqlParameterSource()
                                .addValue("tripId", entry.tripId())
                                .addValue("classCode", entry.classCode())
                                .addValue("createdAt", Timestamp.from(entry.createdAt())),
                        Integer.class);
        return (ahead == null ? 0 : ahead) + 1;
    }

    /**
     * The matcher's core query: the oldest waiting entry that can <b>actually be seated</b> on the
     * released seat, in the right class.
     *
     * <p>Two filters, and both are load-bearing.
     *
     * <p><b>Containment.</b> {@code @>} is range containment: a released {@code [9,25)} matches
     * {@code [9,25)}, {@code [12,20)} and {@code [9,15)}, but not {@code [1,15)} — that extends before
     * the release, where nothing was freed.
     *
     * <p><b>Seatability.</b> Containment alone is not enough, because a release says what was given up,
     * not what is free now. The flagship flow of this system is a passenger shortening their journey:
     * cancel {@code [1,25)}, immediately rebook {@code [1,9)}. That emits a release of the <em>whole</em>
     * {@code [1,25)} even though only {@code [9,25)} actually came free. Containment alone would hand
     * that release to someone waiting on {@code [1,15)}, whose promotion then dies on the exclusion
     * constraint — and because a release is consumed once, the entry waiting on {@code [9,25)} that
     * could have been seated never gets offered at all. The {@code NOT EXISTS} makes the query ask the
     * question that actually matters: is this seat free across the stretch this passenger wants?
     *
     * <p>This is a filter, not a substitute for the constraint. It is read outside the insert, so a
     * booking committed a microsecond later still wins the race — and the exclusion constraint still
     * rejects the promotion. The check removes the <em>predictable</em> collisions, not the racing ones.
     *
     * <p><b>Strictly FIFO, deliberately.</b> A greedy matcher would scan the queue for whichever entry
     * best <em>fills</em> the freed stretch, maximising seat-km. This does not. It takes the oldest
     * entry that fits, and leaves some utilisation on the table. A passenger who has watched three
     * later arrivals promoted ahead of them has been treated unfairly by any reasonable standard, and
     * "our optimiser preferred their journey shape" is not an answer a public operator can give. That
     * is a values decision disguised as an algorithm choice, so it is stated rather than buried.
     *
     * <p>{@code FOR UPDATE OF w SKIP LOCKED} so concurrent releases cannot offer the same entry twice.
     * The {@code OF w} matters: without it PostgreSQL would try to lock rows of every table in the
     * statement, and {@code booking_segment} is only being read here.
     */
    public Optional<EntryRow> findOldestMatching(
            UUID tripId, UUID seatId, Leg released, String classCode) {
        // Note the explicit "SELECT " with its space: a text block strips incidental trailing
        // whitespace from every line, so `SELECT """ + COLUMNS` concatenates to "SELECTid, ...".
        return jdbc
                .query(
                        "SELECT " + COLUMNS + """
                          FROM waitlist_entry w
                         WHERE w.trip_id = :tripId
                           AND w.status = 'WAITING'
                           AND w.class_code = CAST(:classCode AS class_enum)
                           AND int4range(:fromSeq, :toSeq, '[)') @> w.leg
                           AND NOT EXISTS (SELECT 1
                                             FROM booking_segment bs
                                            WHERE bs.trip_id = :tripId
                                              AND bs.seat_id = :seatId
                                              AND bs.status IN ('HELD', 'CONFIRMED')
                                              AND bs.leg && w.leg)
                         ORDER BY w.created_at
                         LIMIT 1
                         FOR UPDATE OF w SKIP LOCKED
                        """,
                        new MapSqlParameterSource()
                                .addValue("tripId", tripId)
                                .addValue("seatId", seatId)
                                .addValue("classCode", classCode)
                                .addValue("fromSeq", released.fromSeq())
                                .addValue("toSeq", released.toSeq()),
                        (rs, i) -> map(rs))
                .stream()
                .findFirst();
    }

    public void markOffered(UUID entryId, UUID bookingId, Instant offerExpiresAt) {
        jdbc.update(
                """
                UPDATE waitlist_entry
                   SET status = 'OFFERED', offered_booking_id = :bookingId, offer_expires_at = :expiresAt
                 WHERE id = :id AND status = 'WAITING'
                """,
                new MapSqlParameterSource()
                        .addValue("id", entryId)
                        .addValue("bookingId", bookingId)
                        .addValue("expiresAt", Timestamp.from(offerExpiresAt)));
    }

    public int markConverted(UUID bookingId) {
        return jdbc.update(
                "UPDATE waitlist_entry SET status = 'CONVERTED' "
                        + "WHERE offered_booking_id = :bookingId AND status = 'OFFERED'",
                Map.of("bookingId", bookingId));
    }

    /**
     * Returns lapsed offers to {@code WAITING}.
     *
     * <p>{@code created_at} is untouched, so the entry resumes its original queue position. This is the
     * whole point of WL-4: missing one notification must not cost your place.
     */
    public List<UUID> returnLapsedOffersToQueue() {
        return jdbc.queryForList(
                """
                UPDATE waitlist_entry w
                   SET status = 'WAITING', offered_booking_id = NULL, offer_expires_at = NULL
                 WHERE w.status = 'OFFERED'
                   AND (w.offer_expires_at < now()
                        OR EXISTS (SELECT 1 FROM booking b
                                    WHERE b.id = w.offered_booking_id
                                      AND b.status IN ('EXPIRED','CANCELLED')))
                RETURNING w.id
                """,
                Map.of(),
                UUID.class);
    }

    public int cancel(UUID entryId, String contactEmail) {
        return jdbc.update(
                """
                UPDATE waitlist_entry SET status = 'CANCELLED'
                 WHERE id = :id AND contact_email = :email AND status IN ('WAITING','OFFERED')
                """,
                Map.of("id", entryId, "email", contactEmail));
    }

    public int countWaiting(UUID tripId, String classCode) {
        Integer n =
                jdbc.queryForObject(
                        """
                        SELECT count(*) FROM waitlist_entry
                         WHERE trip_id = :tripId AND class_code = CAST(:classCode AS class_enum)
                           AND status IN ('WAITING','OFFERED')
                        """,
                        Map.of("tripId", tripId, "classCode", classCode),
                        Integer.class);
        return n == null ? 0 : n;
    }
}
