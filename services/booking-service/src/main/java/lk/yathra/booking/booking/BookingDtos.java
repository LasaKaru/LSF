package lk.yathra.booking.booking;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class BookingDtos {

    private BookingDtos() {}

    /**
     * The signed quote, passed back verbatim as pricing-service issued it.
     *
     * <p>The client is a courier here, not an author: booking-service recomputes the signature over
     * these exact fields and rejects the booking if anything was altered, so a tampered {@code
     * totalMinor} or a leg swapped for a longer one fails closed.
     */
    public record QuoteRef(
            @NotNull UUID quoteId,
            @NotNull UUID tripId,
            int fromSeq,
            int toSeq,
            @NotBlank String classCode,
            @NotBlank String coachType,
            int passengers,
            long unitFareMinor,
            long totalMinor,
            @NotBlank String currency,
            @NotBlank String ruleSetVersion,
            @NotNull Instant expiresAt,
            @NotBlank String signature) {}

    /**
     * {@code SPECIFIC} when the passenger picked seats on the map; {@code AUTO} to let the configured
     * SeatSelectionStrategy assign them.
     */
    public record SeatSelection(String mode, List<UUID> seatIds, Boolean window, Boolean aisle) {
        public boolean isSpecific() {
            return seatIds != null && !seatIds.isEmpty();
        }
    }

    public record Passenger(@NotBlank String name, String type) {}

    public record Contact(@Email String email, String phone, String name) {}

    public record CreateBookingRequest(
            @NotNull UUID tripId,
            @NotBlank String from,
            @NotBlank String to,
            @Valid SeatSelection seatSelection,
            @Valid QuoteRef quote,
            List<@Valid Passenger> passengers,
            @Valid Contact contact) {}

    public record SegmentView(
            UUID seatId,
            String seatLabel,
            String coachNumber,
            String from,
            String to,
            int fromSeq,
            int toSeq,
            BigDecimal distanceKm,
            long fareMinor) {}

    public record BookingResponse(
            UUID bookingId,
            String reference,
            String status,
            Instant expiresAt,
            Long holdSeconds,
            long totalMinor,
            String currency,
            List<SegmentView> segments) {}

    /** Ranked replacements offered alongside a 409 so the UI can recover in one click. */
    public record AlternativeSeat(UUID seatId, String seatLabel, String coachNumber, boolean window) {}

    public record ConflictDetail(UUID seatId, String seatLabel, int[] occupiedLeg) {}
}
