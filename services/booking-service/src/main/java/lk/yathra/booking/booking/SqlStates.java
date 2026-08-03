package lk.yathra.booking.booking;

import java.sql.SQLException;

/**
 * PostgreSQL SQLSTATEs this service reacts to, and the reason each one matters.
 *
 * <p>Matching on SQLSTATE rather than on message text is deliberate: messages are localised and change
 * between server versions, and a booking system that mis-classifies an exclusion violation as an
 * internal error would return 500 to a passenger whose real problem is that somebody else took the
 * seat a millisecond earlier.
 */
public final class SqlStates {

    /** exclusion_violation -- the invariant fired. An overlapping segment already exists. */
    public static final String EXCLUSION_VIOLATION = "23P01";

    /** unique_violation -- here, a concurrent request with the same Idempotency-Key. */
    public static final String UNIQUE_VIOLATION = "23505";

    /** check_violation -- e.g. seq_ordered rejected an inverted leg. */
    public static final String CHECK_VIOLATION = "23514";

    /** deadlock_detected -- two multi-seat transactions crossed. Retryable. */
    public static final String DEADLOCK_DETECTED = "40P01";

    /** serialization_failure -- retryable. */
    public static final String SERIALIZATION_FAILURE = "40001";

    private SqlStates() {}

    /** Walks the cause chain for a SQLSTATE, since Spring wraps the driver exception several deep. */
    public static String of(Throwable t) {
        Throwable current = t;
        while (current != null) {
            if (current instanceof SQLException sql && sql.getSQLState() != null) {
                return sql.getSQLState();
            }
            current = current.getCause();
        }
        return null;
    }

    public static boolean is(Throwable t, String sqlState) {
        return sqlState.equals(of(t));
    }

    public static boolean isRetryable(Throwable t) {
        String state = of(t);
        return DEADLOCK_DETECTED.equals(state) || SERIALIZATION_FAILURE.equals(state);
    }

    /**
     * True only for our named constraint. A different exclusion constraint added later must not be
     * silently reported to the passenger as "that seat is taken".
     */
    public static boolean isSegmentOverlap(Throwable t) {
        if (!is(t, EXCLUSION_VIOLATION)) {
            return false;
        }
        Throwable current = t;
        while (current != null) {
            String message = current.getMessage();
            if (message != null && message.contains("no_overlapping_active_segments")) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }
}
