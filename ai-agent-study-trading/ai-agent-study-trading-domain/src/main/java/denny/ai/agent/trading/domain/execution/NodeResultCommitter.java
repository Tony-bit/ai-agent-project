package denny.ai.agent.trading.domain.execution;

import denny.ai.agent.trading.domain.config.TradingPhase;
import org.springframework.stereotype.Component;

import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Supplier;

@Component
public class NodeResultCommitter {

    public <T> boolean commit(NodeExecutionResult<T> result,
                              TradingPhase expectedPhase,
                              Supplier<TradingPhase> currentPhase,
                              Consumer<T> contextWriter) {
        Objects.requireNonNull(result, "result");
        Objects.requireNonNull(expectedPhase, "expectedPhase");
        Objects.requireNonNull(currentPhase, "currentPhase");
        Objects.requireNonNull(contextWriter, "contextWriter");
        if (!result.isSuccess()) {
            return false;
        }

        NodeExecutionScope scope = result.scope();
        if (currentPhase.get() != expectedPhase) {
            scope.markFailed();
            return false;
        }
        if (!scope.tryStartCommit()) {
            return false;
        }
        if (scope.isRequestCancelled() || scope.isDeadlineElapsed()
                || currentPhase.get() != expectedPhase) {
            scope.failCommit();
            return false;
        }

        try {
            contextWriter.accept(result.value());
            if (!scope.markCommitted()) {
                throw new IllegalStateException("Node commit state changed unexpectedly");
            }
            return true;
        } catch (RuntimeException error) {
            scope.failCommit();
            throw error;
        }
    }
}
