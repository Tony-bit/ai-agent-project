package denny.ai.agent.domain.service.auto.step.react;

import cn.bugstack.wrench.design.framework.tree.StrategyHandler;
import denny.ai.agent.domain.model.entity.AutoAgentExecuteResultEntity;
import denny.ai.agent.domain.model.entity.ExecuteCommandEntity;
import denny.ai.agent.domain.model.valobj.AiAgentClientFlowConfigVO;
import denny.ai.agent.domain.model.valobj.SubTask;
import denny.ai.agent.domain.model.valobj.enums.AiClientTypeEnumVO;
import denny.ai.agent.domain.service.auto.step.AbstractExecuteSupport;
import denny.ai.agent.domain.service.auto.step.factory.DefaultAutoAgentExecuteStrategyFactory;
import denny.ai.agent.domain.service.auto.step.routing.ExecutorAdapter;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Map;

/**
 * 智能巡检节点
 *
 * @author denny
 */
@Slf4j
@Service
public class IntelligentInspection extends AbstractExecuteSupport implements ExecutorAdapter {

    @Override
    public String executeSubTask(SubTask subTask,
                               DefaultAutoAgentExecuteStrategyFactory.DynamicContext dynamicContext) throws Exception {
        log.info("IntelligentInspection 执行子任务: taskId={}, content={}", subTask.getTaskId(), subTask.getContent());

        ExecuteCommandEntity request = ExecuteCommandEntity.builder()
                .message(subTask.getContent())
                .sessionId(dynamicContext.getValue("sessionId") != null
                        ? dynamicContext.getValue("sessionId").toString() : null)
                .userId(dynamicContext.getValue("userId") != null
                        ? dynamicContext.getValue("userId").toString() : null)
                .aiAgentId(dynamicContext.getValue("aiAgentId") != null
                        ? dynamicContext.getValue("aiAgentId").toString() : null)
                .build();

        AiAgentClientFlowConfigVO aiAgentClientFlowConfigVO = dynamicContext.getAiAgentClientFlowConfigVOMap()
                .get(AiClientTypeEnumVO.OPS_ASSISTANT.getCode());
        if (aiAgentClientFlowConfigVO == null) {
            throw new IllegalStateException("未找到巡检客户端配置，请确认智能体流程配置中已添加 OPS_ASSISTANT 类型的节点");
        }

        ChatClient chatClient = getChatClientByClientId(aiAgentClientFlowConfigVO.getClientId(), 0);
        String prompt = String.format(aiAgentClientFlowConfigVO.getStepPrompt(),
                request.getMessage(),
                java.time.LocalDateTime.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")),
                request.getSessionId());

        return chatClient.prompt(prompt)
                .advisors(a -> a
                        .param(CHAT_MEMORY_CONVERSATION_ID_KEY, request.getSessionId())
                        .param("trace_id", dynamicContext.getTraceId()))
                .call().content();
    }

    @Override
    protected String doApply(ExecuteCommandEntity executeCommandEntity, DefaultAutoAgentExecuteStrategyFactory.DynamicContext dynamicContext) throws Exception {
        log.info(">>> [IntelligentInspection.doApply] 开始, dynamicContext.hashCode={}, dataObjects={}",
                System.identityHashCode(dynamicContext), dynamicContext.getDataObjects().keySet());

        log.info("\n🔍 阶段: 智能巡检");
        String traceId = dynamicContext.getTraceId();

        Map<String, Object> spanMetadata = new HashMap<>();
        spanMetadata.put("node", "intelligent_inspection");
        spanMetadata.put("sessionId", executeCommandEntity.getSessionId());
        String spanId = StringUtils.isNotBlank(traceId)
                ? observabilityService.startSpan(traceId, "intelligent_inspection", spanMetadata)
                : "";

        try {
            AiAgentClientFlowConfigVO aiAgentClientFlowConfigVO = dynamicContext.getAiAgentClientFlowConfigVOMap()
                    .get(AiClientTypeEnumVO.OPS_ASSISTANT.getCode());
            if (aiAgentClientFlowConfigVO == null) {
                throw new IllegalStateException("未找到精准执行客户端配置，aiAgentId=" + executeCommandEntity.getSessionId()
                        + "，请确认智能体流程配置中已添加 PRECISION_EXECUTOR_CLIENT 类型的节点");
            }

            String inspectionPrompt = buildInspectionPrompt(executeCommandEntity, aiAgentClientFlowConfigVO);

            // 获取对话客户端
            ChatClient chatClient = getChatClientByClientId(aiAgentClientFlowConfigVO.getClientId(), 0);

            String inspectionResult = chatClient
                    .prompt(inspectionPrompt)
                    .advisors(a -> a
                            .param(CHAT_MEMORY_CONVERSATION_ID_KEY, executeCommandEntity.getSessionId())
                            .param(CHAT_MEMORY_RETRIEVE_SIZE_KEY, 4096)
                            .param("trace_id", traceId))
                    .call().content();

            assert inspectionResult != null;
            log.info("\n🔍 === 巡检结果 ===\n{}", inspectionResult);

            // 发送巡检报告到前端
            sendInspectionReport(dynamicContext, inspectionResult, executeCommandEntity.getSessionId());

            // 标记任务完成
            dynamicContext.setCompleted(true);
            sendCompleteResult(dynamicContext, executeCommandEntity.getSessionId());

            if (StringUtils.isNotBlank(spanId)) {
                observabilityService.endSpan(spanId, true, null);
            }

            return "intelligent inspection completed!";
        } catch (Exception e) {
            log.error("智能巡检执行异常: {}", e.getMessage(), e);
            if (StringUtils.isNotBlank(spanId)) {
                observabilityService.endSpan(spanId, false, e.getMessage());
            }
            sendErrorResult(dynamicContext, "巡检执行异常: " + e.getMessage(), executeCommandEntity.getSessionId());
            throw e;
        }
    }

    @Override
    public StrategyHandler<ExecuteCommandEntity, DefaultAutoAgentExecuteStrategyFactory.DynamicContext, String> get(
            ExecuteCommandEntity executeCommandEntity,
            DefaultAutoAgentExecuteStrategyFactory.DynamicContext dynamicContext) throws Exception {
        // 巡检节点是独立入口，执行完成后结束流程
        return defaultStrategyHandler;
    }

    /**
     * 构建巡检 Prompt
     */
    private String buildInspectionPrompt(ExecuteCommandEntity executeCommandEntity,
                                         AiAgentClientFlowConfigVO aiAgentClientFlowConfigVO) {
        String basePrompt = aiAgentClientFlowConfigVO.getStepPrompt();
        if (StringUtils.isBlank(basePrompt)) {
           return "";
        }
        return String.format(basePrompt,
                executeCommandEntity.getMessage(),
                LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")),
                executeCommandEntity.getSessionId());
    }

    /**
     * 发送巡检报告到前端
     */
    private void sendInspectionReport(DefaultAutoAgentExecuteStrategyFactory.DynamicContext dynamicContext,
                                      String inspectionResult,
                                      String sessionId) {
        AutoAgentExecuteResultEntity result = AutoAgentExecuteResultEntity.builder()
                .type("supervision")
                .subType("inspection_report")
                .step(1)
                .content(inspectionResult)
                .completed(false)
                .timestamp(System.currentTimeMillis())
                .sessionId(sessionId)
                .build();

        log.info("准备发送巡检报告: sessionId={}, contentLength={}", sessionId, inspectionResult.length());
        log.debug("dynamicContext dataObjects keys: {}", dynamicContext.getDataObjects().keySet());

        sendSseResult(dynamicContext, result);
    }

    /**
     * 发送错误结果到前端
     */
    private void sendErrorResult(DefaultAutoAgentExecuteStrategyFactory.DynamicContext dynamicContext,
                                 String errorMessage,
                                 String sessionId) {
        AutoAgentExecuteResultEntity result = AutoAgentExecuteResultEntity.createErrorResult(errorMessage, sessionId);
        sendSseResult(dynamicContext, result);
    }

    /**
     * 发送完成标识到前端
     */
    private void sendCompleteResult(DefaultAutoAgentExecuteStrategyFactory.DynamicContext dynamicContext,
                                    String sessionId) {
        AutoAgentExecuteResultEntity result = AutoAgentExecuteResultEntity.createCompleteResult(sessionId);
        sendSseResult(dynamicContext, result);
        log.info("✅ 巡检完成标识已发送");
    }

}
