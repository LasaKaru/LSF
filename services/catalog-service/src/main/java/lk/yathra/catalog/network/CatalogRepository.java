package lk.yathra.catalog.network;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class CatalogRepository {

    private final NamedParameterJdbcTemplate jdbc;

    public CatalogRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public record Station(UUID id, String code, String nameEn, String nameSi, String nameTa) {}

    public record RouteStop(
            UUID stationId, String code, String nameEn, String nameSi, String nameTa,
            int stopOrder, BigDecimal distanceKm) {}

    public record Route(UUID id, String code, String nameEn) {}

    public record ScheduleRow(
            UUID scheduleId, UUID trainId, String trainCode, String trainNameEn, UUID routeId,
            String routeCode, String direction, LocalTime departsTime, BigDecimal avgSpeedKmh, int dwellMinutes) {}

    public record CoachRow(
            String coachNumber, String coachType, String classCode, boolean reservable,
            int positionIndex, String layoutJson, Integer capacity) {}

    public List<Station> findStations() {
        return jdbc.query(
                "SELECT id, code, name_en, name_si, name_ta FROM station WHERE is_active ORDER BY code",
                Map.of(),
                (rs, i) ->
                        new Station(
                                rs.getObject("id", UUID.class),
                                rs.getString("code"),
                                rs.getString("name_en"),
                                rs.getString("name_si"),
                                rs.getString("name_ta")));
    }

    public List<Route> findRoutes() {
        return jdbc.query(
                "SELECT id, code, name_en FROM route WHERE is_active ORDER BY code",
                Map.of(),
                (rs, i) ->
                        new Route(rs.getObject("id", UUID.class), rs.getString("code"), rs.getString("name_en")));
    }

    /**
     * Route stops in the requested direction.
     *
     * <p>For {@code DOWN} the order is reversed and distances are re-based from the other terminus, so
     * the caller always receives a list that ascends from 1 with distance increasing. That is what lets
     * the rest of the system have no direction flag at all: a down service is simply a trip whose stop
     * list happens to start at Badulla.
     */
    public List<RouteStop> findRouteStops(UUID routeId, String direction) {
        var stops =
                jdbc.query(
                        """
                        SELECT rs.station_id, s.code, s.name_en, s.name_si, s.name_ta,
                               rs.stop_order, rs.distance_from_origin_km
                          FROM route_stop rs JOIN station s ON s.id = rs.station_id
                         WHERE rs.route_id = :routeId
                         ORDER BY rs.stop_order
                        """,
                        Map.of("routeId", routeId),
                        (rs, i) ->
                                new RouteStop(
                                        rs.getObject("station_id", UUID.class),
                                        rs.getString("code"),
                                        rs.getString("name_en"),
                                        rs.getString("name_si"),
                                        rs.getString("name_ta"),
                                        rs.getInt("stop_order"),
                                        rs.getBigDecimal("distance_from_origin_km")));

        if (!"DOWN".equalsIgnoreCase(direction)) {
            return stops;
        }

        BigDecimal total = stops.get(stops.size() - 1).distanceKm();
        List<RouteStop> reversed = new java.util.ArrayList<>(stops.size());
        for (int i = stops.size() - 1, seq = 1; i >= 0; i--, seq++) {
            var stop = stops.get(i);
            reversed.add(
                    new RouteStop(
                            stop.stationId(), stop.code(), stop.nameEn(), stop.nameSi(), stop.nameTa(),
                            seq, total.subtract(stop.distanceKm())));
        }
        return reversed;
    }

    public List<ScheduleRow> findActiveSchedules() {
        return jdbc.query(
                """
                SELECT sc.id AS schedule_id, t.id AS train_id, t.code AS train_code, t.name_en AS train_name,
                       r.id AS route_id, r.code AS route_code, sc.direction::text AS direction,
                       sc.departs_time, sc.avg_speed_kmh, sc.dwell_minutes
                  FROM schedule sc
                  JOIN train t ON t.id = sc.train_id
                  JOIN route r ON r.id = t.route_id
                 WHERE sc.is_active AND t.is_active
                 ORDER BY t.code, sc.direction
                """,
                Map.of(),
                (rs, i) ->
                        new ScheduleRow(
                                rs.getObject("schedule_id", UUID.class),
                                rs.getObject("train_id", UUID.class),
                                rs.getString("train_code"),
                                rs.getString("train_name"),
                                rs.getObject("route_id", UUID.class),
                                rs.getString("route_code"),
                                rs.getString("direction"),
                                rs.getObject("departs_time", LocalTime.class),
                                rs.getBigDecimal("avg_speed_kmh"),
                                rs.getInt("dwell_minutes")));
    }

    public List<CoachRow> findComposition(UUID trainId) {
        return jdbc.query(
                """
                SELECT tc.coach_number, tc.coach_type::text AS coach_type, tc.class_code::text AS class_code,
                       tc.is_reservable, tc.position_index, cl.grid::text AS layout, tc.capacity
                  FROM train_composition tc
                  LEFT JOIN coach_layout cl ON cl.id = tc.layout_id
                 WHERE tc.train_id = :trainId
                 ORDER BY tc.position_index
                """,
                Map.of("trainId", trainId),
                (rs, i) ->
                        new CoachRow(
                                rs.getString("coach_number"),
                                rs.getString("coach_type"),
                                rs.getString("class_code"),
                                rs.getBoolean("is_reservable"),
                                rs.getInt("position_index"),
                                rs.getString("layout"),
                                (Integer) rs.getObject("capacity")));
    }

    public boolean isPublished(UUID scheduleId, LocalDate serviceDate) {
        Integer n =
                jdbc.queryForObject(
                        "SELECT count(*) FROM trip_publication WHERE schedule_id = :s AND service_date = :d",
                        Map.of("s", scheduleId, "d", serviceDate),
                        Integer.class);
        return n != null && n > 0;
    }

    public void recordPublication(UUID scheduleId, LocalDate serviceDate, UUID tripId) {
        jdbc.update(
                """
                INSERT INTO trip_publication (schedule_id, service_date, trip_id)
                VALUES (:s, :d, :t)
                ON CONFLICT (schedule_id, service_date) DO NOTHING
                """,
                new MapSqlParameterSource().addValue("s", scheduleId).addValue("d", serviceDate).addValue("t", tripId));
    }
}
