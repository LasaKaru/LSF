package lk.yathra.booking.booking;

import java.time.Instant;
import java.util.UUID;
import lk.yathra.booking.booking.BookingDtos.QuoteRef;
import lk.yathra.booking.config.BookingProperties;
import lk.yathra.common.api.ApiException;
import lk.yathra.common.api.ErrorCode;
import lk.yathra.common.domain.Leg;
import lk.yathra.common.quote.QuoteSigner;
import org.springframework.stereotype.Component;

/**
 * Verifies a fare quote locally, inside the booking transaction, with no network call.
 *
 * <p>That "no network call" is the point. docs/02 §6.1 states the rule plainly: no synchronous call
 * inside a database transaction. If booking-service had to ask pricing-service whether a quote was
 * valid, pricing-service's latency would become the duration of an open transaction holding index
 * entries on contended seats -- and a pricing outage would become a booking outage.
 */
@Component
public class QuoteVerifier {

    private final QuoteSigner signer;

    public QuoteVerifier(BookingProperties properties) {
        this.signer = new QuoteSigner(properties.getQuoteSigningKey());
    }

    /**
     * @param expectedTripId the trip actually being booked
     * @param expectedLeg the leg actually being booked, resolved server-side from station codes
     * @param seatCount how many seats the booking will take
     */
    public void verify(QuoteRef quote, UUID expectedTripId, Leg expectedLeg, int seatCount) {
        if (quote == null) {
            throw new ApiException(ErrorCode.QUOTE_INVALID, "A fare quote is required to book.");
        }

        if (quote.expiresAt().isBefore(Instant.now())) {
            throw new ApiException(
                            ErrorCode.QUOTE_INVALID,
                            "This fare quote expired at " + quote.expiresAt() + ". Please request a fresh quote.")
                    .with("expiredAt", quote.expiresAt().toString());
        }

        // Bind the price to what is actually being sold. Without these four checks a signature is
        // still valid but a cheap Fort->Kandy quote could be redeemed for a Fort->Badulla booking.
        if (!quote.tripId().equals(expectedTripId)) {
            throw new ApiException(ErrorCode.QUOTE_INVALID, "Quote was issued for a different trip.");
        }
        if (quote.fromSeq() != expectedLeg.fromSeq() || quote.toSeq() != expectedLeg.toSeq()) {
            throw new ApiException(
                            ErrorCode.QUOTE_INVALID, "Quote was issued for a different leg of the journey.")
                    .with("quotedLeg", new int[] {quote.fromSeq(), quote.toSeq()})
                    .with("requestedLeg", new int[] {expectedLeg.fromSeq(), expectedLeg.toSeq()});
        }
        if (quote.passengers() != seatCount) {
            throw new ApiException(
                            ErrorCode.QUOTE_INVALID,
                            "Quote covers " + quote.passengers() + " passenger(s) but " + seatCount + " seat(s) were requested.")
                    .with("quotedPassengers", quote.passengers())
                    .with("requestedSeats", seatCount);
        }

        var payload =
                new QuoteSigner.Payload(
                        quote.quoteId(),
                        quote.tripId(),
                        quote.fromSeq(),
                        quote.toSeq(),
                        quote.classCode(),
                        quote.coachType(),
                        quote.passengers(),
                        quote.totalMinor(),
                        quote.currency(),
                        quote.ruleSetVersion(),
                        quote.expiresAt());

        if (!signer.verify(payload, quote.signature())) {
            // Deliberately vague to the client, loud in the logs: a failed signature is either a bug
            // or an attack, and telling the caller which field was wrong helps only the attacker.
            throw new ApiException(
                    ErrorCode.QUOTE_INVALID, "Fare quote signature is not valid. Please request a fresh quote.");
        }
    }
}
