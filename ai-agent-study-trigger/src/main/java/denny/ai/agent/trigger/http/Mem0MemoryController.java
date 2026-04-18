package denny.ai.agent.trigger.http;

import com.alibaba.cloud.ai.memory.mem0.core.Mem0ServiceClient;
import com.alibaba.cloud.ai.memory.mem0.model.Mem0ServerRequest;
import denny.ai.agent.api.response.Response;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * Mem0 长期记忆 HTTP 接口层
 * 提供记忆的增删查改能力，底层调用 Mem0 REST API Server (localhost:8888)
 *
 * @author denny
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/mem0")
@ConditionalOnBean(Mem0ServiceClient.class)
public class Mem0MemoryController {

    private final Mem0ServiceClient mem0ServiceClient;

    public Mem0MemoryController(Mem0ServiceClient mem0ServiceClient) {
        this.mem0ServiceClient = mem0ServiceClient;
    }

    /**
     * 初始化 ChatClient（延迟注入，避免循环依赖）
     */
//    @org.springframework.context.annotation.Lazy
//    @org.springframework.beans.factory.annotation.Autowired
//    public void setChatClient(ChatClient.Builder chatClientBuilder) {
//        this.chatClient = chatClientBuilder
//                .defaultAdvisors(Mem0ChatMemoryAdvisor.builder(vectorStore).build())
//                .build();
//    }

    /**
     * 添加记忆
     */
    @PostMapping("/memories")
    public Response<Void> addMemory(@RequestBody AddMemoryRequest request) {
        log.info("Mem0 添加记忆, userId={}, agentId={}, content={}",
                request.getUserId(), request.getAgentId(), request.getContent());
        mem0ServiceClient.addMemory(
                Mem0ServerRequest.MemoryCreate.builder()
                        .userId(request.getUserId())
                        .agentId(request.getAgentId())
                        .messages(List.of(new Mem0ServerRequest.Message("user", request.getContent())))
                        .build()
        );
        return Response.ok();
    }

    /**
     * 查询用户所有记忆
     */
    @GetMapping("/memories")
    public Response<?> getMemories(
            @RequestParam String userId,
            @RequestParam(required = false) String agentId) {
        log.info("Mem0 查询所有记忆, userId={}, agentId={}", userId, agentId);
        try {
            Object resp = mem0ServiceClient.getAllMemories(userId, agentId, null);
            return Response.ok(resp);
        } catch (Exception e) {
            log.error("Mem0 查询记忆失败, userId={}", userId, e);
            return Response.error("500", "查询记忆失败: " + e.getMessage());
        }
    }

    /**
     * 语义搜索记忆
     */
//    @GetMapping("/search")
//    public Response<List<Document>> searchMemory(
//            @RequestParam String userId,
//            @RequestParam String query,
//            @RequestParam(defaultValue = "10") int limit) {
//        log.info("Mem0 语义搜索, userId={}, query={}", userId, query);
//        Mem0ServerRequest.SearchRequest request = Mem0ServerRequest.SearchRequest.builder()
//                .query(query)
//                .userId(userId)
//                .limit(limit)
//                .build();
//        List<Document> documents = vectorStore.similaritySearch(request);
//        return Response.ok(documents);
//    }

    /**
     * 清空用户全部记忆
     */
    @DeleteMapping("/memories")
    public Response<Void> deleteAllMemory(@RequestParam String userId) {
        log.info("Mem0 清空用户记忆, userId={}", userId);
//        mem0ServiceClient.deleteAllMemory(userId, null, null);
        return Response.ok();
    }

    /**
     * 初始化 Mem0 配置（透传空配置，触发 Mem0 Server 使用默认配置）
     */
    @PostMapping("/configure")
    public Response<Void> configure() {
        log.info("Mem0 配置初始化");
//        mem0ServiceClient.configure(Map.of());
        return Response.ok();
    }

    @lombok.Data
    public static class AddMemoryRequest {
        private String userId;
        private String agentId;
        private String content;
    }
}
