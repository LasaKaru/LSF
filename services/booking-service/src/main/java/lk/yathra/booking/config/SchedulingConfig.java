package lk.yathra.booking.config;

import javax.sql.DataSource;
import net.javacrumbs.shedlock.core.LockProvider;
import net.javacrumbs.shedlock.provider.jdbctemplate.JdbcTemplateLockProvider;
import net.javacrumbs.shedlock.spring.annotation.EnableSchedulerLock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The hold sweeper must run on exactly one replica at a time.
 *
 * <p>Without a distributed lock, every replica scans the same expiring holds simultaneously and they
 * contend both with each other and with live bookings on the busiest rows in the system.
 *
 * <p>{@code usingDbTime()} is the important detail: lock timing is evaluated by the database clock,
 * not by each JVM's. Replicas with drifting clocks would otherwise disagree about whether a lock had
 * expired, which is precisely the situation the lock exists to prevent. The same reasoning applies to
 * hold expiry itself -- {@code expires_at} is always compared against the database's {@code now()}.
 */
@Configuration
@EnableSchedulerLock(defaultLockAtMostFor = "PT2M")
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
