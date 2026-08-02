package lk.yathra.pricing.quote;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import lk.yathra.common.api.ApiException;
import lk.yathra.common.api.ErrorCode;
import lk.yathra.pricing.config.PricingProperties;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * Reads trip topology from booking-service, which owns it.
 *
 * <p>pricing-service needs stop sequences and cumulative distances to price a leg, and it gets them
 * over the API rather than by reaching into another service's database (principle P5). This call is
 * synchronous, which is fine: it happens while a user waits for a quote and, crucially, <b>not</b>
 * inside any database transaction. The rule the design actually enforces is "no synchronous call
 * inside a transaction" -- which is also why booking-service verifies the resulting quote with a local
 * HMAC check rather than calling back here.
 */
@Component
public class TripClient {

    private final RestClient restClient;

    public TripClient(PricingProperties properties, RestClient.Builder builder) {
        this.restClient = builder.baseUrl(properties.getBookingServiceUrl()).build();
    }

    public record Stop(int stopSequence, String stationCode, BigDecimal distanceKm) {}

    public record TripSummary(UUID id, String routeCode, java.time.LocalDate serviceDate, Instant departsAt) {}

    public record TripDetail(TripSummary trip, List<Stop> stops) {}

    public TripDetail fetch(UUID tripId) {
        try {
            TripDetail detail =
                    restClient.get().uri("/api/v1/trips/{id}", tripId).retrieve().body(TripDetail.class);
            if (detail == null || detail.stops() == null || detail.stops().isEmpty()) {
                throw new ApiException(ErrorCode.TRIP_NOT_FOUND, "Trip not found or has no stops.");
            }
            return detail;
        } catch (ApiException e) {
            throw e;
        } catch (RestClientException e) {
            // Fail as an upstream problem rather than an internal one: the caller can retry, and the
            // distinction matters when reading an incident timeline.
            throw new ApiException(
                    ErrorCode.UPSTREAM_UNAVAILABLE, "Could not read trip details in order to price this leg.", e);
        }
    }
}
