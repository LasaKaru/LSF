package lk.yathra.booking.waitlist;

import java.time.Instant;
import java.util.UUID;
import lk.yathra.booking.availability.AvailabilityService;
import lk.yathra.booking.availability.JourneyResolver;
import lk.yathra.booking.booking.BookingDtos.QuoteRef;
import lk.yathra.booking.booking.QuoteVerifier;
import lk.yathra.common.api.ApiException;
import lk.yathra.common.api.ErrorCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class WaitlistService {

    private static final Logger log = LoggerFactory.getLogger(WaitlistService.class);

    private final WaitlistRepository waitlist;
    private final JourneyResolver journeys;
    private final AvailabilityService availability;
    private final QuoteVerifier quoteVerifier;

    public WaitlistService(
            WaitlistRepository waitlist,
            JourneyResolver journeys,
            AvailabilityService availability,
            QuoteVerifier quoteVerifier) {
        this.waitlist = waitlist;
        this.journeys = journeys;
        this.availability = availability;
        this.quoteVerifier = quoteVerifier;
    }

    public record JoinRequest(
            UUID tripId, String from, String to, String classCode,
            String name, String email, String phone, QuoteRef quote) {}

    public record EntryView(
            UUID id, UUID tripId, String from, String to, String classCode, String status,
            int position, int queueLength, long fareMinor, String currency,
            UUID offeredBookingId, Instant offerExpiresAt, Instant createdAt) {}

    @Transactional
    public EntryView join(JoinRequest request) {
        var journey = journeys.resolve(request.tripId(), request.from(), request.to());
        String classCode = request.classCode() == null ? "SECOND" : request.classCode().toUpperCase();

        if (request.email() == null || request.email().isBlank()) {
            throw new ApiException(
                    ErrorCode.VALIDATION_FAILED, "A contact email is required — it is how we reach you when a seat frees.");
        }

        // Refuse to queue for something that is available right now. Joining a waitlist when you could
        // simply book is never what the passenger meant, and it would leave them waiting for a release
        // that may never come while seats sit unsold.
        var free = availability.freeSeatIds(journey.tripId(), journey.leg(), classCode);
        if (!free.isEmpty()) {
            throw new ApiException(
                            ErrorCode.VALIDATION_FAILED,
                            free.size() + " seat(s) are available for this leg — book directly rather than joining the waitlist.")
                    .with("availableSeats", free.size());
        }

        // The fare is verified and locked now. Promotion happens server-side, possibly at 03:00, and
        // charging whatever the engine says at that moment would be indefensible.
        quoteVerifier.verify(request.quote(), request.tripId(), journey.leg(), 1);

        UUID id =
                waitlist.insert(
                        journey.tripId(), journey.leg(), journey.fromCode(), journey.toCode(), classCode,
                        request.name(), request.email(), request.phone(), 1,
                        request.quote().totalMinor(), request.quote().currency());

        log.info(
                "waitlist.joined entryId={} tripId={} leg=[{},{}) class={}",
                id, journey.tripId(), journey.leg().fromSeq(), journey.leg().toSeq(), classCode);

        return view(id);
    }

    public EntryView view(UUID id) {
        var entry =
                waitlist.findById(id)
                        .orElseThrow(() -> new ApiException(ErrorCode.BOOKING_NOT_FOUND, "Waitlist entry not found."));

        return new EntryView(
                entry.id(),
                entry.tripId(),
                entry.fromCode(),
                entry.toCode(),
                entry.classCode(),
                entry.status(),
                waitlist.positionOf(entry),
                waitlist.countWaiting(entry.tripId(), entry.classCode()),
                entry.fareMinor(),
                entry.currency(),
                entry.offeredBookingId(),
                entry.offerExpiresAt(),
                entry.createdAt());
    }

    /**
     * Leaving requires the contact email, for the same reason booking retrieval does: an entry id alone
     * should not let a stranger remove somebody from a queue.
     */
    @Transactional
    public void leave(UUID id, String email) {
        if (email == null || email.isBlank() || waitlist.cancel(id, email) == 0) {
            throw new ApiException(
                    ErrorCode.BOOKING_NOT_FOUND, "No active waitlist entry matches that id and contact email.");
        }
        log.info("waitlist.left entryId={}", id);
    }
}
