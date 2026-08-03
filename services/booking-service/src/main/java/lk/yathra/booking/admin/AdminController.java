package lk.yathra.booking.admin;

import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Departmental reporting.
 *
 * <p><b>These endpoints are unauthenticated in the compose profile.</b> The topology ships without an
 * identity provider, and gating them would mean standing up Keycloak just to look at a dashboard, so
 * the gateway routes them openly and the reporting is usable in a demo.
 *
 * <p>In any real deployment they sit behind the {@code admin:read} scope, validated at the gateway and
 * re-checked here, because occupancy and revenue are commercially sensitive and the booking search is
 * a route to passenger data. That gap is stated in the README rather than left for a reviewer to
 * discover, and it is the first thing to close before this goes anywhere near production.
 */
@RestController
@RequestMapping("/api/v1/admin")
public class AdminController {

    private final AdminReportingService reporting;

    public AdminController(AdminReportingService reporting) {
        this.reporting = reporting;
    }

    /**
     * @param wholeJourneyFareMinor optional published through fare; supplying it adds the resale
     *     uplift counterfactual to the report. Omitted rather than estimated when absent.
     */
    @GetMapping("/trips/{tripId}/report")
    public AdminReportingService.TripReport report(
            @PathVariable UUID tripId, @RequestParam(required = false) Long wholeJourneyFareMinor) {
        return reporting.tripReport(tripId, wholeJourneyFareMinor);
    }

    @GetMapping("/trips/{tripId}/occupancy")
    public Object occupancy(@PathVariable UUID tripId) {
        var report = reporting.tripReport(tripId, null);
        return java.util.Map.of(
                "tripId", tripId,
                "bookableSeats", report.bookableSeats(),
                "seatKmUtilisationPct", report.seatKmUtilisationPct(),
                "conventionalLoadFactorPct", report.conventionalLoadFactorPct(),
                "segmentsPerSeat", report.segmentsPerSeat(),
                "occupancy", report.occupancy());
    }

    @GetMapping("/trips/{tripId}/revenue")
    public Object revenue(@PathVariable UUID tripId) {
        var report = reporting.tripReport(tripId, null);
        return java.util.Map.of(
                "tripId", tripId,
                "actualRevenueMinor", report.actualRevenueMinor(),
                "soldSeatKm", report.soldSeatKm(),
                "revenueBySegment", report.revenueBySegment());
    }
}
