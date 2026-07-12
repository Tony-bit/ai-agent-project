package denny.ai.agent.domain.service.runtime;

import denny.ai.agent.domain.model.entity.ExecuteCommandEntity;
import denny.ai.agent.domain.model.valobj.AiAgentClientFlowConfigVO;
import denny.ai.agent.domain.model.valobj.runtime.SessionRuntimeContext;
import denny.ai.agent.domain.model.valobj.runtime.TurnRuntimeContext;
import denny.ai.agent.domain.service.auto.step.factory.DefaultAutoAgentExecuteStrategyFactory;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Service;

import java.util.Map;

@Service
public class DefaultRuntimeContextAssembler implements RuntimeContextAssembler {

    @Resource
    private AgentRuntimeConfigCache agentRuntimeConfigCache;

    @Resource
    private SessionRuntimeContextManager sessionRuntimeContextManager;

    @Override
    public TurnRuntimeContext prepare(ExecuteCommandEntity request,
                                      DefaultAutoAgentExecuteStrategyFactory.DynamicContext dynamicContext) {
        String aiAgentId = request.getAiAgentId();
        Map<String, AiAgentClientFlowConfigVO> flowConfigMap =
                aiAgentId == null || aiAgentId.isBlank()
                        ? agentRuntimeConfigCache.getIntentRoutingConfig()
                        : agentRuntimeConfigCache.getAgentFlowConfig(aiAgentId);
        SessionRuntimeContext sessionContext =
                sessionRuntimeContextManager.getOrLoad(request.getSessionId(), request.getUserId());
        TurnRuntimeContext turnContext = TurnRuntimeContext.builder()
                .traceId(dynamicContext.getTraceId())
                .sessionId(request.getSessionId())
                .userId(request.getUserId())
                .currentQuery(request.getMessage())
                .sessionRuntimeContext(sessionContext)
                .flowConfigMap(flowConfigMap)
                .preparedAt(System.currentTimeMillis())
                .build();

        dynamicContext.setValue("sessionId", request.getSessionId());
        dynamicContext.setValue("userId", request.getUserId());
        dynamicContext.setValue(RuntimeContextKeys.RECENT_HISTORY_MESSAGES,
                sessionContext.getRecentHistoryMessages());
        dynamicContext.setValue(RuntimeContextKeys.SESSION_CONTEXT, sessionContext);
        dynamicContext.setValue(RuntimeContextKeys.TURN_CONTEXT, turnContext);
        dynamicContext.setValue(RuntimeContextKeys.FLOW_CONFIG_MAP, flowConfigMap);
        dynamicContext.setAiAgentClientFlowConfigVOMap(flowConfigMap);
        return turnContext;
    }
}
