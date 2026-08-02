package lk.yathra.booking.trip;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * The payload catalog-service sends to publish a trip.
 *
 * <p>This is an anti-corruption boundary, not a shared schema: booking-service translates it into its
 * own snapshot tables. If catalog restructures how routes are stored, only this translation changes.
 */
public final class TripSnapshotDtos {

    private TripSnapshotDtos() {}

    public record PublishTripRequest(
            @NotNull UUID trainId,
            @NotBlank String trainCode,
            @NotBlank String trainNameEn,
            @NotBlank String routeCode,
            @NotNull LocalDate serviceDate,
            @NotBlank String direction,
            @NotNull Instant departsAt,
            @NotNull Instant arrivesAt,
            @NotNull Instant bookingCutoffAt,
            @NotEmpty @Valid List<Stop> stops,
            @NotEmpty @Valid List<Coach> coaches) {}

    public record Stop(
            int stopSequence,
            @NotNull UUID stationId,
            @NotBlank String stationCode,
            @NotBlank String nameEn,
            String nameSi,
            String nameTa,
            @NotNull BigDecimal distanceKm,
            Instant scheduledArrival,
            Instant scheduledDeparture) {}

    /**
     * A coach carries its layout grid rather than a list of seats: booking-service materialises the
     * seats from the grid. One materialiser, one definition of what a layout means, and adding a coach
     * type stays a database row rather than a code change on both sides of the wire.
     */
    public record Coach(
            @NotBlank String coachNumber,
            @NotBlank String coachType,
            @NotBlank String classCode,
            boolean reservable,
            int positionIndex,
            JsonNode layout,
            Integer capacity) {}

    public record PublishTripResponse(UUID tripId, String status, int stops, int coaches, int seats) {}
}
