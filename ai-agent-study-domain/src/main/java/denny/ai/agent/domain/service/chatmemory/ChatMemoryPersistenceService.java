package denny.ai.agent.domain.service.chatmemory;

import denny.ai.agent.domain.adapter.repository.IChatMemoryRepository;
import denny.ai.agent.domain.model.entity.ChatMessageEntity;
import denny.ai.agent.domain.model.entity.ChatSessionEntity;
import denny.ai.agent.domain.model.entity.ConversationMemoryOptions;
import denny.ai.agent.domain.model.entity.ConversationTurn;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 会话记忆持久化服务
 * <p>
 * 核心职责：每轮对话结束后，将 Query + Response 写入 MySQL + Redis。
 * 提供会话历史查询能力。
 * </p>
 *
 * @author denny
 */
@Slf4j
@Service
public class ChatMemoryPersistenceService {

    private static final int DEFAULT_MAX_CACHE_SIZE = 20;

    @Resource(name = "customChatMemoryRepository")
    private IChatMemoryRepository chatMemoryRepository;

    @Resource
    private ConversationMemoryService conversationMemoryService;

    @Value("${chat.memory.max-cache-size:20}")
    private int maxCacheSize = DEFAULT_MAX_CACHE_SIZE;

    /**
     * 持久化一轮对话（Query + Response）
     *
     * @param sessionId  会话ID
     * @param userId     用户ID
     * @param agentId    智能体ID
     * @param clientId   客户端ID
     * @param query      用户原始输入
     * @param response   模型原始输出
     * @param model      模型名称
     * @param latencyMs  响应耗时
     * @param traceId    追踪ID
     */
    public void persistConversation(String sessionId,
                                     String userId,
                                     String agentId,
                                     String clientId,
                                     String query,
                                     String response,
                                     String model,
                                     Long latencyMs,
                                     String traceId) {
        try {
            conversationMemoryService.saveTurn(ConversationTurn.builder()
                    .sessionId(sessionId)
                    .userId(userId)
                    .agentId(agentId)
                    .clientId(clientId)
                    .query(query)
                    .response(response)
                    .model(model)
                    .latencyMs(latencyMs)
                    .traceId(traceId)
                    .build());
        } catch (Exception e) {
            log.error("持久化会话失败: sessionId={}, error={}", sessionId, e.getMessage(), e);
        }
    }

    /**
     * 获取会话历史（优先从 Redis 获取，fallback 到 MySQL）
     *
     * @param sessionId 会话ID
     * @return 消息列表
     */
    public List<ChatMessageEntity> getConversationHistory(String sessionId) {
        return conversationMemoryService.loadSnapshot(sessionId, ConversationMemoryOptions.builder()
                .windowSize(maxCacheSize)
                .allowRuntimeWindow(true)
                .build()).getRecentMessages();
    }

    /**
     * 获取会话信息
     *
     * @param sessionId 会话ID
     * @return 会话实体
     */
    public ChatSessionEntity getSession(String sessionId) {
        return chatMemoryRepository.querySessionBySessionId(sessionId);
    }

    /**
     * 清理会话 Redis 缓存
     *
     * @param sessionId 会话ID
     */
    public void clearCache(String sessionId) {
        conversationMemoryService.clearRuntimeMemory(sessionId);
    }

    private String truncate(String text, int maxLength) {
        if (text == null) return "";
        return text.length() <= maxLength ? text : text.substring(0, maxLength);
    }
}
