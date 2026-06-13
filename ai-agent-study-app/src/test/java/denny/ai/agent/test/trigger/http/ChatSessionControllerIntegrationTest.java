package denny.ai.agent.test.trigger.http;

import denny.ai.agent.api.response.Response;
import lombok.extern.slf4j.Slf4j;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.junit4.SpringRunner;

import static org.junit.Assert.*;

/**
 * ChatSessionController 集成测试
 * 测试同步记忆接口的功能
 * 前置条件：Mem0 Server (localhost:8889)、PostgreSQL 必须已启动
 *
 * @author denny
 */
@Slf4j
@RunWith(SpringRunner.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@EnableAutoConfiguration(exclude = {
        org.springframework.ai.model.chat.client.autoconfigure.ChatClientAutoConfiguration.class
})
public class ChatSessionControllerIntegrationTest {

    @Autowired
    private TestRestTemplate restTemplate;

    @org.springframework.beans.factory.annotation.Value("${local.server.port}")
    private int serverPort;

    /**
     * 获取 Controller 请求基础地址
     */
    private String baseUrl() {
        return "http://localhost:" + serverPort;
    }

    /**
     * 测试正常同步记忆
     */
    @Test
    public void testSyncSessionMemory_Success() {
        String sessionId = "test-session-" + System.currentTimeMillis();
        String userId = "test-user-sync-" + System.currentTimeMillis();

        String url = baseUrl() + "/api/v1/session/" + sessionId + "/sync-memory?userId=" + userId;

        HttpEntity<Void> request = new HttpEntity<>(null);
        ResponseEntity<Response> response = restTemplate.exchange(url, HttpMethod.POST, request, Response.class);

        log.info("同步记忆响应: code={}, status={}", response.getBody(), response.getStatusCode());
        assertEquals("同步记忆应返回200", org.springframework.http.HttpStatus.OK, response.getStatusCode());
        assertEquals("响应code应为200", "200", response.getBody().getCode());

        log.info("testSyncSessionMemory_Success 测试通过, sessionId={}, userId={}", sessionId, userId);
    }

    /**
     * 测试同步不存在的会话（应正常返回，不抛异常）
     */
    @Test
    public void testSyncSessionMemory_NotExist() {
        String sessionId = "not-exist-session-" + System.currentTimeMillis();
        String userId = "test-user-sync-" + System.currentTimeMillis();

        String url = baseUrl() + "/api/v1/session/" + sessionId + "/sync-memory?userId=" + userId;

        HttpEntity<Void> request = new HttpEntity<>(null);
        ResponseEntity<Response> response = restTemplate.exchange(url, HttpMethod.POST, request, Response.class);

        log.info("同步不存在会话响应: code={}, status={}", response.getBody(), response.getStatusCode());
        assertEquals("应返回200", org.springframework.http.HttpStatus.OK, response.getStatusCode());
        assertEquals("响应code应为200", "200", response.getBody().getCode());

        log.info("testSyncSessionMemory_NotExist 测试通过, sessionId={}", sessionId);
    }
}
