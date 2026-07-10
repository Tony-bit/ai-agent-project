package denny.ai.agent.domain.model.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * A completed user/assistant turn ready for durable persistence.
 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class ConversationTurn {

    private String sessionId;

    private String userId;

    private String agentId;

    private String clientId;

    private String query;

    private String response;

    private String model;

    private Long latencyMs;

    private String traceId;
}
