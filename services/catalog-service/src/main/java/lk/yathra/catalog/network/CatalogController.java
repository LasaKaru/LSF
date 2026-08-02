package lk.yathra.catalog.network;

import java.util.List;
import java.util.UUID;
import lk.yathra.catalog.publish.TripPublisher;
import lk.yathra.common.api.ApiException;
import lk.yathra.common.api.ErrorCode;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
public class CatalogController {

    private final CatalogRepository catalog;
    private final TripPublisher publisher;

    public CatalogController(CatalogRepository catalog, TripPublisher publisher) {
        this.catalog = catalog;
        this.publisher = publisher;
    }

    /**
     * All stations, with names in all three official scripts.
     *
     * <p>Translations travel with the data rather than living in the frontend bundle, so adding a
     * station translates it everywhere at once and needs no deployment.
     */
    @GetMapping("/stations")
    public ResponseEntity<List<CatalogRepository.Station>> stations() {
        return ResponseEntity.ok()
                // The network changes on the timescale of civil engineering projects.
                .cacheControl(CacheControl.maxAge(java.time.Duration.ofMinutes(10)).cachePublic())
                .body(catalog.findStations());
    }

    @GetMapping("/routes")
    public ResponseEntity<List<CatalogRepository.Route>> routes() {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.maxAge(java.time.Duration.ofMinutes(10)).cachePublic())
                .body(catalog.findRoutes());
    }

    @GetMapping("/routes/{code}/stops")
    public List<CatalogRepository.RouteStop> routeStops(
            @PathVariable String code, @RequestParam(defaultValue = "UP") String direction) {

        UUID routeId =
                catalog.findRoutes().stream()
                        .filter(r -> r.code().equalsIgnoreCase(code))
                        .map(CatalogRepository.Route::id)
                        .findFirst()
                        .orElseThrow(() -> new ApiException(ErrorCode.TRIP_NOT_FOUND, "Route " + code + " not found."));

        return catalog.findRouteStops(routeId, direction);
    }

    /**
     * Re-publish the horizon on demand.
     *
     * <p>Exists because "add a station, then re-publish future trips" is the documented procedure for
     * extending the route, and because it makes that procedure demonstrable in an interview without
     * waiting for the nightly job. Idempotent, so calling it twice is harmless.
     */
    @PostMapping("/internal/publish")
    public PublishSummary publish() {
        return new PublishSummary(publisher.publishHorizon());
    }

    public record PublishSummary(int published) {}
}
