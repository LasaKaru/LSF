package lk.yathra.booking.support;

import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * Provides a real PostgreSQL for integration tests.
 *
 * <p><b>Never H2, never an in-memory substitute.</b> The entire correctness argument of this project
 * rests on a GiST exclusion constraint, which only PostgreSQL has. Testing against H2 would exercise a
 * different system and pass for the wrong reason -- the tests would be green and the invariant
 * unprotected.
 *
 * <p>Two ways to get that database, because CI and a developer laptop differ:
 *
 * <ul>
 *   <li>{@code TEST_PG_URL} is set -- use it. This is the path when a Postgres service container is
 *       already running (GitHub Actions {@code services:}) or when Docker is unavailable to the test
 *       process itself.
 *   <li>Otherwise start a Testcontainer, pinned to the same major version production runs.
 * </ul>
 *
 * The container is created lazily and only in the second case, so a machine without a Docker daemon
 * never fails at class-initialisation time.
 */
public abstract class PostgresTestSupport {

    private static final String IMAGE = "postgres:16-alpine";

    private static PostgreSQLContainer<?> container;

    private static synchronized PostgreSQLContainer<?> container() {
        if (container == null) {
            container =
                    new PostgreSQLContainer<>(IMAGE)
                            .withDatabaseName("booking_db")
                            .withUsername("yathra")
                            .withPassword("test-password");
            container.start();
        }
        return container;
    }

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        String externalUrl = System.getenv("TEST_PG_URL");

        if (externalUrl != null && !externalUrl.isBlank()) {
            registry.add("spring.datasource.url", () -> externalUrl);
            registry.add("spring.datasource.username", () -> envOrDefault("TEST_PG_USER", "yathra"));
            registry.add("spring.datasource.password", () -> envOrDefault("TEST_PG_PASSWORD", "postgres"));
        } else {
            var pg = container();
            registry.add("spring.datasource.url", pg::getJdbcUrl);
            registry.add("spring.datasource.username", pg::getUsername);
            registry.add("spring.datasource.password", pg::getPassword);
        }

        registry.add("spring.flyway.enabled", () -> "true");
        registry.add("spring.flyway.clean-disabled", () -> "false");
        // Deterministic, and long enough that no test races the sweeper by accident.
        registry.add("yathra.booking.hold-ttl-seconds", () -> "600");
        registry.add("yathra.booking.quote-signing-key", () -> TestKeys.QUOTE_SIGNING_KEY);

        // No background timers in tests. This one property switches off @EnableScheduling and, with it,
        // ShedLock's @SchedulerLock processing -- see SchedulingConfig.
        //
        // Every test here drives the relay and the sweepers by calling them, so that it can assert on
        // the very next line. A timer running alongside that is pure interference, and it produced two
        // separate false diagnoses before this was understood:
        //
        //   * The background relay claimed events first (FOR UPDATE SKIP LOCKED), so a test's own
        //     drain() found nothing and asserted before the background promotion had committed. That
        //     reads exactly like "the waitlist matcher does not match".
        //   * The offer sweeper deadlocked in CI against a test's TRUNCATE -- the sweeper takes
        //     AccessShare on `booking` through its subquery while TRUNCATE wants AccessExclusive.
        //
        // Pushing the intervals out does NOT fix either: a fixedDelay task fires immediately at startup
        // whatever its interval, and each test class builds its own context. Off is off.
        registry.add("yathra.scheduling.enabled", () -> "false");

        // Still set, because they are validated configuration rather than scheduling policy: 300 is the
        // @Max on the sweep interval, and a larger value makes the service refuse to start -- which is
        // the intended behaviour, and was itself caught by a test run.
        registry.add("yathra.booking.sweep-interval-seconds", () -> "300");
        registry.add("yathra.booking.outbox-relay-interval-ms", () -> "3600000");
    }

    private static String envOrDefault(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value;
    }
}
