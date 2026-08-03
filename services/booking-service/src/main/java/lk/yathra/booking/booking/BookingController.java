package lk.yathra.booking.booking;

import jakarta.validation.Valid;
import java.util.UUID;
import lk.yathra.booking.booking.BookingDtos.BookingResponse;
import lk.yathra.booking.booking.BookingDtos.CreateBookingRequest;
import lk.yathra.common.api.ApiException;
import lk.yathra.common.api.ErrorCode;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/bookings")
public class BookingController {

    private final BookingService bookings;

    public BookingController(BookingService bookings) {
        this.bookings = bookings;
    }

    /**
     * Creates a hold.
     *
     * <p>{@code Idempotency-Key} is required rather than optional. Making it optional would mean the
     * clients most likely to need it -- the ones on flaky connections -- are the ones most likely to
     * omit it.
     */
    @PostMapping
    public ResponseEntity<BookingResponse> create(
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody CreateBookingRequest request) {

        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new ApiException(
                    ErrorCode.IDEMPOTENCY_KEY_REQUIRED,
                    "Provide an Idempotency-Key header (a client-generated UUID) so this request is safe to retry.");
        }

        var outcome = bookings.create(request, idempotencyKey);

        // A replay returns the original 201 body with 200, plus a header so a client can tell the
        // difference between "created now" and "created earlier".
        return ResponseEntity.status(outcome.replayed() ? HttpStatus.OK : HttpStatus.CREATED)
                .header("Idempotency-Replayed", String.valueOf(outcome.replayed()))
                .header("Cache-Control", "no-store")
                .body(outcome.response());
    }

    @PostMapping("/{id}/confirm")
    public BookingResponse confirm(@PathVariable UUID id) {
        return bookings.confirm(id);
    }

    @DeleteMapping("/{id}")
    public BookingResponse cancel(@PathVariable UUID id) {
        return bookings.cancel(id);
    }

    /**
     * Retrieval requires the reference <b>and</b> the contact email.
     *
     * <p>A six-character reference is short enough to be worth guessing at scale; requiring a second
     * factor the attacker does not have turns a scraping attack into a targeted one (docs/11 §2).
     */
    @GetMapping("/{reference}")
    public BookingResponse get(
            @PathVariable String reference, @RequestParam(required = false) String email) {
        return bookings.getForContact(reference, email);
    }
}
