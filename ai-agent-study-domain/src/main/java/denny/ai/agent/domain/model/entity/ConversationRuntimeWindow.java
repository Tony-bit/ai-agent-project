package denny.ai.agent.domain.model.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Runtime view of a conversation window stored in L1/Redis.
 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class ConversationRuntimeWindow {

    public static final String SOURCE_ADVISOR_RUNTIME = "advisor_runtime";
    public static final String SOURCE_DURABLE_REBUILD = "durable_rebuild";

    private String sessionId;

    private long runtimeVersion;

    private long durableVersion;

    private String source;

    private boolean durable;

    @Builder.Default
    private List<ChatMessageEntity> recentMessages = new ArrayList<>();

    private String summary;

    private LocalDateTime updatedAt;

    private Long ttlSeconds;
}
