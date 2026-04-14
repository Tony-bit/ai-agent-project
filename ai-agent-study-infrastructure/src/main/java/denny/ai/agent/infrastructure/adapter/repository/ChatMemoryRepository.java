package denny.ai.agent.infrastructure.adapter.repository;

import com.alibaba.fastjson.JSON;
import denny.ai.agent.domain.adapter.repository.IChatMemoryRepository;
import denny.ai.agent.domain.model.entity.ChatMessageEntity;
import denny.ai.agent.domain.model.entity.ChatSessionEntity;
import denny.ai.agent.infrastructure.dao.IChatMessageDao;
import denny.ai.agent.infrastructure.dao.IChatSessionDao;
import denny.ai.agent.infrastructure.dao.po.ChatMessagePO;
import denny.ai.agent.infrastructure.dao.po.ChatSessionPO;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * 会话记忆仓储实现（MySQL + Redis）
 *
 * @author denny
 */
@Slf4j
@Service("customChatMemoryRepository")
public class ChatMemoryRepository implements IChatMemoryRepository {

    private static final String REDIS_KEY_PREFIX = "chat:session:";

    @Resource
    private IChatSessionDao chatSessionDao;

    @Resource
    private IChatMessageDao chatMessageDao;

    @Autowired(required = false)
    private StringRedisTemplate stringRedisTemplate;

    @Value("${chat.memory.redis-ttl-hours:24}")
    private int redisTtlHours = 24;

    @Value("${chat.memory.max-cache-size:20}")
    private int maxCacheSize = 20;

    @Override
    public void saveSession(ChatSessionEntity session) {
        ChatSessionPO po = toSessionPO(session);
        chatSessionDao.insert(po);
    }

    @Override
    public void saveMessage(ChatMessageEntity message) {
        ChatMessagePO po = toMessagePO(message);
        chatMessageDao.insert(po);
    }

    @Override
    public ChatSessionEntity querySessionBySessionId(String sessionId) {
        ChatSessionPO po = chatSessionDao.queryBySessionId(sessionId);
        return po == null ? null : toSessionEntity(po);
    }

    @Override
    public List<ChatMessageEntity> queryMessagesBySessionId(String sessionId) {
        List<ChatMessagePO> pos = chatMessageDao.queryBySessionId(sessionId);
        return pos.stream().map(this::toMessageEntity).collect(Collectors.toList());
    }

    @Override
    public void updateSessionLastResponse(String sessionId, String lastResponse, int incrementCount) {
        chatSessionDao.updateLastResponse(sessionId, lastResponse, incrementCount);
    }

    @Override
    public void cacheMessagesToRedis(String sessionId, List<ChatMessageEntity> messages, int maxSize) {
        if (stringRedisTemplate == null) {
            log.warn("StringRedisTemplate 未配置，跳过 Redis 缓存: sessionId={}", sessionId);
            return;
        }
        try {
            List<ChatMessageEntity> limited = messages.size() > maxSize
                    ? messages.subList(messages.size() - maxSize, messages.size())
                    : messages;

            String key = REDIS_KEY_PREFIX + sessionId;
            String json = JSON.toJSONString(limited);
            stringRedisTemplate.opsForValue().set(key, json, redisTtlHours, TimeUnit.HOURS);
            log.info("会话 {} 缓存到 Redis，消息条数={}, TTL={}h", sessionId, limited.size(), redisTtlHours);
        } catch (Exception e) {
            log.error("缓存消息到 Redis 失败: sessionId={}, error={}", sessionId, e.getMessage(), e);
        }
    }

    @Override
    public List<ChatMessageEntity> getCachedMessagesFromRedis(String sessionId) {
        if (stringRedisTemplate == null) {
            log.warn("StringRedisTemplate 未配置，跳过 Redis 读取: sessionId={}", sessionId);
            return Collections.emptyList();
        }
        try {
            String key = REDIS_KEY_PREFIX + sessionId;
            String json = stringRedisTemplate.opsForValue().get(key);
            if (json == null || json.isEmpty()) {
                return Collections.emptyList();
            }
            return JSON.parseArray(json, ChatMessageEntity.class);
        } catch (Exception e) {
            log.error("从 Redis 读取缓存消息失败: sessionId={}, error={}", sessionId, e.getMessage(), e);
            return Collections.emptyList();
        }
    }

    @Override
    public void deleteRedisCache(String sessionId) {
        if (stringRedisTemplate == null) {
            return;
        }
        try {
            String key = REDIS_KEY_PREFIX + sessionId;
            stringRedisTemplate.delete(key);
        } catch (Exception e) {
            log.error("删除 Redis 缓存失败: sessionId={}, error={}", sessionId, e.getMessage(), e);
        }
    }

    private ChatSessionPO toSessionPO(ChatSessionEntity entity) {
        ChatSessionPO po = new ChatSessionPO();
        po.setSessionId(entity.getSessionId());
        po.setUserId(entity.getUserId());
        po.setAgentId(entity.getAgentId());
        po.setClientId(entity.getClientId());
        po.setMessageCount(entity.getMessageCount() != null ? entity.getMessageCount() : 0);
        po.setFirstQuery(entity.getFirstQuery());
        po.setLastResponse(entity.getLastResponse());
        po.setStatus(entity.getStatus() != null ? entity.getStatus() : ChatSessionEntity.STATUS_ACTIVE);
        po.setCreateTime(entity.getCreateTime() != null ? entity.getCreateTime() : LocalDateTime.now());
        po.setUpdateTime(LocalDateTime.now());
        return po;
    }

    private ChatSessionEntity toSessionEntity(ChatSessionPO po) {
        return ChatSessionEntity.builder()
                .id(po.getId())
                .sessionId(po.getSessionId())
                .userId(po.getUserId())
                .agentId(po.getAgentId())
                .clientId(po.getClientId())
                .messageCount(po.getMessageCount())
                .firstQuery(po.getFirstQuery())
                .lastResponse(po.getLastResponse())
                .status(po.getStatus())
                .createTime(po.getCreateTime())
                .updateTime(po.getUpdateTime())
                .build();
    }

    private ChatMessagePO toMessagePO(ChatMessageEntity entity) {
        ChatMessagePO po = new ChatMessagePO();
        po.setSessionId(entity.getSessionId());
        po.setMessageIndex(entity.getMessageIndex());
        po.setRole(entity.getRole());
        po.setContent(entity.getContent());
        po.setModel(entity.getModel());
        po.setLatencyMs(entity.getLatencyMs());
        po.setTraceId(entity.getTraceId());
        po.setCreateTime(entity.getCreateTime() != null ? entity.getCreateTime() : LocalDateTime.now());
        return po;
    }

    private ChatMessageEntity toMessageEntity(ChatMessagePO po) {
        return ChatMessageEntity.builder()
                .id(po.getId())
                .sessionId(po.getSessionId())
                .messageIndex(po.getMessageIndex())
                .role(po.getRole())
                .content(po.getContent())
                .model(po.getModel())
                .latencyMs(po.getLatencyMs())
                .traceId(po.getTraceId())
                .createTime(po.getCreateTime())
                .build();
    }
}
