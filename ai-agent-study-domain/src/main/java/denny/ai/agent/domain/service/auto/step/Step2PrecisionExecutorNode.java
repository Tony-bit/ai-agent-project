package denny.ai.agent.domain.service.auto.step;

import cn.bugstack.wrench.design.framework.tree.StrategyHandler;
import denny.ai.agent.domain.model.entity.AutoAgentExecuteResultEntity;
import denny.ai.agent.domain.model.entity.ExecuteCommandEntity;
import denny.ai.agent.domain.model.valobj.AiAgentClientFlowConfigVO;
import denny.ai.agent.domain.model.valobj.enums.AiClientTypeEnumVO;
import denny.ai.agent.domain.service.auto.step.factory.DefaultAutoAgentExecuteStrategyFactory;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.Map;

/**
 * 精准执行节点
 *
 * @author denny
 * 2025/7/27 16:42
 */
@Slf4j
@Service
public class Step2PrecisionExecutorNode extends AbstractExecuteSupport{


    @Override
    protected String doApply(ExecuteCommandEntity requestParameter, DefaultAutoAgentExecuteStrategyFactory.DynamicContext dynamicContext) throws Exception {
        log.info("\n⚡ 阶段2: 精准任务执行");

        String traceId = dynamicContext.getTraceId();
        Map<String, Object> spanMetadata = new HashMap<>();
        spanMetadata.put("node", "step2_precision_executor");
        spanMetadata.put("step", dynamicContext.getStep());
        spanMetadata.put("maxStep", dynamicContext.getMaxStep());
        spanMetadata.put("sessionId", requestParameter.getSessionId());
        String spanId = observabilityService.startSpan(traceId, "step2_precision_executor", spanMetadata);

        try {
            // 从动态上下文中获取分析结果
            String analysisResult = dynamicContext.getValue("analysisResult");
            if (analysisResult == null || analysisResult.trim().isEmpty()) {
                log.warn("⚠️ 分析结果为空，使用默认执行策略");
                analysisResult = "执行当前任务步骤";
            }

            AiAgentClientFlowConfigVO aiAgentClientFlowConfigVO = dynamicContext.getAiAgentClientFlowConfigVOMap().get(AiClientTypeEnumVO.PRECISION_EXECUTOR_CLIENT.getCode());

            int taskType = 0;
            // 根据分析任务类型，获取对应的客户端进行执行任务
            if (analysisResult.contains("推理任务类型")) {
                taskType = 1;
            } else if (analysisResult.contains("计算任务类型")) {
                taskType = 2;
            } else if (analysisResult.contains("知识检索任务类型")) {
                taskType = 3;
            }
            log.info("本任务类型为：{}", taskType);

            // 获取对话客户端
            ChatClient chatClient = getChatClientByClientId(aiAgentClientFlowConfigVO.getClientId(), taskType);

            long startAt = System.currentTimeMillis();
            String executionResult;
            if (taskType == 3) {
                // 知识检索任务：UserMessage 只放用户问题，AssistantMessage 放分析结果
                // 这样 RagAnswerAdvisor 做检索时只拿用户问题，不会把 system 指令和分析结果都查一遍
                executionResult = chatClient
                        .prompt()
                        .messages(
                                new UserMessage(requestParameter.getMessage()),
                                new AssistantMessage("【任务分析结果】\n" + analysisResult)
                        )
                        .advisors(a -> a
                                .param(CHAT_MEMORY_CONVERSATION_ID_KEY, requestParameter.getSessionId())
                                .param(CHAT_MEMORY_RETRIEVE_SIZE_KEY, 4096)
                                .param("trace_id", traceId))
                        .call().content();
            } else {
                // 非知识检索任务，保持原有逻辑
                String executionPrompt = String.format(
                        aiAgentClientFlowConfigVO.getStepPrompt(),
                        requestParameter.getMessage(),
                        analysisResult
                );
                executionResult = chatClient
                        .prompt(executionPrompt)
                        .advisors(a -> a
                                .param(CHAT_MEMORY_CONVERSATION_ID_KEY, requestParameter.getSessionId())
                                .param(CHAT_MEMORY_RETRIEVE_SIZE_KEY, 4096)
                                .param("trace_id", traceId))
                        .call().content();
            }

            assert executionResult != null;
            parseExecutionResult(dynamicContext, executionResult, requestParameter.getSessionId());

            long latencyMs = System.currentTimeMillis() - startAt;
            Map<String, Object> generationMetadata = new HashMap<>();
            generationMetadata.put("node", "step2_precision_executor");
            generationMetadata.put("latencyMs", latencyMs);
            generationMetadata.put("step", dynamicContext.getStep());
            generationMetadata.put("taskType", taskType);
            generationMetadata.put("executionLength", executionResult.length());
            generationMetadata.put("analysisLength", analysisResult.length());
            generationMetadata.put("isRagTask", taskType == 3);
            Map<String, Object> tokenUsage = new HashMap<>();

            // 构建输入文本用于日志记录：RAG 分支用用户问题 + 分析结果拼接，非 RAG 用完整的 executionPrompt
            String logInput = (taskType == 3)
                    ? requestParameter.getMessage() + "\n\n【任务分析结果】\n" + analysisResult
                    : String.format(aiAgentClientFlowConfigVO.getStepPrompt(),
                            requestParameter.getMessage(), analysisResult);
            observabilityService.logGeneration(
                    traceId,
                    spanId,
                    aiAgentClientFlowConfigVO.getClientId(),
                    logInput,
                    executionResult,
                    generationMetadata,
                    tokenUsage
            );

            // 将执行结果保存到动态上下文中，供下一步使用
            dynamicContext.setValue("executionResult", executionResult);

            // 更新执行历史
            String stepSummary = String.format("""
                    === 第 %d 步执行记录 ===
                    【分析阶段】%s
                    【执行阶段】%s
                    """, dynamicContext.getStep(), analysisResult, executionResult);

            dynamicContext.getExecutionHistory().append(stepSummary);

            observabilityService.endSpan(spanId, true, null);
            return router(requestParameter, dynamicContext);
        } catch (Exception e) {
            observabilityService.endSpan(spanId, false, e.getMessage());
            throw e;
        }
    }

    @Override
    public StrategyHandler<ExecuteCommandEntity, DefaultAutoAgentExecuteStrategyFactory.DynamicContext, String> get(ExecuteCommandEntity requestParameter, DefaultAutoAgentExecuteStrategyFactory.DynamicContext dynamicContext) throws Exception {
        return getBean("step3QualitySupervisorNode");
    }
    
    /**
     * 解析执行结果
     */
    private void parseExecutionResult(DefaultAutoAgentExecuteStrategyFactory.DynamicContext dynamicContext, String executionResult, String sessionId) {
        int step = dynamicContext.getStep();
        log.info("\n⚡ === 第 {} 步执行结果 ===", step);
        
        String[] lines = executionResult.split("\n");
        String currentSection = "";
        StringBuilder sectionContent = new StringBuilder();
        
        for (String line : lines) {
            line = line.trim();
            if (line.isEmpty()) continue;
            
            if (line.contains("执行目标:")) {
                // 发送上一个section的内容
                sendExecutionSubResult(dynamicContext, currentSection, sectionContent.toString(), sessionId);
                currentSection = "execution_target";
                sectionContent = new StringBuilder();
                log.info("\n🎯 执行目标:");
                continue;
            } else if (line.contains("执行过程:")) {
                // 发送上一个section的内容
                sendExecutionSubResult(dynamicContext, currentSection, sectionContent.toString(), sessionId);
                currentSection = "execution_process";
                sectionContent = new StringBuilder();
                log.info("\n🔧 执行过程:");
                continue;
            } else if (line.contains("执行结果:")) {
                // 发送上一个section的内容
                sendExecutionSubResult(dynamicContext, currentSection, sectionContent.toString(), sessionId);
                currentSection = "execution_result";
                sectionContent = new StringBuilder();
                log.info("\n📈 执行结果:");
                continue;
            } else if (line.contains("质量检查:")) {
                // 发送上一个section的内容
                sendExecutionSubResult(dynamicContext, currentSection, sectionContent.toString(), sessionId);
                currentSection = "execution_quality";
                sectionContent = new StringBuilder();
                log.info("\n🔍 质量检查:");
                continue;
            }
            
            // 收集当前section的内容
            if (!currentSection.isEmpty()) {
                sectionContent.append(line).append("\n");
                switch (currentSection) {
                    case "execution_target":
                        log.info("   🎯 {}", line);
                        break;
                    case "execution_process":
                        log.info("   ⚙️ {}", line);
                        break;
                    case "execution_result":
                        log.info("   📊 {}", line);
                        break;
                    case "execution_quality":
                        log.info("   ✅ {}", line);
                        break;
                    default:
                        log.info("   📝 {}", line);
                        break;
                }
            }
        }
        
        // 发送最后一个section的内容
        sendExecutionSubResult(dynamicContext, currentSection, sectionContent.toString(), sessionId);
    }
    
    /**
     * 发送执行阶段细分结果到流式输出
     */
    private void sendExecutionSubResult(DefaultAutoAgentExecuteStrategyFactory.DynamicContext dynamicContext, 
                                       String subType, String content, String sessionId) {
        // 抽取的通用判断逻辑
        if (!subType.isEmpty() && !content.isEmpty()) {
            AutoAgentExecuteResultEntity result = AutoAgentExecuteResultEntity.createExecutionSubResult(
                    dynamicContext.getStep(), subType, content, sessionId);
            sendSseResult(dynamicContext, result);
        }
    }
    
}
