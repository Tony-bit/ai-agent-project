package denny.ai.agent.test.infrastructure.service;

import denny.ai.agent.domain.service.chatmemory.ConversationMemoryService;
import denny.ai.agent.domain.service.chatsession.SessionAccessState;
import denny.ai.agent.infrastructure.dao.IChatMessageDao;
import denny.ai.agent.infrastructure.dao.IChatSessionDao;
import denny.ai.agent.infrastructure.service.ChatSessionCommandService;
import denny.ai.agent.infrastructure.service.SessionActivityTracker;
import denny.ai.agent.infrastructure.service.SessionOperationRegistry;
import denny.ai.agent.infrastructure.service.SessionOwnershipService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ChatSessionCommandServiceTest {

    private IChatMessageDao messageDao;
    private IChatSessionDao sessionDao;
    private SessionOwnershipService ownershipService;
    private SessionOperationRegistry operationRegistry;
    private ConversationMemoryService conversationMemoryService;
    private SessionActivityTracker activityTracker;
    private ChatSessionCommandService service;

    @BeforeEach
    void setUp() {
        messageDao = mock(IChatMessageDao.class);
        sessionDao = mock(IChatSessionDao.class);
        ownershipService = mock(SessionOwnershipService.class);
        operationRegistry = new SessionOperationRegistry();
        conversationMemoryService = mock(ConversationMemoryService.class);
        activityTracker = mock(SessionActivityTracker.class);
        service = new ChatSessionCommandService(messageDao, sessionDao, ownershipService,
                operationRegistry, conversationMemoryService, activityTracker);
    }

    @AfterEach
    void cleanTransactionSynchronization() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    void deletesMessagesBeforeOwnedSession() {
        when(ownershipService.resolve("user_a", "session_1")).thenReturn(SessionAccessState.OWNED);
        when(sessionDao.deleteByUserIdAndSessionId("user_a", "session_1")).thenReturn(1);

        service.deleteOwnedSession("user_a", "session_1");

        InOrder order = inOrder(messageDao, sessionDao);
        order.verify(messageDao).deleteBySessionId("session_1");
        order.verify(sessionDao).deleteByUserIdAndSessionId("user_a", "session_1");
        verify(conversationMemoryService).clearRuntimeMemory("session_1");
        verify(activityTracker).removeActivity("user_a", "session_1");
    }

    @Test
    void rejectsForeignOrMissingSessionAsNotFound() {
        when(ownershipService.resolve("user_a", "session_1")).thenReturn(SessionAccessState.UNAVAILABLE);

        ChatSessionCommandService.SessionCommandFailure failure = assertThrows(
                ChatSessionCommandService.SessionCommandFailure.class,
                () -> service.deleteOwnedSession("user_a", "session_1"));

        assertEquals(ChatSessionCommandService.FailureReason.NOT_FOUND, failure.getReason());
        verify(messageDao, never()).deleteBySessionId("session_1");
    }

    @Test
    void runningSessionBlocksDeletion() {
        assertTrue(operationRegistry.tryAcquireExecution("user_a", "session_1"));

        ChatSessionCommandService.SessionCommandFailure failure = assertThrows(
                ChatSessionCommandService.SessionCommandFailure.class,
                () -> service.deleteOwnedSession("user_a", "session_1"));

        assertEquals(ChatSessionCommandService.FailureReason.RUNNING, failure.getReason());
    }

    @Test
    void zeroAffectedSessionRowsFailsAndReleasesDeletionLease() {
        when(ownershipService.resolve("user_a", "session_1")).thenReturn(SessionAccessState.OWNED);
        when(sessionDao.deleteByUserIdAndSessionId("user_a", "session_1")).thenReturn(0);

        assertThrows(ChatSessionCommandService.SessionCommandFailure.class,
                () -> service.deleteOwnedSession("user_a", "session_1"));

        assertTrue(operationRegistry.tryAcquireExecution("user_a", "session_1"));
        verify(conversationMemoryService, never()).clearRuntimeMemory("session_1");
    }

    @Test
    void cleanupRunsOnlyAfterCommitAndLeaseReleasesAfterCompletion() {
        TransactionSynchronizationManager.initSynchronization();
        when(ownershipService.resolve("user_a", "session_1")).thenReturn(SessionAccessState.OWNED);
        when(sessionDao.deleteByUserIdAndSessionId("user_a", "session_1")).thenReturn(1);

        service.deleteOwnedSession("user_a", "session_1");

        verify(conversationMemoryService, never()).clearRuntimeMemory("session_1");
        assertFalse(operationRegistry.tryAcquireExecution("user_a", "session_1"));
        List<TransactionSynchronization> synchronizations = TransactionSynchronizationManager.getSynchronizations();
        synchronizations.forEach(TransactionSynchronization::afterCommit);
        verify(conversationMemoryService).clearRuntimeMemory("session_1");
        verify(activityTracker).removeActivity("user_a", "session_1");
        synchronizations.forEach(sync -> sync.afterCompletion(TransactionSynchronization.STATUS_COMMITTED));
        assertTrue(operationRegistry.tryAcquireExecution("user_a", "session_1"));
    }

    @Test
    void postCommitCleanupFailureDoesNotFailCommittedDeletion() {
        when(ownershipService.resolve("user_a", "session_1")).thenReturn(SessionAccessState.OWNED);
        when(sessionDao.deleteByUserIdAndSessionId("user_a", "session_1")).thenReturn(1);
        doThrow(new RuntimeException("cache unavailable"))
                .when(conversationMemoryService).clearRuntimeMemory("session_1");

        assertDoesNotThrow(() -> service.deleteOwnedSession("user_a", "session_1"));
        verify(activityTracker).removeActivity("user_a", "session_1");
    }
}
