package lk.yathra.booking.booking;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import lk.yathra.booking.config.BookingProperties;
import lk.yathra.common.domain.Leg;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The shipped seat-selection policies.
 *
 * <p>They are separate implementations behind one interface rather than flags on one method, because
 * "add a new preference rule" is a change a reviewer might reasonably ask for live -- and an additive
 * one is a new class plus a config value, not surgery on a method everything depends on.
 */
@Configuration
public class SeatSelectionStrategies {

    /** Simplest possible: take them in coach and row order. Predictable and easy to reason about. */
    public static class FirstAvailable implements SeatSelectionStrategy {
        @Override
        public String name() {
            return "FIRST_AVAILABLE";
        }

        @Override
        public List<UUID> select(
                List<Candidate> candidates, Leg leg, int count, Preferences preferences, int routeEndSeq) {
            return candidates.stream().limit(count).map(Candidate::seatId).toList();
        }
    }

    /** Honours window/aisle first, then falls back to natural order. */
    public static class PreferenceMatching implements SeatSelectionStrategy {
        @Override
        public String name() {
            return "PREFERENCE_MATCHING";
        }

        @Override
        public List<UUID> select(
                List<Candidate> candidates, Leg leg, int count, Preferences preferences, int routeEndSeq) {
            return candidates.stream()
                    .sorted(
                            SeatSelectionStrategy.byPreference(preferences)
                                    .thenComparing(Candidate::coachNumber)
                                    .thenComparing(Candidate::rowIndex)
                                    .thenComparing(Candidate::seatLabel))
                    .limit(count)
                    .map(Candidate::seatId)
                    .toList();
        }
    }

    /**
     * Best-fit packing: prefer the seat where this leg fits most tightly between already-sold
     * stretches.
     *
     * <p>The intuition is the one that makes segment inventory pay. Booking Kandy->Badulla on a seat
     * that already carries Fort->Kandy consumes a stretch that was hard to sell anyway. Booking the
     * same leg on a completely empty seat leaves a 121 km hole at the front of it -- sellable, but only
     * to a passenger travelling exactly that stretch.
     *
     * <p>It is a heuristic, deliberately. True optimal interval packing is NP-hard, and an offline
     * optimum is worthless regardless because bookings arrive online and we cannot see tomorrow's.
     * Best-fit is the standard, cheap, defensible approximation.
     */
    public static class FragmentationMinimising implements SeatSelectionStrategy {
        @Override
        public String name() {
            return "FRAGMENTATION_MINIMISING";
        }

        @Override
        public List<UUID> select(
                List<Candidate> candidates, Leg leg, int count, Preferences preferences, int routeEndSeq) {
            return candidates.stream()
                    .sorted(
                            Comparator.comparingInt(
                                            (Candidate c) ->
                                                    SeatSelectionStrategy.freeGapContaining(
                                                            c.occupied(), leg, routeEndSeq))
                                    .thenComparing(SeatSelectionStrategy.byPreference(preferences))
                                    .thenComparing(Candidate::coachNumber)
                                    .thenComparing(Candidate::rowIndex)
                                    .thenComparing(Candidate::seatLabel))
                    .limit(count)
                    .map(Candidate::seatId)
                    .toList();
        }
    }

    /**
     * Resolves the configured strategy by name at startup and fails fast on an unknown value, rather
     * than silently falling back to a policy nobody asked for.
     */
    @Bean
    public SeatSelectionStrategy seatSelectionStrategy(BookingProperties properties) {
        Map<String, SeatSelectionStrategy> byName =
                List.of(new FirstAvailable(), new PreferenceMatching(), new FragmentationMinimising()).stream()
                        .collect(Collectors.toMap(SeatSelectionStrategy::name, s -> s));

        String configured = properties.getSeatSelectionStrategy();
        SeatSelectionStrategy chosen = byName.get(configured);
        if (chosen == null) {
            throw new IllegalStateException(
                    "Unknown SEAT_SELECTION_STRATEGY '"
                            + configured
                            + "'. Valid values: "
                            + String.join(", ", byName.keySet()));
        }
        return chosen;
    }
}
