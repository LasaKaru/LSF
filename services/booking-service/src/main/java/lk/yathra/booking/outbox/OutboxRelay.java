package lk.yathra.booking.outbox;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Drains the transactional outbox and dispatches events to in-process consumers.
 *
 * <p>In the topology docs/02 describes, this role belongs to Debezium (production) or a Kafka producer,
 * and consumers live in separate services. The shipped profile has no broker, so the relay dispatches
 * in-process instead. The contract consumers see is identical — an event type, an event id and a JSON
 * payload — so moving one to a real broker later is a deployment change rather than a rewrite.
 *
 * <p>Two properties are preserved exactly because they are the ones that matter:
 *
 * <ul>
 *   <li><b>At-least-once delivery.</b> An event is marked published only after its consumers have run,
 *       so a crash mid-dispatch redelivers rather than loses. Consumers must therefore be idempotent,
 *       which is what {@code processed_event} is for.
 *   <li><b>Per-trip ordering.</b> Events are drained in id order, and {@code partition_key} is the trip
 *       id, so a trip's events are seen in the order they were written.
 * </ul>
 */
@Component
public class OutboxRelay {

    private static final Logger log = LoggerFactory.getLogger(OutboxRelay.class);

    private final NamedParameterJdbcTemplate jdbc;
    private final ObjectMapper objectMapper;
    private final List<OutboxConsumer> consumers;

    public OutboxRelay(
            NamedParameterJdbcTemplate jdbc, ObjectMapper objectMapper, List<OutboxConsumer> consumers) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
        this.consumers = consumers;
        log.info(
                "outbox.relay_ready consumers={}",
                consumers.stream().map(c -> c.consumerName() + "<-" + c.eventType()).toList());
    }

    /** What an in-process consumer implements. Deliberately shaped like a broker subscription. */
    public interface OutboxConsumer {
        /** The {@code event_type} this consumer reacts to. */
        String eventType();

        /** A stable name, used to dedupe redeliveries in {@code processed_event}. */
        String consumerName();

        void handle(UUID eventId, JsonNode payload);
    }

    @Scheduled(fixedDelayString = "${yathra.booking.outbox-relay-interval-ms:1000}")
    // lockAtLeastFor is 0, unlike the sweeper's. The sweeper uses a floor to stop replicas with
    // skewed clocks re-running an expensive scan; the relay is cheap, uses SKIP LOCKED, and dedupes
    // per consumer, so a back-to-back drain is harmless -- and a floor here would silently swallow
    // the second call, which is exactly what it did to the idempotency test.
    @SchedulerLock(name = "outboxRelay", lockAtLeastFor = "PT0S", lockAtMostFor = "PT1M")
    @Transactional
    public void drain() {
        var batch =
                jdbc.query(
                        """
                        SELECT id, event_id, event_type, payload::text AS payload
                          FROM outbox_event
                         WHERE published_at IS NULL
                         ORDER BY id
                         LIMIT 200
                         FOR UPDATE SKIP LOCKED
                        """,
                        Map.of(),
                        (rs, i) ->
                                new Object[] {
                                    rs.getLong("id"),
                                    rs.getObject("event_id", UUID.class),
                                    rs.getString("event_type"),
                                    rs.getString("payload")
                                });

        if (batch.isEmpty()) {
            return;
        }

        for (Object[] row : batch) {
            long id = (Long) row[0];
            UUID eventId = (UUID) row[1];
            String eventType = (String) row[2];

            for (OutboxConsumer consumer : consumers) {
                if (!consumer.eventType().equals(eventType)) {
                    continue;
                }
                // Dedupe first. Delivery is at-least-once, so the same event can arrive twice after a
                // crash; a duplicate SegmentReleased must not promote two people onto one seat.
                if (!claim(eventId, consumer.consumerName())) {
                    continue;
                }
                try {
                    consumer.handle(eventId, objectMapper.readTree((String) row[3]));
                } catch (Exception e) {
                    // A failing consumer must not wedge the relay: the event is still marked published
                    // and the failure is logged loudly. Retrying forever would block every later event
                    // behind one poison message.
                    log.error(
                            "outbox.consumer_failed consumer={} eventType={} eventId={}",
                            consumer.consumerName(), eventType, eventId, e);
                }
            }

            jdbc.update(
                    "UPDATE outbox_event SET published_at = now() WHERE id = :id", Map.of("id", id));
        }

        log.debug("outbox.drained count={}", batch.size());
    }

    /**
     * @return true if this consumer has not already processed the event
     *     <p>Claimed in the relay's transaction, before the consumer runs in its own. A consumer that
     *     fails therefore stays claimed and is not retried forever -- the failure is logged loudly
     *     instead. Retrying a poison message indefinitely would block every later event behind it.
     */
    private boolean claim(UUID eventId, String consumer) {
        return jdbc.update(
                        """
                        INSERT INTO processed_event (event_id, consumer) VALUES (:eventId, :consumer)
                        ON CONFLICT (event_id, consumer) DO NOTHING
                        """,
                        new MapSqlParameterSource().addValue("eventId", eventId).addValue("consumer", consumer))
                > 0;
    }
}
