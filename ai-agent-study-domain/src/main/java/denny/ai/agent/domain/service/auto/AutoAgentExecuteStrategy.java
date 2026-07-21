package denny.ai.agent.domain.service.auto;

import cn.bugstack.wrench.design.framework.tree.StrategyHandler;
import denny.ai.agent.domain.model.entity.ExecuteCommandEntity;
import denny.ai.agent.domain.service.auto.step.factory.DefaultAutoAgentExecuteStrategyFactory;
import denny.ai.agent.domain.service.excute.IExecuteStrategy;
import denny.ai.agent.domain.service.observability.ObservabilityService;
import denny.ai.agent.domain.service.runtime.RuntimeContextAssembler;
import denny.ai.agent.domain.model.valobj.runtime.RetryRuntimeContext;
import denny.ai.agent.domain.model.valobj.runtime.TurnRuntimeContext;
import denny.ai.agent.domain.service.runtime.RetryRuntimeContextHolder;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyEmitter;

import java.util.HashMap;
import java.util.Map;

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

    @Resource
    private ObservabilityService observabilityService;

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

        Map<String, Object> traceMetadata = new HashMap<>();
        traceMetadata.put("traceName", "auto-agent");
        traceMetadata.put("owner", "auto_agent");
        if (executeCommandEntity.getUserId() != null) {
            traceMetadata.put("userId", executeCommandEntity.getUserId());
        }
        if (executeCommandEntity.getAiAgentId() != null) {
            traceMetadata.put("agentId", executeCommandEntity.getAiAgentId());
        }
        String traceId = observabilityService.startTrace(
                executeCommandEntity.getSessionId(), executeCommandEntity.getMessage(), traceMetadata);
        dynamicContext.setTraceId(traceId);

        String output = "";
        Exception traceFailure = null;
        try {
            TurnRuntimeContext turnContext = runtimeContextAssembler.prepare(executeCommandEntity, dynamicContext);
            RetryRuntimeContext retryContext = RetryRuntimeContext.from(turnContext);
            StrategyHandler<ExecuteCommandEntity, DefaultAutoAgentExecuteStrategyFactory.DynamicContext, String> executeHandler
                    = defaultAutoAgentExecuteStrategyFactory.armoryStrategyHandler();

            log.info("开始执行处理器链，agentType={}, aiAgentId={}",
                    executeCommandEntity.getAgentType(), executeCommandEntity.getAiAgentId());

            Exception executionFailure = null;
            String apply;
            try {
                apply = RetryRuntimeContextHolder.withContextThrowing(retryContext,
                        () -> executeHandler.apply(executeCommandEntity, dynamicContext));
            } catch (Exception error) {
                executionFailure = error;
                throw error;
            } finally {
                try {
                    runtimeContextAssembler.afterTurn(executeCommandEntity, dynamicContext, turnContext);
                } catch (RuntimeException cleanupError) {
                    if (executionFailure != null) {
                        executionFailure.addSuppressed(cleanupError);
                    } else {
                        throw cleanupError;
                    }
                }
            }
            output = apply == null ? "" : apply;
            log.info("测试结果:{}", apply);
        } catch (Exception e) {
            traceFailure = e;
            log.error("节点链执行异常: {}", e.getMessage(), e);
            safeComplete(emitter, "执行异常：" + e.getMessage());
        } finally {
            if (traceId != null && !traceId.isBlank()) {
                Map<String, Object> finalMetadata = new HashMap<>();
                finalMetadata.put("owner", "auto_agent");
                finalMetadata.put("success", traceFailure == null);
                finalMetadata.put("sessionId", executeCommandEntity.getSessionId());
                if (traceFailure != null) {
                    finalMetadata.put("error", traceFailure.getMessage());
                }
                observabilityService.endTrace(traceId, output, finalMetadata);
            }
        }

        // 关闭 SSE 流
        if (traceFailure == null) {
            safeComplete(emitter, null);
        }
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
            log.info("SSE emitter close completed: owner=auto_agent");
        } catch (Exception e) {
            log.warn("SSE 流关闭异常: error={}, msg={}", e.getMessage(), errorMessage);
        }
    }
}
