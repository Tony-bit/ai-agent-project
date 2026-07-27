package denny.ai.agent.trading.domain.validation;

import java.time.Instant;
import java.util.List;

public record NodeValidationAudit(
        String nodeName,
        Status status,
        List<ValidationErrorCode> errorCodes,
        Instant recordedAt
) {
    public NodeValidationAudit {
        errorCodes = errorCodes == null ? List.of() : List.copyOf(errorCodes);
    }

    public enum Status {
        VALID,
        INVALID,
        EXECUTION_FAILED,
        MISSING
    }
}
