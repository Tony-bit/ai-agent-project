package denny.ai.agent.infrastructure.service;

import org.springframework.stereotype.Component;

import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

@Component
public class SessionOperationRegistry {

    private final ConcurrentMap<SessionKey, Operation> operations = new ConcurrentHashMap<>();

    public boolean tryAcquireExecution(String userId, String sessionId) {
        return tryAcquire(userId, sessionId, Operation.EXECUTING);
    }

    public void releaseExecution(String userId, String sessionId) {
        release(userId, sessionId, Operation.EXECUTING);
    }

    public boolean tryAcquireDeletion(String userId, String sessionId) {
        return tryAcquire(userId, sessionId, Operation.DELETING);
    }

    public void releaseDeletion(String userId, String sessionId) {
        release(userId, sessionId, Operation.DELETING);
    }

    public boolean isRunning(String userId, String sessionId) {
        return operations.get(key(userId, sessionId)) == Operation.EXECUTING;
    }

    private boolean tryAcquire(String userId, String sessionId, Operation operation) {
        return operations.putIfAbsent(key(userId, sessionId), operation) == null;
    }

    private void release(String userId, String sessionId, Operation operation) {
        operations.remove(key(userId, sessionId), operation);
    }

    private SessionKey key(String userId, String sessionId) {
        return new SessionKey(
                Objects.requireNonNull(userId, "userId"),
                Objects.requireNonNull(sessionId, "sessionId"));
    }

    private enum Operation {
        EXECUTING,
        DELETING
    }

    private record SessionKey(String userId, String sessionId) {
    }
}
