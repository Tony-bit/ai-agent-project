package denny.ai.agent.domain.service.chatmemory;

import denny.ai.agent.domain.adapter.repository.IChatMemoryRepository;
import denny.ai.agent.domain.model.entity.ChatMessageEntity;
import denny.ai.agent.domain.model.entity.ChatSessionEntity;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
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
            // 1. 查找或创建会话
            ChatSessionEntity session = chatMemoryRepository.querySessionBySessionId(sessionId);
            if (session == null) {
                session = ChatSessionEntity.builder()
                        .sessionId(sessionId)
                        .userId(userId)
                        .agentId(agentId)
                        .clientId(clientId)
                        .messageCount(0)
                        .firstQuery(truncate(query, 500))
                        .lastResponse(truncate(response, 500))
                        .status(ChatSessionEntity.STATUS_ACTIVE)
                        .createTime(LocalDateTime.now())
                        .build();
                chatMemoryRepository.saveSession(session);
                log.info("创建新会话: sessionId={}", sessionId);
            }

            // 2. 查询当前消息序号
            List<ChatMessageEntity> existingMessages = chatMemoryRepository.queryMessagesBySessionId(sessionId);
            int nextIndex = existingMessages.size() + 1;

            // 3. 保存 user 消息
            ChatMessageEntity userMessage = ChatMessageEntity.builder()
                    .sessionId(sessionId)
                    .messageIndex(nextIndex)
                    .role(ChatMessageEntity.ROLE_USER)
                    .content(query)
                    .model(model)
                    .latencyMs(latencyMs)
                    .traceId(traceId)
                    .createTime(LocalDateTime.now())
                    .build();
            chatMemoryRepository.saveMessage(userMessage);

            // 4. 保存 assistant 消息
            ChatMessageEntity assistantMessage = ChatMessageEntity.builder()
                    .sessionId(sessionId)
                    .messageIndex(nextIndex + 1)
                    .role(ChatMessageEntity.ROLE_ASSISTANT)
                    .content(response)
                    .model(model)
                    .latencyMs(latencyMs)
                    .traceId(traceId)
                    .createTime(LocalDateTime.now())
                    .build();
            chatMemoryRepository.saveMessage(assistantMessage);

            // 5. 更新会话摘要
            chatMemoryRepository.updateSessionLastResponse(
                    sessionId,
                    truncate(response, 500),
                    2
            );

            // 6. 刷新 Redis 缓存
            List<ChatMessageEntity> allMessages = chatMemoryRepository.queryMessagesBySessionId(sessionId);
            chatMemoryRepository.cacheMessagesToRedis(sessionId, allMessages, maxCacheSize);

            log.info("会话持久化完成: sessionId={}, messageIndex={}+{}", sessionId, nextIndex, nextIndex + 1);

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
        // 1. 优先从 Redis 读取
        List<ChatMessageEntity> cached = chatMemoryRepository.getCachedMessagesFromRedis(sessionId);
        if (cached != null && !cached.isEmpty()) {
            log.debug("从 Redis 获取会话历史: sessionId={}, count={}", sessionId, cached.size());
            return cached;
        }

        // 2. Fallback 到 MySQL
        List<ChatMessageEntity> messages = chatMemoryRepository.queryMessagesBySessionId(sessionId);
        if (!messages.isEmpty()) {
            // 回填到 Redis
            chatMemoryRepository.cacheMessagesToRedis(sessionId, messages, maxCacheSize);
            log.debug("从 MySQL 获取会话历史并回填 Redis: sessionId={}, count={}", sessionId, messages.size());
        }

        return messages;
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
        chatMemoryRepository.deleteRedisCache(sessionId);
    }

    private String truncate(String text, int maxLength) {
        if (text == null) return "";
        return text.length() <= maxLength ? text : text.substring(0, maxLength);
    }
}
