package lk.yathra.booking;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lk.yathra.booking.availability.AvailabilityService;
import lk.yathra.booking.availability.JourneyResolver;
import lk.yathra.booking.booking.BookingDtos.Contact;
import lk.yathra.booking.booking.BookingDtos.CreateBookingRequest;
import lk.yathra.booking.booking.BookingDtos.Passenger;
import lk.yathra.booking.booking.BookingDtos.SeatSelection;
import lk.yathra.booking.booking.BookingService;
import lk.yathra.booking.outbox.OutboxRelay;
import lk.yathra.booking.support.PostgresTestSupport;
import lk.yathra.booking.support.QuoteFixture;
import lk.yathra.booking.support.TripFixture;
import lk.yathra.booking.trip.TripPublicationService;
import lk.yathra.booking.waitlist.WaitlistMatcher;
import lk.yathra.booking.waitlist.WaitlistService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

/**
 * The waitlist, end to end: join a full leg, release inventory, get promoted.
 *
 * <p>Uses a deliberately tiny coach (2 rows × 2 columns = 4 seats) so "sold out" is reachable in a
 * handful of bookings rather than 60.
 */
@SpringBootTest
class WaitlistIT extends PostgresTestSupport {

    @Autowired NamedParameterJdbcTemplate jdbc;
    @Autowired TripPublicationService publication;
    @Autowired BookingService bookings;
    @Autowired WaitlistService waitlist;
    @Autowired WaitlistMatcher matcher;
    @Autowired OutboxRelay relay;
    @Autowired JourneyResolver journeys;
    @Autowired AvailabilityService availability;

    private UUID tripId;
    private List<UUID> seats;

    @BeforeEach
    void setUp() {
        jdbc.getJdbcTemplate()
                .execute("TRUNCATE booking, booking_segment, trip, waitlist_entry, "
                        + "outbox_event, processed_event, idempotency_record CASCADE");
        // shedlock is deliberately NOT reset here -- see NoOpLockConfig. Truncating it behind
        // ShedLock's in-JVM lock registry permanently breaks every later acquisition, which reads as
        // "the matcher does not match". The lock is disabled in tests instead.

        tripId =
                publication
                        .publish(TripFixture.upCountryTrip(UUID.randomUUID(), LocalDate.now().plusDays(7), 2, 2))
                        .tripId();
        seats =
                jdbc.queryForList(
                        "SELECT id FROM trip_seat WHERE trip_id = :t ORDER BY row_index, seat_label",
                        Map.of("t", tripId), UUID.class);
    }

    // ------------------------------------------------------------------ guard

    @Test
    @DisplayName("joining is refused while seats are still available")
    void cannotJoinWhenSeatsAreFree() {
        assertThatThrownBy(() -> join("CMB", "BDL", "queue@example.lk"))
                .hasMessageContaining("book directly");
    }

    // ---------------------------------------------------------- the main path

    @Test
    @DisplayName("★ a release promotes the oldest waiting entry onto the freed seat")
    void releasePromotesOldestEntry() {
        var sellOut = sellEverySeat("CMB", "BDL");
        assertThat(availability.freeSeatIds(tripId, leg("CMB", "BDL"), "SECOND")).isEmpty();

        var first = join("CMB", "BDL", "first@example.lk");
        var second = join("CMB", "BDL", "second@example.lk");
        assertThat(waitlist.view(first.id()).position()).isEqualTo(1);
        assertThat(waitlist.view(second.id()).position()).isEqualTo(2);

        // Cancelling emits SegmentReleased; the relay dispatches it to the matcher.
        bookings.cancel(sellOut.get(0));
        relay.drain();

        var promoted = waitlist.view(first.id());
        assertThat(promoted.status()).isEqualTo("OFFERED");
        assertThat(promoted.offeredBookingId()).isNotNull();
        assertThat(promoted.offerExpiresAt()).isNotNull();

        // The offer is a REAL hold: it occupies inventory under the same constraint as any booking.
        assertThat(availability.freeSeatIds(tripId, leg("CMB", "BDL"), "SECOND")).isEmpty();

        // FIFO: the later entry is untouched.
        assertThat(waitlist.view(second.id()).status()).isEqualTo("WAITING");
    }

    @Test
    @DisplayName("★ shortening a journey offers the freed tail to someone who fits it")
    void onlyFittingLegsAreOffered() {
        var sold = sellEverySeat("CMB", "BDL");

        // Joined FIRST, so strict FIFO would pick this one. Wants Fort->Nanu Oya [1,15).
        var tooWide = join("CMB", "NAN", "toowide@example.lk");
        // Joined second. Wants Hatton->Ella [12,23).
        var fits = join("HAT", "ELA", "fits@example.lk");

        // The flagship resale flow: a passenger shortens Fort->Badulla to Fort->Kandy, freeing [9,25).
        // Note this emits a release of the WHOLE [1,25) -- a cancel gives up the entire booked leg --
        // even though the rebooked [1,9) means only the tail actually came free. The matcher must not
        // be fooled by that: [1,15) is inside [1,25) and would pass a containment-only filter, but the
        // seat is occupied from 1 to 9, so offering it there would die on the exclusion constraint and
        // burn the release for everyone.
        rebookPartially(sold.get(0), "KDY", "BDL");
        relay.drain();

        assertThat(waitlist.view(tooWide.id()).status())
                .as("the seat is not free across [1,15) - being first in the queue cannot conjure a seat")
                .isEqualTo("WAITING");
        assertThat(waitlist.view(fits.id()).status())
                .as("[12,23) is free on that seat, so the oldest seatable entry is this one")
                .isEqualTo("OFFERED");
    }

    @Test
    @DisplayName("a lapsed offer returns the entry to its ORIGINAL queue position")
    void lapsedOfferKeepsPosition() {
        var sold = sellEverySeat("CMB", "BDL");
        var first = join("CMB", "BDL", "first@example.lk");
        var second = join("CMB", "BDL", "second@example.lk");

        bookings.cancel(sold.get(0));
        relay.drain();
        assertThat(waitlist.view(first.id()).status()).isEqualTo("OFFERED");

        // The offered hold is cancelled (the passenger never confirmed).
        bookings.cancel(waitlist.view(first.id()).offeredBookingId());
        matcher.returnLapsedOffers();

        var back = waitlist.view(first.id());
        assertThat(back.status()).isEqualTo("WAITING");
        assertThat(back.position())
                .as("missing one notification must not cost your place in the queue")
                .isEqualTo(1);
        assertThat(waitlist.view(second.id()).position()).isEqualTo(2);
    }

    @Test
    @DisplayName("redelivery of the same event does not promote twice")
    void consumerIsIdempotent() {
        var sold = sellEverySeat("CMB", "BDL");
        var first = join("CMB", "BDL", "first@example.lk");
        var second = join("CMB", "BDL", "second@example.lk");

        bookings.cancel(sold.get(0));

        // Delivery is at-least-once. Draining twice must not offer the freed seat to both entries.
        relay.drain();
        relay.drain();

        assertThat(waitlist.view(first.id()).status()).isEqualTo("OFFERED");
        assertThat(waitlist.view(second.id()).status()).isEqualTo("WAITING");
    }

    @Test
    @DisplayName("confirming an offered booking converts the entry")
    void confirmingAnOfferConvertsTheEntry() {
        var sold = sellEverySeat("CMB", "BDL");
        var entry = join("CMB", "BDL", "first@example.lk");

        bookings.cancel(sold.get(0));
        relay.drain();

        bookings.confirm(waitlist.view(entry.id()).offeredBookingId());
        relay.drain(); // BookingConfirmed -> WaitlistConversionListener

        assertThat(waitlist.view(entry.id()).status()).isEqualTo("CONVERTED");
    }

    @Test
    @DisplayName("leaving the queue requires the contact email")
    void leavingRequiresEmail() {
        sellEverySeat("CMB", "BDL");
        var entry = join("CMB", "BDL", "mine@example.lk");

        assertThatThrownBy(() -> waitlist.leave(entry.id(), "someone-else@example.lk"))
                .hasMessageContaining("No active waitlist entry");

        waitlist.leave(entry.id(), "mine@example.lk");
        assertThat(waitlist.view(entry.id()).status()).isEqualTo("CANCELLED");
    }

    // ---------------------------------------------------------------- helpers

    private static org.assertj.core.api.AbstractThrowableAssert<?, ? extends Throwable> assertThatThrownBy(
            org.assertj.core.api.ThrowableAssert.ThrowingCallable callable) {
        return org.assertj.core.api.Assertions.assertThatThrownBy(callable);
    }

    private lk.yathra.common.domain.Leg leg(String from, String to) {
        return journeys.resolve(tripId, from, to).leg();
    }

    private WaitlistService.EntryView join(String from, String to, String email) {
        var resolved = journeys.resolve(tripId, from, to);
        return waitlist.join(
                new WaitlistService.JoinRequest(
                        tripId, from, to, "SECOND", "Queued", email, "+94770000000",
                        QuoteFixture.quote(tripId, resolved.leg().fromSeq(), resolved.leg().toSeq(), 1, 47000)));
    }

    /** Books every seat for the given leg, returning the booking ids. */
    private List<UUID> sellEverySeat(String from, String to) {
        var resolved = journeys.resolve(tripId, from, to);
        return seats.stream()
                .map(
                        seatId ->
                                bookings
                                        .create(
                                                new CreateBookingRequest(
                                                        tripId, from, to,
                                                        new SeatSelection("SPECIFIC", List.of(seatId), null, null),
                                                        QuoteFixture.quote(
                                                                tripId, resolved.leg().fromSeq(),
                                                                resolved.leg().toSeq(), 1, 47000),
                                                        List.of(new Passenger("Occupant", "ADULT")),
                                                        new Contact(UUID.randomUUID() + "@example.lk", "+94770000000", "Occupant")),
                                                UUID.randomUUID().toString())
                                        .response()
                                        .bookingId())
                .toList();
    }

    /** Cancels a whole-journey booking and re-books only its first half, freeing the second. */
    private void rebookPartially(UUID bookingId, String from, String to) {
        var seatId =
                jdbc.queryForList(
                                "SELECT seat_id FROM booking_segment WHERE booking_id = :b",
                                Map.of("b", bookingId), UUID.class)
                        .get(0);
        bookings.cancel(bookingId);

        var resolved = journeys.resolve(tripId, "CMB", from);
        bookings.create(
                new CreateBookingRequest(
                        tripId, "CMB", from,
                        new SeatSelection("SPECIFIC", List.of(seatId), null, null),
                        QuoteFixture.quote(tripId, resolved.leg().fromSeq(), resolved.leg().toSeq(), 1, 47000),
                        List.of(new Passenger("Occupant", "ADULT")),
                        new Contact(UUID.randomUUID() + "@example.lk", "+94770000000", "Occupant")),
                UUID.randomUUID().toString());
    }
}
