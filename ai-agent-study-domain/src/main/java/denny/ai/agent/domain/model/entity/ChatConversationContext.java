package denny.ai.agent.domain.model.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class ChatConversationContext {

    private String sessionId;

    @Builder.Default
    private List<ChatMessageEntity> recentMessages = new ArrayList<>();

    private String summary;

    private boolean durable;
}
