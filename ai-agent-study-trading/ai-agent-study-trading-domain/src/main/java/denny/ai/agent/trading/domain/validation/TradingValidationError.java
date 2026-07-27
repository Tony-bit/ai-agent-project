package denny.ai.agent.trading.domain.validation;

import java.util.Objects;

/** 可记录且可安全暴露错误码的节点校验错误。 */
public record TradingValidationError(
        ValidationErrorCode code,
        String message,
        String field
) {
    public TradingValidationError {
        Objects.requireNonNull(code, "code must not be null");
        if (message == null || message.isBlank()) {
            throw new IllegalArgumentException("message must not be blank");
        }
        message = message.trim();
        field = field == null || field.isBlank() ? null : field.trim();
    }
}
