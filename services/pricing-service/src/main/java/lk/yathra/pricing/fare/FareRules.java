package lk.yathra.pricing.fare;

import java.math.BigDecimal;
import java.util.List;

/**
 * An immutable, effective-dated snapshot of one fare rule set.
 *
 * <p>Rule sets are versioned and never mutated in place: publishing new fares creates a new version.
 * That is what makes a historical quote reproducible months later when a passenger disputes a charge,
 * and it means a bad fare change is rolled back by changing an effective date rather than by a
 * deployment.
 */
public record FareRules(
        String version,
        String currency,
        long roundingMinor,
        long minimumFareMinor,
        List<Band> bands,
        List<Multiplier> classMultipliers,
        List<Multiplier> coachMultipliers,
        List<ScenicSegment> scenicSegments,
        List<DemandTier> demandTiers,
        List<AdvanceTier> advanceTiers) {

    /** A telescopic band. {@code upperKm} null means the open-ended top band. */
    public record Band(int order, BigDecimal lowerKm, BigDecimal upperKm, BigDecimal ratePerKm, String label) {}

    public record Multiplier(String code, BigDecimal value) {}

    public record ScenicSegment(
            String routeCode, String fromStationCode, String toStationCode, BigDecimal uplift, String label) {}

    public record DemandTier(BigDecimal lowerPct, BigDecimal upperPct, BigDecimal multiplier) {}

    public record AdvanceTier(int minDaysAhead, Integer maxDaysAhead, BigDecimal discount) {}

    public BigDecimal classMultiplier(String classCode) {
        return lookup(classMultipliers, classCode);
    }

    public BigDecimal coachMultiplier(String coachType) {
        return lookup(coachMultipliers, coachType);
    }

    private static BigDecimal lookup(List<Multiplier> multipliers, String code) {
        return multipliers.stream()
                .filter(m -> m.code().equalsIgnoreCase(code))
                .map(Multiplier::value)
                .findFirst()
                .orElse(BigDecimal.ONE);
    }
}
