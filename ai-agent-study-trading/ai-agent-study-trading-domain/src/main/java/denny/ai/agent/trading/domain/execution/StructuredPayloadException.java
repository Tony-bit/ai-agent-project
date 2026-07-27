package denny.ai.agent.trading.domain.execution;

import denny.ai.agent.trading.domain.validation.ValidationErrorCode;

public class StructuredPayloadException extends RuntimeException {
    private final ValidationErrorCode errorCode;

    public StructuredPayloadException(String message, Throwable cause) {
        super(message, cause);
        this.errorCode = ValidationErrorCode.INVALID_SCHEMA;
    }

    public StructuredPayloadException(String message) {
        this(message, null);
    }

    public ValidationErrorCode getErrorCode() {
        return errorCode;
    }
}
