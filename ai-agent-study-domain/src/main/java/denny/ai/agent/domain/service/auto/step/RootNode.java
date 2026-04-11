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
        log.info(">>> [RootNode.doApply] 开始, dynamicContext.hashCode={}, dataObjects={}",
                System.identityHashCode(dynamicContext), dynamicContext.getDataObjects().keySet());

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

        String result = router(requestParameter, dynamicContext);

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

}
