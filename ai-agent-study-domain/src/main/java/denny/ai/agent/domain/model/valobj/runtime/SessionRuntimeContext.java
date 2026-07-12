package denny.ai.agent.domain.model.valobj.runtime;

import denny.ai.agent.domain.model.entity.ChatMessageEntity;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Set;

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class SessionRuntimeContext {

    private String sessionId;

    private String userId;

    @Builder.Default
    private List<ChatMessageEntity> recentMessages = List.of();

    @Builder.Default
    private List<String> recentHistoryMessages = List.of();

    private String sessionSummary;

    @Builder.Default
    private Set<String> activeSkillNames = Set.of();

    private Integer lastMessageIndex;

    private long loadedAt;

    private long version;
}
