package lk.yathra.booking.booking;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;
import lk.yathra.common.api.ApiException;
import lk.yathra.common.api.ErrorCode;
import org.springframework.stereotype.Component;

/**
 * The booking lifecycle as an explicit transition table rather than as {@code if} statements scattered
 * across the service.
 *
 * <p>Two benefits worth the class: the legal transitions are readable in one place, and the test can
 * enumerate the table to assert that every <em>illegal</em> transition is rejected -- which is the half
 * that scattered conditionals never cover.
 */
@Component
public class BookingStateMachine {

    public enum Status {
        HELD,
        CONFIRMED,
        CANCELLED,
        EXPIRED
    }

    public enum Event {
        CONFIRM,
        CANCEL,
        EXPIRE
    }

    private static final Map<Status, Map<Event, Status>> TRANSITIONS = new EnumMap<>(Status.class);

    static {
        Map<Event, Status> fromHeld = new EnumMap<>(Event.class);
        fromHeld.put(Event.CONFIRM, Status.CONFIRMED);
        fromHeld.put(Event.CANCEL, Status.CANCELLED);
        fromHeld.put(Event.EXPIRE, Status.EXPIRED);
        TRANSITIONS.put(Status.HELD, fromHeld);

        // A confirmed booking can still be cancelled (with a refund), but it can never expire:
        // once the passenger has paid, a background sweeper must not be able to take the seat away.
        Map<Event, Status> fromConfirmed = new EnumMap<>(Event.class);
        fromConfirmed.put(Event.CANCEL, Status.CANCELLED);
        TRANSITIONS.put(Status.CONFIRMED, fromConfirmed);

        // Terminal.
        TRANSITIONS.put(Status.CANCELLED, Map.of());
        TRANSITIONS.put(Status.EXPIRED, Map.of());
    }

    public boolean canTransition(Status from, Event event) {
        return TRANSITIONS.getOrDefault(from, Map.of()).containsKey(event);
    }

    public Set<Event> legalEvents(Status from) {
        Map<Event, Status> map = TRANSITIONS.getOrDefault(from, Map.of());
        return map.isEmpty() ? EnumSet.noneOf(Event.class) : EnumSet.copyOf(map.keySet());
    }

    /** @throws ApiException with a contract error code when the transition is not legal. */
    public Status transition(Status from, Event event) {
        Status to = TRANSITIONS.getOrDefault(from, Map.of()).get(event);
        if (to == null) {
            ErrorCode code =
                    from == Status.EXPIRED && event == Event.CONFIRM
                            ? ErrorCode.HOLD_EXPIRED
                            : ErrorCode.BOOKING_NOT_HELD;
            throw new ApiException(
                    code, "Cannot " + event.name().toLowerCase() + " a booking in state " + from + ".");
        }
        return to;
    }
}
