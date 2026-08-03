package lk.yathra.booking.waitlist;

import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import lk.yathra.booking.booking.BookingRepository;
import lk.yathra.booking.booking.ReferenceGenerator;
import lk.yathra.booking.config.BookingProperties;
import lk.yathra.booking.outbox.OutboxPublisher;
import lk.yathra.booking.outbox.OutboxRelay;
import lk.yathra.booking.trip.TripRepository;
import lk.yathra.common.domain.Leg;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Promotes the oldest compatible waitlist entry when a seat-segment is released.
 *
 * <p>Event-driven rather than polled, so promotion happens within seconds of a cancellation or a hold
 * expiry rather than on the next sweep.
 *
 * <p><b>The offer is a real hold.</b> The matcher creates an ordinary {@code HELD} booking, subject to
 * exactly the same exclusion constraint as any other booking. Nothing about the waitlist bypasses the
 * invariant — which means that even if this matching logic were wrong, it could not produce a double
 * sale. The worst a bug here can do is offer a seat to the wrong person or fail to offer it at all.
 * That containment is deliberate and is why the feature was safe to add late.
 */
@Component
public class WaitlistMatcher implements OutboxRelay.OutboxConsumer {

    private static final Logger log = LoggerFactory.getLogger(WaitlistMatcher.class);

    /** Longer than a normal hold: the passenger is reacting to a notification, not sitting at a form. */
    private static final long OFFER_TTL_SECONDS = 1800;

    private final WaitlistRepository waitlist;
    private final BookingRepository bookings;
    private final TripRepository trips;
    private final ReferenceGenerator references;
    private final OutboxPublisher outbox;
    private final BookingProperties properties;

    public WaitlistMatcher(
            WaitlistRepository waitlist,
            BookingRepository bookings,
            TripRepository trips,
            ReferenceGenerator references,
            OutboxPublisher outbox,
            BookingProperties properties) {
        this.waitlist = waitlist;
        this.bookings = bookings;
        this.trips = trips;
        this.references = references;
        this.outbox = outbox;
        this.properties = properties;
    }

    @Override
    public String eventType() {
        return "SegmentReleased";
    }

    @Override
    public String consumerName() {
        return "waitlist-matcher";
    }

    @Override
    // REQUIRES_NEW so a failed promotion rolls back only itself. Sharing the relay's transaction
    // meant one lost race aborted it, and every subsequent statement -- including the relay's own
    // "mark published" -- failed with 25P02, wedging the whole drain.
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void handle(UUID eventId, JsonNode payload) {
        UUID tripId = UUID.fromString(payload.get("tripId").asText());
        UUID seatId = UUID.fromString(payload.get("seatId").asText());
        Leg released = Leg.of(payload.get("fromSeq").asInt(), payload.get("toSeq").asInt());

        var seatMeta = bookings.findSeatMeta(java.util.List.of(seatId));
        if (seatMeta.isEmpty()) {
            return;
        }
        var seat = seatMeta.get(0);

        var candidate = waitlist.findOldestMatching(tripId, seatId, released, seat.classCode());
        if (candidate.isEmpty()) {
            return;
        }
        var entry = candidate.get();
        Leg wanted = Leg.of(entry.fromSeq(), entry.toSeq());

        BigDecimal distance =
                trips.distanceBetween(tripId, wanted.fromSeq(), wanted.toSeq()).orElse(null);
        if (distance == null) {
            log.warn("waitlist.no_distance tripId={} leg={}", tripId, wanted);
            return;
        }

        UUID bookingId = UUID.randomUUID();
        String reference = references.generate();
        Instant expiresAt = Instant.now().plusSeconds(OFFER_TTL_SECONDS);

        try {
            bookings.insertBooking(
                    bookingId,
                    reference,
                    tripId,
                    entry.contactName(),
                    entry.contactEmail(),
                    entry.contactPhone(),
                    entry.passengers(),
                    null, // no client quote: the fare was locked when they joined the queue
                    null,
                    entry.fareMinor(),
                    entry.currency(),
                    expiresAt);

            bookings.insertSegment(
                    bookingId, tripId, seatId, wanted,
                    entry.fromCode(), entry.toCode(), distance, entry.fareMinor());

        } catch (DataAccessException e) {
            // Someone booked the seat between the release and this promotion. The constraint stopped
            // it, which is the system working correctly. The entry stays WAITING for the next release.
            log.info(
                    "waitlist.promotion_lost_race entryId={} tripId={} seat={} reason={}",
                    entry.id(), tripId, seat.label(), e.getClass().getSimpleName());
            throw e; // roll back this promotion only; the relay logs and moves on
        }

        waitlist.markOffered(entry.id(), bookingId, expiresAt);

        outbox.publish(
                "WAITLIST",
                entry.id(),
                "WaitlistPromoted",
                tripId,
                Map.of(
                        "entryId", entry.id().toString(),
                        "bookingId", bookingId.toString(),
                        "reference", reference,
                        "seatLabel", seat.label(),
                        "coachNumber", seat.coachNumber(),
                        "fromCode", entry.fromCode(),
                        "toCode", entry.toCode(),
                        "offerExpiresAt", expiresAt.toString(),
                        "contactEmail", entry.contactEmail()));

        log.info(
                "waitlist.promoted entryId={} reference={} seat={} leg=[{},{}) offerExpiresAt={}",
                entry.id(), reference, seat.label(), wanted.fromSeq(), wanted.toSeq(), expiresAt);
    }

    /**
     * Returns lapsed offers to the queue, at their original position.
     *
     * <p>Runs alongside the hold sweeper: when an offered booking expires or is cancelled, the entry
     * becomes {@code WAITING} again and is eligible for the next release. {@code created_at} is never
     * touched, so a passenger who was asleep when the notification fired keeps their place.
     */
    @org.springframework.scheduling.annotation.Scheduled(
            fixedDelayString = "${yathra.booking.sweep-interval-seconds:15}000")
    @net.javacrumbs.shedlock.spring.annotation.SchedulerLock(
            name = "waitlistOfferSweeper", lockAtLeastFor = "PT5S", lockAtMostFor = "PT2M")
    @Transactional
    public void returnLapsedOffers() {
        var returned = waitlist.returnLapsedOffersToQueue();
        if (!returned.isEmpty()) {
            log.info("waitlist.offers_lapsed count={} (queue positions preserved)", returned.size());
        }
    }

    int offerTtlSeconds() {
        return (int) OFFER_TTL_SECONDS;
    }
}
