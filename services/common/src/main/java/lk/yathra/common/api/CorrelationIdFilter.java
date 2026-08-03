package lk.yathra.common.api;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Accepts {@code X-Correlation-Id} from the caller, generates one when absent, puts it in the logging
 * MDC and echoes it on the response.
 *
 * <p>One id follows a request across every service and into event envelopes, so an asynchronous
 * effect can be traced back to the synchronous action that caused it.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class CorrelationIdFilter extends OncePerRequestFilter {

    public static final String HEADER = "X-Correlation-Id";
    private static final String MDC_KEY = "correlationId";

    /** The correlation id of the request being handled on this thread, or null outside a request. */
    public static String current() {
        return MDC.get(MDC_KEY);
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {

        String correlationId = request.getHeader(HEADER);
        if (correlationId == null || correlationId.isBlank()) {
            correlationId = "c-" + UUID.randomUUID();
        }
        // Bound so a hostile client cannot use the header to bloat every log line.
        if (correlationId.length() > 64) {
            correlationId = correlationId.substring(0, 64);
        }

        MDC.put(MDC_KEY, correlationId);
        response.setHeader(HEADER, correlationId);
        try {
            chain.doFilter(request, response);
        } finally {
            MDC.remove(MDC_KEY);
        }
    }
}
