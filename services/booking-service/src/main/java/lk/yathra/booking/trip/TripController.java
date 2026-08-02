package lk.yathra.booking.trip;

import jakarta.validation.Valid;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import lk.yathra.booking.trip.TripSnapshotDtos.PublishTripRequest;
import lk.yathra.booking.trip.TripSnapshotDtos.PublishTripResponse;
import lk.yathra.common.api.ApiException;
import lk.yathra.common.api.ErrorCode;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
public class TripController {

    private final TripRepository trips;
    private final TripPublicationService publication;

    public TripController(TripRepository trips, TripPublicationService publication) {
        this.trips = trips;
        this.publication = publication;
    }

    public record TripSummary(
            UUID id,
            String trainCode,
            String trainName,
            String routeCode,
            LocalDate serviceDate,
            String direction,
            java.time.Instant departsAt,
            java.time.Instant arrivesAt,
            java.time.Instant bookingCutoffAt) {}

    public record TripDetail(TripSummary trip, List<TripRepository.StopRow> stops, List<CoachSummary> coaches) {}

    public record CoachSummary(
            String coachNumber, String coachType, String classCode, boolean reservable, Integer capacity) {}

    @GetMapping("/trips")
    public List<TripSummary> search(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
            @RequestParam(required = false) String routeCode,
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to) {

        return trips.search(date, routeCode, upper(from), upper(to)).stream().map(TripController::toSummary).toList();
    }

    @GetMapping("/trips/{tripId}")
    public TripDetail detail(@PathVariable UUID tripId) {
        var trip =
                trips.findById(tripId)
                        .orElseThrow(() -> new ApiException(ErrorCode.TRIP_NOT_FOUND, "Trip not found."));
        var coaches =
                trips.findCoaches(tripId).stream()
                        .map(
                                c ->
                                        new CoachSummary(
                                                c.coachNumber(), c.coachType(), c.classCode(), c.reservable(), c.capacity()))
                        .toList();
        return new TripDetail(toSummary(trip), trips.findStops(tripId), coaches);
    }

    /**
     * Internal: catalog-service publishes a trip by POSTing its snapshot here.
     *
     * <p>Under {@code /internal} rather than the public API because it is service-to-service, and in
     * a deployed environment the gateway does not route {@code /internal/**} from outside at all.
     */
    @PostMapping("/internal/trips")
    public PublishTripResponse publish(@Valid @RequestBody PublishTripRequest request) {
        return publication.publish(request);
    }

    private static String upper(String s) {
        return s == null ? null : s.trim().toUpperCase();
    }

    private static TripSummary toSummary(TripRepository.TripRow t) {
        return new TripSummary(
                t.id(),
                t.trainCode(),
                t.trainNameEn(),
                t.routeCode(),
                t.serviceDate(),
                t.direction(),
                t.departsAt(),
                t.arrivesAt(),
                t.bookingCutoffAt());
    }
}
