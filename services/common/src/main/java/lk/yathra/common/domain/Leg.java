package lk.yathra.common.domain;

import java.util.Objects;

/**
 * A journey leg: the half-open integer interval {@code [fromSeq, toSeq)} over a trip's stop
 * sequence.
 *
 * <p>This is the single most important type in the system, and the half-open convention is the
 * single most important thing about it. Two legs overlap iff
 *
 * <pre>{@code a.fromSeq < b.toSeq && b.fromSeq < a.toSeq}</pre>
 *
 * <p>Under that rule {@code [1,9)} (Colombo Fort to Kandy) and {@code [9,25)} (Kandy to Badulla)
 * are <em>adjacent, not overlapping</em>, so the same physical seat can be sold to both passengers.
 * That is the brief's headline requirement. Had closed ranges {@code [1,9]} and {@code [9,25]} been
 * used, Kandy would belong to both bookings and the requirement would fail.
 *
 * <p>The type is deliberately not a bare pair of ints: a private constructor plus a validating
 * factory means an invalid or inverted leg cannot exist in memory, so no caller has to remember to
 * check. The database enforces the same rule independently via {@code CHECK (from_seq < to_seq)} --
 * belt and braces, because the two live in different places and either could be bypassed alone.
 *
 * @see <a href="../../../../../../docs/04-segment-concurrency-design.md">docs/04 §3</a>
 */
public final class Leg {

    private final int fromSeq;
    private final int toSeq;

    private Leg(int fromSeq, int toSeq) {
        this.fromSeq = fromSeq;
        this.toSeq = toSeq;
    }

    /**
     * @throws IllegalArgumentException if the leg is degenerate ({@code from == to}), inverted
     *     ({@code from > to}) or starts before the first stop.
     */
    public static Leg of(int fromSeq, int toSeq) {
        if (fromSeq < 1) {
            throw new IllegalArgumentException(
                    "leg must start at stop sequence 1 or later, got " + fromSeq);
        }
        if (fromSeq >= toSeq) {
            throw new IllegalArgumentException(
                    "leg must be non-empty and forward-ordered: [" + fromSeq + "," + toSeq + ")");
        }
        return new Leg(fromSeq, toSeq);
    }

    public int fromSeq() {
        return fromSeq;
    }

    public int toSeq() {
        return toSeq;
    }

    /** Number of stop-to-stop hops this leg spans. */
    public int spanStops() {
        return toSeq - fromSeq;
    }

    /**
     * Half-open overlap. Adjacency ({@code this.toSeq == other.fromSeq}) is deliberately NOT an
     * overlap -- that is the entire point of the model.
     */
    public boolean overlaps(Leg other) {
        return this.fromSeq < other.toSeq && other.fromSeq < this.toSeq;
    }

    /** True when {@code other} lies entirely within this leg. Used by the waitlist matcher. */
    public boolean contains(Leg other) {
        return this.fromSeq <= other.fromSeq && other.toSeq <= this.toSeq;
    }

    /** Number of stop-hops shared with {@code other}; zero when they do not overlap. */
    public int overlapStops(Leg other) {
        return Math.max(0, Math.min(this.toSeq, other.toSeq) - Math.max(this.fromSeq, other.fromSeq));
    }

    /** The intersection of two legs, or null when they do not overlap. */
    public Leg intersect(Leg other) {
        int lo = Math.max(this.fromSeq, other.fromSeq);
        int hi = Math.min(this.toSeq, other.toSeq);
        return lo < hi ? new Leg(lo, hi) : null;
    }

    /** PostgreSQL literal form, always half-open. */
    public String toRangeLiteral() {
        return "[" + fromSeq + "," + toSeq + ")";
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof Leg other)) return false;
        return fromSeq == other.fromSeq && toSeq == other.toSeq;
    }

    @Override
    public int hashCode() {
        return Objects.hash(fromSeq, toSeq);
    }

    @Override
    public String toString() {
        return toRangeLiteral();
    }
}
