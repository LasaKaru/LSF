package lk.yathra.pricing.config;

import jakarta.annotation.PostConstruct;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import java.math.BigDecimal;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@ConfigurationProperties(prefix = "yathra.pricing")
@Validated
public class PricingProperties {

    @NotBlank private String quoteSigningKey = "";

    @Min(60)
    @Max(3600)
    private int quoteTtlSeconds = 600;

    @NotBlank private String bookingServiceUrl = "http://booking-service:8081";

    /**
     * Off by default, and that is a policy position rather than a technical one.
     *
     * <p>Shipping a system that silently varies public transport fares by demand, without the
     * department having chosen that, would be inappropriate regardless of how well the feature works.
     */
    private boolean demandPricingEnabled = false;

    private BigDecimal demandMultiplierMin = new BigDecimal("0.90");
    private BigDecimal demandMultiplierMax = new BigDecimal("1.40");

    private boolean scenicSurchargeEnabled = true;

    @PostConstruct
    void validate() {
        if (quoteSigningKey.getBytes().length < 32) {
            throw new IllegalStateException(
                    "QUOTE_SIGNING_KEY must be at least 32 bytes. Generate one with: openssl rand -base64 48");
        }
        if (demandMultiplierMin.compareTo(demandMultiplierMax) >= 0) {
            throw new IllegalStateException(
                    "DEMAND_MULTIPLIER_MIN ("
                            + demandMultiplierMin
                            + ") must be less than DEMAND_MULTIPLIER_MAX ("
                            + demandMultiplierMax
                            + ")");
        }
    }

    public String getQuoteSigningKey() {
        return quoteSigningKey;
    }

    public void setQuoteSigningKey(String v) {
        this.quoteSigningKey = v;
    }

    public int getQuoteTtlSeconds() {
        return quoteTtlSeconds;
    }

    public void setQuoteTtlSeconds(int v) {
        this.quoteTtlSeconds = v;
    }

    public String getBookingServiceUrl() {
        return bookingServiceUrl;
    }

    public void setBookingServiceUrl(String v) {
        this.bookingServiceUrl = v;
    }

    public boolean isDemandPricingEnabled() {
        return demandPricingEnabled;
    }

    public void setDemandPricingEnabled(boolean v) {
        this.demandPricingEnabled = v;
    }

    public BigDecimal getDemandMultiplierMin() {
        return demandMultiplierMin;
    }

    public void setDemandMultiplierMin(BigDecimal v) {
        this.demandMultiplierMin = v;
    }

    public BigDecimal getDemandMultiplierMax() {
        return demandMultiplierMax;
    }

    public void setDemandMultiplierMax(BigDecimal v) {
        this.demandMultiplierMax = v;
    }

    public boolean isScenicSurchargeEnabled() {
        return scenicSurchargeEnabled;
    }

    public void setScenicSurchargeEnabled(boolean v) {
        this.scenicSurchargeEnabled = v;
    }
}
