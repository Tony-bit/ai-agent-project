package denny.ai.agent.trading.domain.execution;

import java.util.Objects;

public record NodeExecutionResult<T>(NodeExecutionStatus status,
                                     T value,
                                     Throwable error,
                                     NodeExecutionScope scope,
                                     long latencyMs) {

    public NodeExecutionResult {
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(scope, "scope");
        if (latencyMs < 0) {
            throw new IllegalArgumentException("latencyMs must not be negative");
        }
    }

    public static <T> NodeExecutionResult<T> success(T value, NodeExecutionScope scope) {
        return new NodeExecutionResult<>(NodeExecutionStatus.SUCCESS,
                Objects.requireNonNull(value, "value"), null, scope, scope.elapsedMillis());
    }

    public static <T> NodeExecutionResult<T> failed(Throwable error, NodeExecutionScope scope) {
        scope.markFailed();
        return new NodeExecutionResult<>(NodeExecutionStatus.FAILED, null,
                Objects.requireNonNull(error, "error"), scope, scope.elapsedMillis());
    }

    public static <T> NodeExecutionResult<T> timedOut(Throwable error, NodeExecutionScope scope) {
        scope.markTimedOut();
        return new NodeExecutionResult<>(NodeExecutionStatus.TIMED_OUT, null, error, scope,
                scope.elapsedMillis());
    }

    public static <T> NodeExecutionResult<T> cancelled(Throwable error, NodeExecutionScope scope) {
        scope.markCancelled();
        return new NodeExecutionResult<>(NodeExecutionStatus.CANCELLED, null, error, scope,
                scope.elapsedMillis());
    }

    public boolean isSuccess() {
        return status == NodeExecutionStatus.SUCCESS;
    }
}
