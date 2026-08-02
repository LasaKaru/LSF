package lk.yathra.common.api;

import jakarta.servlet.http.HttpServletRequest;
import java.net.URI;
import java.time.Instant;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

/**
 * Translates exceptions into RFC 9457 {@code application/problem+json}.
 *
 * <p>Two rules, both deliberate:
 *
 * <ul>
 *   <li><b>Stack traces never reach a client.</b> Unexpected failures are logged with their
 *       correlation id and returned as an opaque {@code INTERNAL_ERROR}; the correlation id is the
 *       thread the operator pulls, not the client.
 *   <li><b>A contested seat is not an error.</b> {@code 409 SEAT_SEGMENT_UNAVAILABLE} is ordinary
 *       business flow on a busy trip, so it logs at INFO. Reserving WARN/ERROR for things a human
 *       should look at is what keeps the signal usable.
 * </ul>
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(ApiException.class)
    public ProblemDetail handleApi(ApiException ex, HttpServletRequest request) {
        ProblemDetail pd = base(ex.code(), ex.getMessage(), request);
        ex.extensions().forEach(pd::setProperty);

        if (ex.code().status().is5xxServerError()) {
            log.error("api_error code={} detail={}", ex.code(), ex.getMessage(), ex);
        } else {
            log.info("api_error code={} detail={}", ex.code(), ex.getMessage());
        }
        return pd;
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ProblemDetail handleValidation(MethodArgumentNotValidException ex, HttpServletRequest request) {
        String detail =
                ex.getBindingResult().getFieldErrors().stream()
                        .map(f -> f.getField() + ": " + f.getDefaultMessage())
                        .collect(Collectors.joining("; "));
        return base(ErrorCode.VALIDATION_FAILED, detail, request);
    }

    @ExceptionHandler(MissingRequestHeaderException.class)
    public ProblemDetail handleMissingHeader(MissingRequestHeaderException ex, HttpServletRequest request) {
        ErrorCode code =
                "Idempotency-Key".equalsIgnoreCase(ex.getHeaderName())
                        ? ErrorCode.IDEMPOTENCY_KEY_REQUIRED
                        : ErrorCode.MALFORMED_REQUEST;
        return base(code, "Missing required header: " + ex.getHeaderName(), request);
    }

    @ExceptionHandler({HttpMessageNotReadableException.class, MethodArgumentTypeMismatchException.class})
    public ProblemDetail handleUnreadable(Exception ex, HttpServletRequest request) {
        return base(ErrorCode.MALFORMED_REQUEST, "Request body or parameter could not be parsed", request);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ProblemDetail handleIllegalArgument(IllegalArgumentException ex, HttpServletRequest request) {
        // Leg.of() rejects degenerate and inverted ranges this way.
        return base(ErrorCode.INVALID_JOURNEY_LEG, ex.getMessage(), request);
    }

    @ExceptionHandler(Exception.class)
    public ProblemDetail handleUnexpected(Exception ex, HttpServletRequest request) {
        log.error("unhandled_exception path={}", request.getRequestURI(), ex);
        return base(ErrorCode.INTERNAL_ERROR, "An unexpected error occurred.", request);
    }

    private ProblemDetail base(ErrorCode code, String detail, HttpServletRequest request) {
        ProblemDetail pd =
                ProblemDetail.forStatusAndDetail(
                        code.status() == null ? HttpStatus.INTERNAL_SERVER_ERROR : code.status(), detail);
        pd.setType(URI.create(code.typeUri()));
        pd.setTitle(code.title());
        pd.setInstance(URI.create(request.getRequestURI()));
        pd.setProperty("code", code.name());
        pd.setProperty("timestamp", Instant.now().toString());
        String correlationId = CorrelationIdFilter.current();
        if (correlationId != null) {
            pd.setProperty("correlationId", correlationId);
        }
        return pd;
    }
}
