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
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.junit4.SpringRunner;
import org.springframework.web.util.UriComponentsBuilder;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static org.junit.Assert.*;

/**
 * Mem0MemoryController 集成测试
 * 通过 HTTP 请求验证记忆接口是否正常工作
 * 前置条件：Mem0 Server (localhost:8888)、PostgreSQL (pgvector) 必须已启动
 *
 * @author denny
 */
@Slf4j
@RunWith(SpringRunner.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@EnableAutoConfiguration(exclude = {
        org.springframework.ai.model.chat.client.autoconfigure.ChatClientAutoConfiguration.class
})
public class Mem0MemoryControllerTest {

    @Autowired
    private TestRestTemplate restTemplate;

    @org.springframework.beans.factory.annotation.Value("${local.server.port}")
    private int serverPort;

    private static final String TEST_USER_ID = "test-user-" + UUID.randomUUID().toString().substring(0, 8);
    private static final String TEST_AGENT_ID = "test-agent-mem0";

    /**
     * 获取 Controller 请求基础地址
     */
    private String baseUrl() {
        return "http://localhost:" + serverPort;
    }

    @Test
    public void testAddAndGetMemory() {
        String sessionId = UUID.randomUUID().toString().substring(0, 8);
        String content = "这是一条测试记忆，内容ID=" + sessionId;

        // 1. 添加记忆（走 Mem0ServiceClient -> Mem0 REST API）
        Map<String, String> requestBody = new HashMap<>();
        requestBody.put("userId", TEST_USER_ID);
        requestBody.put("agentId", TEST_AGENT_ID);
        requestBody.put("content", content);

        HttpEntity<Map<String, String>> addRequest = new HttpEntity<>(requestBody);
        ResponseEntity<Response> addResponse = restTemplate.exchange(
                baseUrl() + "/api/v1/mem0/memories", HttpMethod.POST, addRequest, Response.class);

        log.info("添加记忆响应: code={}, status={}", addResponse.getBody(), addResponse.getStatusCode());
        assertEquals("添加记忆应返回200", HttpStatus.OK, addResponse.getStatusCode());
        assertEquals("响应code应为200", "200", addResponse.getBody().getCode());

        // 2. 查询该用户所有记忆（走 VectorStore 直接查询）
        String getUrl = UriComponentsBuilder
                .fromHttpUrl(baseUrl() + "/api/v1/mem0/memories")
                .queryParam("userId", TEST_USER_ID)
                .build().toUriString();

        ResponseEntity<Response> getResponse = restTemplate.getForEntity(getUrl, Response.class);

        log.info("查询记忆响应: code={}, status={}, data={}",
                getResponse.getBody(), getResponse.getStatusCode(), getResponse.getBody().getData());
        assertEquals("查询记忆应返回200", HttpStatus.OK, getResponse.getStatusCode());
        assertNotNull("查询结果data不应为null", getResponse.getBody().getData());

        log.info("testAddAndGetMemory 测试通过, userId={}, sessionId={}", TEST_USER_ID, sessionId);
    }

    @Test
    public void testGetMemories_NoData() {
        String randomUserId = "not-exist-user-" + UUID.randomUUID().toString().substring(0, 8);
        String getUrl = UriComponentsBuilder
                .fromHttpUrl(baseUrl() + "/api/v1/mem0/memories")
                .queryParam("userId", randomUserId)
                .build().toUriString();

        ResponseEntity<Response> response = restTemplate.getForEntity(getUrl, Response.class);

        log.info("查询不存在用户记忆: code={}, status={}", response.getBody(), response.getStatusCode());
        assertEquals("查询应返回200", HttpStatus.OK, response.getStatusCode());
        assertNotNull("data不应为null", response.getBody().getData());

        log.info("testGetMemories_NoData 测试通过, userId={}", randomUserId);
    }

    @Test
    public void testConfigure() {
        HttpEntity<Void> request = new HttpEntity<>(null);
        ResponseEntity<Response> response = restTemplate.exchange(
                baseUrl() + "/api/v1/mem0/configure", HttpMethod.POST, request, Response.class);

        log.info("配置初始化响应: code={}, status={}", response.getBody(), response.getStatusCode());
        assertEquals("配置初始化应返回200", HttpStatus.OK, response.getStatusCode());
        assertEquals("响应code应为200", "200", response.getBody().getCode());

        log.info("testConfigure 测试通过");
    }
}
