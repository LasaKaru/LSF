package lk.yathra.booking.config;

import javax.sql.DataSource;
import net.javacrumbs.shedlock.core.LockProvider;
import net.javacrumbs.shedlock.provider.jdbctemplate.JdbcTemplateLockProvider;
import net.javacrumbs.shedlock.spring.annotation.EnableSchedulerLock;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Turns on background work, and makes sure it runs on exactly one replica.
 *
 * <p>Without a distributed lock, every replica scans the same expiring holds simultaneously and they
 * contend both with each other and with live bookings on the busiest rows in the system.
 *
 * <p>{@code usingDbTime()} is the important detail: lock timing is evaluated by the database clock,
 * not by each JVM's. Replicas with drifting clocks would otherwise disagree about whether a lock had
 * expired, which is precisely the situation the lock exists to prevent. The same reasoning applies to
 * hold expiry itself -- {@code expires_at} is always compared against the database's {@code now()}.
 *
 * <p><b>Why {@code @EnableScheduling} lives here rather than on the application class.</b> It used to
 * be an annotation on {@code BookingServiceApplication}, which meant it was unconditional — every
 * Spring context ever built started the timers, including each integration test's. A {@code fixedDelay}
 * task fires <em>immediately</em> on startup regardless of how far out its interval is, so pushing the
 * intervals to an hour in tests did nothing: the offer sweeper still ran once per context, and in CI one
 * of those runs deadlocked against a test's {@code TRUNCATE} (the sweeper takes AccessShare on
 * {@code booking} through its subquery; TRUNCATE wants AccessExclusive; classic lock-order inversion).
 * The tests drive the relay and the sweepers explicitly so they can assert immediately afterwards, so
 * background firing buys them nothing and costs them determinism. Gathering the switch here lets a test
 * turn all of it off with one property. Production leaves it on — {@code matchIfMissing}.
 */
@Configuration
@EnableScheduling
@EnableSchedulerLock(defaultLockAtMostFor = "PT2M")
@ConditionalOnProperty(name = "yathra.scheduling.enabled", havingValue = "true", matchIfMissing = true)
public class SchedulingConfig {

    @Bean
    public LockProvider lockProvider(DataSource dataSource) {
        return new JdbcTemplateLockProvider(
                JdbcTemplateLockProvider.Configuration.builder()
                        .withJdbcTemplate(new org.springframework.jdbc.core.JdbcTemplate(dataSource))
                        .withTableName("shedlock")
                        .usingDbTime()
                        .build());
    }
}
