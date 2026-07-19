package denny.ai.agent.trading.domain.execution;

public enum NodeExecutionState {
    RUNNING,
    COMMITTING,
    COMMITTED,
    FAILED,
    TIMED_OUT,
    CANCELLED
}
