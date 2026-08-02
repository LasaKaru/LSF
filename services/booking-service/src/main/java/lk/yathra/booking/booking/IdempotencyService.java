package lk.yathra.booking.booking;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import lk.yathra.common.api.ApiException;
import lk.yathra.common.api.ErrorCode;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * Makes {@code POST /bookings} safe to retry.
 *
 * <p>This is not gold-plating. The target users are on congested mobile networks: they double-tap
 * "Confirm", they refresh mid-request, and the gateway retries on 5xx. Without idempotency each of
 * those sells a second seat and takes a second payment.
 *
 * <p>The record is written <b>in the same transaction as the booking</b>, so there is no window in
 * which the booking exists but its idempotency record does not -- which would be the one state where
 * a retry double-books.
 */
@Service
public class IdempotencyService {

    private static final Duration RETENTION = Duration.ofHours(24);

    private final NamedParameterJdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    public IdempotencyService(NamedParameterJdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    public record Stored(int status, String body) {}

    public String hash(Object requestBody) {
        try {
            byte[] canonical = objectMapper.writeValueAsBytes(requestBody);
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(canonical);
            return HexFormat.of().formatHex(digest);
        } catch (Exception e) {
            throw new IllegalStateException("Unable to hash request body", e);
        }
    }

    /**
     * @return the original response when this exact request has been seen before
     * @throws ApiException {@code IDEMPOTENCY_KEY_REUSED} when the key is reused with a different body
     *     -- silently returning the first response there would be worse, because the caller would
     *     believe a different booking had been made
     */
    public Optional<Stored> lookup(String key, String requestHash) {
        var rows =
                jdbc.query(
                        "SELECT request_hash, response_status, response_body::text AS body "
                                + "FROM idempotency_record WHERE key = :key AND expires_at > now()",
                        Map.of("key", key),
                        (rs, i) ->
                                new Object[] {
                                    rs.getString("request_hash"), rs.getInt("response_status"), rs.getString("body")
                                });

        if (rows.isEmpty()) {
            return Optional.empty();
        }
        Object[] row = rows.get(0);
        if (!requestHash.equals(row[0])) {
            throw new ApiException(
                    ErrorCode.IDEMPOTENCY_KEY_REUSED,
                    "This Idempotency-Key was already used with a different request body.");
        }
        return Optional.of(new Stored((Integer) row[1], (String) row[2]));
    }

    public void store(String key, String requestHash, int status, Object responseBody, UUID bookingId) {
        try {
            jdbc.update(
                    """
                    INSERT INTO idempotency_record (key, request_hash, response_status, response_body,
                                                    booking_id, expires_at)
                    VALUES (:key, :hash, :status, CAST(:body AS jsonb), :bookingId, :expiresAt)
                    """,
                    new MapSqlParameterSource()
                            .addValue("key", key)
                            .addValue("hash", requestHash)
                            .addValue("status", status)
                            .addValue("body", objectMapper.writeValueAsString(responseBody))
                            .addValue("bookingId", bookingId)
                            .addValue("expiresAt", Timestamp.from(Instant.now().plus(RETENTION))));
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException("Unable to serialise idempotent response", e);
        }
    }
}
