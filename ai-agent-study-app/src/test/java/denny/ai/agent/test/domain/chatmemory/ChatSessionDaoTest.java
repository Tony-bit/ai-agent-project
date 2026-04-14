package denny.ai.agent.test.domain.chatmemory;

import denny.ai.agent.infrastructure.dao.IChatSessionDao;
import denny.ai.agent.infrastructure.dao.IChatMessageDao;
import denny.ai.agent.infrastructure.dao.po.ChatSessionPO;
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
 * ChatSessionDao 增删查改 测试用例
 *
 * @author denny
 */
@Slf4j
@RunWith(SpringRunner.class)
@SpringBootTest
@FixMethodOrder(MethodSorters.JVM)
public class ChatSessionDaoTest {

    @Resource
    private IChatSessionDao chatSessionDao;

    @Resource
    private IChatMessageDao chatMessageDao;

    private static final String TEST_SESSION_ID = "test-session-" + UUID.randomUUID().toString().substring(0, 8);
    private static final String TEST_USER_ID = "test-user-001";
    private static final String TEST_AGENT_ID = "test-agent-001";
    private static final String TEST_CLIENT_ID = "test-client-001";

    @Test
    public void test01_InsertSession() {
        ChatSessionPO po = new ChatSessionPO();
        po.setSessionId(TEST_SESSION_ID);
        po.setUserId(TEST_USER_ID);
        po.setAgentId(TEST_AGENT_ID);
        po.setClientId(TEST_CLIENT_ID);
        po.setMessageCount(0);
        po.setFirstQuery("你好，请帮我查询天气");
        po.setLastResponse("");
        po.setStatus(1);
        po.setCreateTime(LocalDateTime.now());
        po.setUpdateTime(LocalDateTime.now());

        chatSessionDao.insert(po);

        assertNotNull("插入后自增ID不应为空", po.getId());
        assertTrue("插入后自增ID应大于0", po.getId() > 0);
        log.info("插入会话成功: id={}, sessionId={}", po.getId(), TEST_SESSION_ID);
    }

    @Test
    public void test02_QueryBySessionId() {
        ChatSessionPO po = chatSessionDao.queryBySessionId(TEST_SESSION_ID);

        assertNotNull("查询结果不应为空", po);
        assertEquals("会话ID应匹配", TEST_SESSION_ID, po.getSessionId());
        assertEquals("用户ID应匹配", TEST_USER_ID, po.getUserId());
        assertEquals("智能体ID应匹配", TEST_AGENT_ID, po.getAgentId());
        assertEquals("客户端ID应匹配", TEST_CLIENT_ID, po.getClientId());
        assertEquals("消息数初始应为0", Integer.valueOf(0), po.getMessageCount());
        assertEquals("首条问题应匹配", "你好，请帮我查询天气", po.getFirstQuery());
        assertEquals("状态应为活跃", Integer.valueOf(1), po.getStatus());
        log.info("查询会话成功: {}", po);
    }

    @Test
    public void test03_QueryNotExist() {
        ChatSessionPO po = chatSessionDao.queryBySessionId("not-exist-session-id-xxx");
        assertNull("不存在的会话应返回null", po);
        log.info("查询不存在的会话测试通过");
    }

    @Test
    public void test04_UpdateLastResponse() {
        String newResponse = "北京今天晴转多云，气温15-25度，适宜出行";
        chatSessionDao.updateLastResponse(TEST_SESSION_ID, newResponse, 2);

        ChatSessionPO po = chatSessionDao.queryBySessionId(TEST_SESSION_ID);

        assertNotNull("更新后查询结果不应为空", po);
        assertEquals("最后回复应更新", newResponse, po.getLastResponse());
        assertEquals("消息数应增加2", Integer.valueOf(2), po.getMessageCount());
        log.info("更新会话回复成功: lastResponse={}, messageCount={}", po.getLastResponse(), po.getMessageCount());
    }

    @Test
    public void test05_UpdateLastResponse_MultipleTimes() {
        String response1 = "第一次回复";
        String response2 = "第二次回复内容更长了";
        String response3 = "这是第三次对话的回复内容";

        chatSessionDao.updateLastResponse(TEST_SESSION_ID, response1, 2);
        chatSessionDao.updateLastResponse(TEST_SESSION_ID, response2, 2);
        chatSessionDao.updateLastResponse(TEST_SESSION_ID, response3, 2);

        ChatSessionPO po = chatSessionDao.queryBySessionId(TEST_SESSION_ID);

        assertNotNull("多次更新后查询结果不应为空", po);
        assertEquals("最后回复应为最新值", response3, po.getLastResponse());
        assertEquals("消息数应累计为8", Integer.valueOf(8), po.getMessageCount());
        log.info("多次更新会话回复成功: messageCount={}", po.getMessageCount());
    }

    @Test
    public void test06_BatchInsertAndQuery() {
        for (int i = 1; i <= 5; i++) {
            String sessionId = "batch-test-session-" + UUID.randomUUID().toString().substring(0, 8);
            ChatSessionPO po = new ChatSessionPO();
            po.setSessionId(sessionId);
            po.setUserId(TEST_USER_ID);
            po.setAgentId(TEST_AGENT_ID);
            po.setClientId(TEST_CLIENT_ID);
            po.setMessageCount(i * 3);
            po.setFirstQuery("第" + i + "条测试问题");
            po.setLastResponse("第" + i + "条测试回复");
            po.setStatus(1);
            po.setCreateTime(LocalDateTime.now());
            po.setUpdateTime(LocalDateTime.now());
            chatSessionDao.insert(po);
        }
        log.info("批量插入5条会话测试通过");
    }
}
