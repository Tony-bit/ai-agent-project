package denny.ai.agent.test.infrastructure.service;

import denny.ai.agent.api.vo.ChatMessageVO;
import denny.ai.agent.api.vo.ChatSessionVO;
import denny.ai.agent.api.vo.MessageListResult;
import denny.ai.agent.api.vo.SessionListResult;
import denny.ai.agent.infrastructure.dao.IChatMessageDao;
import denny.ai.agent.infrastructure.dao.IChatSessionDao;
import denny.ai.agent.infrastructure.dao.po.ChatMessagePO;
import denny.ai.agent.infrastructure.dao.po.ChatSessionPO;
import denny.ai.agent.infrastructure.service.ChatSessionQueryService;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * ChatSessionQueryService 边界校验和业务逻辑测试
 *
 * @author denny
 */
@RunWith(MockitoJUnitRunner.class)
public class ChatSessionQueryServiceTest {

    @Mock
    private IChatSessionDao chatSessionDao;

    @Mock
    private IChatMessageDao chatMessageDao;

    @InjectMocks
    private ChatSessionQueryService chatSessionQueryService;

    private static final String TEST_USER_ID = "test-user-001";
    private static final String TEST_SESSION_ID = "test-session-001";

    // ========== getSessionList 边界校验测试 ==========

    @Test
    public void testGetSessionList_UserIdIsNull() {
        SessionListResult result = chatSessionQueryService.getSessionList(null, null, null);
        assertNotNull("结果不应为空", result);
        assertFalse("不应有更多数据", result.isHasMore());
        assertEquals("应返回空列表", 0, result.getSize());
    }

    @Test
    public void testGetSessionList_UserIdIsEmpty() {
        SessionListResult result = chatSessionQueryService.getSessionList("", null, null);
        assertNotNull("结果不应为空", result);
        assertFalse("不应有更多数据", result.isHasMore());
        assertEquals("应返回空列表", 0, result.getSize());
    }

    @Test
    public void testGetSessionList_UserIdTooLong() {
        String longUserId = "a".repeat(65);
        SessionListResult result = chatSessionQueryService.getSessionList(longUserId, null, null);
        assertNotNull("结果不应为空", result);
        assertFalse("不应有更多数据", result.isHasMore());
        assertEquals("应返回空列表", 0, result.getSize());
    }

    @Test
    public void testGetSessionList_CursorTimeFormatError() {
        SessionListResult result = chatSessionQueryService.getSessionList(TEST_USER_ID, "invalid-time", "1");
        assertNotNull("结果不应为空", result);
        assertFalse("不应有更多数据", result.isHasMore());
        assertEquals("应返回空列表", 0, result.getSize());
    }

    @Test
    public void testGetSessionList_CursorIdFormatError() {
        SessionListResult result = chatSessionQueryService.getSessionList(TEST_USER_ID, "2026-04-17 10:00:00", "not-a-number");
        assertNotNull("结果不应为空", result);
        assertFalse("不应有更多数据", result.isHasMore());
        assertEquals("应返回空列表", 0, result.getSize());
    }

    @Test
    public void testGetSessionList_HasTimeButNoId() {
        SessionListResult result = chatSessionQueryService.getSessionList(TEST_USER_ID, "2026-04-17 10:00:00", null);
        assertNotNull("结果不应为空", result);
        assertFalse("不应有更多数据", result.isHasMore());
        assertEquals("应返回空列表", 0, result.getSize());
    }

    @Test
    public void testGetSessionList_HasIdButNoTime() {
        SessionListResult result = chatSessionQueryService.getSessionList(TEST_USER_ID, null, "1");
        assertNotNull("结果不应为空", result);
        assertFalse("不应有更多数据", result.isHasMore());
        assertEquals("应返回空列表", 0, result.getSize());
    }

    // ========== getSessionList 业务逻辑测试 ==========

    @Test
    public void testGetSessionList_FirstPage_Success() {
        List<ChatSessionPO> mockSessions = createMockSessionList(10);
        when(chatSessionDao.queryFirstPage(eq(TEST_USER_ID), eq(11))).thenReturn(mockSessions);

        SessionListResult result = chatSessionQueryService.getSessionList(TEST_USER_ID, null, null);

        assertNotNull("结果不应为空", result);
        assertEquals("应返回10条数据", 10, result.getSize());
        assertFalse("首次查询不应有更多数据", result.isHasMore());
        assertNull("首次查询游标时间应为空", result.getNextCursorTime());
        assertNull("首次查询游标ID应为空", result.getNextCursorId());

        List<ChatSessionVO> sessions = result.getSessions();
        assertEquals("第一条会话的sessionId应匹配", "session-0", sessions.get(0).getSessionId());
        assertEquals("最后一条会话的sessionId应匹配", "session-9", sessions.get(9).getSessionId());
    }

    @Test
    public void testGetSessionList_FirstPage_HasMore() {
        // 模拟返回11条数据，说明还有更多
        List<ChatSessionPO> mockSessions = createMockSessionList(11);
        when(chatSessionDao.queryFirstPage(eq(TEST_USER_ID), eq(11))).thenReturn(mockSessions);

        SessionListResult result = chatSessionQueryService.getSessionList(TEST_USER_ID, null, null);

        assertNotNull("结果不应为空", result);
        assertEquals("应返回10条数据（只取前10条）", 10, result.getSize());
        assertTrue("应有更多数据", result.isHasMore());
        assertNotNull("游标时间不应为空", result.getNextCursorTime());
        assertNotNull("游标ID不应为空", result.getNextCursorId());
    }

    @Test
    public void testGetSessionList_CursorPage_Success() {
        List<ChatSessionPO> mockSessions = createMockSessionList(10);
        when(chatSessionDao.queryByCursor(eq(TEST_USER_ID), any(LocalDateTime.class), anyLong(), eq(11)))
                .thenReturn(mockSessions);

        SessionListResult result = chatSessionQueryService.getSessionList(
                TEST_USER_ID, "2026-04-17 10:00:00", "100");

        assertNotNull("结果不应为空", result);
        assertEquals("应返回10条数据", 10, result.getSize());
        verify(chatSessionDao).queryByCursor(eq(TEST_USER_ID), any(LocalDateTime.class), eq(100L), eq(11));
    }

    // ========== getSessionMessages 边界校验测试 ==========

    @Test
    public void testGetSessionMessages_SessionIdIsNull() {
        MessageListResult result = chatSessionQueryService.getSessionMessages(null, null);
        assertNotNull("结果不应为空", result);
        assertFalse("不应有更多数据", result.isHasMore());
        assertEquals("应返回空列表", 0, result.getSize());
    }

    @Test
    public void testGetSessionMessages_SessionIdIsEmpty() {
        MessageListResult result = chatSessionQueryService.getSessionMessages("", null);
        assertNotNull("结果不应为空", result);
        assertFalse("不应有更多数据", result.isHasMore());
        assertEquals("应返回空列表", 0, result.getSize());
    }

    @Test
    public void testGetSessionMessages_SessionIdTooLong() {
        String longSessionId = "s".repeat(65);
        MessageListResult result = chatSessionQueryService.getSessionMessages(longSessionId, null);
        assertNotNull("结果不应为空", result);
        assertFalse("不应有更多数据", result.isHasMore());
        assertEquals("应返回空列表", 0, result.getSize());
    }

    @Test
    public void testGetSessionMessages_SessionIdFormatError() {
        MessageListResult result = chatSessionQueryService.getSessionMessages("session@#$%", null);
        assertNotNull("结果不应为空", result);
        assertFalse("不应有更多数据", result.isHasMore());
        assertEquals("应返回空列表", 0, result.getSize());
    }

    @Test
    public void testGetSessionMessages_CursorIndexInvalid() {
        MessageListResult result = chatSessionQueryService.getSessionMessages(TEST_SESSION_ID, 0);
        assertNotNull("结果不应为空", result);
        assertFalse("不应有更多数据", result.isHasMore());
        assertEquals("应返回空列表", 0, result.getSize());
    }

    @Test
    public void testGetSessionMessages_CursorIndexNegative() {
        MessageListResult result = chatSessionQueryService.getSessionMessages(TEST_SESSION_ID, -1);
        assertNotNull("结果不应为空", result);
        assertFalse("不应有更多数据", result.isHasMore());
        assertEquals("应返回空列表", 0, result.getSize());
    }

    // ========== getSessionMessages 业务逻辑测试 ==========

    @Test
    public void testGetSessionMessages_FirstPage_Success() {
        List<ChatMessagePO> mockMessages = createMockMessageList(10);
        when(chatMessageDao.queryLatestMessages(eq(TEST_SESSION_ID), eq(11))).thenReturn(mockMessages);

        MessageListResult result = chatSessionQueryService.getSessionMessages(TEST_SESSION_ID, null);

        assertNotNull("结果不应为空", result);
        assertEquals("应返回10条数据", 10, result.getSize());
        assertFalse("首次查询不应有更多数据", result.isHasMore());
        assertNull("首次查询游标索引应为空", result.getNextCursorIndex());

        // 验证消息是否按时间正序排列
        List<ChatMessageVO> messages = result.getMessages();
        assertEquals("第一条消息索引应为1", Integer.valueOf(1), messages.get(0).getMessageIndex());
        assertEquals("最后一条消息索引应为10", Integer.valueOf(10), messages.get(9).getMessageIndex());
        assertEquals("第一条消息角色应为user", "user", messages.get(0).getRole());
        assertEquals("第二条消息角色应为assistant", "assistant", messages.get(1).getRole());
    }

    @Test
    public void testGetSessionMessages_FirstPage_HasMore() {
        // 模拟返回11条数据，说明还有更多
        List<ChatMessagePO> mockMessages = createMockMessageList(11);
        when(chatMessageDao.queryLatestMessages(eq(TEST_SESSION_ID), eq(11))).thenReturn(mockMessages);

        MessageListResult result = chatSessionQueryService.getSessionMessages(TEST_SESSION_ID, null);

        assertNotNull("结果不应为空", result);
        assertEquals("应返回10条数据（只取前10条）", 10, result.getSize());
        assertTrue("应有更多数据", result.isHasMore());
        assertNotNull("游标索引不应为空", result.getNextCursorIndex());
    }

    @Test
    public void testGetSessionMessages_CursorPage_Success() {
        List<ChatMessagePO> mockMessages = createMockMessageList(10);
        when(chatMessageDao.queryByCursor(eq(TEST_SESSION_ID), eq(10), eq(11))).thenReturn(mockMessages);

        MessageListResult result = chatSessionQueryService.getSessionMessages(TEST_SESSION_ID, 10);

        assertNotNull("结果不应为空", result);
        assertEquals("应返回10条数据", 10, result.getSize());
        verify(chatMessageDao).queryByCursor(eq(TEST_SESSION_ID), eq(10), eq(11));
    }

    @Test
    public void testGetSessionMessages_EmptyResult() {
        when(chatMessageDao.queryLatestMessages(eq(TEST_SESSION_ID), eq(11))).thenReturn(new ArrayList<>());

        MessageListResult result = chatSessionQueryService.getSessionMessages(TEST_SESSION_ID, null);

        assertNotNull("结果不应为空", result);
        assertEquals("应返回0条数据", 0, result.getSize());
        assertFalse("不应有更多数据", result.isHasMore());
    }

    // ========== 辅助方法 ==========

    private List<ChatSessionPO> createMockSessionList(int count) {
        List<ChatSessionPO> sessions = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            ChatSessionPO po = new ChatSessionPO();
            po.setId((long) (100 + i));
            po.setSessionId("session-" + i);
            po.setUserId(TEST_USER_ID);
            po.setAgentId("agent-001");
            po.setClientId("client-001");
            po.setMessageCount(i * 2);
            po.setFirstQuery("第" + i + "条问题");
            po.setLastResponse("第" + i + "条回复");
            po.setStatus(1);
            po.setCreateTime(LocalDateTime.now().minusHours(i));
            po.setUpdateTime(LocalDateTime.now().minusHours(i));
            sessions.add(po);
        }
        return sessions;
    }

    private List<ChatMessagePO> createMockMessageList(int count) {
        List<ChatMessagePO> messages = new ArrayList<>();
        // SQL 使用 ORDER BY message_index DESC，所以返回的是倒序数据
        // 即 messageIndex 从大到小：10, 9, 8, 7, 6, 5, 4, 3, 2, 1
        // Service 层会反转成顺序：1, 2, 3, 4, 5, 6, 7, 8, 9, 10
        for (int i = count; i >= 1; i--) {
            ChatMessagePO po = new ChatMessagePO();
            po.setId((long) i);
            po.setSessionId(TEST_SESSION_ID);
            po.setMessageIndex(i);
            po.setRole(i % 2 == 1 ? "user" : "assistant");
            po.setContent("消息内容 " + i);
            po.setModel("gpt-4");
            po.setLatencyMs(1000L + i);
            po.setTraceId("trace-" + i);
            po.setCreateTime(LocalDateTime.now().minusMinutes(i - 1));
            messages.add(po);
        }
        return messages;
    }
}
