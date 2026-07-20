package denny.ai.agent.infrastructure.service;

import denny.ai.agent.domain.service.chatmemory.ConversationMemoryService;
import denny.ai.agent.domain.service.chatsession.SessionAccessState;
import denny.ai.agent.infrastructure.dao.IChatMessageDao;
import denny.ai.agent.infrastructure.dao.IChatSessionDao;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Slf4j
@Service
public class ChatSessionCommandService {

    private final IChatMessageDao messageDao;
    private final IChatSessionDao sessionDao;
    private final SessionOwnershipService ownershipService;
    private final SessionOperationRegistry operationRegistry;
    private final ConversationMemoryService conversationMemoryService;
    private final SessionActivityTracker activityTracker;

    public ChatSessionCommandService(IChatMessageDao messageDao,
                                     IChatSessionDao sessionDao,
                                     SessionOwnershipService ownershipService,
                                     SessionOperationRegistry operationRegistry,
                                     ConversationMemoryService conversationMemoryService,
                                     SessionActivityTracker activityTracker) {
        this.messageDao = messageDao;
        this.sessionDao = sessionDao;
        this.ownershipService = ownershipService;
        this.operationRegistry = operationRegistry;
        this.conversationMemoryService = conversationMemoryService;
        this.activityTracker = activityTracker;
    }

    @Transactional(rollbackFor = Exception.class)
    public void deleteOwnedSession(String currentUserId, String sessionId) {
        ownershipService.validateSessionId(sessionId);
        if (!operationRegistry.tryAcquireDeletion(currentUserId, sessionId)) {
            throw new SessionCommandFailure(FailureReason.RUNNING, "session is running");
        }

        boolean releaseOnExit = true;
        try {
            if (TransactionSynchronizationManager.isSynchronizationActive()) {
                registerTransactionCallbacks(currentUserId, sessionId);
                releaseOnExit = false;
            }

            if (ownershipService.resolve(currentUserId, sessionId) != SessionAccessState.OWNED) {
                throw new SessionCommandFailure(FailureReason.NOT_FOUND, "session not found");
            }

            messageDao.deleteBySessionId(sessionId);
            int deletedSessions = sessionDao.deleteByUserIdAndSessionId(currentUserId, sessionId);
            if (deletedSessions != 1) {
                throw new SessionCommandFailure(FailureReason.NOT_FOUND, "session not found");
            }

            if (!TransactionSynchronizationManager.isSynchronizationActive()) {
                cleanupAfterCommit(currentUserId, sessionId);
            }
        } finally {
            if (releaseOnExit) {
                operationRegistry.releaseDeletion(currentUserId, sessionId);
            }
        }
    }

    private void registerTransactionCallbacks(String userId, String sessionId) {
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                cleanupAfterCommit(userId, sessionId);
            }

            @Override
            public void afterCompletion(int status) {
                operationRegistry.releaseDeletion(userId, sessionId);
            }
        });
    }

    private void cleanupAfterCommit(String userId, String sessionId) {
        try {
            conversationMemoryService.clearRuntimeMemory(sessionId);
        } catch (RuntimeException exception) {
            log.warn("Failed to clear runtime memory after deleting session: sessionId={}", sessionId, exception);
        }
        try {
            activityTracker.removeActivity(userId, sessionId);
        } catch (RuntimeException exception) {
            log.warn("Failed to clear session activity after deletion: sessionId={}", sessionId, exception);
        }
    }

    public enum FailureReason {
        NOT_FOUND,
        RUNNING
    }

    @Getter
    public static class SessionCommandFailure extends RuntimeException {

        private final FailureReason reason;

        public SessionCommandFailure(FailureReason reason, String message) {
            super(message);
            this.reason = reason;
        }
    }
}
