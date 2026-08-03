package lk.yathra.catalog.config;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@ConfigurationProperties(prefix = "yathra.catalog")
@Validated
public class CatalogProperties {

    /** How far ahead trips are published. */
    @Min(1)
    @Max(365)
    private int bookingHorizonDays = 30;

    /** Booking closes this many minutes before departure. */
    @Min(0)
    private int bookingCutoffMinutes = 30;

    @NotBlank private String bookingServiceUrl = "http://booking-service:8081";

    private boolean autoPublishEnabled = true;

    public int getBookingHorizonDays() {
        return bookingHorizonDays;
    }

    public void setBookingHorizonDays(int v) {
        this.bookingHorizonDays = v;
    }

    public int getBookingCutoffMinutes() {
        return bookingCutoffMinutes;
    }

    public void setBookingCutoffMinutes(int v) {
        this.bookingCutoffMinutes = v;
    }

    public String getBookingServiceUrl() {
        return bookingServiceUrl;
    }

    public void setBookingServiceUrl(String v) {
        this.bookingServiceUrl = v;
    }

    public boolean isAutoPublishEnabled() {
        return autoPublishEnabled;
    }

    public void setAutoPublishEnabled(boolean v) {
        this.autoPublishEnabled = v;
    }
}
