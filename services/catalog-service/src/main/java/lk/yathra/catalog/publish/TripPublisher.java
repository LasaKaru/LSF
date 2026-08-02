package lk.yathra.catalog.publish;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import lk.yathra.catalog.config.CatalogProperties;
import lk.yathra.catalog.network.CatalogRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * Publishes trips for the booking horizon by sending each one's topology to booking-service.
 *
 * <p>This is the anti-corruption boundary in action: catalog owns routes and consists, booking owns
 * inventory, and the handover is an API call carrying a snapshot rather than either service reading
 * the other's database.
 *
 * <p>It runs on startup and again daily, and it is <b>idempotent at two levels</b>: catalog records
 * what it has published in {@code trip_publication}, and booking-service independently returns
 * {@code ALREADY_PUBLISHED} for a trip it already holds. Either alone would be enough; both means a
 * restart, a re-run, or a replayed message can never duplicate or -- much worse -- rebuild a trip whose
 * seats already carry sold bookings.
 *
 * <p>Startup publication retries with backoff rather than failing, because on a cold
 * {@code docker compose up} this service can easily be ready before booking-service is.
 */
@Component
public class TripPublisher {

    private static final Logger log = LoggerFactory.getLogger(TripPublisher.class);

    private final CatalogRepository catalog;
    private final CatalogProperties properties;
    private final RestClient restClient;
    private final ObjectMapper objectMapper;

    public TripPublisher(
            CatalogRepository catalog,
            CatalogProperties properties,
            RestClient.Builder builder,
            ObjectMapper objectMapper) {
        this.catalog = catalog;
        this.properties = properties;
        this.restClient = builder.baseUrl(properties.getBookingServiceUrl()).build();
        this.objectMapper = objectMapper;
    }

    @EventListener(ApplicationReadyEvent.class)
    @Async
    public void publishOnStartup() {
        if (!properties.isAutoPublishEnabled()) {
            log.info("trip.publish.disabled");
            return;
        }
        for (int attempt = 1; attempt <= 30; attempt++) {
            try {
                int published = publishHorizon();
                log.info("trip.publish.startup published={} attempt={}", published, attempt);
                return;
            } catch (Exception e) {
                long waitMs = Math.min(15_000, 1000L * attempt);
                log.warn(
                        "trip.publish.startup_retry attempt={} waitMs={} reason={}",
                        attempt, waitMs, e.getMessage());
                try {
                    Thread.sleep(waitMs);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }
        log.error("trip.publish.startup_failed - booking-service never became reachable");
    }

    /** Rolls the horizon forward once a day so there is always the configured window on sale. */
    @Scheduled(cron = "${yathra.catalog.publish-cron:0 15 2 * * *}")
    public void publishDaily() {
        if (properties.isAutoPublishEnabled()) {
            log.info("trip.publish.daily published={}", publishHorizon());
        }
    }

    public int publishHorizon() {
        int published = 0;
        LocalDate today = LocalDate.now();

        for (var schedule : catalog.findActiveSchedules()) {
            var stops = catalog.findRouteStops(schedule.routeId(), schedule.direction());
            var coaches = catalog.findComposition(schedule.trainId());

            for (int day = 0; day < properties.getBookingHorizonDays(); day++) {
                LocalDate serviceDate = today.plusDays(day);
                if (catalog.isPublished(schedule.scheduleId(), serviceDate)) {
                    continue;
                }
                UUID tripId = publishOne(schedule, stops, coaches, serviceDate);
                if (tripId != null) {
                    catalog.recordPublication(schedule.scheduleId(), serviceDate, tripId);
                    published++;
                }
            }
        }
        return published;
    }

    private UUID publishOne(
            CatalogRepository.ScheduleRow schedule,
            List<CatalogRepository.RouteStop> stops,
            List<CatalogRepository.CoachRow> coaches,
            LocalDate serviceDate) {

        Instant departure = serviceDate.atTime(schedule.departsTime()).toInstant(ZoneOffset.UTC);

        List<Object> stopPayload = new ArrayList<>();
        Instant lastDeparture = departure;
        for (int i = 0; i < stops.size(); i++) {
            var stop = stops.get(i);
            // Scheduled times are DERIVED from distance, an average speed and a per-stop dwell.
            // This is an explicit simplification -- a real deployment imports the published SLR
            // timetable. It is confined to these four lines precisely so that swap is local, and
            // nothing about inventory depends on it: occupancy is indexed by stop sequence, never
            // by clock time.
            long travelMinutes =
                    stop.distanceKm()
                            .divide(schedule.avgSpeedKmh(), 6, RoundingMode.HALF_UP)
                            .multiply(BigDecimal.valueOf(60))
                            .setScale(0, RoundingMode.HALF_UP)
                            .longValue();
            Instant arrival = departure.plus(travelMinutes + (long) i * schedule.dwellMinutes(), ChronoUnit.MINUTES);
            Instant dep = arrival.plus(schedule.dwellMinutes(), ChronoUnit.MINUTES);
            lastDeparture = arrival;

            stopPayload.add(
                    java.util.Map.of(
                            "stopSequence", stop.stopOrder(),
                            "stationId", stop.stationId().toString(),
                            "stationCode", stop.code(),
                            "nameEn", stop.nameEn(),
                            "nameSi", stop.nameSi() == null ? "" : stop.nameSi(),
                            "nameTa", stop.nameTa() == null ? "" : stop.nameTa(),
                            "distanceKm", stop.distanceKm(),
                            "scheduledArrival", arrival.toString(),
                            "scheduledDeparture", dep.toString()));
        }

        List<Object> coachPayload = new ArrayList<>();
        for (var coach : coaches) {
            var map = new java.util.HashMap<String, Object>();
            map.put("coachNumber", coach.coachNumber());
            map.put("coachType", coach.coachType());
            map.put("classCode", coach.classCode());
            map.put("reservable", coach.reservable());
            map.put("positionIndex", coach.positionIndex());
            map.put("layout", readJson(coach.layoutJson()));
            map.put("capacity", coach.capacity());
            coachPayload.add(map);
        }

        var body = new java.util.HashMap<String, Object>();
        body.put("trainId", schedule.trainId().toString());
        body.put("trainCode", schedule.trainCode());
        body.put("trainNameEn", schedule.trainNameEn());
        body.put("routeCode", schedule.routeCode());
        body.put("serviceDate", serviceDate.toString());
        body.put("direction", schedule.direction());
        body.put("departsAt", departure.toString());
        body.put("arrivesAt", lastDeparture.toString());
        body.put(
                "bookingCutoffAt",
                departure.minus(properties.getBookingCutoffMinutes(), ChronoUnit.MINUTES).toString());
        body.put("stops", stopPayload);
        body.put("coaches", coachPayload);

        var response =
                restClient
                        .post()
                        .uri("/api/v1/internal/trips")
                        .body(body)
                        .retrieve()
                        .body(PublishResponse.class);

        return response == null ? null : response.tripId();
    }

    private JsonNode readJson(String json) {
        if (json == null) {
            return null;
        }
        try {
            return objectMapper.readTree(json);
        } catch (Exception e) {
            log.warn("trip.publish.bad_layout_json", e);
            return null;
        }
    }

    public record PublishResponse(UUID tripId, String status, int stops, int coaches, int seats) {}
}
