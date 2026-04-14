package denny.ai.agent.domain.adapter.repository;

import denny.ai.agent.domain.model.entity.ChatMessageEntity;
import denny.ai.agent.domain.model.entity.ChatSessionEntity;

import java.util.List;

/**
 * 会话记忆仓储接口（MySQL + Redis 双重持久化）
 *
 * @author denny
 */
public interface IChatMemoryRepository {

    /**
     * 保存会话（会话主表）
     *
     * @param session 会话实体
     */
    void saveSession(ChatSessionEntity session);

    /**
     * 保存消息（消息明细表）
     *
     * @param message 消息实体
     */
    void saveMessage(ChatMessageEntity message);

    /**
     * 根据会话ID查询会话信息
     *
     * @param sessionId 会话ID
     * @return 会话实体
     */
    ChatSessionEntity querySessionBySessionId(String sessionId);

    /**
     * 根据会话ID查询消息列表（按消息序号升序）
     *
     * @param sessionId 会话ID
     * @return 消息列表
     */
    List<ChatMessageEntity> queryMessagesBySessionId(String sessionId);

    /**
     * 更新会话的最新回复摘要
     *
     * @param sessionId       会话ID
     * @param lastResponse    最新回复摘要
     * @param incrementCount  消息增量（通常为2，表示新增一对问答）
     */
    void updateSessionLastResponse(String sessionId, String lastResponse, int incrementCount);

    /**
     * 将消息缓存到 Redis
     *
     * @param sessionId 会话ID
     * @param messages  消息列表（JSON 格式存储）
     * @param maxSize   最大缓存条数
     */
    void cacheMessagesToRedis(String sessionId, List<ChatMessageEntity> messages, int maxSize);

    /**
     * 从 Redis 获取缓存的消息
     *
     * @param sessionId 会话ID
     * @return 缓存的消息列表
     */
    List<ChatMessageEntity> getCachedMessagesFromRedis(String sessionId);

    /**
     * 删除 Redis 缓存
     *
     * @param sessionId 会话ID
     */
    void deleteRedisCache(String sessionId);
}
