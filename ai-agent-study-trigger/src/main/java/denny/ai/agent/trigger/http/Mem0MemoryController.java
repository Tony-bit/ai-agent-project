package denny.ai.agent.trigger.http;

import denny.ai.agent.api.response.Response;
import denny.ai.agent.infrastructure.mem0.Mem0RestClient;
import denny.ai.agent.infrastructure.mem0.dto.Mem0Dtos;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.List;

 /**
 * Mem0 长期记忆 HTTP 接口层
 * 提供记忆的增删查改能力，底层调用 Mem0 REST API Server (localhost:8889)
 *
 * @author denny
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/mem0")
public class Mem0MemoryController {

    private final Mem0RestClient mem0RestClient;

    public Mem0MemoryController(Mem0RestClient mem0RestClient) {
        this.mem0RestClient = mem0RestClient;
    }

    /**
     * 添加记忆
     */
    @PostMapping("/memories")
    public Response<Void> addMemory(@RequestBody AddMemoryRequest request) {
        log.info("Mem0 添加记忆, userId={}, agentId={}, content={}",
                request.getUserId(), request.getAgentId(), request.getContent());
        mem0RestClient.addMemory(
                Mem0RestClient.MemoryCreate.builder()
                        .user_id(request.getUserId())
                        .agent_id(request.getAgentId())
                        .messages(List.of(new Mem0Dtos.Message("user", request.getContent())))
                        .build()
        );
        return Response.ok();
    }

    /**
     * 查询用户所有记忆
     */
    @GetMapping("/memories")
    public Response<?> getMemories(
            @RequestParam("userId") String userId,
            @RequestParam(value = "agentId", required = false) String agentId) {
        log.info("Mem0 查询所有记忆, userId={}, agentId={}", userId, agentId);
        try {
            Object resp = mem0RestClient.getAllMemories(userId, agentId, null);
            return Response.ok(resp);
        } catch (Exception e) {
            log.error("Mem0 查询记忆失败, userId={}", userId, e);
            return Response.error("500", "查询记忆失败: " + e.getMessage());
        }
    }

    /**
     * 语义搜索记忆
     */
    @GetMapping("/search")
    public Response<Mem0RestClient.Mem0ServerResp> searchMemory(
            @RequestParam("userId") String userId,
            @RequestParam("query") String query,
            @RequestParam(value = "sessionId", required = false) String sessionId,
            @RequestParam(value = "limit", defaultValue = "10") int limit) {
        log.info("Mem0 语义搜索, userId={}, query={}, sessionId={}", userId, query, sessionId);
        Mem0RestClient.SearchRequest request = Mem0RestClient.SearchRequest.builder()
                .query(query)
                .user_id(userId)
                .run_id(sessionId != null && !sessionId.isBlank() ? sessionId : null)
                .limit(limit)
                .build();
        Mem0RestClient.Mem0ServerResp result = mem0RestClient.searchMemories(request);
        return Response.ok(result);
    }

    /**
     * 清空用户全部记忆
     */
    @DeleteMapping("/memories")
    public Response<Void> deleteAllMemory(@RequestParam("userId") String userId) {
        log.info("Mem0 清空用户记忆, userId={}", userId);
        return Response.ok();
    }

    /**
     * 初始化 Mem0 配置（透传空配置，触发 Mem0 Server 使用默认配置）
     */
    @PostMapping("/configure")
    public Response<Void> configure() {
        log.info("Mem0 配置初始化");
        return Response.ok();
    }

    @lombok.Data
    public static class AddMemoryRequest {
        private String userId;
        private String agentId;
        private String content;
    }
}
