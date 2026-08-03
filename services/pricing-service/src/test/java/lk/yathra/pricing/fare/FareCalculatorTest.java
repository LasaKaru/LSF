package lk.yathra.pricing.fare;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * The fare engine's behaviour, including the worked example the documentation publishes.
 *
 * <p>If docs/05 §5 and this test ever disagree, one of them is lying to a reviewer. Pinning the
 * published numbers here is what stops that happening quietly.
 */
class FareCalculatorTest {

    private final FareCalculator calculator = new FareCalculator();

    /** The seeded rule set 2026.08.01, built by hand so the test does not depend on a database. */
    private static FareRules seededRules() {
        return new FareRules(
                "2026.08.01",
                "LKR",
                1000, // round to nearest LKR 10
                2000, // minimum fare LKR 20
                List.of(
                        new FareRules.Band(1, bd("0"), bd("50"), bd("4.3000"), "First 50 km"),
                        new FareRules.Band(2, bd("50"), bd("150"), bd("3.6550"), "Next 100 km"),
                        new FareRules.Band(3, bd("150"), null, bd("3.0100"), "Beyond 150 km")),
                List.of(
                        new FareRules.Multiplier("FIRST", bd("1.6")),
                        new FareRules.Multiplier("SECOND", bd("1.0")),
                        new FareRules.Multiplier("THIRD", bd("0.7")),
                        new FareRules.Multiplier("OBSERVATION", bd("1.0"))),
                List.of(
                        new FareRules.Multiplier("RESERVED", bd("1.0")),
                        new FareRules.Multiplier("UNRESERVED", bd("0.8")),
                        new FareRules.Multiplier("OBSERVATION", bd("1.35"))),
                List.of(
                        new FareRules.ScenicSegment(
                                "MAIN_UPCOUNTRY", "NAN", "ELA", bd("0.15"), "Nanu Oya - Ella scenic section")),
                List.of(),
                List.of());
    }

    private static BigDecimal bd(String v) {
        return new BigDecimal(v);
    }

    private FareCalculator.Request leg(String km, String scenicKm) {
        return new FareCalculator.Request(
                bd(km), "SECOND", "RESERVED", bd(scenicKm), bd("0.15"), "Nanu Oya - Ella", null, null, 1);
    }

    // ------------------------------------------------------ the published example

    /**
     * Reproduces docs/05 §5.1, with one deliberate difference.
     *
     * <p>The published table shows band subtotals rounded to 2dp and sums the displayed values:
     * {@code 71 x 3.655 = 259.505} is printed as {@code 259.51}, giving a total of {@code 474.51}. The
     * engine keeps full precision and rounds exactly once at the end -- which is what docs/05 §2
     * itself mandates ("never on intermediate values, which would compound rounding error across
     * bands"). So the exact base is {@code 474.505}, and the table is a display artefact rather than a
     * disagreement.
     *
     * <p>The final fare is identical either way (LKR 470), so nothing a passenger sees changes.
     */
    @Test
    @DisplayName("★ telescopic base reproduces docs/05 §5.1 (at full precision)")
    void telescopicBaseMatchesPublishedTable() {
        var rules = seededRules();

        assertThat(calculator.telescopicBase(rules, bd("121"), null))
                .isEqualByComparingTo(bd("474.505")); // 50x4.30 + 71x3.655
        assertThat(calculator.telescopicBase(rules, bd("171"), null))
                .isEqualByComparingTo(bd("643.71")); // 215 + 365.50 + 21x3.010
        assertThat(calculator.telescopicBase(rules, bd("292"), null))
                .isEqualByComparingTo(bd("1007.92")); // 215 + 365.50 + 142x3.010
    }

    @Test
    @DisplayName("★ the brief's exact scenario: one seat, sold twice, for more than it earns today")
    void theHeadlineRevenueScenario() {
        var rules = seededRules();

        // Colombo Fort -> Kandy, 121 km, no scenic overlap.
        long fortToKandy = calculator.calculate(rules, leg("121", "0")).totalMinor();
        // Kandy -> Badulla, 171 km, all 71 scenic km travelled.
        long kandyToBadulla = calculator.calculate(rules, leg("171", "71")).totalMinor();
        // Colombo Fort -> Badulla, 292 km, all 71 scenic km travelled.
        long fortToBadulla = calculator.calculate(rules, leg("292", "71")).totalMinor();

        assertThat(fortToKandy).isEqualTo(47_000L); // LKR 470
        assertThat(kandyToBadulla).isEqualTo(68_000L); // LKR 680
        assertThat(fortToBadulla).isEqualTo(104_000L); // LKR 1,040

        // The point of the whole project, as a number: today that seat carries one Fort->Kandy
        // passenger for ~LKR 760 and then runs empty for 171 km. Under segment booking it earns
        // LKR 1,150 -- while the short-leg passenger pays LKR 470 instead of ~760.
        assertThat(fortToKandy + kandyToBadulla).isEqualTo(115_000L);
        assertThat(fortToKandy + kandyToBadulla).isGreaterThan(fortToBadulla);
    }

    @Test
    @DisplayName("the reserved coach carries no empty-seat surcharge; unreserved is 0.80")
    void reservedIsNoLongerPenalised() {
        var rules = seededRules();

        long reserved = calculator.calculate(rules, leg("121", "0")).totalMinor();
        long unreserved =
                calculator
                        .calculate(
                                rules,
                                new FareCalculator.Request(
                                        bd("121"), "SECOND", "UNRESERVED", bd("0"), bd("0.15"), null, null, null, 1))
                        .totalMinor();

        assertThat(unreserved).isEqualTo(38_000L); // LKR 380

        // The brief's grievance is that reserved costs ~2x unreserved for the same partial leg.
        // It is now 1.24x -- a defensible premium for a guaranteed seat rather than a subsidy for
        // dead space.
        double ratio = (double) reserved / unreserved;
        assertThat(ratio).isLessThan(1.25).isGreaterThan(1.0);
    }

    // ------------------------------------------------------------- band boundaries

    @ParameterizedTest(name = "{0} km -> base {1}")
    @CsvSource({
        "49,  210.70",   // wholly inside band 1
        "50,  215.00",   // exactly the band 1/2 boundary
        "51,  218.655",  // one km into band 2
        "149, 576.845",  // 215 + 99 x 3.655
        "150, 580.50",   // exactly the band 2/3 boundary: 215 + 100 x 3.655
        "151, 583.51"    // one km into band 3: 580.50 + 3.010
    })
    @DisplayName("band boundaries and their off-by-ones")
    void bandBoundaries(String km, String expectedBase) {
        assertThat(calculator.telescopicBase(seededRules(), bd(km), null))
                .isEqualByComparingTo(bd(expectedBase));
    }

    @Test
    @DisplayName("a very short hop is floored at the minimum fare")
    void minimumFareApplies() {
        var rules = seededRules();
        var result =
                calculator.calculate(
                        rules,
                        new FareCalculator.Request(
                                bd("0.5"), "SECOND", "RESERVED", bd("0"), null, null, null, null, 1));

        assertThat(result.totalMinor()).isGreaterThanOrEqualTo(2000L);
        assertThat(result.breakdown()).anyMatch(l -> l.code().equals("MINIMUM_FARE"));
    }

    // ------------------------------------------------------------ scenic surcharge

    @Test
    @DisplayName("scenic surcharge is zero outside the scenic stretch and pro-rata within it")
    void scenicSurchargeIsChargedOnlyOnKmTravelled() {
        var rules = seededRules();

        long noOverlap = calculator.calculate(rules, leg("171", "0")).totalMinor();
        long partialOverlap = calculator.calculate(rules, leg("171", "35")).totalMinor();
        long fullOverlap = calculator.calculate(rules, leg("171", "71")).totalMinor();

        assertThat(noOverlap).isLessThan(partialOverlap);
        assertThat(partialOverlap).isLessThan(fullOverlap);

        // A Fort->Kandy passenger never reaches the scenic section and pays nothing towards it.
        assertThat(calculator.calculate(rules, leg("121", "0")).breakdown())
                .noneMatch(l -> l.code().equals("SCENIC_SURCHARGE"));
    }

    // -------------------------------------------------------- the additivity guard

    /**
     * FARE-1, stated exactly: the <em>unrounded</em> fare is subadditive.
     *
     * <p>This is the mathematically meaningful form of the claim, and it holds for any concave,
     * monotonically increasing fare function -- which a non-increasing marginal-rate band structure
     * is. "It holds by construction" stops being true the moment someone adds a fourth band with a
     * rate higher than the third, so it is a test rather than a comment.
     */
    @Test
    @DisplayName("★ FARE-1: the unrounded fare is subadditive for every split point")
    void unroundedWholeJourneyIsNeverMoreExpensiveThanItsParts() {
        var rules = seededRules();
        int checked = 0;

        for (int total = 2; total <= 292; total++) {
            BigDecimal whole = calculator.telescopicBase(rules, BigDecimal.valueOf(total), null);
            for (int split = 1; split < total; split++) {
                BigDecimal parts =
                        calculator
                                .telescopicBase(rules, BigDecimal.valueOf(split), null)
                                .add(calculator.telescopicBase(rules, BigDecimal.valueOf(total - split), null));
                assertThat(whole)
                        .as("base(%d) must not exceed base(%d) + base(%d)", total, split, total - split)
                        .isLessThanOrEqualTo(parts);
                checked++;
            }
        }
        assertThat(checked).isGreaterThan(40_000);
    }

    /**
     * FARE-1 under rounding: subadditive <b>to within one rounding increment</b>.
     *
     * <p>This is a correction to docs/05 §3.1, which claims the exact property survives rounding. It
     * does not, and the reason is worth stating precisely:
     *
     * <p>Below 50 km the fare sits entirely in band 1, where the rate is constant and the base fare is
     * therefore <em>exactly linear</em>. Concavity contributes zero headroom, so rounding is the only
     * term left -- and rounding the whole up while rounding both halves down costs up to one
     * increment. Concretely, at the seeded rates two 8 km legs are LKR 30 each (LKR 60) while one
     * 16 km leg is LKR 70.
     *
     * <p>Why this is acceptable rather than a defect to engineer away:
     *
     * <ul>
     *   <li>The leak is bounded by <b>one rounding increment per split</b> (LKR 10), against the cost
     *       and friction of issuing an extra ticket.
     *   <li>It cannot be compounded. Splitting repeatedly runs into concavity and the minimum fare,
     *       both of which grow far faster -- the 292 km journey split into 24 legs costs well over the
     *       through fare.
     *   <li>No rounding mode removes it. FLOOR merely moves which pairs violate it; the granularity
     *       itself is the cause.
     * </ul>
     *
     * <p>The alternative -- distorting a published public tariff to satisfy a property it misses by
     * LKR 10 -- would be the wrong trade for a state operator whose fares must be explainable.
     */
    @Test
    @DisplayName("★ FARE-1 under rounding: subadditive to within one rounding increment")
    void roundedWholeJourneyIsSubadditiveWithinOneIncrement() {
        var rules = seededRules();
        long increment = rules.roundingMinor();
        long worstExcess = 0;

        for (int total = 2; total <= 292; total++) {
            long whole = bareFare(rules, total);
            for (int split = 1; split < total; split++) {
                long parts = bareFare(rules, split) + bareFare(rules, total - split);
                worstExcess = Math.max(worstExcess, whole - parts);
                assertThat(whole)
                        .as("fare(%d) may exceed fare(%d)+fare(%d) by at most one increment",
                                total, split, total - split)
                        .isLessThanOrEqualTo(parts + increment);
            }
        }

        // Pin the observed worst case, so a future rate change that widens it fails loudly here.
        assertThat(worstExcess).isLessThanOrEqualTo(increment);
    }

    @Test
    @DisplayName("★ fare is monotonic: a longer leg is never cheaper than a shorter one")
    void fareIsMonotonicInDistance() {
        var rules = seededRules();
        long previous = 0;
        for (int km = 1; km <= 292; km++) {
            long fare = bareFare(rules, km);
            assertThat(fare).as("fare at %d km", km).isGreaterThanOrEqualTo(previous);
            previous = fare;
        }
    }

    /** Distance-only fare: no scenic, no multipliers, so the property tests isolate the band maths. */
    private long bareFare(FareRules rules, int km) {
        return calculator
                .calculate(
                        rules,
                        new FareCalculator.Request(
                                BigDecimal.valueOf(km), "SECOND", "RESERVED", BigDecimal.ZERO, null, null, null, null, 1))
                .totalMinor();
    }

    // ---------------------------------------------------------------- rounding

    @Test
    @DisplayName("rounding is applied once, at the end, to the nearest LKR 10")
    void roundingHappensOnceAtTheEnd() {
        assertThat(FareCalculator.roundTo(47_451L, 1000L)).isEqualTo(47_000L);
        assertThat(FareCalculator.roundTo(67_577L, 1000L)).isEqualTo(68_000L);
        assertThat(FareCalculator.roundTo(103_998L, 1000L)).isEqualTo(104_000L);
        assertThat(FareCalculator.roundTo(500L, 1000L)).isEqualTo(1000L); // exact half rounds up
    }

    @Test
    @DisplayName("multi-passenger fares are the unit fare multiplied, rounded once")
    void multiPassengerFare() {
        var rules = seededRules();
        var four =
                calculator.calculate(
                        rules,
                        new FareCalculator.Request(
                                bd("121"), "SECOND", "RESERVED", bd("0"), null, null, null, null, 4));
        assertThat(four.totalMinor()).isEqualTo(47_000L * 4);
    }
}
