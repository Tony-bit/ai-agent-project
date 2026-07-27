package denny.ai.agent.trading.domain.execution;

import denny.ai.agent.trading.domain.validation.TradingValidationError;

import java.util.List;

public record NodeCommitResult(boolean committed, List<TradingValidationError> validationErrors) {
    public NodeCommitResult {
        validationErrors = validationErrors == null ? List.of() : List.copyOf(validationErrors);
    }

    public static NodeCommitResult committedResult() {
        return new NodeCommitResult(true, List.of());
    }

    public static NodeCommitResult rejected(List<TradingValidationError> errors) {
        return new NodeCommitResult(false, errors);
    }

    public static NodeCommitResult notCommitted() {
        return new NodeCommitResult(false, List.of());
    }

    public boolean validationFailed() {
        return !validationErrors.isEmpty();
    }
}
