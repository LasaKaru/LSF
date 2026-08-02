package lk.yathra.booking.booking;

import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import lk.yathra.common.domain.Leg;

/**
 * Chooses seats when the passenger did not pick one.
 *
 * <p>"Which seat do we give them?" looks trivial and is in fact the lever that decides how much of the
 * resale revenue the department actually captures. Assign carelessly and you punch small holes in the
 * middle of otherwise-empty seats, leaving stretches too short for anyone to buy.
 */
public interface SeatSelectionStrategy {

    /** A candidate seat and the stretches already sold on it. */
    record Candidate(
            UUID seatId,
            String coachNumber,
            String seatLabel,
            int rowIndex,
            boolean window,
            boolean aisle,
            List<int[]> occupied) {}

    record Preferences(Boolean window, Boolean aisle) {
        public static final Preferences NONE = new Preferences(null, null);
    }

    String name();

    /**
     * @param routeEndSeq the trip's last stop sequence, which bounds the free stretch at the end of a
     *     seat. Passed in rather than injected because it is per-trip, not per-deployment.
     */
    List<UUID> select(
            List<Candidate> candidates, Leg leg, int count, Preferences preferences, int routeEndSeq);

    // ---------------------------------------------------------------- helpers

    /**
     * The maximal free stretch on a seat that contains {@code leg}, bounded by the neighbouring sold
     * segments. Used by the fragmentation-minimising strategy as its "hole size".
     */
    static int freeGapContaining(List<int[]> occupied, Leg leg, int routeEndSeq) {
        int gapStart = 1;
        int gapEnd = routeEndSeq;
        for (int[] range : occupied) {
            if (range[1] <= leg.fromSeq()) {
                gapStart = Math.max(gapStart, range[1]); // ends before us
            } else if (range[0] >= leg.toSeq()) {
                gapEnd = Math.min(gapEnd, range[0]); // starts after us
            }
        }
        return gapEnd - gapStart;
    }

    /** Applies window/aisle preferences as a sort key; unstated preferences cost nothing. */
    static Comparator<Candidate> byPreference(Preferences preferences) {
        return Comparator.comparingInt(
                (Candidate c) -> {
                    int penalty = 0;
                    if (preferences.window() != null && preferences.window() && !c.window()) {
                        penalty += 2;
                    }
                    if (preferences.aisle() != null && preferences.aisle() && !c.aisle()) {
                        penalty += 2;
                    }
                    return penalty;
                });
    }
}
