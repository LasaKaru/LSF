package lk.yathra.booking.admin;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lk.yathra.common.api.ApiException;
import lk.yathra.common.api.ErrorCode;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * Occupancy and revenue reporting.
 *
 * <p>The brief says leadership <em>believes</em> revenue is being left on the table. Believes. The
 * department currently has no instrument that shows where the train empties out or how much inventory
 * evaporates at departure, which is why the change cannot be justified, tuned or defended today.
 *
 * <p>The headline metric is <b>seat-km utilisation</b>, and it is the point of the whole feature.
 * Conventional load factor -- seats sold divided by seats available -- reports a train as 100% full
 * when every seat was sold for a tenth of the route. That metric is precisely what hid the problem in
 * the first place.
 *
 * <p>These queries run against the transactional tables directly. At the documented scale
 * (~1.2M bookings a year, a few hundred seats per trip) that is comfortably fast and avoids standing
 * up a projection nobody needs yet. The moment an analyst's query is capable of slowing a passenger
 * down, this moves behind the CQRS read model the events already support -- that trigger is recorded
 * in docs/15 §2.2 rather than guessed at now.
 */
@Service
public class AdminReportingService {

    private final NamedParameterJdbcTemplate jdbc;

    public AdminReportingService(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public record HopOccupancy(
            int fromSeq, int toSeq, String fromCode, String toCode,
            BigDecimal distanceKm, int seatsOccupied, int seatsTotal, BigDecimal occupancyPct) {}

    public record SegmentRevenue(
            String fromCode, String toCode, int bookings, long revenueMinor, BigDecimal seatKm) {}

    public record TripReport(
            UUID tripId,
            String trainCode,
            String routeCode,
            java.time.LocalDate serviceDate,
            String direction,
            int bookableSeats,
            BigDecimal routeKm,
            BigDecimal availableSeatKm,
            BigDecimal soldSeatKm,
            BigDecimal seatKmUtilisationPct,
            int distinctSeatsUsed,
            BigDecimal conventionalLoadFactorPct,
            BigDecimal segmentsPerSeat,
            long actualRevenueMinor,
            /** Revenue if each seat had been sold once, to whoever booked it first. */
            Long firstSaleRevenueMinor,
            /** Extra revenue earned by second-and-subsequent sales of a seat, as a percentage. */
            BigDecimal resaleUpliftPct,
            /** Separate fare-policy comparison; null unless a through fare was supplied. */
            Long wholeJourneyCounterfactualMinor,
            List<HopOccupancy> occupancy,
            List<SegmentRevenue> revenueBySegment) {}

    /**
     * @param wholeJourneyFareMinor optional published through fare. The resale uplift does NOT need
     *     it -- that is computed from this trip's own recorded fares. It is used only for the separate
     *     fare-policy comparison, and is supplied by the caller rather than fetched from
     *     pricing-service on purpose: pricing already depends on booking for trip topology, and calling
     *     back would close a dependency cycle for a reporting figure.
     */
    public TripReport tripReport(UUID tripId, Long wholeJourneyFareMinor) {
        var trip =
                jdbc.query(
                                """
                                SELECT id, train_code, route_code, service_date, direction::text AS direction
                                  FROM trip WHERE id = :id
                                """,
                                Map.of("id", tripId),
                                (rs, i) ->
                                        new Object[] {
                                            rs.getString("train_code"),
                                            rs.getString("route_code"),
                                            rs.getObject("service_date", java.time.LocalDate.class),
                                            rs.getString("direction")
                                        })
                        .stream()
                        .findFirst()
                        .orElseThrow(() -> new ApiException(ErrorCode.TRIP_NOT_FOUND, "Trip not found."));

        Map<String, Object> params = Map.of("tripId", tripId);

        int bookableSeats = intOf("""
                SELECT count(*) FROM trip_seat s JOIN trip_coach c ON c.id = s.trip_coach_id
                 WHERE s.trip_id = :tripId AND c.is_reservable AND s.is_bookable
                """, params);

        BigDecimal routeKm =
                decimalOf("SELECT COALESCE(MAX(distance_km),0) FROM trip_stop WHERE trip_id = :tripId", params);

        BigDecimal soldSeatKm =
                decimalOf(
                        """
                        SELECT COALESCE(SUM(bs.distance_km),0) FROM booking_segment bs
                         WHERE bs.trip_id = :tripId AND bs.status IN ('HELD','CONFIRMED')
                        """,
                        params);

        long actualRevenue =
                longOf(
                        """
                        SELECT COALESCE(SUM(bs.fare_minor),0) FROM booking_segment bs
                         WHERE bs.trip_id = :tripId AND bs.status IN ('HELD','CONFIRMED')
                        """,
                        params);

        int distinctSeatsUsed =
                intOf(
                        """
                        SELECT count(DISTINCT bs.seat_id) FROM booking_segment bs
                         WHERE bs.trip_id = :tripId AND bs.status IN ('HELD','CONFIRMED')
                        """,
                        params);

        int totalSegments =
                intOf(
                        """
                        SELECT count(*) FROM booking_segment bs
                         WHERE bs.trip_id = :tripId AND bs.status IN ('HELD','CONFIRMED')
                        """,
                        params);

        BigDecimal availableSeatKm = routeKm.multiply(BigDecimal.valueOf(bookableSeats));
        BigDecimal utilisation = pct(soldSeatKm, availableSeatKm);
        BigDecimal loadFactor = pct(BigDecimal.valueOf(distinctSeatsUsed), BigDecimal.valueOf(bookableSeats));

        // The product metric: if this is 1.0, seats are not reselling and the change delivered nothing.
        BigDecimal segmentsPerSeat =
                distinctSeatsUsed == 0
                        ? BigDecimal.ZERO
                        : BigDecimal.valueOf(totalSegments)
                                .divide(BigDecimal.valueOf(distinctSeatsUsed), 2, RoundingMode.HALF_UP);

        // ---------------------------------------------------------------- resale uplift
        //
        // The counterfactual is "each seat sold ONCE, to whoever booked it first" -- the defining
        // constraint of whole-journey-only ticketing, where the first passenger commits the seat for
        // the entire route and it cannot be resold.
        //
        // Deliberately NOT "each occupied seat sold at the full through fare". That was the first
        // model here and it is wrong in a way that matters: it credits the old regime with selling a
        // 292 km ticket to a passenger travelling 29 km to Gampaha, who under any regime would have
        // gone unreserved rather than pay for the whole line. It inflated the baseline so far that the
        // report showed resale LOSING 24% -- the exact opposite of the truth, on the one number the
        // whole feature exists to produce.
        //
        // Summing each seat's earliest fare needs no assumption about historic pricing at all, and
        // isolates the thing being measured: revenue from second-and-subsequent sales of a seat.
        // Fare-policy change is a separate effect and is not conflated with it here.
        long firstSaleRevenue =
                longOf(
                        """
                        SELECT COALESCE(SUM(first_fare), 0) FROM (
                            SELECT DISTINCT ON (bs.seat_id) bs.fare_minor AS first_fare
                              FROM booking_segment bs
                             WHERE bs.trip_id = :tripId AND bs.status IN ('HELD','CONFIRMED')
                             ORDER BY bs.seat_id, bs.from_seq
                        ) firsts
                        """,
                        params);

        Long counterfactual = firstSaleRevenue > 0 ? firstSaleRevenue : null;
        BigDecimal upliftPct =
                firstSaleRevenue > 0
                        ? BigDecimal.valueOf(actualRevenue - firstSaleRevenue)
                                .multiply(BigDecimal.valueOf(100))
                                .divide(BigDecimal.valueOf(firstSaleRevenue), 1, RoundingMode.HALF_UP)
                        : null;

        // Retained for the separate question "what would this trip have grossed if every occupied seat
        // had been sold whole-journey at the through fare?" -- a fare-policy comparison, not a resale
        // one. Reported alongside rather than as the headline.
        Long wholeJourneyCounterfactual =
                wholeJourneyFareMinor != null && wholeJourneyFareMinor > 0
                        ? wholeJourneyFareMinor * distinctSeatsUsed
                        : null;

        return new TripReport(
                tripId,
                (String) trip[0],
                (String) trip[1],
                (java.time.LocalDate) trip[2],
                (String) trip[3],
                bookableSeats,
                routeKm,
                availableSeatKm,
                soldSeatKm,
                utilisation,
                distinctSeatsUsed,
                loadFactor,
                segmentsPerSeat,
                actualRevenue,
                counterfactual,
                upliftPct,
                wholeJourneyCounterfactual,
                occupancyByHop(tripId, bookableSeats),
                revenueBySegment(tripId));
    }

    /**
     * Occupancy for every hop between consecutive stops -- the heatmap that answers "where does the
     * train empty out?", which is the question leadership actually has.
     */
    public List<HopOccupancy> occupancyByHop(UUID tripId, int bookableSeats) {
        return jdbc.query(
                """
                WITH hops AS (
                    SELECT s.stop_sequence AS from_seq,
                           LEAD(s.stop_sequence) OVER (ORDER BY s.stop_sequence) AS to_seq,
                           s.station_code AS from_code,
                           LEAD(s.station_code) OVER (ORDER BY s.stop_sequence) AS to_code,
                           LEAD(s.distance_km) OVER (ORDER BY s.stop_sequence) - s.distance_km AS distance_km
                      FROM trip_stop s WHERE s.trip_id = :tripId
                )
                SELECT h.from_seq, h.to_seq, h.from_code, h.to_code, h.distance_km,
                       (SELECT count(*) FROM booking_segment bs
                         WHERE bs.trip_id = :tripId
                           AND bs.status IN ('HELD','CONFIRMED')
                           -- a segment occupies this hop iff its half-open range contains the hop
                           AND bs.leg @> h.from_seq) AS seats_occupied
                  FROM hops h
                 WHERE h.to_seq IS NOT NULL
                 ORDER BY h.from_seq
                """,
                new MapSqlParameterSource().addValue("tripId", tripId),
                (rs, i) -> {
                    int occupied = rs.getInt("seats_occupied");
                    return new HopOccupancy(
                            rs.getInt("from_seq"),
                            rs.getInt("to_seq"),
                            rs.getString("from_code"),
                            rs.getString("to_code"),
                            rs.getBigDecimal("distance_km"),
                            occupied,
                            bookableSeats,
                            pct(BigDecimal.valueOf(occupied), BigDecimal.valueOf(bookableSeats)));
                });
    }

    public List<SegmentRevenue> revenueBySegment(UUID tripId) {
        return jdbc.query(
                """
                SELECT bs.from_station_code, bs.to_station_code, count(*) AS bookings,
                       SUM(bs.fare_minor) AS revenue_minor, SUM(bs.distance_km) AS seat_km
                  FROM booking_segment bs
                 WHERE bs.trip_id = :tripId AND bs.status IN ('HELD','CONFIRMED')
                 GROUP BY bs.from_station_code, bs.to_station_code
                 ORDER BY revenue_minor DESC
                """,
                new MapSqlParameterSource().addValue("tripId", tripId),
                (rs, i) ->
                        new SegmentRevenue(
                                rs.getString("from_station_code"),
                                rs.getString("to_station_code"),
                                rs.getInt("bookings"),
                                rs.getLong("revenue_minor"),
                                rs.getBigDecimal("seat_km")));
    }

    private static BigDecimal pct(BigDecimal numerator, BigDecimal denominator) {
        if (denominator == null || denominator.signum() == 0) {
            return BigDecimal.ZERO;
        }
        return numerator.multiply(BigDecimal.valueOf(100)).divide(denominator, 1, RoundingMode.HALF_UP);
    }

    private int intOf(String sql, Map<String, ?> params) {
        Integer n = jdbc.queryForObject(sql, params, Integer.class);
        return n == null ? 0 : n;
    }

    private long longOf(String sql, Map<String, ?> params) {
        Long n = jdbc.queryForObject(sql, params, Long.class);
        return n == null ? 0 : n;
    }

    private BigDecimal decimalOf(String sql, Map<String, ?> params) {
        BigDecimal v = jdbc.queryForObject(sql, params, BigDecimal.class);
        return v == null ? BigDecimal.ZERO : v;
    }
}
