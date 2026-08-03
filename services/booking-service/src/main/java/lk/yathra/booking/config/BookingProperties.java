package lk.yathra.booking.config;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.annotation.PostConstruct;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Every domain knob, bound from environment variables and validated at startup.
 *
 * <p>The service <em>refuses to start</em> on bad configuration rather than degrading. A service that
 * boots with a nonsensical hold TTL and misbehaves three hours later is far worse to operate than one
 * that fails immediately with the offending variable named.
 *
 * <p>See docs/17 for the full reference.
 */
@ConfigurationProperties(prefix = "yathra.booking")
@Validated
public class BookingProperties {

    /** How long a hold reserves inventory. Longer is kinder UX and more squatting surface. */
    @Min(60)
    @Max(3600)
    private int holdTtlSeconds = 600;

    @Min(1)
    @Max(300)
    private int sweepIntervalSeconds = 15;

    @Min(1)
    @Max(10000)
    private int sweepBatchSize = 500;

    /** Booking closes this many minutes before departure. */
    @Min(0)
    private int cutoffMinutes = 30;

    @Min(1)
    @Max(20)
    private int maxSeatsPerBooking = 6;

    /** Anti-squatting control: caps how much inventory one contact can freeze at once (docs/11 §4). */
    @Min(1)
    @Max(50)
    private int maxActiveHoldsPerContact = 6;

    @Min(1)
    @Max(10)
    private int retryMaxAttempts = 3;

    @Min(1)
    private int retryBackoffMs = 50;

    /**
     * Stops a seat stays blocked after a passenger alights. Zero for trains -- the alighting and
     * boarding passengers swap at the same stop. The hook exists because hotels need it and the
     * department might one day want it; it is applied at the single range-construction site.
     */
    @Min(0)
    @Max(5)
    private int turnaroundBufferStops = 0;

    /** Auto-assignment policy. See SeatSelectionStrategy. */
    @NotBlank
    private String seatSelectionStrategy = "FRAGMENTATION_MINIMISING";

    /** Shared secret used to verify pricing-service's quote signatures locally. */
    @NotBlank
    private String quoteSigningKey = "";

    @PostConstruct
    void validateSecrets() {
        // A short signing key is worse than none, because it looks like security.
        if (quoteSigningKey.getBytes().length < 32) {
            throw new IllegalStateException(
                    "QUOTE_SIGNING_KEY must be at least 32 bytes. Generate one with: openssl rand -base64 48");
        }
    }

    public int getHoldTtlSeconds() {
        return holdTtlSeconds;
    }

    public void setHoldTtlSeconds(int v) {
        this.holdTtlSeconds = v;
    }

    public int getSweepIntervalSeconds() {
        return sweepIntervalSeconds;
    }

    public void setSweepIntervalSeconds(int v) {
        this.sweepIntervalSeconds = v;
    }

    public int getSweepBatchSize() {
        return sweepBatchSize;
    }

    public void setSweepBatchSize(int v) {
        this.sweepBatchSize = v;
    }

    public int getCutoffMinutes() {
        return cutoffMinutes;
    }

    public void setCutoffMinutes(int v) {
        this.cutoffMinutes = v;
    }

    public int getMaxSeatsPerBooking() {
        return maxSeatsPerBooking;
    }

    public void setMaxSeatsPerBooking(int v) {
        this.maxSeatsPerBooking = v;
    }

    public int getMaxActiveHoldsPerContact() {
        return maxActiveHoldsPerContact;
    }

    public void setMaxActiveHoldsPerContact(int v) {
        this.maxActiveHoldsPerContact = v;
    }

    public int getRetryMaxAttempts() {
        return retryMaxAttempts;
    }

    public void setRetryMaxAttempts(int v) {
        this.retryMaxAttempts = v;
    }

    public int getRetryBackoffMs() {
        return retryBackoffMs;
    }

    public void setRetryBackoffMs(int v) {
        this.retryBackoffMs = v;
    }

    public int getTurnaroundBufferStops() {
        return turnaroundBufferStops;
    }

    public void setTurnaroundBufferStops(int v) {
        this.turnaroundBufferStops = v;
    }

    public String getSeatSelectionStrategy() {
        return seatSelectionStrategy;
    }

    public void setSeatSelectionStrategy(String v) {
        this.seatSelectionStrategy = v;
    }

    public String getQuoteSigningKey() {
        return quoteSigningKey;
    }

    public void setQuoteSigningKey(String v) {
        this.quoteSigningKey = v;
    }
}
