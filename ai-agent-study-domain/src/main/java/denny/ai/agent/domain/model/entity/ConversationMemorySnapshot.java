package denny.ai.agent.domain.model.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Conversation memory returned to callers after resolving L1/Redis/MySQL.
 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class ConversationMemorySnapshot {

    private String sessionId;

    @Builder.Default
    private List<ChatMessageEntity> recentMessages = new ArrayList<>();

    private String summary;

    private boolean durable;

    private String source;

    private long runtimeVersion;

    private long durableVersion;

    private LocalDateTime updatedAt;

    public static ConversationMemorySnapshot fromWindow(ConversationRuntimeWindow window) {
        if (window == null) {
            return ConversationMemorySnapshot.builder().build();
        }
        return ConversationMemorySnapshot.builder()
                .sessionId(window.getSessionId())
                .recentMessages(window.getRecentMessages())
                .summary(window.getSummary())
                .durable(window.isDurable())
                .source(window.getSource())
                .runtimeVersion(window.getRuntimeVersion())
                .durableVersion(window.getDurableVersion())
                .updatedAt(window.getUpdatedAt())
                .build();
    }
}
