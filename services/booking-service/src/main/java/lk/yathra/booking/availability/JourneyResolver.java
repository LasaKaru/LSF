package lk.yathra.booking.availability;

import java.math.BigDecimal;
import java.util.UUID;
import lk.yathra.booking.trip.TripRepository;
import lk.yathra.common.api.ApiException;
import lk.yathra.common.api.ErrorCode;
import lk.yathra.common.domain.Leg;
import org.springframework.stereotype.Component;

/**
 * Turns a pair of station codes into a validated {@link Leg} on a specific trip.
 *
 * <p>Every request that touches inventory goes through here, so the validation rules live in exactly
 * one place and every endpoint rejects a bad journey identically.
 *
 * <p>Note there is no direction handling. An UP service and a DOWN service are separate trips with
 * independently ascending stop sequences, so "Badulla to Colombo" resolves to an ascending leg on the
 * DOWN trip and to a rejected reversed leg on the UP trip -- which is exactly right, and falls out of
 * the data rather than out of a branch.
 */
@Component
public class JourneyResolver {

    private final TripRepository trips;

    public JourneyResolver(TripRepository trips) {
        this.trips = trips;
    }

    public record ResolvedJourney(UUID tripId, String fromCode, String toCode, Leg leg, BigDecimal distanceKm) {}

    public ResolvedJourney resolve(UUID tripId, String fromCode, String toCode) {
        if (fromCode == null || toCode == null || fromCode.isBlank() || toCode.isBlank()) {
            throw new ApiException(ErrorCode.INVALID_JOURNEY_LEG, "Both 'from' and 'to' stations are required.");
        }
        String from = fromCode.trim().toUpperCase();
        String to = toCode.trim().toUpperCase();

        if (from.equals(to)) {
            throw new ApiException(
                    ErrorCode.INVALID_JOURNEY_LEG, "Origin and destination must differ (both were " + from + ").");
        }

        int fromSeq =
                trips.resolveStopSequence(tripId, from)
                        .orElseThrow(
                                () ->
                                        new ApiException(
                                                        ErrorCode.STATION_NOT_ON_TRIP,
                                                        "Station " + from + " is not served by this trip.")
                                                .with("station", from));
        int toSeq =
                trips.resolveStopSequence(tripId, to)
                        .orElseThrow(
                                () ->
                                        new ApiException(
                                                        ErrorCode.STATION_NOT_ON_TRIP,
                                                        "Station " + to + " is not served by this trip.")
                                                .with("station", to));

        if (fromSeq >= toSeq) {
            throw new ApiException(
                            ErrorCode.INVALID_JOURNEY_LEG,
                            "This trip serves " + from + " after " + to + "; you may want the service in the other direction.")
                    .with("fromSeq", fromSeq)
                    .with("toSeq", toSeq);
        }

        BigDecimal distance =
                trips.distanceBetween(tripId, fromSeq, toSeq)
                        .orElseThrow(
                                () ->
                                        new ApiException(
                                                ErrorCode.INVALID_JOURNEY_LEG,
                                                "Could not determine the distance for this leg."));

        return new ResolvedJourney(tripId, from, to, Leg.of(fromSeq, toSeq), distance);
    }
}
