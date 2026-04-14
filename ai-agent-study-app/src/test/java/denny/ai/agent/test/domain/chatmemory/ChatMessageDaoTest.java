package denny.ai.agent.test.domain.chatmemory;

import denny.ai.agent.infrastructure.dao.IChatMessageDao;
import denny.ai.agent.infrastructure.dao.po.ChatMessagePO;
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
 * ChatMessageDao 增删查改 测试用例
 *
 * @author denny
 */
@Slf4j
@RunWith(SpringRunner.class)
@SpringBootTest
public class ChatMessageDaoTest {

    @Resource
    private IChatMessageDao chatMessageDao;

    private static final String TEST_SESSION_ID = "msg-test-session-" + UUID.randomUUID().toString().substring(0, 8);

    @Test
    public void test01_InsertUserMessage() {
        ChatMessagePO po = new ChatMessagePO();
        po.setSessionId(TEST_SESSION_ID);
        po.setMessageIndex(1);
        po.setRole("user");
        po.setContent("请帮我写一段关于春天的诗");
        po.setModel("glm-4-flash");
        po.setLatencyMs(1500L);
        po.setTraceId(UUID.randomUUID().toString().substring(0, 16));
        po.setCreateTime(LocalDateTime.now());

        chatMessageDao.insert(po);

        assertNotNull("插入后自增ID不应为空", po.getId());
        assertTrue("插入后自增ID应大于0", po.getId() > 0);
        log.info("插入用户消息成功: id={}, sessionId={}", po.getId(), TEST_SESSION_ID);
    }

    @Test
    public void test02_InsertAssistantMessage() {
        ChatMessagePO po = new ChatMessagePO();
        po.setSessionId(TEST_SESSION_ID);
        po.setMessageIndex(2);
        po.setRole("assistant");
        po.setContent("春风又绿江南岸，明月何时照我还。——王安石《泊船瓜洲》");
        po.setModel("glm-4-flash");
        po.setLatencyMs(2000L);
        po.setTraceId(UUID.randomUUID().toString().substring(0, 16));
        po.setCreateTime(LocalDateTime.now());

        chatMessageDao.insert(po);

        assertNotNull("插入后自增ID不应为空", po.getId());
        assertTrue("插入后自增ID应大于0", po.getId() > 0);
        assertEquals("角色应为assistant", "assistant", po.getRole());
        log.info("插入助手消息成功: id={}, sessionId={}", po.getId(), TEST_SESSION_ID);
    }

    @Test
    public void test03_QueryBySessionId_OrderByIndex() {
        // 先插入多轮对话
        String sessionId = "multi-turn-" + UUID.randomUUID().toString().substring(0, 8);

        for (int i = 1; i <= 6; i++) {
            ChatMessagePO po = new ChatMessagePO();
            po.setSessionId(sessionId);
            po.setMessageIndex(i);
            po.setRole(i % 2 == 1 ? "user" : "assistant");
            po.setContent("第" + i + "轮对话内容，role=" + (i % 2 == 1 ? "user" : "assistant"));
            po.setModel("glm-4-flash");
            po.setLatencyMs(1000L + i * 100L);
            po.setTraceId("trace-" + i);
            po.setCreateTime(LocalDateTime.now());
            chatMessageDao.insert(po);
        }

        // 查询验证顺序
        List<ChatMessagePO> messages = chatMessageDao.queryBySessionId(sessionId);

        assertNotNull("消息列表不应为空", messages);
        assertEquals("应包含6条消息", 6, messages.size());

        for (int i = 0; i < messages.size(); i++) {
            Integer index = i + 1;
            assertEquals(index, messages.get(i).getMessageIndex());
        }
        log.info("多轮对话查询测试通过，共{}条消息", messages.size());
    }

    @Test
    public void test04_QueryNotExistSession() {
        List<ChatMessagePO> messages = chatMessageDao.queryBySessionId("not-exist-session-xxx");
        assertNotNull("查询结果不应为null", messages);
        assertTrue("不存在的会话应返回空列表", messages.isEmpty());
        log.info("查询不存在的会话消息列表测试通过");
    }

    @Test
    public void test05_InsertLongContent() {
        StringBuilder longContent = new StringBuilder();
        for (int i = 0; i < 100; i++) {
            longContent.append("这是一段很长的消息内容，用于测试长文本存储是否正常。第" + i + "段。");
        }

        ChatMessagePO po = new ChatMessagePO();
        po.setSessionId(TEST_SESSION_ID);
        po.setMessageIndex(10);
        po.setRole("assistant");
        po.setContent(longContent.toString());
        po.setModel("glm-4-flash");
        po.setLatencyMs(5000L);
        po.setTraceId("long-content-trace");
        po.setCreateTime(LocalDateTime.now());

        chatMessageDao.insert(po);

        // 查询验证长文本是否完整存储
        List<ChatMessagePO> messages = chatMessageDao.queryBySessionId(TEST_SESSION_ID);
        ChatMessagePO saved = messages.stream()
                .filter(m -> m.getMessageIndex() == 10)
                .findFirst()
                .orElse(null);

        assertNotNull("应找到第10条消息", saved);
        assertEquals("长文本内容应完整存储", longContent.toString(), saved.getContent());
        assertEquals("内容长度应一致", longContent.length(), saved.getContent().length());
        log.info("长文本存储测试通过，内容长度={}", saved.getContent().length());
    }

    @Test
    public void test06_InsertMessageWithNullFields() {
        ChatMessagePO po = new ChatMessagePO();
        po.setSessionId(TEST_SESSION_ID);
        po.setMessageIndex(20);
        po.setRole("user");
        po.setContent("普通文本消息");
        // model、latencyMs、traceId 均为 null
        po.setCreateTime(LocalDateTime.now());

        chatMessageDao.insert(po);

        List<ChatMessagePO> messages = chatMessageDao.queryBySessionId(TEST_SESSION_ID);
        ChatMessagePO saved = messages.stream()
                .filter(m -> m.getMessageIndex() == 20)
                .findFirst()
                .orElse(null);

        assertNotNull("应找到第20条消息", saved);
        assertNull("model字段允许为null", saved.getModel());
        assertNull("latencyMs字段允许为null", saved.getLatencyMs());
        assertNull("traceId字段允许为null", saved.getTraceId());
        log.info("允许null字段的插入测试通过");
    }
}
