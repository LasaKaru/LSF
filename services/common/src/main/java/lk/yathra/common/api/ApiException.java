package lk.yathra.common.api;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * An error that is part of the API contract rather than a bug.
 *
 * <p>Extension members ({@code with}) carry the machine-readable recovery data that makes a 409
 * actionable -- the conflicting seats, ranked alternatives, a fresh availability URL. The frontend's
 * error handling is then data-driven instead of string-matching on a message.
 */
public class ApiException extends RuntimeException {

    private final ErrorCode code;
    private final Map<String, Object> extensions = new LinkedHashMap<>();

    public ApiException(ErrorCode code, String detail) {
        super(detail);
        this.code = code;
    }

    public ApiException(ErrorCode code, String detail, Throwable cause) {
        super(detail, cause);
        this.code = code;
    }

    public ApiException with(String key, Object value) {
        extensions.put(key, value);
        return this;
    }

    public ErrorCode code() {
        return code;
    }

    public Map<String, Object> extensions() {
        return extensions;
    }
}
