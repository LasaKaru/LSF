package lk.yathra.common.api;

import org.springframework.http.HttpStatus;

/**
 * The API's stable, machine-readable error catalogue (docs/06 §3).
 *
 * <p>Clients branch on {@code code}, never on the human-readable message. Messages are free to change
 * and to be translated; codes are part of the contract and only change with a major version.
 */
public enum ErrorCode {
    MALFORMED_REQUEST(HttpStatus.BAD_REQUEST, "Malformed request"),
    VALIDATION_FAILED(HttpStatus.BAD_REQUEST, "Request validation failed"),
    IDEMPOTENCY_KEY_REQUIRED(HttpStatus.BAD_REQUEST, "Idempotency-Key header is required"),

    UNAUTHENTICATED(HttpStatus.UNAUTHORIZED, "Authentication required"),
    FORBIDDEN(HttpStatus.FORBIDDEN, "Not permitted"),

    /** No route matches the URL at all — a typo or a stale client, not a missing entity. */
    ROUTE_NOT_FOUND(HttpStatus.NOT_FOUND, "No such endpoint"),
    METHOD_NOT_ALLOWED(HttpStatus.METHOD_NOT_ALLOWED, "Method not allowed for this endpoint"),

    TRIP_NOT_FOUND(HttpStatus.NOT_FOUND, "Trip not found"),
    BOOKING_NOT_FOUND(HttpStatus.NOT_FOUND, "Booking not found"),
    SEAT_NOT_FOUND(HttpStatus.NOT_FOUND, "Seat not found"),
    QUOTE_NOT_FOUND(HttpStatus.NOT_FOUND, "Quote not found"),

    /**
     * The one that matters. Raised when the exclusion constraint rejects an overlapping segment, and
     * carried with enough data for the UI to recover in one click rather than starting over.
     */
    SEAT_SEGMENT_UNAVAILABLE(HttpStatus.CONFLICT, "Seat is not available for this leg"),
    BOOKING_CONTENTION(HttpStatus.CONFLICT, "Booking contention, please retry"),
    BOOKING_NOT_HELD(HttpStatus.CONFLICT, "Booking is not in a held state"),
    HOLD_LIMIT_EXCEEDED(HttpStatus.CONFLICT, "Too many active holds for this contact"),

    HOLD_EXPIRED(HttpStatus.GONE, "Hold has expired"),

    INVALID_JOURNEY_LEG(HttpStatus.UNPROCESSABLE_ENTITY, "Invalid journey leg"),
    STATION_NOT_ON_TRIP(HttpStatus.UNPROCESSABLE_ENTITY, "Station is not served by this trip"),
    QUOTE_INVALID(HttpStatus.UNPROCESSABLE_ENTITY, "Fare quote is missing, expired or invalid"),
    BOOKING_WINDOW_CLOSED(HttpStatus.UNPROCESSABLE_ENTITY, "Booking window has closed for this trip"),
    IDEMPOTENCY_KEY_REUSED(HttpStatus.UNPROCESSABLE_ENTITY, "Idempotency key reused with a different body"),
    SEAT_NOT_BOOKABLE(HttpStatus.UNPROCESSABLE_ENTITY, "Seat is not individually bookable"),

    RATE_LIMITED(HttpStatus.TOO_MANY_REQUESTS, "Rate limit exceeded"),
    INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "Internal error"),
    UPSTREAM_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "Upstream service unavailable");

    private final HttpStatus status;
    private final String title;

    ErrorCode(HttpStatus status, String title) {
        this.status = status;
        this.title = title;
    }

    public HttpStatus status() {
        return status;
    }

    public String title() {
        return title;
    }

    /** Stable, dereferenceable problem type URI. */
    public String typeUri() {
        return "https://yathra.lk/problems/" + name().toLowerCase().replace('_', '-');
    }
}
