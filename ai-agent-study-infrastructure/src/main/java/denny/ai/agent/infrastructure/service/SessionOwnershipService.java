package denny.ai.agent.infrastructure.service;

import denny.ai.agent.domain.service.chatsession.SessionAccessState;
import denny.ai.agent.infrastructure.dao.IChatSessionDao;
import denny.ai.agent.infrastructure.dao.po.ChatSessionPO;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
public class SessionOwnershipService {

    private static final String SESSION_ID_PATTERN = "^[a-zA-Z0-9_-]{1,64}$";

    private final IChatSessionDao chatSessionDao;

    public SessionOwnershipService(IChatSessionDao chatSessionDao) {
        this.chatSessionDao = chatSessionDao;
    }

    public SessionAccessState resolve(String currentUserId, String sessionId) {
        if (!StringUtils.hasText(currentUserId)) {
            throw new IllegalArgumentException("current user is required");
        }
        validateSessionId(sessionId);

        ChatSessionPO session = chatSessionDao.queryBySessionId(sessionId);
        if (session == null) {
            return SessionAccessState.AVAILABLE;
        }
        return currentUserId.equals(session.getUserId())
                ? SessionAccessState.OWNED
                : SessionAccessState.UNAVAILABLE;
    }

    public void validateSessionId(String sessionId) {
        if (!StringUtils.hasText(sessionId) || !sessionId.matches(SESSION_ID_PATTERN)) {
            throw new IllegalArgumentException("invalid session id");
        }
    }
}
