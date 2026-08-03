package lk.yathra.booking.trip;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class TripRepository {

    private final NamedParameterJdbcTemplate jdbc;

    public TripRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public record TripRow(
            UUID id,
            String trainCode,
            String trainNameEn,
            String routeCode,
            LocalDate serviceDate,
            String direction,
            String status,
            Instant departsAt,
            Instant arrivesAt,
            Instant bookingCutoffAt) {}

    public record StopRow(
            int stopSequence,
            String stationCode,
            String nameEn,
            String nameSi,
            String nameTa,
            BigDecimal distanceKm,
            Instant scheduledArrival,
            Instant scheduledDeparture) {}

    public record CoachRow(
            UUID id,
            String coachNumber,
            String coachType,
            String classCode,
            boolean reservable,
            int positionIndex,
            String layoutJson,
            Integer capacity) {}

    public record SeatRow(
            UUID id,
            UUID coachId,
            String coachNumber,
            String classCode,
            String seatLabel,
            int rowIndex,
            int columnIndex,
            boolean window,
            boolean aisle,
            String facing) {}

    // ---------------------------------------------------------------- queries

    public Optional<TripRow> findById(UUID tripId) {
        var rows =
                jdbc.query(
                        """
                        SELECT id, train_code, train_name_en, route_code, service_date, direction::text AS direction,
                               status::text AS status, departs_at, arrives_at, booking_cutoff_at
                          FROM trip WHERE id = :id
                        """,
                        Map.of("id", tripId),
                        (rs, i) ->
                                new TripRow(
                                        rs.getObject("id", UUID.class),
                                        rs.getString("train_code"),
                                        rs.getString("train_name_en"),
                                        rs.getString("route_code"),
                                        rs.getObject("service_date", LocalDate.class),
                                        rs.getString("direction"),
                                        rs.getString("status"),
                                        rs.getTimestamp("departs_at").toInstant(),
                                        rs.getTimestamp("arrives_at").toInstant(),
                                        rs.getTimestamp("booking_cutoff_at").toInstant()));
        return rows.stream().findFirst();
    }

    /** Trip search. Only PUBLISHED trips are bookable, and only before the cutoff. */
    public List<TripRow> search(LocalDate date, String routeCode, String fromCode, String toCode) {
        var params =
                new MapSqlParameterSource()
                        .addValue("date", date)
                        .addValue("routeCode", routeCode)
                        .addValue("fromCode", fromCode)
                        .addValue("toCode", toCode);

        // The from/to filter is not cosmetic: a trip only appears if it serves BOTH stations, and
        // serves them in the requested order. That ordering check is what makes the same route in the
        // opposite direction correctly fall out of the results without a direction flag anywhere.
        return jdbc.query(
                """
                SELECT t.id, t.train_code, t.train_name_en, t.route_code, t.service_date,
                       t.direction::text AS direction, t.status::text AS status,
                       t.departs_at, t.arrives_at, t.booking_cutoff_at
                  FROM trip t
                 WHERE t.service_date = :date
                   AND t.status = 'PUBLISHED'
                   AND (CAST(:routeCode AS text) IS NULL OR t.route_code = CAST(:routeCode AS text))
                   AND (CAST(:fromCode AS text) IS NULL OR CAST(:toCode AS text) IS NULL OR EXISTS (
                         SELECT 1 FROM trip_stop f
                           JOIN trip_stop x ON x.trip_id = f.trip_id
                          WHERE f.trip_id = t.id
                            AND f.station_code = CAST(:fromCode AS text)
                            AND x.station_code = CAST(:toCode AS text)
                            AND f.stop_sequence < x.stop_sequence))
                 ORDER BY t.departs_at
                """,
                params,
                (rs, i) ->
                        new TripRow(
                                rs.getObject("id", UUID.class),
                                rs.getString("train_code"),
                                rs.getString("train_name_en"),
                                rs.getString("route_code"),
                                rs.getObject("service_date", LocalDate.class),
                                rs.getString("direction"),
                                rs.getString("status"),
                                rs.getTimestamp("departs_at").toInstant(),
                                rs.getTimestamp("arrives_at").toInstant(),
                                rs.getTimestamp("booking_cutoff_at").toInstant()));
    }

    public List<StopRow> findStops(UUID tripId) {
        return jdbc.query(
                """
                SELECT stop_sequence, station_code, station_name_en, station_name_si, station_name_ta,
                       distance_km, scheduled_arrival, scheduled_departure
                  FROM trip_stop WHERE trip_id = :tripId ORDER BY stop_sequence
                """,
                Map.of("tripId", tripId),
                (rs, i) ->
                        new StopRow(
                                rs.getInt("stop_sequence"),
                                rs.getString("station_code"),
                                rs.getString("station_name_en"),
                                rs.getString("station_name_si"),
                                rs.getString("station_name_ta"),
                                rs.getBigDecimal("distance_km"),
                                ts(rs.getTimestamp("scheduled_arrival")),
                                ts(rs.getTimestamp("scheduled_departure"))));
    }

    private static Instant ts(Timestamp t) {
        return t == null ? null : t.toInstant();
    }

    public List<CoachRow> findCoaches(UUID tripId) {
        return jdbc.query(
                """
                SELECT id, coach_number, coach_type::text AS coach_type, class_code::text AS class_code,
                       is_reservable, position_index, layout::text AS layout, capacity
                  FROM trip_coach WHERE trip_id = :tripId ORDER BY position_index
                """,
                Map.of("tripId", tripId),
                (rs, i) ->
                        new CoachRow(
                                rs.getObject("id", UUID.class),
                                rs.getString("coach_number"),
                                rs.getString("coach_type"),
                                rs.getString("class_code"),
                                rs.getBoolean("is_reservable"),
                                rs.getInt("position_index"),
                                rs.getString("layout"),
                                (Integer) rs.getObject("capacity")));
    }

    public List<SeatRow> findBookableSeats(UUID tripId, String classCode) {
        return jdbc.query(
                """
                SELECT s.id, s.trip_coach_id, c.coach_number, c.class_code::text AS class_code,
                       s.seat_label, s.row_index, s.column_index, s.is_window, s.is_aisle,
                       s.facing::text AS facing
                  FROM trip_seat s
                  JOIN trip_coach c ON c.id = s.trip_coach_id
                 WHERE s.trip_id = :tripId
                   AND c.is_reservable
                   AND s.is_bookable
                   AND (CAST(:classCode AS text) IS NULL OR c.class_code::text = CAST(:classCode AS text))
                 ORDER BY c.position_index, s.row_index, s.seat_label
                """,
                new MapSqlParameterSource().addValue("tripId", tripId).addValue("classCode", classCode),
                (rs, i) ->
                        new SeatRow(
                                rs.getObject("id", UUID.class),
                                rs.getObject("trip_coach_id", UUID.class),
                                rs.getString("coach_number"),
                                rs.getString("class_code"),
                                rs.getString("seat_label"),
                                rs.getInt("row_index"),
                                rs.getInt("column_index"),
                                rs.getBoolean("is_window"),
                                rs.getBoolean("is_aisle"),
                                rs.getString("facing")));
    }

    /**
     * Resolves a station code to its position on this trip.
     *
     * <p>Clients address stations by code and never see or send a sequence number: the code is stable
     * across environments, readable in a log, and does not leak an internal identifier. The sequence is
     * trip-scoped, so resolution has to happen here.
     */
    public Optional<Integer> resolveStopSequence(UUID tripId, String stationCode) {
        var seqs =
                jdbc.queryForList(
                        "SELECT stop_sequence FROM trip_stop WHERE trip_id = :tripId AND station_code = :code",
                        Map.of("tripId", tripId, "code", stationCode),
                        Integer.class);
        return seqs.stream().findFirst();
    }

    public Optional<BigDecimal> distanceBetween(UUID tripId, int fromSeq, int toSeq) {
        var list =
                jdbc.queryForList(
                        """
                        SELECT (SELECT distance_km FROM trip_stop WHERE trip_id = :tripId AND stop_sequence = :toSeq)
                             - (SELECT distance_km FROM trip_stop WHERE trip_id = :tripId AND stop_sequence = :fromSeq)
                        """,
                        Map.of("tripId", tripId, "fromSeq", fromSeq, "toSeq", toSeq),
                        BigDecimal.class);
        return list.stream().filter(java.util.Objects::nonNull).findFirst();
    }

    public int maxStopSequence(UUID tripId) {
        Integer max =
                jdbc.queryForObject(
                        "SELECT COALESCE(MAX(stop_sequence), 0) FROM trip_stop WHERE trip_id = :tripId",
                        Map.of("tripId", tripId),
                        Integer.class);
        return max == null ? 0 : max;
    }
}
