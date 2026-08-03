package lk.yathra.booking.support;

/** Test-only constants. Not a secret: it signs nothing outside the test JVM. */
public final class TestKeys {

    public static final String QUOTE_SIGNING_KEY =
            "test-only-quote-signing-key-at-least-32-bytes-long-for-hmac";

    private TestKeys() {}
}
