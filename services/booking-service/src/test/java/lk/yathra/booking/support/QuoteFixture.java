package lk.yathra.booking.support;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import lk.yathra.booking.booking.BookingDtos.QuoteRef;
import lk.yathra.common.quote.QuoteSigner;

/** Issues correctly signed quotes for tests, exactly as pricing-service would. */
public final class QuoteFixture {

    private static final QuoteSigner SIGNER = new QuoteSigner(TestKeys.QUOTE_SIGNING_KEY);

    private QuoteFixture() {}

    public static QuoteRef quote(UUID tripId, int fromSeq, int toSeq, int passengers, long unitFareMinor) {
        UUID quoteId = UUID.randomUUID();
        long total = unitFareMinor * passengers;
        Instant expiresAt = Instant.now().plus(10, ChronoUnit.MINUTES);
        String version = "2026.08.01";

        String signature =
                SIGNER.sign(
                        new QuoteSigner.Payload(
                                quoteId, tripId, fromSeq, toSeq, "SECOND", "RESERVED",
                                passengers, total, "LKR", version, expiresAt));

        return new QuoteRef(
                quoteId, tripId, fromSeq, toSeq, "SECOND", "RESERVED", passengers,
                unitFareMinor, total, "LKR", version, expiresAt, signature);
    }
}
