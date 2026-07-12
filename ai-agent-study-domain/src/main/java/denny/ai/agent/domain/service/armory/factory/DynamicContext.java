package denny.ai.agent.domain.service.armory.factory;

import denny.ai.agent.domain.model.valobj.AiAgentClientFlowConfigVO;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.HashMap;
import java.util.Map;

@Data
@NoArgsConstructor
public class DynamicContext {
    private Map<String, Object> dataObjects = new HashMap<>();

    private String sessionId;
    private Map<String, AiAgentClientFlowConfigVO> aiAgentClientFlowConfigVOMap;

    public <T> void setValue(String key, T value) {
        dataObjects.put(key, value);
    }

    public <T> T getValue(String key) {
        return (T) dataObjects.get(key);
    }
}
