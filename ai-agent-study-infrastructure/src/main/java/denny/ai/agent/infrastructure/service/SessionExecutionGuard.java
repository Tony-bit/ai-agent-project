package denny.ai.agent.infrastructure.service;

import denny.ai.agent.domain.service.chatsession.SessionAccessState;
import lombok.Getter;
import org.springframework.stereotype.Service;

import java.util.concurrent.atomic.AtomicBoolean;

@Service
public class SessionExecutionGuard {

    private final SessionOwnershipService ownershipService;
    private final SessionOperationRegistry operationRegistry;

    public SessionExecutionGuard(SessionOwnershipService ownershipService,
                                 SessionOperationRegistry operationRegistry) {
        this.ownershipService = ownershipService;
        this.operationRegistry = operationRegistry;
    }

    public ExecutionLease acquire(String currentUserId, String sessionId) {
        SessionAccessState accessState;
        try {
            accessState = ownershipService.resolve(currentUserId, sessionId);
        } catch (IllegalArgumentException exception) {
            throw new ExecutionFailure(FailureReason.INVALID, "invalid request");
        }
        if (accessState == SessionAccessState.UNAVAILABLE) {
            throw new ExecutionFailure(FailureReason.UNAVAILABLE, "session id unavailable");
        }
        if (!operationRegistry.tryAcquireExecution(currentUserId, sessionId)) {
            throw new ExecutionFailure(FailureReason.BUSY, "session is running");
        }
        return new ExecutionLease(operationRegistry, currentUserId, sessionId);
    }

    public enum FailureReason {
        INVALID,
        UNAVAILABLE,
        BUSY
    }

    @Getter
    public static class ExecutionFailure extends RuntimeException {

        private final FailureReason reason;

        public ExecutionFailure(FailureReason reason, String message) {
            super(message);
            this.reason = reason;
        }
    }

    public static class ExecutionLease implements AutoCloseable {

        private final SessionOperationRegistry operationRegistry;
        private final String userId;
        private final String sessionId;
        private final AtomicBoolean closed = new AtomicBoolean();

        ExecutionLease(SessionOperationRegistry operationRegistry, String userId, String sessionId) {
            this.operationRegistry = operationRegistry;
            this.userId = userId;
            this.sessionId = sessionId;
        }

        @Override
        public void close() {
            if (closed.compareAndSet(false, true)) {
                operationRegistry.releaseExecution(userId, sessionId);
            }
        }
    }
}
