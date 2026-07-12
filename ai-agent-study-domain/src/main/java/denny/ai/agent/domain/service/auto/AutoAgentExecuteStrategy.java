package denny.ai.agent.domain.service.auto;

import cn.bugstack.wrench.design.framework.tree.StrategyHandler;
import denny.ai.agent.domain.model.entity.ExecuteCommandEntity;
import denny.ai.agent.domain.service.auto.step.factory.DefaultAutoAgentExecuteStrategyFactory;
import denny.ai.agent.domain.service.excute.IExecuteStrategy;
import denny.ai.agent.domain.service.runtime.RuntimeContextAssembler;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyEmitter;

import java.util.UUID;

/**
 * 自动执行策略
 *
 * @author denny
 * 2025/8/5 09:49
 */
@Slf4j
@Service
public class AutoAgentExecuteStrategy implements IExecuteStrategy {

    @Resource
    private DefaultAutoAgentExecuteStrategyFactory defaultAutoAgentExecuteStrategyFactory;

    @Resource
    private RuntimeContextAssembler runtimeContextAssembler;

    @Override
    public void execute(ExecuteCommandEntity executeCommandEntity, ResponseBodyEmitter emitter) throws Exception {
        // 创建动态上下文并初始化必要字段
        DefaultAutoAgentExecuteStrategyFactory.DynamicContext dynamicContext = new DefaultAutoAgentExecuteStrategyFactory.DynamicContext();
        dynamicContext.setMaxStep(executeCommandEntity.getMaxStep() != null ? executeCommandEntity.getMaxStep() : 3);
        dynamicContext.setExecutionHistory(new StringBuilder());
        dynamicContext.setCurrentTask(executeCommandEntity.getMessage());
        dynamicContext.setValue("emitter", emitter);
        dynamicContext.setValue("sessionId", executeCommandEntity.getSessionId());
        dynamicContext.setValue("userId", executeCommandEntity.getUserId());

        log.info(">>> [AutoAgentExecuteStrategy.execute] dynamicContext创建, hashCode={}, dataObjects={}",
                System.identityHashCode(dynamicContext), dynamicContext.getDataObjects().keySet());

        // 初始化追踪ID
        String traceId = UUID.randomUUID().toString().replace("-", "");
        dynamicContext.setTraceId(traceId);

        runtimeContextAssembler.prepare(executeCommandEntity, dynamicContext);

        StrategyHandler<ExecuteCommandEntity, DefaultAutoAgentExecuteStrategyFactory.DynamicContext, String> executeHandler
                = defaultAutoAgentExecuteStrategyFactory.armoryStrategyHandler();

        log.info("开始执行处理器链，agentType={}, aiAgentId={}", executeCommandEntity.getAgentType(), executeCommandEntity.getAiAgentId());

        try {
            String apply = executeHandler.apply(executeCommandEntity, dynamicContext);
            log.info("测试结果:{}", apply);
        } catch (Exception e) {
            log.error("节点链执行异常: {}", e.getMessage(), e);
            safeComplete(emitter, "执行异常：" + e.getMessage());
            return;
        }

        // 关闭 SSE 流
        safeComplete(emitter, null);
    }

    /**
     * 安全关闭 SSE 流
     */
    private void safeComplete(ResponseBodyEmitter emitter, String errorMessage) {
        if (emitter == null) {
            return;
        }
        try {
            if (errorMessage != null && !errorMessage.isEmpty()) {
                String sseData = "data: {\"type\":\"error\",\"content\":\"" + errorMessage + "\"}\n\n";
                emitter.send(sseData);
            }
            emitter.complete();
        } catch (Exception e) {
            log.warn("SSE 流关闭异常: error={}, msg={}", e.getMessage(), errorMessage);
        }
    }
}
