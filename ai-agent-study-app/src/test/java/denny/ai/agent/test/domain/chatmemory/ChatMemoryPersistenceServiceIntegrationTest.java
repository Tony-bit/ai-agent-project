package denny.ai.agent.test.domain.chatmemory;

import denny.ai.agent.domain.adapter.repository.IChatMemoryRepository;
import denny.ai.agent.domain.model.entity.ChatMessageEntity;
import denny.ai.agent.domain.model.entity.ChatSessionEntity;
import denny.ai.agent.domain.service.chatmemory.ChatMemoryPersistenceService;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.junit.FixMethodOrder;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.MethodSorters;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.junit4.SpringRunner;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.junit.Assert.*;

/**
 * ChatMemoryPersistenceService 持久化测试用例
 * <p>
 * 测试完整的会话持久化流程：创建会话 -> 保存消息 -> 更新摘要 -> Redis缓存
 * </p>
 *
 * @author denny
 */
@Slf4j
@RunWith(SpringRunner.class)
@SpringBootTest
@FixMethodOrder(MethodSorters.JVM)
public class ChatMemoryPersistenceServiceIntegrationTest {

    @Resource
    private ChatMemoryPersistenceService chatMemoryPersistenceService;

    @Resource(name = "customChatMemoryRepository")
    private IChatMemoryRepository chatMemoryRepository;

    private static final String TEST_USER_ID = "test-user-persist";
    private static final String TEST_AGENT_ID = "test-agent-persist";
    private static final String TEST_CLIENT_ID = "test-client-persist";
    private static final String TEST_MODEL = "glm-4-flash";

    @Test
    public void test01_PersistConversation_NewSession() {
        String sessionId = "persist-test-" + UUID.randomUUID().toString().substring(0, 8);

        chatMemoryPersistenceService.persistConversation(
                sessionId,
                TEST_USER_ID,
                TEST_AGENT_ID,
                TEST_CLIENT_ID,
                "请介绍一下北京的历史",
                "北京是中国的首都，有着三千多年的历史...",
                TEST_MODEL,
                2500L,
                "trace-001"
        );

        // 验证会话创建
        ChatSessionEntity session = chatMemoryPersistenceService.getSession(sessionId);
        assertNotNull("会话不应为空", session);
        assertEquals("用户ID应匹配", TEST_USER_ID, session.getUserId());
        assertEquals("智能体ID应匹配", TEST_AGENT_ID, session.getAgentId());
        assertEquals("客户端ID应匹配", TEST_CLIENT_ID, session.getClientId());
        assertEquals("消息数应为2（一问一答）", Integer.valueOf(2), session.getMessageCount());
        assertEquals("首条问题应正确截取", "请介绍一下北京的历史", session.getFirstQuery());
        assertEquals("最后回复应正确截取", "北京是中国的首都，有着三千多年的历史...", session.getLastResponse());
        assertEquals("状态应为活跃", Integer.valueOf(ChatSessionEntity.STATUS_ACTIVE), session.getStatus());

        log.info("新会话持久化测试通过: sessionId={}", sessionId);
    }

    @Test
    public void test02_PersistConversation_ExistingSession() {
        String sessionId = "persist-test-existing-" + UUID.randomUUID().toString().substring(0, 8);

        // 第一轮对话
        chatMemoryPersistenceService.persistConversation(
                sessionId, TEST_USER_ID, TEST_AGENT_ID, TEST_CLIENT_ID,
                "第一轮问题", "第一轮回复",
                TEST_MODEL, 1000L, "trace-101"
        );

        // 第二轮对话
        chatMemoryPersistenceService.persistConversation(
                sessionId, TEST_USER_ID, TEST_AGENT_ID, TEST_CLIENT_ID,
                "第二轮问题", "第二轮回复内容",
                TEST_MODEL, 1500L, "trace-102"
        );

        // 验证会话累计
        ChatSessionEntity session = chatMemoryPersistenceService.getSession(sessionId);
        assertNotNull("会话不应为空", session);
        assertEquals("消息数应累计为4", Integer.valueOf(4), session.getMessageCount());
        assertEquals("最后回复应更新为第二轮", "第二轮回复内容", session.getLastResponse());
        assertEquals("首条问题保持不变", "第一轮问题", session.getFirstQuery());

        log.info("已有会话追加消息测试通过: sessionId={}, messageCount={}", sessionId, session.getMessageCount());
    }

    @Test
    public void test03_GetConversationHistory_FromRedis() {
        String sessionId = "history-redis-" + UUID.randomUUID().toString().substring(0, 8);

        // 先创建会话和消息
        chatMemoryPersistenceService.persistConversation(
                sessionId, TEST_USER_ID, TEST_AGENT_ID, TEST_CLIENT_ID,
                "Redis缓存测试问题", "Redis缓存测试回复",
                TEST_MODEL, 800L, "trace-redis-001"
        );

        // 从Redis获取（首次会回填，后续直接从Redis读取）
        List<ChatMessageEntity> history = chatMemoryPersistenceService.getConversationHistory(sessionId);

        assertNotNull("历史消息列表不应为空", history);
        assertEquals("应包含2条消息", 2, history.size());

        ChatMessageEntity userMsg = history.get(0);
        assertEquals("第一条消息应为user角色", "user", userMsg.getRole());
        assertEquals("user消息内容应匹配", "Redis缓存测试问题", userMsg.getContent());

        ChatMessageEntity assistantMsg = history.get(1);
        assertEquals("第二条消息应为assistant角色", "assistant", assistantMsg.getRole());
        assertEquals("assistant消息内容应匹配", "Redis缓存测试回复", assistantMsg.getContent());

        log.info("从Redis获取会话历史测试通过: sessionId={}, count={}", sessionId, history.size());
    }

    @Test
    public void test04_GetConversationHistory_FallbackToMySQL() {
        String sessionId = "history-mysql-" + UUID.randomUUID().toString().substring(0, 8);

        // 直接通过仓储写入MySQL（不走Redis缓存）
        ChatSessionEntity session = ChatSessionEntity.builder()
                .sessionId(sessionId)
                .userId(TEST_USER_ID)
                .agentId(TEST_AGENT_ID)
                .clientId(TEST_CLIENT_ID)
                .messageCount(2)
                .firstQuery("MySQL直接写入的问题")
                .lastResponse("MySQL直接写入的回复")
                .status(ChatSessionEntity.STATUS_ACTIVE)
                .createTime(LocalDateTime.now())
                .build();
        chatMemoryRepository.saveSession(session);

        ChatMessageEntity msg1 = ChatMessageEntity.builder()
                .sessionId(sessionId)
                .messageIndex(1)
                .role("user")
                .content("MySQL直接写入的问题")
                .model(TEST_MODEL)
                .latencyMs(1000L)
                .traceId("mysql-trace-1")
                .createTime(LocalDateTime.now())
                .build();
        ChatMessageEntity msg2 = ChatMessageEntity.builder()
                .sessionId(sessionId)
                .messageIndex(2)
                .role("assistant")
                .content("MySQL直接写入的回复")
                .model(TEST_MODEL)
                .latencyMs(1500L)
                .traceId("mysql-trace-1")
                .createTime(LocalDateTime.now())
                .build();
        chatMemoryRepository.saveMessage(msg1);
        chatMemoryRepository.saveMessage(msg2);

        // 清理Redis缓存，强制从MySQL读取
        chatMemoryRepository.deleteRedisCache(sessionId);

        // 验证从MySQL读取并回填Redis
        List<ChatMessageEntity> history = chatMemoryPersistenceService.getConversationHistory(sessionId);

        assertNotNull("从MySQL读取的历史消息不应为空", history);
        assertEquals("应包含2条消息", 2, history.size());
        assertEquals("第一条消息角色应为user", "user", history.get(0).getRole());
        assertEquals("第二条消息角色应为assistant", "assistant", history.get(1).getRole());

        // 验证已回填Redis
        List<ChatMessageEntity> cached = chatMemoryRepository.getCachedMessagesFromRedis(sessionId);
        assertFalse("Redis缓存应已回填", cached.isEmpty());
        assertEquals("Redis缓存条数应一致", 2, cached.size());

        log.info("MySQL Fallback测试通过: sessionId={}, count={}", sessionId, history.size());
    }

    @Test
    public void test05_ClearCache() {
        String sessionId = "clear-cache-" + UUID.randomUUID().toString().substring(0, 8);

        // 创建会话和消息
        chatMemoryPersistenceService.persistConversation(
                sessionId, TEST_USER_ID, TEST_AGENT_ID, TEST_CLIENT_ID,
                "清理缓存测试", "清理缓存回复",
                TEST_MODEL, 600L, "trace-clear-001"
        );

        // 验证Redis有缓存
        List<ChatMessageEntity> beforeClear = chatMemoryRepository.getCachedMessagesFromRedis(sessionId);
        assertFalse("清理前Redis应有缓存", beforeClear.isEmpty());

        // 清理缓存
        chatMemoryPersistenceService.clearCache(sessionId);

        // 验证Redis缓存已清空
        List<ChatMessageEntity> afterClear = chatMemoryRepository.getCachedMessagesFromRedis(sessionId);
        assertTrue("清理后Redis缓存应为空", afterClear.isEmpty());

        // 验证会话信息仍然存在（只清缓存，不删数据）
        ChatSessionEntity session = chatMemoryPersistenceService.getSession(sessionId);
        assertNotNull("清理缓存后会话信息仍应存在", session);
        assertEquals("会话ID应匹配", sessionId, session.getSessionId());

        log.info("清理缓存测试通过: sessionId={}", sessionId);
    }

    @Test
    public void test06_GetNotExistSession() {
        String sessionId = "not-exist-session-" + UUID.randomUUID().toString().substring(0, 8);

        ChatSessionEntity session = chatMemoryPersistenceService.getSession(sessionId);
        assertNull("不存在的会话应返回null", session);

        List<ChatMessageEntity> history = chatMemoryPersistenceService.getConversationHistory(sessionId);
        assertNotNull("不存在的会话历史查询应返回非null", history);
        assertTrue("不存在的会话历史应为空", history.isEmpty());

        log.info("不存在会话查询测试通过");
    }

    @Test
    public void test07_TruncateLongContent() {
        String sessionId = "long-content-" + UUID.randomUUID().toString().substring(0, 8);
        StringBuilder longQuery = new StringBuilder();
        StringBuilder longResponse = new StringBuilder();
        for (int i = 0; i < 500; i++) {
            longQuery.append("问题内容段").append(i).append(" ");
            longResponse.append("回复内容段").append(i).append(" ");
        }

        chatMemoryPersistenceService.persistConversation(
                sessionId, TEST_USER_ID, TEST_AGENT_ID, TEST_CLIENT_ID,
                longQuery.toString(), longResponse.toString(),
                TEST_MODEL, 3000L, "trace-long-001"
        );

        ChatSessionEntity session = chatMemoryPersistenceService.getSession(sessionId);
        assertNotNull("会话不应为空", session);
        // 摘要字段最大500字符，超长内容应被截断
        assertTrue("首条问题摘要不应超过500字符",
                session.getFirstQuery() == null || session.getFirstQuery().length() <= 500);
        assertTrue("最后回复摘要不应超过500字符",
                session.getLastResponse() == null || session.getLastResponse().length() <= 500);

        // 完整内容仍应正确存储在消息表中
        List<ChatMessageEntity> history = chatMemoryPersistenceService.getConversationHistory(sessionId);
        assertEquals("应包含2条消息", 2, history.size());
        assertEquals("用户消息内容长度应一致",
                longQuery.toString().length(), history.get(0).getContent().length());
        assertEquals("助手消息内容长度应一致",
                longResponse.toString().length(), history.get(1).getContent().length());

        log.info("长内容截断测试通过: query长度={}, response长度={}",
                history.get(0).getContent().length(), history.get(1).getContent().length());
    }
}
