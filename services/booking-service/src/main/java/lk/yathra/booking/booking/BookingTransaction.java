package lk.yathra.booking.booking;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lk.yathra.booking.availability.JourneyResolver.ResolvedJourney;
import lk.yathra.booking.booking.BookingDtos.BookingResponse;
import lk.yathra.booking.booking.BookingDtos.CreateBookingRequest;
import lk.yathra.booking.booking.BookingDtos.SegmentView;
import lk.yathra.booking.config.BookingProperties;
import lk.yathra.booking.outbox.OutboxPublisher;
import lk.yathra.common.api.ApiException;
import lk.yathra.common.api.ErrorCode;
import lk.yathra.common.domain.Leg;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Everything that must happen atomically when a hold is created.
 *
 * <p>Separated from {@link BookingService} because the retry loop has to live <em>outside</em> the
 * transaction: retrying inside a transaction that the database has already marked rollback-only does
 * nothing useful.
 *
 * <p>READ COMMITTED is deliberate (docs/04 §4.4). We are not relying on a read to make the decision --
 * we are relying on a write the database refuses to accept -- so SERIALIZABLE would tax every
 * transaction for a guarantee only this one needs, and would report conflicts as an unattributable
 * {@code 40001} instead of a precise {@code 23P01} naming the constraint.
 */
@Component
public class BookingTransaction {

    private final BookingRepository bookings;
    private final ReferenceGenerator references;
    private final IdempotencyService idempotency;
    private final OutboxPublisher outbox;
    private final BookingProperties properties;

    public BookingTransaction(
            BookingRepository bookings,
            ReferenceGenerator references,
            IdempotencyService idempotency,
            OutboxPublisher outbox,
            BookingProperties properties) {
        this.bookings = bookings;
        this.references = references;
        this.idempotency = idempotency;
        this.outbox = outbox;
        this.properties = properties;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW, isolation = Isolation.READ_COMMITTED)
    public BookingResponse createHold(
            CreateBookingRequest request,
            ResolvedJourney journey,
            List<UUID> seatIds,
            String idempotencyKey,
            String requestHash) {

        Leg leg = journey.leg();
        UUID tripId = journey.tripId();

        // Sorting is not cosmetic. Two transactions booking the same four seats in different orders
        // can deadlock on each other's index entries; acquiring in one total order makes that cycle
        // structurally impossible. Measured in MultiSeatDeadlockIT: 47 deadlocks in one run with this
        // line removed, 0 with it. The retry below absorbs them, so the symptom is latency and
        // pg_stat_database.deadlocks rather than a failed booking -- which is exactly why it would be
        // easy to delete this and see nothing wrong (docs/14 §4).
        List<UUID> orderedSeatIds = seatIds.stream().sorted(Comparator.comparing(UUID::toString)).toList();

        // Lazy expiry: clear only the dead holds actually blocking us, in this same transaction.
        bookings.expireConflictingHolds(tripId, orderedSeatIds, leg);

        String contactEmail = request.contact() == null ? null : request.contact().email();
        if (contactEmail != null && !contactEmail.isBlank()) {
            int active = bookings.countActiveHolds(contactEmail);
            if (active + orderedSeatIds.size() > properties.getMaxActiveHoldsPerContact()) {
                // The hold mechanism that makes checkout humane is also a free inventory-freezing
                // weapon. A design that introduces holds without hold limits has built one.
                throw new ApiException(
                                ErrorCode.HOLD_LIMIT_EXCEEDED,
                                "This contact already holds "
                                        + active
                                        + " seat(s); the limit is "
                                        + properties.getMaxActiveHoldsPerContact()
                                        + ".")
                        .with("activeHolds", active)
                        .with("limit", properties.getMaxActiveHoldsPerContact());
            }
        }

        var seatMeta = bookings.findSeatMeta(orderedSeatIds);
        if (seatMeta.size() != orderedSeatIds.size()) {
            throw new ApiException(ErrorCode.SEAT_NOT_FOUND, "One or more requested seats do not exist on this trip.");
        }
        for (var meta : seatMeta) {
            if (!meta.reservable() || !meta.bookable()) {
                throw new ApiException(
                        ErrorCode.SEAT_NOT_BOOKABLE,
                        "Seat " + meta.label() + " is in an unreserved coach and is not individually bookable.");
            }
        }

        UUID bookingId = UUID.randomUUID();
        String reference = references.generate();
        Instant expiresAt = Instant.now().plusSeconds(properties.getHoldTtlSeconds());

        long unitFare = request.quote().unitFareMinor();
        long total = request.quote().totalMinor();

        bookings.insertBooking(
                bookingId,
                reference,
                tripId,
                request.contact() == null ? null : request.contact().name(),
                contactEmail,
                request.contact() == null ? null : request.contact().phone(),
                orderedSeatIds.size(),
                request.quote().quoteId(),
                request.quote().ruleSetVersion(),
                total,
                request.quote().currency(),
                expiresAt);

        // Distance is per-seat identical (same leg), so the per-segment fare is the unit fare.
        BigDecimal distance = journey.distanceKm().setScale(2, RoundingMode.HALF_UP);

        for (UUID seatId : orderedSeatIds) {
            // *** The insert the exclusion constraint guards. ***
            bookings.insertSegment(
                    bookingId, tripId, seatId, leg, journey.fromCode(), journey.toCode(), distance, unitFare);
        }

        List<SegmentView> segments = new ArrayList<>();
        var metaById = new java.util.HashMap<UUID, BookingRepository.SeatMeta>();
        seatMeta.forEach(m -> metaById.put(m.id(), m));
        for (UUID seatId : orderedSeatIds) {
            var meta = metaById.get(seatId);
            segments.add(
                    new SegmentView(
                            seatId,
                            meta.label(),
                            meta.coachNumber(),
                            journey.fromCode(),
                            journey.toCode(),
                            leg.fromSeq(),
                            leg.toSeq(),
                            distance,
                            unitFare));
        }

        BookingResponse response =
                new BookingResponse(
                        bookingId,
                        reference,
                        "HELD",
                        expiresAt,
                        (long) properties.getHoldTtlSeconds(),
                        total,
                        request.quote().currency(),
                        segments);

        outbox.publish(
                "BOOKING",
                bookingId,
                "BookingHeld",
                tripId,
                Map.of(
                        "bookingId", bookingId.toString(),
                        "reference", reference,
                        "tripId", tripId.toString(),
                        "fromSeq", leg.fromSeq(),
                        "toSeq", leg.toSeq(),
                        "seatIds", orderedSeatIds.stream().map(UUID::toString).toList(),
                        "totalMinor", total,
                        "expiresAt", expiresAt.toString()));

        // Same transaction as the booking: there is no instant at which the booking exists but a
        // retry of the same request would create a second one.
        idempotency.store(idempotencyKey, requestHash, 201, response, bookingId);

        return response;
    }
}
