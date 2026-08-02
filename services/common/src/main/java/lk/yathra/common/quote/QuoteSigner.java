package lk.yathra.common.quote;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * HMAC-SHA256 signing and verification of fare quotes.
 *
 * <p>This class lives in {@code common} on purpose. pricing-service signs and booking-service verifies,
 * and if the two ever disagreed about the canonical byte sequence, every booking would fail with an
 * invalid-quote error that looked like a key problem. Sharing the canonicalisation makes drift
 * impossible rather than merely unlikely.
 *
 * <p>Why signed quotes at all (ADR-008):
 *
 * <ul>
 *   <li>The client never sends a price, so a tampered payload cannot underpay.
 *   <li>booking-service verifies with a <b>local</b> HMAC check -- no HTTP call inside the booking
 *       transaction, so pricing-service latency can never hold a database transaction open.
 *   <li>The signature binds the price to the exact {@code (trip, fromSeq, toSeq, class, coach,
 *       passengers)}, so a cheap Fort->Kandy quote cannot be redeemed for a Fort->Badulla booking.
 *   <li>The TTL stops a stale quote being replayed after a fare change.
 * </ul>
 */
public final class QuoteSigner {

    private static final String ALGORITHM = "HmacSHA256";
    private static final String VERSION = "v1";

    private final byte[] key;

    public QuoteSigner(String key) {
        if (key == null || key.getBytes(StandardCharsets.UTF_8).length < 32) {
            throw new IllegalArgumentException(
                    "Quote signing key must be at least 32 bytes. Generate one with: openssl rand -base64 48");
        }
        this.key = key.getBytes(StandardCharsets.UTF_8);
    }

    /**
     * The signed payload. Field order and separator are part of the contract; changing either is a
     * breaking change that must be rolled out with overlapping key validity.
     */
    public record Payload(
            UUID quoteId,
            UUID tripId,
            int fromSeq,
            int toSeq,
            String classCode,
            String coachType,
            int passengers,
            long totalMinor,
            String currency,
            String ruleSetVersion,
            Instant expiresAt) {

        String canonical() {
            return String.join(
                    "|",
                    quoteId.toString(),
                    tripId.toString(),
                    Integer.toString(fromSeq),
                    Integer.toString(toSeq),
                    classCode,
                    coachType,
                    Integer.toString(passengers),
                    Long.toString(totalMinor),
                    currency,
                    ruleSetVersion,
                    Long.toString(expiresAt.getEpochSecond()));
        }
    }

    public String sign(Payload payload) {
        return VERSION + ":" + Base64.getUrlEncoder().withoutPadding().encodeToString(mac(payload.canonical()));
    }

    /**
     * Constant-time verification.
     *
     * <p>{@link MessageDigest#isEqual} rather than {@code String.equals}: a short-circuiting comparison
     * leaks how many leading bytes were correct, which is enough to forge a signature one byte at a
     * time given enough attempts.
     */
    public boolean verify(Payload payload, String signature) {
        if (signature == null || !signature.startsWith(VERSION + ":")) {
            return false;
        }
        String provided = signature.substring(VERSION.length() + 1);
        byte[] expected = mac(payload.canonical());
        byte[] actual;
        try {
            actual = Base64.getUrlDecoder().decode(provided);
        } catch (IllegalArgumentException e) {
            return false;
        }
        return MessageDigest.isEqual(expected, actual);
    }

    private byte[] mac(String data) {
        try {
            Mac mac = Mac.getInstance(ALGORITHM);
            mac.init(new SecretKeySpec(key, ALGORITHM));
            return mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new IllegalStateException("Unable to compute quote signature", e);
        }
    }
}
