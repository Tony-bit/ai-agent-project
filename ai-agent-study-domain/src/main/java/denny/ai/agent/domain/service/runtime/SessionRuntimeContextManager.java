package denny.ai.agent.domain.service.runtime;

import denny.ai.agent.domain.model.entity.ChatMessageEntity;
import denny.ai.agent.domain.model.valobj.runtime.SessionRuntimeContext;
import denny.ai.agent.domain.service.chatmemory.ChatMemoryPersistenceService;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Service
public class SessionRuntimeContextManager {

    @Resource
    private ChatMemoryPersistenceService chatMemoryPersistenceService;

    @Value("${agent.runtime.session-context-cache.ttl-ms:0}")
    private long ttlMs = 0L;

    private final Map<String, CacheEntry> cache = new ConcurrentHashMap<>();

    public SessionRuntimeContext getOrLoad(String sessionId, String userId) {
        if (sessionId == null || sessionId.isBlank()) {
            return empty(sessionId, userId);
        }
        long now = System.currentTimeMillis();
        CacheEntry entry = cache.get(sessionId);
        if (entry != null && !entry.isExpired(now, ttlMs)) {
            return entry.value();
        }
        SessionRuntimeContext context = load(sessionId, userId, now);
        cache.put(sessionId, new CacheEntry(context, now));
        return context;
    }

    public void clear(String sessionId) {
        if (sessionId != null) {
            cache.remove(sessionId);
        }
    }

    public void clearAll() {
        cache.clear();
    }

    private SessionRuntimeContext load(String sessionId, String userId, long now) {
        try {
            List<ChatMessageEntity> messages = chatMemoryPersistenceService.getConversationHistory(sessionId);
            List<ChatMessageEntity> safeMessages = messages == null ? List.of() : messages;
            Integer lastMessageIndex = safeMessages.stream()
                    .map(ChatMessageEntity::getMessageIndex)
                    .filter(index -> index != null)
                    .max(Comparator.naturalOrder())
                    .orElse(null);
            return SessionRuntimeContext.builder()
                    .sessionId(sessionId)
                    .userId(userId)
                    .recentMessages(safeMessages)
                    .recentHistoryMessages(formatHistory(safeMessages))
                    .lastMessageIndex(lastMessageIndex)
                    .loadedAt(now)
                    .version(lastMessageIndex == null ? 0L : lastMessageIndex)
                    .build();
        } catch (Exception e) {
            log.warn("Failed to load session runtime context: sessionId={}, error={}", sessionId, e.getMessage());
            return empty(sessionId, userId);
        }
    }

    private SessionRuntimeContext empty(String sessionId, String userId) {
        long now = System.currentTimeMillis();
        return SessionRuntimeContext.builder()
                .sessionId(sessionId)
                .userId(userId)
                .loadedAt(now)
                .version(0L)
                .build();
    }

    private List<String> formatHistory(List<ChatMessageEntity> messages) {
        return messages.stream()
                .filter(message -> message.getRole() != null && message.getContent() != null)
                .map(message -> message.getRole() + ": " + message.getContent())
                .toList();
    }

    private record CacheEntry(SessionRuntimeContext value, long loadedAt) {
        boolean isExpired(long now, long ttlMs) {
            return ttlMs <= 0 || now - loadedAt >= ttlMs;
        }
    }
}
