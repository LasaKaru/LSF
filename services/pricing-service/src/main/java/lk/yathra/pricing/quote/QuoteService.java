package lk.yathra.pricing.quote;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import lk.yathra.common.api.ApiException;
import lk.yathra.common.api.ErrorCode;
import lk.yathra.common.domain.Leg;
import lk.yathra.common.quote.QuoteSigner;
import lk.yathra.pricing.config.PricingProperties;
import lk.yathra.pricing.fare.FareCalculator;
import lk.yathra.pricing.fare.FareRuleRepository;
import lk.yathra.pricing.fare.FareRules;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

/** Prices a leg and issues a signed, expiring, itemised quote. */
@Service
public class QuoteService {

    private final FareRuleRepository rules;
    private final FareCalculator calculator;
    private final TripClient trips;
    private final NamedParameterJdbcTemplate jdbc;
    private final ObjectMapper objectMapper;
    private final PricingProperties properties;
    private final QuoteSigner signer;

    public QuoteService(
            FareRuleRepository rules,
            FareCalculator calculator,
            TripClient trips,
            NamedParameterJdbcTemplate jdbc,
            ObjectMapper objectMapper,
            PricingProperties properties) {
        this.rules = rules;
        this.calculator = calculator;
        this.trips = trips;
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
        this.properties = properties;
        this.signer = new QuoteSigner(properties.getQuoteSigningKey());
    }

    public record QuoteRequest(
            UUID tripId, String fromStation, String toStation, String classCode, String coachType, Integer passengers) {}

    public record QuoteResponse(
            UUID quoteId,
            UUID tripId,
            String fromStation,
            String toStation,
            int fromSeq,
            int toSeq,
            BigDecimal distanceKm,
            String classCode,
            String coachType,
            int passengers,
            String currency,
            List<FareCalculator.LineItem> breakdown,
            long unitFareMinor,
            long totalMinor,
            String ruleSetVersion,
            Instant issuedAt,
            Instant expiresAt,
            String signature) {}

    public QuoteResponse quote(QuoteRequest request) {
        var trip = trips.fetch(request.tripId());
        FareRules ruleSet = rules.loadEffective();

        String from = request.fromStation().trim().toUpperCase();
        String to = request.toStation().trim().toUpperCase();

        int fromSeq = seqOf(trip, from);
        int toSeq = seqOf(trip, to);
        if (fromSeq >= toSeq) {
            throw new ApiException(
                    ErrorCode.INVALID_JOURNEY_LEG,
                    "This trip serves " + from + " after " + to + "; you may want the opposite direction.");
        }
        Leg leg = Leg.of(fromSeq, toSeq);

        BigDecimal distance = distanceAt(trip, toSeq).subtract(distanceAt(trip, fromSeq));

        String classCode = request.classCode() == null ? "SECOND" : request.classCode().toUpperCase();
        String coachType = request.coachType() == null ? "RESERVED" : request.coachType().toUpperCase();
        int passengers = request.passengers() == null ? 1 : Math.max(1, request.passengers());

        // Scenic overlap, in kilometres actually travelled within the premium stretch.
        BigDecimal scenicKm = BigDecimal.ZERO;
        BigDecimal scenicUplift = null;
        String scenicLabel = null;
        if (properties.isScenicSurchargeEnabled()) {
            for (var segment : FareRuleRepository.scenicFor(ruleSet, trip.trip().routeCode())) {
                var overlap = scenicOverlapKm(trip, leg, segment);
                if (overlap.isPresent() && overlap.get().signum() > 0) {
                    scenicKm = overlap.get();
                    scenicUplift = segment.uplift();
                    scenicLabel = segment.label();
                    break;
                }
            }
        }

        long daysAhead =
                ChronoUnit.DAYS.between(LocalDate.now(), trip.trip().serviceDate() == null ? LocalDate.now() : trip.trip().serviceDate());
        BigDecimal advanceDiscount = FareRuleRepository.advanceDiscount(ruleSet, Math.max(0, daysAhead));

        var result =
                calculator.calculate(
                        ruleSet,
                        new FareCalculator.Request(
                                distance,
                                classCode,
                                coachType,
                                scenicKm,
                                scenicUplift,
                                scenicLabel,
                                // Demand pricing is a policy switch, off unless the department turns it on.
                                properties.isDemandPricingEnabled() ? BigDecimal.ONE : null,
                                advanceDiscount,
                                passengers));

        long unitFare = result.totalMinor() / passengers;
        UUID quoteId = UUID.randomUUID();
        Instant issuedAt = Instant.now();
        Instant expiresAt = issuedAt.plusSeconds(properties.getQuoteTtlSeconds());

        String signature =
                signer.sign(
                        new QuoteSigner.Payload(
                                quoteId,
                                request.tripId(),
                                fromSeq,
                                toSeq,
                                classCode,
                                coachType,
                                passengers,
                                result.totalMinor(),
                                ruleSet.currency(),
                                ruleSet.version(),
                                expiresAt));

        persist(quoteId, request.tripId(), from, to, fromSeq, toSeq, distance, classCode, coachType,
                passengers, unitFare, result, ruleSet, signature, expiresAt);

        return new QuoteResponse(
                quoteId,
                request.tripId(),
                from,
                to,
                fromSeq,
                toSeq,
                distance,
                classCode,
                coachType,
                passengers,
                ruleSet.currency(),
                result.breakdown(),
                unitFare,
                result.totalMinor(),
                ruleSet.version(),
                issuedAt,
                expiresAt,
                signature);
    }

    /**
     * Kilometres of {@code leg} that fall inside the scenic segment.
     *
     * <p>The segment is configured by station codes, and resolved to this trip's sequences with
     * min/max -- so the same physical stretch is found whether the service runs up or down, without a
     * direction branch.
     */
    private Optional<BigDecimal> scenicOverlapKm(
            TripClient.TripDetail trip, Leg leg, FareRules.ScenicSegment segment) {

        Integer a = seqOrNull(trip, segment.fromStationCode());
        Integer b = seqOrNull(trip, segment.toStationCode());
        if (a == null || b == null) {
            return Optional.empty();
        }
        Leg scenic = Leg.of(Math.min(a, b), Math.max(a, b));

        Leg intersection = leg.intersect(scenic);
        if (intersection == null) {
            return Optional.empty();
        }
        return Optional.of(distanceAt(trip, intersection.toSeq()).subtract(distanceAt(trip, intersection.fromSeq())));
    }

    private static int seqOf(TripClient.TripDetail trip, String stationCode) {
        Integer seq = seqOrNull(trip, stationCode);
        if (seq == null) {
            throw new ApiException(
                            ErrorCode.STATION_NOT_ON_TRIP, "Station " + stationCode + " is not served by this trip.")
                    .with("station", stationCode);
        }
        return seq;
    }

    private static Integer seqOrNull(TripClient.TripDetail trip, String stationCode) {
        return trip.stops().stream()
                .filter(s -> s.stationCode().equalsIgnoreCase(stationCode))
                .map(TripClient.Stop::stopSequence)
                .findFirst()
                .orElse(null);
    }

    private static BigDecimal distanceAt(TripClient.TripDetail trip, int seq) {
        return trip.stops().stream()
                .filter(s -> s.stopSequence() == seq)
                .map(TripClient.Stop::distanceKm)
                .findFirst()
                .orElseThrow(
                        () -> new ApiException(ErrorCode.INVALID_JOURNEY_LEG, "Unknown stop sequence " + seq));
    }

    private void persist(
            UUID quoteId, UUID tripId, String from, String to, int fromSeq, int toSeq, BigDecimal distance,
            String classCode, String coachType, int passengers, long unitFare, FareCalculator.Result result,
            FareRules ruleSet, String signature, Instant expiresAt) {
        try {
            jdbc.update(
                    """
                    INSERT INTO quote (id, trip_id, from_station_code, to_station_code, from_seq, to_seq,
                                       distance_km, class_code, coach_type, passengers, unit_fare_minor,
                                       total_minor, currency, breakdown, rule_set_version, signature, expires_at)
                    VALUES (:id, :tripId, :from, :to, :fromSeq, :toSeq, :distance,
                            CAST(:classCode AS class_enum), CAST(:coachType AS coach_type_enum), :passengers,
                            :unitFare, :total, :currency, CAST(:breakdown AS jsonb), :version, :signature, :expiresAt)
                    """,
                    new MapSqlParameterSource()
                            .addValue("id", quoteId)
                            .addValue("tripId", tripId)
                            .addValue("from", from)
                            .addValue("to", to)
                            .addValue("fromSeq", fromSeq)
                            .addValue("toSeq", toSeq)
                            .addValue("distance", distance)
                            .addValue("classCode", classCode)
                            .addValue("coachType", coachType)
                            .addValue("passengers", passengers)
                            .addValue("unitFare", unitFare)
                            .addValue("total", result.totalMinor())
                            .addValue("currency", ruleSet.currency())
                            .addValue("breakdown", objectMapper.writeValueAsString(result.breakdown()))
                            .addValue("version", ruleSet.version())
                            .addValue("signature", signature)
                            .addValue("expiresAt", Timestamp.from(expiresAt)));
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException("Unable to serialise fare breakdown", e);
        }
    }

    public FareRules currentRules() {
        return rules.loadEffective();
    }
}
