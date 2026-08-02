package lk.yathra.booking.support;

import java.util.Optional;
import net.javacrumbs.shedlock.core.LockProvider;
import net.javacrumbs.shedlock.core.SimpleLock;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/**
 * Grants every scheduler lock, unconditionally, in tests.
 *
 * <p>ShedLock exists to stop <em>replicas</em> from running the same scheduled job at once. A test JVM
 * is a single replica, so the lock protects nothing here — but it does silently change behaviour, and
 * that cost is real. Integration tests call {@code relay.drain()} and {@code returnLapsedOffers()}
 * directly so they can assert immediately afterwards. Those are ordinary calls to a proxied bean, so
 * ShedLock intercepts them exactly as it would a scheduled firing, and a lock it declines to grant
 * turns the call into a no-op with no exception and no log line.
 *
 * <p>That failure mode cost real time. The suite reset the domain tables between tests, so an earlier
 * attempt added {@code TRUNCATE shedlock} to the reset for symmetry. This makes things worse, not
 * better: {@code AbstractStorageBasedLockProvider} keeps an in-JVM registry of lock names it has
 * already inserted, and for a name in that registry it issues only an {@code UPDATE}. Truncating the
 * table behind its back leaves the registry claiming a row that no longer exists, the update matches
 * zero rows, and every subsequent acquisition fails forever. The symptom is a drain that quietly does
 * nothing, which reads as "the waitlist matcher does not match" — a bug hunt pointed at correct code.
 *
 * <p>Removing the lock in tests is the honest fix. These tests are about the relay and the matcher;
 * ShedLock's own behaviour is not what they assert, and leaving it in only lets scheduling mechanics
 * masquerade as domain failures. Production keeps the real {@link
 * lk.yathra.booking.config.SchedulingConfig} provider, which is where single-execution actually
 * matters.
 */
@TestConfiguration
public class NoOpLockConfig {

    @Bean
    @Primary
    LockProvider testLockProvider() {
        return configuration -> Optional.of((SimpleLock) () -> {});
    }
}
