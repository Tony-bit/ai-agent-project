package denny.ai.agent.domain.service.armory.factory.element;

public class ResponseValidationException extends RuntimeException {

    private final ResponseValidationFailureType failureType;

    public ResponseValidationException(ResponseValidationFailureType failureType, String message) {
        super(message);
        this.failureType = failureType;
    }

    public ResponseValidationException(ResponseValidationFailureType failureType, String message, Throwable cause) {
        super(message, cause);
        this.failureType = failureType;
    }

    public ResponseValidationFailureType getFailureType() {
        return failureType;
    }
}
