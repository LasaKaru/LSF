package lk.yathra.booking.waitlist;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.UUID;
import lk.yathra.booking.outbox.OutboxRelay;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Closes a waitlist entry when the offer it produced is confirmed.
 *
 * <p>Deliberately an event consumer rather than a call from {@code BookingService.confirm}. The booking
 * path should not know the waitlist exists — it is the waitlist that cares about bookings, not the
 * reverse. Keeping the arrow pointing this way is what makes the waitlist genuinely extractable into
 * its own service later, and it means a bug here can never fail a passenger's confirmation.
 */
@Component
public class WaitlistConversionListener implements OutboxRelay.OutboxConsumer {

    private static final Logger log = LoggerFactory.getLogger(WaitlistConversionListener.class);

    private final WaitlistRepository waitlist;

    public WaitlistConversionListener(WaitlistRepository waitlist) {
        this.waitlist = waitlist;
    }

    @Override
    public String eventType() {
        return "BookingConfirmed";
    }

    @Override
    public String consumerName() {
        return "waitlist-conversion";
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void handle(UUID eventId, JsonNode payload) {
        UUID bookingId = UUID.fromString(payload.get("bookingId").asText());
        if (waitlist.markConverted(bookingId) > 0) {
            log.info("waitlist.converted bookingId={}", bookingId);
        }
    }
}
