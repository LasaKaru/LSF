package lk.yathra.booking;

import static org.assertj.core.api.Assertions.assertThat;

import lk.yathra.booking.support.PostgresTestSupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;

/**
 * The dispatcher's miss path: URLs that match no route, and verbs a route does not support.
 *
 * <p>This exists because of a real defect. Every other test in this suite reaches the application
 * through a controller that Spring has already matched, so none of them ever exercised what happens
 * when nothing matches. In that case Spring's static-resource handler is the last thing to see the
 * request and throws {@code NoResourceFoundException}, which landed in the catch-all
 * {@code @ExceptionHandler(Exception.class)} and was reported as a <b>500 with a stack trace logged at
 * ERROR</b>. A mistyped URL was indistinguishable from the service falling over — the kind of thing
 * that wakes an on-call engineer for a typo, and the kind of thing that only shows up when you drive
 * the API over HTTP rather than through the beans.
 *
 * <p>Neither assertion here is about the domain. Both are about not lying to the caller.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ApiErrorMappingIT extends PostgresTestSupport {

    @Autowired TestRestTemplate rest;

    @Test
    @DisplayName("an unmatched URL is 404 ROUTE_NOT_FOUND, not 500")
    void unmatchedUrlIsNotFound() {
        var response = rest.getForEntity("/api/v1/bookings/nope/cancel", String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody()).contains("ROUTE_NOT_FOUND");
    }

    @Test
    @DisplayName("an unsupported verb on a real route is 405, and names what is supported")
    void wrongVerbIsMethodNotAllowed() {
        // /api/v1/bookings exists for POST only.
        var response = rest.exchange("/api/v1/bookings", HttpMethod.PUT, null, String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.METHOD_NOT_ALLOWED);
        assertThat(response.getBody()).contains("METHOD_NOT_ALLOWED").contains("POST");
    }
}
