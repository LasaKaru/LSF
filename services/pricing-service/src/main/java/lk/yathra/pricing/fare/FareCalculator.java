package lk.yathra.pricing.fare;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * The fare engine.
 *
 * <pre>
 * segmentFare = round( max( MINIMUM_FARE,
 *                 telescopicBase(km)
 *                   x classMultiplier x coachMultiplier
 *                   x demandMultiplier x (1 - advanceDiscount)
 *                 + scenicSurcharge(overlapping km only) ) )
 * </pre>
 *
 * <p>The ordering is deliberate:
 *
 * <ul>
 *   <li><b>Multiplicative</b> factors (class, coach, demand, advance) scale with distance, so a first
 *       class ticket costs proportionally more on a long leg than on a short one.
 *   <li><b>Scenic is additive</b> and computed only on the scenic kilometres actually travelled. Making
 *       it multiplicative would apply it to the whole journey and overcharge a Fort->Kandy passenger
 *       for a view they never see.
 *   <li><b>Minimum fare</b> applies before rounding, so a 3 km hop is never priced below the cost of
 *       issuing the ticket.
 *   <li><b>Rounding happens last, once.</b> Rounding intermediate values compounds error across bands.
 * </ul>
 *
 * <p>Note what is <b>absent</b>: there is no reserved-coach surcharge. It existed only to cover the
 * seat sitting empty for the rest of the route, and the seat now resells, so keeping it would be
 * indefensible. That single missing multiplier is the fare-side fix to the brief's grievance.
 *
 * <p>All arithmetic is {@link BigDecimal}. Money is never a float: rates are exact decimals and the
 * result is an integer count of minor units.
 */
@Component
public class FareCalculator {

    /** One line of the itemised breakdown the passenger sees. */
    public record LineItem(String code, String label, long amountMinor) {}

    public record Result(long totalMinor, List<LineItem> breakdown) {}

    public record Request(
            BigDecimal distanceKm,
            String classCode,
            String coachType,
            BigDecimal scenicOverlapKm,
            BigDecimal scenicUplift,
            String scenicLabel,
            BigDecimal demandMultiplier,
            BigDecimal advanceDiscount,
            int passengers) {}

    private static final int SCALE = 6;

    public Result calculate(FareRules rules, Request request) {
        List<LineItem> breakdown = new ArrayList<>();

        BigDecimal base = telescopicBase(rules, request.distanceKm(), breakdown);

        BigDecimal classMultiplier = rules.classMultiplier(request.classCode());
        BigDecimal coachMultiplier = rules.coachMultiplier(request.coachType());
        BigDecimal demand = request.demandMultiplier() == null ? BigDecimal.ONE : request.demandMultiplier();
        BigDecimal advance = request.advanceDiscount() == null ? BigDecimal.ZERO : request.advanceDiscount();

        BigDecimal scaled =
                base.multiply(classMultiplier)
                        .multiply(coachMultiplier)
                        .multiply(demand)
                        .multiply(BigDecimal.ONE.subtract(advance))
                        .setScale(SCALE, RoundingMode.HALF_UP);

        addMultiplierLine(breakdown, "CLASS_MULT", "Class " + request.classCode(), classMultiplier);
        addMultiplierLine(breakdown, "COACH_MULT", coachLabel(request.coachType()), coachMultiplier);
        if (demand.compareTo(BigDecimal.ONE) != 0) {
            addMultiplierLine(breakdown, "DEMAND_MULT", "Demand adjustment", demand);
        }
        if (advance.compareTo(BigDecimal.ZERO) != 0) {
            breakdown.add(
                    new LineItem(
                            "ADVANCE_DISCOUNT",
                            "Advance purchase discount "
                                    + advance.multiply(BigDecimal.valueOf(100)).stripTrailingZeros().toPlainString()
                                    + "%",
                            toMinor(scaled.subtract(base.multiply(classMultiplier).multiply(coachMultiplier).multiply(demand)))));
        }

        BigDecimal scenic = scenicSurcharge(rules, request, breakdown);
        BigDecimal beforeMinimum = scaled.add(scenic);

        BigDecimal minimum = BigDecimal.valueOf(rules.minimumFareMinor()).movePointLeft(2);
        BigDecimal afterMinimum = beforeMinimum.max(minimum);
        if (afterMinimum.compareTo(beforeMinimum) != 0) {
            breakdown.add(
                    new LineItem("MINIMUM_FARE", "Minimum fare applied", toMinor(afterMinimum.subtract(beforeMinimum))));
        }

        long unroundedMinor = toMinor(afterMinimum);
        long roundedMinor = roundTo(unroundedMinor, rules.roundingMinor());
        if (roundedMinor != unroundedMinor) {
            breakdown.add(
                    new LineItem(
                            "ROUNDING",
                            "Rounded to nearest " + rules.currency() + " " + rules.roundingMinor() / 100,
                            roundedMinor - unroundedMinor));
        }

        long total = roundedMinor * Math.max(1, request.passengers());
        return new Result(total, breakdown);
    }

    /**
     * Telescopic base fare: marginal, cumulative, exactly like income-tax brackets.
     *
     * <p>The marginal rate per km <em>falls</em> as distance rises, which reflects genuine cost
     * structure (fixed per-ticket costs amortise over distance) and protects long-haul affordability.
     */
    public BigDecimal telescopicBase(FareRules rules, BigDecimal km, List<LineItem> breakdown) {
        BigDecimal total = BigDecimal.ZERO;

        for (FareRules.Band band : rules.bands()) {
            BigDecimal upper = band.upperKm() == null ? km : band.upperKm().min(km);
            BigDecimal kmInBand = upper.subtract(band.lowerKm()).max(BigDecimal.ZERO);
            if (kmInBand.signum() == 0) {
                continue;
            }
            BigDecimal amount = kmInBand.multiply(band.ratePerKm());
            total = total.add(amount);
            if (breakdown != null) {
                breakdown.add(
                        new LineItem(
                                "BASE_BAND_" + band.order(),
                                kmInBand.stripTrailingZeros().toPlainString() + " km @ " + band.ratePerKm().stripTrailingZeros().toPlainString(),
                                toMinor(amount)));
            }
        }
        return total.setScale(SCALE, RoundingMode.HALF_UP);
    }

    /**
     * The scenic premium, charged only on the scenic kilometres actually travelled.
     *
     * <p>The marginal rate applied is the rate of the band containing the journey's <em>total</em>
     * distance. That is a simplification and worth naming: the scenic stretch sits at the far end of
     * the route, so any journey reaching it is already long enough to be in the top band, which makes
     * the simple rule and a positional rule agree on this route. On a route where a scenic section sat
     * near the origin they would diverge, and this is the line that would need revisiting.
     */
    private BigDecimal scenicSurcharge(FareRules rules, Request request, List<LineItem> breakdown) {
        BigDecimal overlapKm = request.scenicOverlapKm();
        if (overlapKm == null || overlapKm.signum() <= 0 || request.scenicUplift() == null) {
            return BigDecimal.ZERO;
        }

        BigDecimal marginalRate = marginalRateAt(rules, request.distanceKm());
        BigDecimal surcharge =
                overlapKm.multiply(marginalRate).multiply(request.scenicUplift()).setScale(SCALE, RoundingMode.HALF_UP);

        breakdown.add(
                new LineItem(
                        "SCENIC_SURCHARGE",
                        (request.scenicLabel() == null ? "Scenic section" : request.scenicLabel())
                                + " "
                                + overlapKm.stripTrailingZeros().toPlainString()
                                + " km",
                        toMinor(surcharge)));
        return surcharge;
    }

    /** The rate of the band containing {@code km}. */
    public BigDecimal marginalRateAt(FareRules rules, BigDecimal km) {
        BigDecimal rate = BigDecimal.ZERO;
        for (FareRules.Band band : rules.bands()) {
            if (km.compareTo(band.lowerKm()) > 0) {
                rate = band.ratePerKm();
            }
        }
        return rate;
    }

    private static void addMultiplierLine(
            List<LineItem> breakdown, String code, String label, BigDecimal multiplier) {
        breakdown.add(
                new LineItem(code, label + " x" + multiplier.stripTrailingZeros().toPlainString(), 0));
    }

    private static String coachLabel(String coachType) {
        return "RESERVED".equalsIgnoreCase(coachType)
                // Say it out loud in the breakdown the passenger reads.
                ? "Reserved coach (no empty-seat surcharge)"
                : coachType + " coach";
    }

    /** Major currency units -> integer minor units. */
    private static long toMinor(BigDecimal major) {
        return major.movePointRight(2).setScale(0, RoundingMode.HALF_UP).longValueExact();
    }

    /** Round a minor-unit amount to the nearest {@code increment}, half up. */
    static long roundTo(long minor, long increment) {
        if (increment <= 1) {
            return minor;
        }
        long half = increment / 2;
        return ((minor + half) / increment) * increment;
    }
}
