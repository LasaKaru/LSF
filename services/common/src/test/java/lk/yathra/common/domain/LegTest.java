package lk.yathra.common.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * The overlap truth table from docs/04 §3.1.
 *
 * <p>The first row is the one the entire project exists for: {@code [1,9)} and {@code [9,25)} must NOT
 * overlap, so one physical seat can be sold Colombo Fort to Kandy and again Kandy to Badulla. An
 * implementation that fails only that row looks correct in every other respect and has silently
 * rebuilt whole-journey booking.
 */
class LegTest {

    @ParameterizedTest(name = "[{0},{1}) vs [{2},{3}) -> overlap={4}  ({5})")
    @CsvSource({
        // a.from, a.to, b.from, b.to, expected, description
        "1,  9,  9, 25, false, 'adjacent forward - THE headline scenario: Fort-Kandy then Kandy-Badulla'",
        "9, 25,  1,  9, false, 'adjacent backward - symmetric, insertion order is irrelevant'",
        "1,  5, 12, 20, false, 'disjoint with a gap - seat empty in between, sold twice'",
        "1,  9,  5, 15, true,  'partial overlap - B boards while A is still seated'",
        "1, 25,  9, 11, true,  'containment - a whole-journey booking blocks everything'",
        "9, 11,  1, 25, true,  'reverse containment - symmetric'",
        "1,  9,  1,  9, true,  'identical - duplicate sale'",
        "1,  9,  1, 25, true,  'shared start'",
        "1, 25,  9, 25, true,  'shared end'",
        "1,  2,  2,  3, false, 'minimal adjacent single-stop hops'",
        "1,  3,  2,  4, true,  'minimal partial overlap'"
    })
    @DisplayName("half-open overlap is symmetric and treats adjacency as free")
    void overlapTruthTable(int aFrom, int aTo, int bFrom, int bTo, boolean expected, String description) {
        Leg a = Leg.of(aFrom, aTo);
        Leg b = Leg.of(bFrom, bTo);

        assertThat(a.overlaps(b)).as(description).isEqualTo(expected);
        // Overlap must be commutative; asserting both directions catches an asymmetric predicate,
        // which is a real bug class when someone "optimises" the comparison.
        assertThat(b.overlaps(a)).as(description + " (commutative)").isEqualTo(expected);
    }

    @Test
    @DisplayName("a degenerate leg [9,9) cannot be constructed")
    void degenerateLegRejected() {
        assertThatThrownBy(() -> Leg.of(9, 9))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("non-empty");
    }

    @Test
    @DisplayName("an inverted leg [9,1) cannot be constructed")
    void invertedLegRejected() {
        assertThatThrownBy(() -> Leg.of(9, 1)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("a leg cannot start before the first stop")
    void legBeforeFirstStopRejected() {
        assertThatThrownBy(() -> Leg.of(0, 5))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("stop sequence 1");
    }

    @Test
    void validLegIsConstructed() {
        assertThatCode(() -> Leg.of(1, 25)).doesNotThrowAnyException();
        assertThat(Leg.of(1, 9).spanStops()).isEqualTo(8);
    }

    @Test
    @DisplayName("containment is used by the waitlist matcher: an entry must fit inside a release")
    void containment() {
        Leg released = Leg.of(9, 25);
        assertThat(released.contains(Leg.of(9, 25))).isTrue();
        assertThat(released.contains(Leg.of(12, 20))).isTrue();
        assertThat(released.contains(Leg.of(9, 15))).isTrue();
        // Extends before the released stretch, so it does not fit.
        assertThat(released.contains(Leg.of(1, 15))).isFalse();
    }

    @Test
    void intersectionAndOverlapStops() {
        assertThat(Leg.of(1, 9).intersect(Leg.of(5, 15))).isEqualTo(Leg.of(5, 9));
        assertThat(Leg.of(1, 9).intersect(Leg.of(9, 25))).isNull();
        assertThat(Leg.of(1, 9).overlapStops(Leg.of(5, 15))).isEqualTo(4);
        assertThat(Leg.of(1, 9).overlapStops(Leg.of(9, 25))).isZero();
    }

    @Test
    @DisplayName("the PostgreSQL literal is always half-open")
    void rangeLiteralIsHalfOpen() {
        assertThat(Leg.of(1, 9).toRangeLiteral()).isEqualTo("[1,9)");
    }

    /**
     * Exhaustive cross-check of {@link Leg#overlaps} against a brute-force set-intersection over the
     * seeded 25-stop route. If the arithmetic predicate and the naive definition ever disagree, this
     * fails -- which is the kind of guarantee an example-based test cannot give.
     */
    @Test
    @DisplayName("the arithmetic predicate agrees with brute-force set intersection for every leg pair")
    void overlapMatchesBruteForceForAllLegPairsOnTheRoute() {
        int stops = 25;
        int checked = 0;
        for (int aFrom = 1; aFrom < stops; aFrom++) {
            for (int aTo = aFrom + 1; aTo <= stops; aTo++) {
                for (int bFrom = 1; bFrom < stops; bFrom++) {
                    for (int bTo = bFrom + 1; bTo <= stops; bTo++) {
                        boolean arithmetic = Leg.of(aFrom, aTo).overlaps(Leg.of(bFrom, bTo));
                        boolean bruteForce = sharesAnyHop(aFrom, aTo, bFrom, bTo);
                        assertThat(arithmetic)
                                .as("[%d,%d) vs [%d,%d)", aFrom, aTo, bFrom, bTo)
                                .isEqualTo(bruteForce);
                        checked++;
                    }
                }
            }
        }
        assertThat(checked).isGreaterThan(80_000);
    }

    /**
     * A leg occupies the seat on the hops between its stops: [from, to) covers hops from..to-1. Two
     * legs conflict iff they share a hop. This is the definition the arithmetic must implement.
     */
    private static boolean sharesAnyHop(int aFrom, int aTo, int bFrom, int bTo) {
        for (int hop = aFrom; hop < aTo; hop++) {
            if (hop >= bFrom && hop < bTo) {
                return true;
            }
        }
        return false;
    }
}
