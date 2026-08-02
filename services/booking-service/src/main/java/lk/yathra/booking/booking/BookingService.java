package lk.yathra.booking.booking;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import lk.yathra.booking.availability.AvailabilityService;
import lk.yathra.booking.availability.JourneyResolver;
import lk.yathra.booking.availability.JourneyResolver.ResolvedJourney;
import lk.yathra.booking.booking.BookingDtos.AlternativeSeat;
import lk.yathra.booking.booking.BookingDtos.BookingResponse;
import lk.yathra.booking.booking.BookingDtos.CreateBookingRequest;
import lk.yathra.booking.booking.BookingDtos.SegmentView;
import lk.yathra.booking.config.BookingProperties;
import lk.yathra.booking.outbox.OutboxPublisher;
import lk.yathra.booking.trip.TripRepository;
import lk.yathra.common.api.ApiException;
import lk.yathra.common.api.ErrorCode;
import lk.yathra.common.domain.Leg;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Orchestrates the booking write path: idempotency, validation, quote verification, seat selection,
 * the bounded retry loop, and translation of database errors into the API's error contract.
 *
 * <p>The transactional work itself is in {@link BookingTransaction}.
 */
@Service
public class BookingService {

    private static final Logger log = LoggerFactory.getLogger(BookingService.class);

    private final BookingTransaction transaction;
    private final BookingRepository bookings;
    private final TripRepository trips;
    private final JourneyResolver journeys;
    private final AvailabilityService availability;
    private final QuoteVerifier quoteVerifier;
    private final SeatSelectionStrategy seatSelection;
    private final IdempotencyService idempotency;
    private final BookingStateMachine stateMachine;
    private final OutboxPublisher outbox;
    private final BookingProperties properties;
    private final ObjectMapper objectMapper;

    public BookingService(
            BookingTransaction transaction,
            BookingRepository bookings,
            TripRepository trips,
            JourneyResolver journeys,
            AvailabilityService availability,
            QuoteVerifier quoteVerifier,
            SeatSelectionStrategy seatSelection,
            IdempotencyService idempotency,
            BookingStateMachine stateMachine,
            OutboxPublisher outbox,
            BookingProperties properties,
            ObjectMapper objectMapper) {
        this.transaction = transaction;
        this.bookings = bookings;
        this.trips = trips;
        this.journeys = journeys;
        this.availability = availability;
        this.quoteVerifier = quoteVerifier;
        this.seatSelection = seatSelection;
        this.idempotency = idempotency;
        this.stateMachine = stateMachine;
        this.outbox = outbox;
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    public record CreateOutcome(BookingResponse response, boolean replayed) {}

    // ------------------------------------------------------------------ create

    public CreateOutcome create(CreateBookingRequest request, String idempotencyKey) {
        String requestHash = idempotency.hash(request);

        var replay = idempotency.lookup(idempotencyKey, requestHash);
        if (replay.isPresent()) {
            return new CreateOutcome(deserialise(replay.get().body()), true);
        }

        ResolvedJourney journey = journeys.resolve(request.tripId(), request.from(), request.to());
        var trip =
                trips.findById(request.tripId())
                        .orElseThrow(() -> new ApiException(ErrorCode.TRIP_NOT_FOUND, "Trip not found."));

        if (!"PUBLISHED".equals(trip.status())) {
            throw new ApiException(
                    ErrorCode.BOOKING_WINDOW_CLOSED, "This trip is not open for booking (status " + trip.status() + ").");
        }
        if (Instant.now().isAfter(trip.bookingCutoffAt())) {
            throw new ApiException(
                            ErrorCode.BOOKING_WINDOW_CLOSED,
                            "Booking closed at " + trip.bookingCutoffAt() + " for this departure.")
                    .with("cutoffAt", trip.bookingCutoffAt().toString());
        }

        int seatCount = requestedSeatCount(request);
        if (seatCount > properties.getMaxSeatsPerBooking()) {
            throw new ApiException(
                    ErrorCode.VALIDATION_FAILED,
                    "A single booking may cover at most " + properties.getMaxSeatsPerBooking() + " seats.");
        }

        quoteVerifier.verify(request.quote(), request.tripId(), journey.leg(), seatCount);

        int attempts = properties.getRetryMaxAttempts();
        for (int attempt = 1; ; attempt++) {
            List<UUID> seatIds = chooseSeats(request, journey, seatCount);
            try {
                BookingResponse response =
                        transaction.createHold(request, journey, seatIds, idempotencyKey, requestHash);
                log.info(
                        "booking.held reference={} tripId={} leg=[{},{}) seats={} totalMinor={}",
                        response.reference(),
                        journey.tripId(),
                        journey.leg().fromSeq(),
                        journey.leg().toSeq(),
                        seatIds.size(),
                        response.totalMinor());
                return new CreateOutcome(response, false);

            } catch (DataAccessException e) {
                // Before classifying this as anything else: did a concurrent request carrying OUR
                // idempotency key just commit?
                //
                // This matters more than it looks. Two identical submits race; the loser blocks on the
                // winner's index entry and, when the winner commits, fails with 23P01 -- the exclusion
                // violation fires on the segment insert, which happens BEFORE the idempotency record
                // is written, so the unique-violation path never gets a chance. Without this check the
                // loser reports "that seat is taken" to a passenger whose own booking succeeded a
                // millisecond earlier.
                //
                // The lookup is safe and deterministic here: we only unblocked because the winner's
                // transaction completed, so its idempotency record is committed and visible to the new
                // snapshot this read takes.
                var concurrentWinner = idempotency.lookup(idempotencyKey, requestHash);
                if (concurrentWinner.isPresent()) {
                    return new CreateOutcome(deserialise(concurrentWinner.get().body()), true);
                }

                if (SqlStates.isSegmentOverlap(e)) {
                    // Somebody committed an overlapping segment while we were writing ours. On an
                    // AUTO selection a different seat may well be free, so one more pass is worth it
                    // before giving up; on a SPECIFIC selection the passenger asked for that seat.
                    boolean canReselect = !request.seatSelection().isSpecific();
                    if (canReselect && attempt < attempts) {
                        backoff(attempt);
                        continue;
                    }
                    throw seatUnavailable(journey, seatIds, request);
                }

                if (SqlStates.isRetryable(e) && attempt < attempts) {
                    log.warn("booking.retry attempt={} sqlState={}", attempt, SqlStates.of(e));
                    backoff(attempt);
                    continue;
                }

                if (SqlStates.isRetryable(e)) {
                    throw new ApiException(
                            ErrorCode.BOOKING_CONTENTION,
                            "The system is busy with concurrent bookings for this trip. Please retry.",
                            e);
                }
                if (SqlStates.is(e, SqlStates.CHECK_VIOLATION)) {
                    throw new ApiException(ErrorCode.INVALID_JOURNEY_LEG, "The requested leg is not valid.", e);
                }
                throw e;
            }
        }
    }

    /**
     * Builds the 409 the frontend recovers from in one click: which seats blocked us, where they are
     * occupied, ranked alternatives, and a URL to re-read availability.
     */
    private ApiException seatUnavailable(
            ResolvedJourney journey, List<UUID> attemptedSeatIds, CreateBookingRequest request) {

        var conflicts = bookings.findConflicts(journey.tripId(), attemptedSeatIds, journey.leg());

        var prefs =
                new SeatSelectionStrategy.Preferences(
                        request.seatSelection() == null ? null : request.seatSelection().window(),
                        request.seatSelection() == null ? null : request.seatSelection().aisle());

        List<AlternativeSeat> alternatives = new ArrayList<>();
        var freeIds = availability.freeSeatIds(journey.tripId(), journey.leg(), null);
        for (var meta : bookings.findSeatMeta(freeIds.stream().limit(5).toList())) {
            alternatives.add(new AlternativeSeat(meta.id(), meta.label(), meta.coachNumber(), meta.window()));
        }

        String seatNames =
                conflicts.stream().map(BookingDtos.ConflictDetail::seatLabel).distinct().reduce((a, b) -> a + ", " + b).orElse("The selected seat(s)");

        return new ApiException(
                        ErrorCode.SEAT_SEGMENT_UNAVAILABLE,
                        seatNames
                                + " "
                                + (conflicts.size() == 1 ? "is" : "are")
                                + " already booked for part of "
                                + journey.fromCode()
                                + " to "
                                + journey.toCode()
                                + ".")
                .with("conflicts", conflicts)
                .with("suggestedAlternatives", alternatives)
                .with(
                        "availabilityUrl",
                        "/api/v1/trips/"
                                + journey.tripId()
                                + "/availability?from="
                                + journey.fromCode()
                                + "&to="
                                + journey.toCode())
                .with("preferencesApplied", Map.of("window", String.valueOf(prefs.window())));
    }

    private int requestedSeatCount(CreateBookingRequest request) {
        if (request.seatSelection() != null && request.seatSelection().isSpecific()) {
            return request.seatSelection().seatIds().size();
        }
        return request.passengers() == null || request.passengers().isEmpty() ? 1 : request.passengers().size();
    }

    private List<UUID> chooseSeats(CreateBookingRequest request, ResolvedJourney journey, int seatCount) {
        var selection = request.seatSelection();
        if (selection != null && selection.isSpecific()) {
            return selection.seatIds();
        }

        String classCode = request.quote() == null ? null : request.quote().classCode();
        var freeIds = availability.freeSeatIds(journey.tripId(), journey.leg(), classCode);
        if (freeIds.size() < seatCount) {
            throw new ApiException(
                            ErrorCode.SEAT_SEGMENT_UNAVAILABLE,
                            "Only " + freeIds.size() + " seat(s) are free for " + journey.fromCode() + " to " + journey.toCode() + ".")
                    .with("availableSeats", freeIds.size())
                    .with("requestedSeats", seatCount);
        }

        var occupancy = bookings.occupancyFor(journey.tripId(), freeIds);
        var metaById = bookings.findSeatMeta(freeIds);

        List<SeatSelectionStrategy.Candidate> candidates = new ArrayList<>();
        for (var meta : metaById) {
            candidates.add(
                    new SeatSelectionStrategy.Candidate(
                            meta.id(),
                            meta.coachNumber(),
                            meta.label(),
                            0,
                            meta.window(),
                            false,
                            occupancy.getOrDefault(meta.id(), List.of())));
        }

        var prefs =
                new SeatSelectionStrategy.Preferences(
                        selection == null ? null : selection.window(), selection == null ? null : selection.aisle());

        return seatSelection.select(
                candidates, journey.leg(), seatCount, prefs, trips.maxStopSequence(journey.tripId()));
    }

    /** Jittered backoff: without the jitter, retries from many threads re-collide in lockstep. */
    private void backoff(int attempt) {
        long base = (long) properties.getRetryBackoffMs() * attempt;
        long jitter = ThreadLocalRandom.current().nextLong(base + 1);
        try {
            Thread.sleep(base + jitter);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw new ApiException(ErrorCode.BOOKING_CONTENTION, "Interrupted while retrying the booking.");
        }
    }

    private BookingResponse deserialise(String json) {
        try {
            return objectMapper.readValue(json, BookingResponse.class);
        } catch (Exception e) {
            throw new IllegalStateException("Stored idempotent response could not be read back", e);
        }
    }

    // --------------------------------------------------------- confirm / cancel

    @Transactional
    public BookingResponse confirm(UUID bookingId) {
        var booking =
                bookings.findById(bookingId)
                        .orElseThrow(() -> new ApiException(ErrorCode.BOOKING_NOT_FOUND, "Booking not found."));

        var current = BookingStateMachine.Status.valueOf(booking.status());

        // Check expiry before the state machine so an elapsed hold reports HOLD_EXPIRED (410) rather
        // than a generic conflict -- the client's recovery differs: re-quote and re-select, not retry.
        if (current == BookingStateMachine.Status.HELD
                && booking.expiresAt() != null
                && booking.expiresAt().isBefore(Instant.now())) {
            throw new ApiException(
                            ErrorCode.HOLD_EXPIRED,
                            "This hold expired at " + booking.expiresAt() + ". The seats have been released.")
                    .with("expiredAt", booking.expiresAt().toString());
        }

        stateMachine.transition(current, BookingStateMachine.Event.CONFIRM);

        int updated = bookings.updateStatus(bookingId, "CONFIRMED", "HELD");
        if (updated == 0) {
            // Lost a race with the sweeper or a concurrent cancel between our read and our write.
            throw new ApiException(
                    ErrorCode.BOOKING_NOT_HELD, "The booking is no longer held and could not be confirmed.");
        }

        outbox.publish(
                "BOOKING",
                bookingId,
                "BookingConfirmed",
                booking.tripId(),
                Map.of(
                        "bookingId", bookingId.toString(),
                        "reference", booking.reference(),
                        "tripId", booking.tripId().toString(),
                        "totalMinor", booking.totalFareMinor()));

        log.info("booking.confirmed reference={} tripId={}", booking.reference(), booking.tripId());
        return get(booking.reference());
    }

    @Transactional
    public BookingResponse cancel(UUID bookingId) {
        var booking =
                bookings.findById(bookingId)
                        .orElseThrow(() -> new ApiException(ErrorCode.BOOKING_NOT_FOUND, "Booking not found."));

        var current = BookingStateMachine.Status.valueOf(booking.status());
        stateMachine.transition(current, BookingStateMachine.Event.CANCEL);

        int updated = bookings.updateStatus(bookingId, "CANCELLED", booking.status());
        if (updated == 0) {
            throw new ApiException(ErrorCode.BOOKING_NOT_HELD, "The booking state changed concurrently.");
        }

        var segments = bookings.findSegments(bookingId);
        // SegmentReleased is what wakes the waitlist matcher. One event per released stretch, because
        // a waiting entry matches against a specific leg, not against a booking.
        for (var segment : segments) {
            outbox.publish(
                    "SEGMENT",
                    segment.id(),
                    "SegmentReleased",
                    booking.tripId(),
                    Map.of(
                            "tripId", booking.tripId().toString(),
                            "seatId", segment.seatId().toString(),
                            "fromSeq", segment.fromSeq(),
                            "toSeq", segment.toSeq(),
                            "reason", "CANCELLED"));
        }

        log.info("booking.cancelled reference={} segments={}", booking.reference(), segments.size());
        return get(booking.reference());
    }

    // ------------------------------------------------------------------- read

    public BookingResponse get(String reference) {
        var booking =
                bookings.findByReference(reference)
                        .orElseThrow(() -> new ApiException(ErrorCode.BOOKING_NOT_FOUND, "Booking not found."));
        return toResponse(booking);
    }

    /**
     * Retrieval by reference, gated on the contact email.
     *
     * <p>A six-character reference is short enough to be worth enumerating, and a booking carries
     * passenger contact details. Requiring a second factor the attacker does not already have turns a
     * cheap scraping attack into a targeted one (docs/11 §2).
     *
     * <p>A mismatch returns {@code BOOKING_NOT_FOUND}, never "wrong email" -- distinguishing the two
     * would confirm that the reference is real, which is exactly the bit an enumerator wants.
     */
    public BookingResponse getForContact(String reference, String email) {
        var booking =
                bookings.findByReference(reference)
                        .orElseThrow(() -> new ApiException(ErrorCode.BOOKING_NOT_FOUND, "Booking not found."));

        String onRecord = booking.contactEmail();
        if (onRecord != null && !onRecord.isBlank()) {
            if (email == null || !onRecord.trim().equalsIgnoreCase(email.trim())) {
                log.info("booking.retrieval_denied reference={}", reference);
                throw new ApiException(
                        ErrorCode.BOOKING_NOT_FOUND,
                        "No booking matches that reference and contact email.");
            }
        }
        return toResponse(booking);
    }

    public BookingResponse getById(UUID id) {
        var booking =
                bookings.findById(id)
                        .orElseThrow(() -> new ApiException(ErrorCode.BOOKING_NOT_FOUND, "Booking not found."));
        return toResponse(booking);
    }

    private BookingResponse toResponse(BookingRepository.BookingRow booking) {
        List<SegmentView> segments =
                bookings.findSegments(booking.id()).stream()
                        .map(
                                s ->
                                        new SegmentView(
                                                s.seatId(),
                                                s.seatLabel(),
                                                s.coachNumber(),
                                                s.fromCode(),
                                                s.toCode(),
                                                s.fromSeq(),
                                                s.toSeq(),
                                                s.distanceKm(),
                                                s.fareMinor()))
                        .toList();

        Long holdSeconds =
                booking.expiresAt() == null
                        ? null
                        : Math.max(0, booking.expiresAt().getEpochSecond() - Instant.now().getEpochSecond());

        return new BookingResponse(
                booking.id(),
                booking.reference(),
                booking.status(),
                booking.expiresAt(),
                holdSeconds,
                booking.totalFareMinor(),
                booking.currency(),
                segments);
    }

    /** Exposed for the availability/seat-map layer to build a {@link Leg} without re-resolving. */
    public JourneyResolver journeys() {
        return journeys;
    }
}
