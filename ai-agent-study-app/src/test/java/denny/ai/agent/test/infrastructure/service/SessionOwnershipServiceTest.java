package denny.ai.agent.test.infrastructure.service;

import denny.ai.agent.domain.service.chatsession.SessionAccessState;
import denny.ai.agent.infrastructure.dao.IChatSessionDao;
import denny.ai.agent.infrastructure.dao.po.ChatSessionPO;
import denny.ai.agent.infrastructure.service.SessionOwnershipService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SessionOwnershipServiceTest {

    private IChatSessionDao chatSessionDao;
    private SessionOwnershipService service;

    @BeforeEach
    void setUp() {
        chatSessionDao = mock(IChatSessionDao.class);
        service = new SessionOwnershipService(chatSessionDao);
    }

    @Test
    void returnsAvailableWhenSessionDoesNotExist() {
        when(chatSessionDao.queryBySessionId("new_session")).thenReturn(null);
        assertEquals(SessionAccessState.AVAILABLE, service.resolve("user_a", "new_session"));
    }

    @Test
    void returnsOwnedForCurrentUsersSession() {
        when(chatSessionDao.queryBySessionId("session_1")).thenReturn(session("user_a"));
        assertEquals(SessionAccessState.OWNED, service.resolve("user_a", "session_1"));
    }

    @Test
    void returnsUnavailableWithoutExposingForeignSession() {
        when(chatSessionDao.queryBySessionId("session_1")).thenReturn(session("user_b"));
        assertEquals(SessionAccessState.UNAVAILABLE, service.resolve("user_a", "session_1"));
    }

    @Test
    void rejectsBlankUserAndInvalidSessionIds() {
        assertThrows(IllegalArgumentException.class, () -> service.resolve(null, "session_1"));
        assertThrows(IllegalArgumentException.class, () -> service.resolve("user_a", null));
        assertThrows(IllegalArgumentException.class, () -> service.resolve("user_a", ""));
        assertThrows(IllegalArgumentException.class, () -> service.resolve("user_a", "bad/session"));
        assertThrows(IllegalArgumentException.class, () -> service.resolve("user_a", "s".repeat(65)));
    }

    private ChatSessionPO session(String userId) {
        ChatSessionPO session = new ChatSessionPO();
        session.setSessionId("session_1");
        session.setUserId(userId);
        return session;
    }
}
