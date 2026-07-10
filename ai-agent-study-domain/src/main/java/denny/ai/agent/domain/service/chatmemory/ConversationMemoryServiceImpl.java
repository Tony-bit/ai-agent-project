package denny.ai.agent.domain.service.chatmemory;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import denny.ai.agent.domain.adapter.repository.IChatMemoryRepository;
import denny.ai.agent.domain.model.entity.ChatMessageEntity;
import denny.ai.agent.domain.model.entity.ChatSessionEntity;
import denny.ai.agent.domain.model.entity.ConversationMemoryOptions;
import denny.ai.agent.domain.model.entity.ConversationMemorySnapshot;
import denny.ai.agent.domain.model.entity.ConversationRuntimeWindow;
import denny.ai.agent.domain.model.entity.ConversationTurn;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;

@Slf4j
@Service
public class ConversationMemoryServiceImpl implements ConversationMemoryService {

    private static final int DEFAULT_WINDOW_SIZE = 20;

    @Resource(name = "customChatMemoryRepository")
    private IChatMemoryRepository chatMemoryRepository;

    @Value("${chat.memory.runtime-window-size:${chat.memory.max-cache-size:20}}")
    private int runtimeWindowSize = DEFAULT_WINDOW_SIZE;

    @Value("${chat.memory.local-cache-ttl-minutes:60}")
    private int localCacheTtlMinutes = 60;

    @Value("${chat.memory.local-cache-max-sessions:10000}")
    private int localCacheMaxSessions = 10000;

    private Cache<String, ConversationRuntimeWindow> localRuntimeCache;

    @PostConstruct
    public void init() {
        localRuntimeCache = Caffeine.newBuilder()
                .maximumSize(localCacheMaxSessions)
                .expireAfterAccess(localCacheTtlMinutes, TimeUnit.MINUTES)
                .build();
    }

    @Override
    public ConversationMemorySnapshot loadSnapshot(String sessionId, ConversationMemoryOptions options) {
        if (sessionId == null || sessionId.isBlank()) {
            return emptySnapshot(sessionId);
        }
        ConversationMemoryOptions effectiveOptions = options == null ? ConversationMemoryOptions.defaults() : options;

        ConversationRuntimeWindow localWindow = localRuntimeCache.getIfPresent(sessionId);
        if (isUsable(localWindow, effectiveOptions)) {
            return ConversationMemorySnapshot.fromWindow(trimWindow(localWindow, effectiveOptions.getWindowSize()));
        }

        ConversationRuntimeWindow redisWindow = chatMemoryRepository.getCachedRuntimeWindowFromRedis(sessionId);
        if (isUsable(redisWindow, effectiveOptions)) {
            localRuntimeCache.put(sessionId, redisWindow);
            return ConversationMemorySnapshot.fromWindow(trimWindow(redisWindow, effectiveOptions.getWindowSize()));
        }

        List<ChatMessageEntity> messages = safeQueryMessages(sessionId);
        if (messages.isEmpty()) {
            return emptySnapshot(sessionId);
        }

        ConversationRuntimeWindow durableWindow = durableWindow(sessionId, messages, effectiveOptions.getWindowSize());
        chatMemoryRepository.cacheRuntimeWindowToRedis(sessionId, durableWindow, runtimeWindowSize);
        localRuntimeCache.put(sessionId, durableWindow);
        return ConversationMemorySnapshot.fromWindow(durableWindow);
    }

    @Override
    public void saveTurn(ConversationTurn turn) {
        if (turn == null || turn.getSessionId() == null || turn.getSessionId().isBlank()) {
            throw new IllegalArgumentException("ConversationTurn sessionId must not be blank");
        }
        String sessionId = turn.getSessionId();
        try {
            ChatSessionEntity session = chatMemoryRepository.querySessionBySessionId(sessionId);
            if (session == null) {
                session = ChatSessionEntity.builder()
                        .sessionId(sessionId)
                        .userId(turn.getUserId())
                        .agentId(turn.getAgentId())
                        .clientId(turn.getClientId())
                        .messageCount(0)
                        .firstQuery(truncate(turn.getQuery(), 500))
                        .lastResponse(truncate(turn.getResponse(), 500))
                        .status(ChatSessionEntity.STATUS_ACTIVE)
                        .createTime(LocalDateTime.now())
                        .build();
                chatMemoryRepository.saveSession(session);
            }

            List<ChatMessageEntity> existingMessages = chatMemoryRepository.queryMessagesBySessionId(sessionId);
            int nextIndex = existingMessages.size() + 1;
            LocalDateTime now = LocalDateTime.now();
            chatMemoryRepository.saveMessage(ChatMessageEntity.builder()
                    .sessionId(sessionId)
                    .messageIndex(nextIndex)
                    .role(ChatMessageEntity.ROLE_USER)
                    .content(turn.getQuery())
                    .model(turn.getModel())
                    .latencyMs(turn.getLatencyMs())
                    .traceId(turn.getTraceId())
                    .createTime(now)
                    .build());
            chatMemoryRepository.saveMessage(ChatMessageEntity.builder()
                    .sessionId(sessionId)
                    .messageIndex(nextIndex + 1)
                    .role(ChatMessageEntity.ROLE_ASSISTANT)
                    .content(turn.getResponse())
                    .model(turn.getModel())
                    .latencyMs(turn.getLatencyMs())
                    .traceId(turn.getTraceId())
                    .createTime(now)
                    .build());
            chatMemoryRepository.updateSessionLastResponse(sessionId, truncate(turn.getResponse(), 500), 2);

            List<ChatMessageEntity> allMessages = chatMemoryRepository.queryMessagesBySessionId(sessionId);
            ConversationRuntimeWindow durableWindow = durableWindow(sessionId, allMessages, runtimeWindowSize);
            chatMemoryRepository.cacheRuntimeWindowToRedis(sessionId, durableWindow, runtimeWindowSize);
            localRuntimeCache.put(sessionId, durableWindow);
            log.info("会话持久化完成并重建运行时窗口: sessionId={}, durableVersion={}", sessionId,
                    durableWindow.getDurableVersion());
        } catch (Exception e) {
            log.error("持久化会话失败: sessionId={}, error={}", sessionId, e.getMessage(), e);
            throw e;
        }
    }

    @Override
    public void refreshRuntimeCache(String sessionId, List<ChatMessageEntity> recentMessages) {
        if (sessionId == null || sessionId.isBlank()) {
            return;
        }
        List<ChatMessageEntity> limited = limitRecent(recentMessages, runtimeWindowSize);
        ConversationRuntimeWindow runtimeWindow = ConversationRuntimeWindow.builder()
                .sessionId(sessionId)
                .runtimeVersion(resolveVersion(limited))
                .durableVersion(0)
                .source(ConversationRuntimeWindow.SOURCE_ADVISOR_RUNTIME)
                .durable(false)
                .recentMessages(limited)
                .updatedAt(LocalDateTime.now())
                .build();
        localRuntimeCache.put(sessionId, runtimeWindow);
        chatMemoryRepository.cacheRuntimeWindowToRedis(sessionId, runtimeWindow, runtimeWindowSize);
    }

    @Override
    public void clearRuntimeMemory(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return;
        }
        localRuntimeCache.invalidate(sessionId);
        chatMemoryRepository.deleteRedisCache(sessionId);
    }

    private boolean isUsable(ConversationRuntimeWindow window, ConversationMemoryOptions options) {
        if (window == null || window.getRecentMessages() == null || window.getRecentMessages().isEmpty()) {
            return false;
        }
        if (!options.isAllowRuntimeWindow() && !window.isDurable()) {
            return false;
        }
        return !options.isRequireDurable() || window.isDurable();
    }

    private ConversationRuntimeWindow durableWindow(String sessionId, List<ChatMessageEntity> messages, int windowSize) {
        List<ChatMessageEntity> limited = limitRecent(messages, windowSize);
        long durableVersion = resolveVersion(messages);
        return ConversationRuntimeWindow.builder()
                .sessionId(sessionId)
                .runtimeVersion(durableVersion)
                .durableVersion(durableVersion)
                .source(ConversationRuntimeWindow.SOURCE_DURABLE_REBUILD)
                .durable(true)
                .recentMessages(limited)
                .updatedAt(LocalDateTime.now())
                .build();
    }

    private ConversationRuntimeWindow trimWindow(ConversationRuntimeWindow window, int windowSize) {
        window.setRecentMessages(limitRecent(window.getRecentMessages(), windowSize));
        return window;
    }

    private List<ChatMessageEntity> safeQueryMessages(String sessionId) {
        try {
            List<ChatMessageEntity> messages = chatMemoryRepository.queryMessagesBySessionId(sessionId);
            return messages == null ? Collections.emptyList() : messages;
        } catch (Exception e) {
            log.error("从 MySQL 查询会话历史失败: sessionId={}, error={}", sessionId, e.getMessage(), e);
            return Collections.emptyList();
        }
    }

    private List<ChatMessageEntity> limitRecent(List<ChatMessageEntity> messages, int maxSize) {
        if (messages == null || messages.isEmpty()) {
            return List.of();
        }
        int effectiveMaxSize = maxSize > 0 ? maxSize : DEFAULT_WINDOW_SIZE;
        if (messages.size() <= effectiveMaxSize) {
            return List.copyOf(messages);
        }
        return List.copyOf(messages.subList(messages.size() - effectiveMaxSize, messages.size()));
    }

    private long resolveVersion(List<ChatMessageEntity> messages) {
        if (messages == null || messages.isEmpty()) {
            return 0;
        }
        return messages.stream()
                .map(ChatMessageEntity::getMessageIndex)
                .filter(index -> index != null)
                .mapToLong(Integer::longValue)
                .max()
                .orElse(messages.size());
    }

    private ConversationMemorySnapshot emptySnapshot(String sessionId) {
        return ConversationMemorySnapshot.builder()
                .sessionId(sessionId)
                .recentMessages(List.of())
                .durable(true)
                .source(ConversationRuntimeWindow.SOURCE_DURABLE_REBUILD)
                .updatedAt(LocalDateTime.now())
                .build();
    }

    private String truncate(String text, int maxLength) {
        if (text == null) {
            return "";
        }
        return text.length() <= maxLength ? text : text.substring(0, maxLength);
    }
}
