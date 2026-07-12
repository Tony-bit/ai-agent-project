package denny.ai.agent.domain.model.valobj.runtime;

import denny.ai.agent.domain.model.valobj.AiAgentClientFlowConfigVO;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class TurnRuntimeContext {

    private String traceId;

    private String sessionId;

    private String userId;

    private String currentQuery;

    private SessionRuntimeContext sessionRuntimeContext;

    @Builder.Default
    private Map<String, AiAgentClientFlowConfigVO> flowConfigMap = Map.of();

    private long preparedAt;
}
