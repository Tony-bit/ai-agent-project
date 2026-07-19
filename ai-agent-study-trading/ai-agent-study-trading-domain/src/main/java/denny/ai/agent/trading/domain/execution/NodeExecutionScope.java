package denny.ai.agent.trading.domain.execution;

import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

public class NodeExecutionScope {

    private final Instant deadline;
    private final BooleanSupplier requestCancelled;
    private final Clock clock;
    private final AtomicReference<NodeExecutionState> state =
            new AtomicReference<>(NodeExecutionState.RUNNING);

    public NodeExecutionScope(Instant deadline, BooleanSupplier requestCancelled) {
        this(deadline, requestCancelled, Clock.systemUTC());
    }

    NodeExecutionScope(Instant deadline, BooleanSupplier requestCancelled, Clock clock) {
        this.deadline = Objects.requireNonNull(deadline, "deadline");
        this.requestCancelled = requestCancelled == null ? () -> false : requestCancelled;
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public Instant deadline() {
        return deadline;
    }

    public NodeExecutionState state() {
        return state.get();
    }

    public boolean tryStartCommit() {
        if (isRequestCancelled()) {
            markCancelled();
            return false;
        }
        if (isDeadlineElapsed()) {
            markTimedOut();
            return false;
        }
        return state.compareAndSet(NodeExecutionState.RUNNING, NodeExecutionState.COMMITTING);
    }

    public boolean markCommitted() {
        return state.compareAndSet(NodeExecutionState.COMMITTING, NodeExecutionState.COMMITTED);
    }

    public boolean markFailed() {
        return state.compareAndSet(NodeExecutionState.RUNNING, NodeExecutionState.FAILED);
    }

    public boolean failCommit() {
        return state.compareAndSet(NodeExecutionState.COMMITTING, NodeExecutionState.FAILED);
    }

    public boolean markTimedOut() {
        return state.compareAndSet(NodeExecutionState.RUNNING, NodeExecutionState.TIMED_OUT);
    }

    public boolean markCancelled() {
        return state.compareAndSet(NodeExecutionState.RUNNING, NodeExecutionState.CANCELLED);
    }

    public boolean isDeadlineElapsed() {
        return !clock.instant().isBefore(deadline);
    }

    public boolean isRequestCancelled() {
        return requestCancelled.getAsBoolean();
    }
}
