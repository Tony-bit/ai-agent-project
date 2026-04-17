package denny.ai.agent.domain.service.auto.step;

import cn.bugstack.wrench.design.framework.tree.StrategyHandler;
import denny.ai.agent.domain.model.entity.ExecuteCommandEntity;
import denny.ai.agent.domain.model.valobj.AiAgentClientFlowConfigVO;
import denny.ai.agent.domain.service.auto.step.factory.DefaultAutoAgentExecuteStrategyFactory;
import denny.ai.agent.domain.service.auto.step.pe.Step1AnalyzerNode;
import denny.ai.agent.domain.service.auto.step.react.IntelligentInspection;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.Objects;

/**
 * 执行根节点
 *
 * @author denny
 * 2025/7/27 16:33
 */
@Slf4j
@Service("executeRootNode")
public class RootNode extends AbstractExecuteSupport {

    @Resource
    private Step1AnalyzerNode step1AnalyzerNode;

    @Resource
    private IntelligentInspection intelligentInspection;

    @Override
    protected String doApply(ExecuteCommandEntity requestParameter, DefaultAutoAgentExecuteStrategyFactory.DynamicContext dynamicContext) throws Exception {
        log.info("=== 动态多轮执行测试开始 ====");
        log.info("用户输入: {}", requestParameter.getMessage());
        log.info("最大执行步数: {}", requestParameter.getMaxStep());
        log.info("会话ID: {}", requestParameter.getSessionId());

        Map<String, AiAgentClientFlowConfigVO> aiAgentClientFlowConfigVOMap = repository.queryAiAgentClientFlowConfig(requestParameter.getAiAgentId());

        // 客户端对话组
        dynamicContext.setAiAgentClientFlowConfigVOMap(aiAgentClientFlowConfigVOMap);
        // 上下文信息 - 注意：这里创建了新的 StringBuilder，可能丢失原 dynamicContext 中的某些信息
        StringBuilder oldHistory = dynamicContext.getExecutionHistory();
        dynamicContext.setExecutionHistory(new StringBuilder());
        // 当前任务信息
        dynamicContext.setCurrentTask(requestParameter.getMessage());
        // 最大任务步骤
        dynamicContext.setMaxStep(requestParameter.getMaxStep());

        log.info(">>> [RootNode.doApply] router前检查 - oldHistory={}, currentTask={}, dataObjects={}",
                oldHistory != null ? "exists" : "null",
                dynamicContext.getCurrentTask(),
                dynamicContext.getDataObjects().keySet());
        // 特别注意：检查emitter是否还在
        Object emitterCheck = dynamicContext.getValue("emitter");
        log.info(">>> [RootNode.doApply] emitter检查: {}", emitterCheck != null ? "存在" : "NULL");

        long startAt = System.currentTimeMillis();
        String result = router(requestParameter, dynamicContext);
        long latencyMs = System.currentTimeMillis() - startAt;

        // 持久化最终会话记录（路由完成后，统一在此处记录一次）
        persistRootConversation(requestParameter, dynamicContext, latencyMs);

        log.info(">>> [RootNode.doApply] 路由后, dynamicContext.hashCode={}, dataObjects={}",
                System.identityHashCode(dynamicContext), dynamicContext.getDataObjects().keySet());

        return result;
    }

    @Override
    public StrategyHandler<ExecuteCommandEntity, DefaultAutoAgentExecuteStrategyFactory.DynamicContext, String> get(ExecuteCommandEntity requestParameter, DefaultAutoAgentExecuteStrategyFactory.DynamicContext dynamicContext) throws Exception {
        if (Objects.equals(requestParameter.getAiAgentId(), "5")) {
            return intelligentInspection;
        }
        return step1AnalyzerNode;
    }

    /**
     * 持久化根节点的最终会话记录。
     * <p>
     * input 取用户原始请求，output 根据路由结果从 dynamicContext 中获取：
     * - PE 流：从 finalSummary 获取最终回复
     * - 巡检流：从 inspectionResult 获取巡检报告
     */
    private void persistRootConversation(ExecuteCommandEntity requestParameter,
                                         DefaultAutoAgentExecuteStrategyFactory.DynamicContext dynamicContext,
                                         long latencyMs) {
        String sessionId = requestParameter.getSessionId();
        String userId = requestParameter.getUserId();
        String agentId = requestParameter.getAiAgentId();
        String input = requestParameter.getMessage();
        String traceId = dynamicContext.getTraceId();

        // PE 流：finalSummary（正常总结流）
        String output = dynamicContext.getValue("finalSummary");
        // PE 流：intentRecognitionResult（意图识别流）
        if (output == null) {
            output = dynamicContext.getValue("intentRecognitionResult");
        }
        // 巡检流：inspectionResult
        if (output == null) {
            output = dynamicContext.getValue("inspectionResult");
        }
        String clientId = "RESPONSE_ASSISTANT";

        if (output == null) {
            log.warn("RootNode 未找到可持久化的 output，跳过会话持久化: sessionId={}", sessionId);
            return;
        }

        persistConversation(sessionId, userId, agentId, clientId, input, output, null, latencyMs, traceId);
    }

}
