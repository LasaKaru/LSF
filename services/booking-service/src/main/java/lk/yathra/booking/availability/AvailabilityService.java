package lk.yathra.booking.availability;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lk.yathra.booking.trip.TripRepository;
import lk.yathra.common.domain.Leg;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * The leg-scoped read path.
 *
 * <p>The important thing about every query here is the {@code NOT EXISTS} predicate: it is answered by
 * the very GiST index that backs the exclusion constraint. The correctness mechanism and the
 * availability index are <em>the same database object</em>, so they cannot drift apart -- a seat can
 * never be reported free by a query that uses a different definition of "free" from the one the write
 * path enforces.
 *
 * <p>Availability is explicitly advisory. Only {@code POST /bookings} is authoritative, and the API
 * documentation says so, because between rendering this answer and the passenger clicking Book,
 * somebody else can take the seat.
 */
@Service
public class AvailabilityService {

    private final NamedParameterJdbcTemplate jdbc;
    private final TripRepository trips;

    public AvailabilityService(NamedParameterJdbcTemplate jdbc, TripRepository trips) {
        this.jdbc = jdbc;
        this.trips = trips;
    }

    /**
     * A segment blocks a seat when it is CONFIRMED, or HELD and not yet expired.
     *
     * <p>Reads deliberately treat an expired-but-unswept hold as free. The exclusion constraint cannot
     * (its predicate must be immutable, so it cannot reference {@code now()}), which is why the write
     * path expires conflicting holds in-transaction. Reads have no such restriction and should show
     * the truth.
     */
    private static final String ACTIVE_SEGMENT_PREDICATE =
            """
            EXISTS (
                SELECT 1 FROM booking_segment bs
                  JOIN booking b ON b.id = bs.booking_id
                 WHERE bs.trip_id = s.trip_id
                   AND bs.seat_id = s.id
                   AND bs.leg && int4range(:fromSeq, :toSeq, '[)')
                   AND ( bs.status = 'CONFIRMED'
                      OR (bs.status = 'HELD' AND b.expires_at > now()) )
            )
            """;

    public record CoachAvailability(
            String coachNumber, String classCode, boolean reservable, int totalSeats, int availableSeats) {}

    public record AvailabilitySummary(
            UUID tripId,
            String from,
            String to,
            int fromSeq,
            int toSeq,
            java.math.BigDecimal distanceKm,
            List<CoachAvailability> coaches,
            int totalAvailable,
            java.time.Instant generatedAt) {}

    public AvailabilitySummary availability(JourneyResolver.ResolvedJourney journey, String classCode) {
        Leg leg = journey.leg();

        var params =
                new MapSqlParameterSource()
                        .addValue("tripId", journey.tripId())
                        .addValue("fromSeq", leg.fromSeq())
                        .addValue("toSeq", leg.toSeq())
                        .addValue("classCode", classCode);

        List<CoachAvailability> coaches =
                jdbc.query(
                        """
                        SELECT c.coach_number, c.class_code::text AS class_code, c.is_reservable,
                               count(s.id) AS total_seats,
                               count(s.id) FILTER (WHERE NOT %s) AS available_seats
                          FROM trip_coach c
                          LEFT JOIN trip_seat s ON s.trip_coach_id = c.id AND s.is_bookable
                         WHERE c.trip_id = :tripId
                           AND c.is_reservable
                           AND (CAST(:classCode AS text) IS NULL OR c.class_code::text = CAST(:classCode AS text))
                         GROUP BY c.coach_number, c.class_code, c.is_reservable, c.position_index
                         ORDER BY c.position_index
                        """
                                .formatted(ACTIVE_SEGMENT_PREDICATE),
                        params,
                        (rs, i) ->
                                new CoachAvailability(
                                        rs.getString("coach_number"),
                                        rs.getString("class_code"),
                                        rs.getBoolean("is_reservable"),
                                        rs.getInt("total_seats"),
                                        rs.getInt("available_seats")));

        int total = coaches.stream().mapToInt(CoachAvailability::availableSeats).sum();

        return new AvailabilitySummary(
                journey.tripId(),
                journey.fromCode(),
                journey.toCode(),
                leg.fromSeq(),
                leg.toSeq(),
                journey.distanceKm(),
                coaches,
                total,
                java.time.Instant.now());
    }

    /** Seat ids that are free for the whole requested leg, in coach/row order. */
    public List<UUID> freeSeatIds(UUID tripId, Leg leg, String classCode) {
        return jdbc.queryForList(
                """
                SELECT s.id
                  FROM trip_seat s
                  JOIN trip_coach c ON c.id = s.trip_coach_id
                 WHERE s.trip_id = :tripId
                   AND c.is_reservable
                   AND s.is_bookable
                   AND (CAST(:classCode AS text) IS NULL OR c.class_code::text = CAST(:classCode AS text))
                   AND NOT %s
                 ORDER BY c.position_index, s.row_index, s.seat_label
                """
                        .formatted(ACTIVE_SEGMENT_PREDICATE),
                new MapSqlParameterSource()
                        .addValue("tripId", tripId)
                        .addValue("fromSeq", leg.fromSeq())
                        .addValue("toSeq", leg.toSeq())
                        .addValue("classCode", classCode),
                UUID.class);
    }

    // ------------------------------------------------------------- seat map

    public record SeatView(
            UUID seatId,
            String label,
            int row,
            int col,
            boolean window,
            boolean aisle,
            boolean availableForRequestedLeg,
            boolean partiallyAvailable,
            List<int[]> occupied) {}

    public record CoachView(
            String coachNumber, String classCode, Object layout, List<SeatView> seats) {}

    public record SeatMapView(
            UUID tripId, String from, String to, int fromSeq, int toSeq,
            List<CoachView> coaches, List<TripRepository.StopRow> stops) {}

    /**
     * Returns occupancy <em>ranges</em> per seat rather than a boolean.
     *
     * <p>That single choice is what makes the tri-state seat map possible in one round trip: a seat
     * that is free for part of your leg (amber) is a state that simply does not exist in whole-journey
     * booking, and a boolean cannot express it. It also powers the hover detail showing where a seat
     * frees up.
     *
     * <p>Ranges only -- no names, no references, nothing identifying. The UI could not leak passenger
     * data here even if a future developer tried, because the endpoint never had it.
     */
    public SeatMapView seatMap(JourneyResolver.ResolvedJourney journey, String classCode) {
        UUID tripId = journey.tripId();
        Leg leg = journey.leg();

        var seats = trips.findBookableSeats(tripId, classCode);

        // One query for all active occupancy on the trip, then grouped in memory. Cheaper and simpler
        // than a correlated subquery per seat, and the result set is bounded by a single trip.
        Map<UUID, List<int[]>> occupancy = new LinkedHashMap<>();
        jdbc.query(
                """
                SELECT bs.seat_id, bs.from_seq, bs.to_seq
                  FROM booking_segment bs
                  JOIN booking b ON b.id = bs.booking_id
                 WHERE bs.trip_id = :tripId
                   AND ( bs.status = 'CONFIRMED'
                      OR (bs.status = 'HELD' AND b.expires_at > now()) )
                 ORDER BY bs.seat_id, bs.from_seq
                """,
                new MapSqlParameterSource().addValue("tripId", tripId),
                rs -> {
                    occupancy
                            .computeIfAbsent(rs.getObject("seat_id", UUID.class), k -> new ArrayList<>())
                            .add(new int[] {rs.getInt("from_seq"), rs.getInt("to_seq")});
                });

        Map<String, CoachAccumulator> byCoach = new LinkedHashMap<>();
        var coachRows = trips.findCoaches(tripId);
        Map<String, TripRepository.CoachRow> coachMeta = new LinkedHashMap<>();
        coachRows.forEach(c -> coachMeta.put(c.coachNumber(), c));

        for (var seat : seats) {
            List<int[]> occ = occupancy.getOrDefault(seat.id(), List.of());

            boolean blocked = occ.stream().anyMatch(r -> overlaps(r, leg));
            // Free for some, but not all, of the requested leg -- the amber state.
            boolean partial = blocked && occ.stream().noneMatch(r -> covers(r, leg));

            byCoach
                    .computeIfAbsent(
                            seat.coachNumber(), k -> new CoachAccumulator(seat.coachNumber(), seat.classCode()))
                    .seats
                    .add(
                            new SeatView(
                                    seat.id(),
                                    seat.seatLabel(),
                                    seat.rowIndex(),
                                    seat.columnIndex(),
                                    seat.window(),
                                    seat.aisle(),
                                    !blocked,
                                    partial,
                                    occ));
        }

        List<CoachView> coaches = new ArrayList<>();
        byCoach.forEach(
                (number, acc) -> {
                    var meta = coachMeta.get(number);
                    coaches.add(
                            new CoachView(
                                    number, acc.classCode, meta == null ? null : rawJson(meta.layoutJson()), acc.seats));
                });

        return new SeatMapView(
                tripId,
                journey.fromCode(),
                journey.toCode(),
                leg.fromSeq(),
                leg.toSeq(),
                coaches,
                trips.findStops(tripId));
    }

    /** Half-open overlap, identical to {@link Leg#overlaps} and to the SQL {@code &&}. */
    private static boolean overlaps(int[] range, Leg leg) {
        return range[0] < leg.toSeq() && leg.fromSeq() < range[1];
    }

    private static boolean covers(int[] range, Leg leg) {
        return range[0] <= leg.fromSeq() && leg.toSeq() <= range[1];
    }

    private static Object rawJson(String json) {
        if (json == null) {
            return null;
        }
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper().readTree(json);
        } catch (Exception e) {
            return null;
        }
    }

    private static final class CoachAccumulator {
        final String coachNumber;
        final String classCode;
        final List<SeatView> seats = new ArrayList<>();

        CoachAccumulator(String coachNumber, String classCode) {
            this.coachNumber = coachNumber;
            this.classCode = classCode;
        }
    }
}
