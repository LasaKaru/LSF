package lk.yathra.booking.booking;

import java.security.SecureRandom;
import org.springframework.stereotype.Component;

/**
 * Generates booking references like {@code YTH-7K2QD9}.
 *
 * <p>Two properties, both practical rather than decorative:
 *
 * <ul>
 *   <li><b>Ambiguity-free alphabet.</b> No {@code O}/{@code 0} and no {@code I}/{@code 1}. References
 *       get read down a phone line to a station clerk and copied off a screen by hand; a character
 *       pair that two people can transcribe differently is a support ticket.
 *   <li><b>Random, not sequential.</b> 32^6 is about a billion values, so references are not
 *       enumerable by counting. That matters because a reference is one of the two factors for
 *       retrieving a booking -- see docs/11 §2, which is also why the contact email is required
 *       alongside it.
 * </ul>
 */
@Component
public class ReferenceGenerator {

    private static final String ALPHABET = "23456789ABCDEFGHJKLMNPQRSTUVWXYZ";
    private static final int LENGTH = 6;
    private static final String PREFIX = "YTH-";

    private final SecureRandom random = new SecureRandom();

    public String generate() {
        StringBuilder sb = new StringBuilder(PREFIX);
        for (int i = 0; i < LENGTH; i++) {
            sb.append(ALPHABET.charAt(random.nextInt(ALPHABET.length())));
        }
        return sb.toString();
    }
}
