package denny.ai.agent.trigger.http;

import denny.ai.agent.api.response.Response;
import denny.ai.agent.domain.model.entity.ChatMessageEntity;
import denny.ai.agent.domain.model.entity.ChatSessionEntity;
import denny.ai.agent.domain.service.chatmemory.ChatMemoryPersistenceService;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 会话记忆 HTTP 接口
 * 提供会话历史查询能力
 *
 * @author denny
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/chat-memory")
public class ChatMemoryController {

    @Resource
    private ChatMemoryPersistenceService chatMemoryPersistenceService;

    /**
     * 获取会话历史消息
     */
    @GetMapping("/messages")
    public Response<List<ChatMessageEntity>> getMessages(@RequestParam String sessionId) {
        log.info("查询会话历史: sessionId={}", sessionId);
        List<ChatMessageEntity> messages = chatMemoryPersistenceService.getConversationHistory(sessionId);
        return Response.ok(messages);
    }

    /**
     * 获取会话基本信息
     */
    @GetMapping("/session")
    public Response<ChatSessionEntity> getSession(@RequestParam String sessionId) {
        log.info("查询会话信息: sessionId={}", sessionId);
        ChatSessionEntity session = chatMemoryPersistenceService.getSession(sessionId);
        return Response.ok(session);
    }

    /**
     * 清理会话 Redis 缓存
     */
    @DeleteMapping("/cache")
    public Response<Void> clearCache(@RequestParam String sessionId) {
        log.info("清理会话缓存: sessionId={}", sessionId);
        chatMemoryPersistenceService.clearCache(sessionId);
        return Response.ok();
    }
}
