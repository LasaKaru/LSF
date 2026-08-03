package lk.yathra.booking.booking;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import lk.yathra.booking.config.BookingProperties;
import lk.yathra.booking.outbox.OutboxPublisher;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * The eager half of hold expiry.
 *
 * <p>Why this exists at all: the exclusion constraint's predicate must be immutable, so it cannot
 * reference {@code now()} and therefore cannot distinguish a live hold from a dead one. An abandoned
 * checkout keeps blocking the seat until something flips its status.
 *
 * <p>Why it is not the whole answer: a sweeper alone leaves a window (up to one interval) in which a
 * dead hold still blocks a legitimate booking. So the booking path <em>also</em> expires the specific
 * conflicting holds inside its own transaction (see {@link BookingRepository#expireConflictingHolds}).
 * Together the worst case is a seat that looks taken for a few seconds after a hold dies -- which
 * self-heals, and errs toward "looks taken" rather than "looks free". That is the safe direction: the
 * failure mode is a mild inconvenience, never a double sale.
 *
 * <p>This is the least elegant part of the design and the docs say so plainly. It is the price of
 * putting the invariant in an immutable index predicate, and it is worth paying.
 */
@Component
public class HoldSweeper {

    private static final Logger log = LoggerFactory.getLogger(HoldSweeper.class);

    private final BookingRepository bookings;
    private final OutboxPublisher outbox;
    private final BookingProperties properties;

    public HoldSweeper(BookingRepository bookings, OutboxPublisher outbox, BookingProperties properties) {
        this.bookings = bookings;
        this.outbox = outbox;
        this.properties = properties;
    }

    @Scheduled(fixedDelayString = "${yathra.booking.sweep-interval-seconds:15}000")
    @SchedulerLock(name = "holdSweeper", lockAtLeastFor = "PT5S", lockAtMostFor = "PT2M")
    @Transactional
    public void sweep() {
        // Batched, and the underlying query uses FOR UPDATE SKIP LOCKED, so a backlog can never turn
        // into one enormous long-running transaction blocking the booking path.
        List<UUID> expired = bookings.expireDueHolds(properties.getSweepBatchSize());
        if (expired.isEmpty()) {
            return;
        }

        for (UUID bookingId : expired) {
            var segments = bookings.findSegments(bookingId);
            for (var segment : segments) {
                outbox.publish(
                        "SEGMENT",
                        segment.id(),
                        "SegmentReleased",
                        segment.tripId(),
                        Map.of(
                                "bookingId", bookingId.toString(),
                                "seatId", segment.seatId().toString(),
                                "fromSeq", segment.fromSeq(),
                                "toSeq", segment.toSeq(),
                                "reason", "HOLD_EXPIRED"));
            }
        }

        log.info("holds.swept count={}", expired.size());
    }
}
