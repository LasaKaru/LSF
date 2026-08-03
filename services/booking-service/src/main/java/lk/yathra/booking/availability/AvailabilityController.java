package lk.yathra.booking.availability;

import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/trips/{tripId}")
public class AvailabilityController {

    private final AvailabilityService availability;
    private final JourneyResolver journeys;

    public AvailabilityController(AvailabilityService availability, JourneyResolver journeys) {
        this.availability = availability;
        this.journeys = journeys;
    }

    /**
     * Availability for a specific leg, not for the whole train.
     *
     * <p>That distinction is the entire product. "How many seats are free on this train" is
     * meaningless once inventory is segment-granular -- a seat can be free Fort to Kandy and sold
     * Kandy to Badulla, so it is simultaneously available and not.
     *
     * <p>The response is explicitly a snapshot, not a reservation. Only POST /bookings is
     * authoritative; this is cached for a few seconds precisely because it is advisory.
     */
    @GetMapping("/availability")
    public ResponseEntity<AvailabilityService.AvailabilitySummary> availability(
            @PathVariable UUID tripId,
            @RequestParam String from,
            @RequestParam String to,
            @RequestParam(name = "class", required = false) String classCode) {

        var journey = journeys.resolve(tripId, from, to);
        var summary = availability.availability(journey, classCode);

        return ResponseEntity.ok()
                .cacheControl(CacheControl.maxAge(java.time.Duration.ofSeconds(5)))
                .eTag(Integer.toHexString(summary.hashCode()))
                .body(summary);
    }

    /**
     * The seat map, returning occupancy ranges per seat rather than a boolean.
     *
     * <p>Ranges are what make the "partially available" (amber) state expressible, and they carry no
     * passenger data of any kind -- so the UI cannot leak identities because the endpoint never had
     * them.
     */
    @GetMapping("/seat-map")
    public ResponseEntity<AvailabilityService.SeatMapView> seatMap(
            @PathVariable UUID tripId,
            @RequestParam String from,
            @RequestParam String to,
            @RequestParam(name = "class", required = false) String classCode) {

        var journey = journeys.resolve(tripId, from, to);
        return ResponseEntity.ok()
                .cacheControl(CacheControl.maxAge(java.time.Duration.ofSeconds(5)))
                .body(availability.seatMap(journey, classCode));
    }
}
