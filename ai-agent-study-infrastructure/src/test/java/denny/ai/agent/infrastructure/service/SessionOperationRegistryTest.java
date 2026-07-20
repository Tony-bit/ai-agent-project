package denny.ai.agent.infrastructure.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SessionOperationRegistryTest {

    private SessionOperationRegistry registry;

    @BeforeEach
    void setUp() {
        registry = new SessionOperationRegistry();
    }

    @Test
    void executionBlocksDeletionUntilReleased() {
        assertTrue(registry.tryAcquireExecution("user_a", "session_1"));
        assertTrue(registry.isRunning("user_a", "session_1"));
        assertFalse(registry.tryAcquireDeletion("user_a", "session_1"));

        registry.releaseExecution("user_a", "session_1");
        assertTrue(registry.tryAcquireDeletion("user_a", "session_1"));
    }

    @Test
    void deletionBlocksExecutionUntilReleased() {
        assertTrue(registry.tryAcquireDeletion("user_a", "session_1"));
        assertFalse(registry.tryAcquireExecution("user_a", "session_1"));

        registry.releaseDeletion("user_a", "session_1");
        assertTrue(registry.tryAcquireExecution("user_a", "session_1"));
    }

    @Test
    void wrongLeaseTypeCannotReleaseCurrentOperation() {
        assertTrue(registry.tryAcquireExecution("user_a", "session_1"));
        registry.releaseDeletion("user_a", "session_1");
        assertFalse(registry.tryAcquireDeletion("user_a", "session_1"));

        registry.releaseExecution("user_a", "session_1");
        assertTrue(registry.tryAcquireDeletion("user_a", "session_1"));
        registry.releaseExecution("user_a", "session_1");
        assertFalse(registry.tryAcquireExecution("user_a", "session_1"));
    }

    @Test
    void sessionsAndUsersHaveIndependentLeases() {
        assertTrue(registry.tryAcquireExecution("user_a", "session_1"));
        assertTrue(registry.tryAcquireExecution("user_a", "session_2"));
        assertTrue(registry.tryAcquireExecution("user_b", "session_1"));
    }
}
