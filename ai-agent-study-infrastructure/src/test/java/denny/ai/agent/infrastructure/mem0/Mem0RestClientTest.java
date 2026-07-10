package denny.ai.agent.infrastructure.mem0;

import com.fasterxml.jackson.databind.ObjectMapper;
import denny.ai.agent.infrastructure.mem0.dto.Mem0Dtos;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;

class Mem0RestClientTest {

    private Mem0RestClient client;
    private MockRestServiceServer mockServer;

    @BeforeEach
    void setUp() {
        RestTemplate restTemplate = new RestTemplate();
        mockServer = MockRestServiceServer.createServer(restTemplate);
        client = new Mem0RestClient(restTemplate, "http://localhost:8889", new ObjectMapper());
    }

    @Test
    void addMemoryShouldPostMemoryPayload() {
        mockServer.expect(requestTo("http://localhost:8889/memories"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(content().json("""
                        {
                          "messages": [{"role": "user", "content": "你好"}],
                          "user_id": "user-001",
                          "agent_id": "agent-001",
                          "run_id": "session-001",
                          "metadata": {"source": "chat"}
                        }
                        """))
                .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));

        Mem0RestClient.MemoryCreate request = Mem0RestClient.MemoryCreate.builder()
                .messages(List.of(new Mem0Dtos.Message("user", "你好")))
                .user_id("user-001")
                .agent_id("agent-001")
                .run_id("session-001")
                .metadata(Map.of("source", "chat"))
                .build();

        assertDoesNotThrow(() -> client.addMemory(request));
        mockServer.verify();
    }

    @Test
    void searchMemoriesShouldPostSearchPayloadAndMapResults() {
        mockServer.expect(requestTo("http://localhost:8889/search"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(content().json("""
                        {
                          "query": "用户信息",
                          "user_id": "user-001",
                          "run_id": "session-001",
                          "limit": 10
                        }
                        """))
                .andRespond(withSuccess("""
                        {
                          "results": [
                            {
                              "memory": "用户喜欢喝咖啡",
                              "metadata": {"source": "chat"},
                              "score": 0.95
                            }
                          ]
                        }
                        """, MediaType.APPLICATION_JSON));

        Mem0RestClient.SearchRequest request = Mem0RestClient.SearchRequest.builder()
                .query("用户信息")
                .user_id("user-001")
                .run_id("session-001")
                .limit(10)
                .build();

        Mem0RestClient.Mem0ServerResp response = client.searchMemories(request);

        assertNotNull(response);
        assertEquals(1, response.getResults().size());
        assertEquals("用户喜欢喝咖啡", response.getResults().get(0).getMemory());
        assertEquals(Map.of("source", "chat"), response.getResults().get(0).getMetadata());
        assertEquals(0.95, response.getResults().get(0).getScore());
        mockServer.verify();
    }

    @Test
    void searchMemoriesShouldReturnEmptyListWhenResponseHasNoResults() {
        mockServer.expect(requestTo("http://localhost:8889/search"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));

        Mem0RestClient.SearchRequest request = Mem0RestClient.SearchRequest.builder()
                .query("用户信息")
                .build();

        Mem0RestClient.Mem0ServerResp response = client.searchMemories(request);

        assertNotNull(response);
        assertTrue(response.getResults().isEmpty());
        mockServer.verify();
    }

    @Test
    void getAllMemoriesShouldBuildUrlWithNonNullQueryParams() {
        mockServer.expect(requestTo("http://localhost:8889/memories?user_id=user-001&agent_id=agent-001"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("{\"memories\": []}", MediaType.APPLICATION_JSON));

        Object response = client.getAllMemories("user-001", "agent-001", null);

        assertNotNull(response);
        mockServer.verify();
    }

    /**
     * TC-Mem0-001: getPersona 正常返回画像数据
     */
    @Test
    void getPersonaShouldReturnPersonaData() {
        mockServer.expect(requestTo("http://localhost:8889/mem0/persona/user-001"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("""
                        {"data": "用户画像: 喜欢咖啡, 工作狂, 常用上海地点"}
                        """, MediaType.APPLICATION_JSON));

        String persona = client.getPersona("user-001");

        assertNotNull(persona);
        assertEquals("用户画像: 喜欢咖啡, 工作狂, 常用上海地点", persona);
        mockServer.verify();
    }

    /**
     * TC-Mem0-002: getPersona 无画像时返回 null
     */
    @Test
    void getPersonaShouldReturnNullWhenNoData() {
        mockServer.expect(requestTo("http://localhost:8889/mem0/persona/user-new"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));

        String persona = client.getPersona("user-new");

        assertEquals(null, persona);
        mockServer.verify();
    }

    /**
     * TC-Mem0-003: getPersona Mem0 服务异常时返回 null（不抛异常）
     */
    @Test
    void getPersonaShouldReturnNullOnError() {
        mockServer.expect(requestTo("http://localhost:8889/mem0/persona/user-error"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withServerError());

        String persona = client.getPersona("user-error");

        assertEquals(null, persona);
    }

    /**
     * TC-Mem0-004: getPersona 响应体无 data 字段时返回 null
     */
    @Test
    void getPersonaShouldReturnNullWhenDataFieldMissing() {
        mockServer.expect(requestTo("http://localhost:8889/mem0/persona/user-no-data"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("""
                        {"status": "ok"}
                        """, MediaType.APPLICATION_JSON));

        String persona = client.getPersona("user-no-data");

        assertEquals(null, persona);
    }
}
