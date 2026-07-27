package denny.ai.agent.trading.domain.validation;

import java.util.List;

public record NodeValidationResult(List<TradingValidationError> errors) {
    public NodeValidationResult {
        errors = errors == null ? List.of() : List.copyOf(errors);
    }

    public boolean isValid() {
        return errors.isEmpty();
    }
}
