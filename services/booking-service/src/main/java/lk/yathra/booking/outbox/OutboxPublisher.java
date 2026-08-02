package lk.yathra.booking.outbox;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import java.util.UUID;
import lk.yathra.common.api.CorrelationIdFilter;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Writes domain events to the outbox table inside the caller's transaction.
 *
 * <p>The alternative -- commit the database, then publish to the broker -- is a dual write, and its
 * failure mode is silent: the process dies between the two and the event is lost forever with nothing
 * to reconcile against. Writing the event in the same transaction as the state change makes "the
 * booking happened" and "the event will be delivered" a single atomic fact (ADR-006).
 *
 * <p>{@code partition_key} is the trip id so that every event for one trip is ordered relative to the
 * others, which is what the waitlist matcher needs to reason correctly about releases.
 */
@Component
public class OutboxPublisher {

    private final NamedParameterJdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    public OutboxPublisher(NamedParameterJdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    public void publish(String aggregateType, UUID aggregateId, String eventType, UUID tripId, Map<String, ?> payload) {
        try {
            jdbc.update(
                    """
                    INSERT INTO outbox_event (event_id, aggregate_type, aggregate_id, event_type,
                                              partition_key, payload, correlation_id)
                    VALUES (:eventId, :aggregateType, :aggregateId, :eventType, :partitionKey,
                            CAST(:payload AS jsonb), :correlationId)
                    """,
                    new MapSqlParameterSource()
                            .addValue("eventId", UUID.randomUUID())
                            .addValue("aggregateType", aggregateType)
                            .addValue("aggregateId", aggregateId)
                            .addValue("eventType", eventType)
                            .addValue("partitionKey", tripId.toString())
                            .addValue("payload", objectMapper.writeValueAsString(payload))
                            .addValue("correlationId", CorrelationIdFilter.current()));
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException("Unable to serialise outbox payload for " + eventType, e);
        }
    }
}
