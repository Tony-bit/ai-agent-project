package denny.ai.agent.domain.service.auto.step.pe;

import cn.bugstack.wrench.design.framework.tree.StrategyHandler;
import denny.ai.agent.domain.model.entity.AutoAgentExecuteResultEntity;
import denny.ai.agent.domain.model.entity.ExecuteCommandEntity;
import denny.ai.agent.domain.model.valobj.AiAgentClientFlowConfigVO;
import denny.ai.agent.domain.model.valobj.enums.AiClientTypeEnumVO;
import denny.ai.agent.domain.service.auto.step.AbstractExecuteSupport;
import denny.ai.agent.domain.service.auto.step.factory.DefaultAutoAgentExecuteStrategyFactory;
import lombok.extern.slf4j.Slf4j;

import java.util.HashMap;
import java.util.Map;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Service;

/**
 * 任务分析节点
 *
 * @author denny
 * 2025/7/27 16:36
 */
@Slf4j
@Service
public class Step1AnalyzerNode extends AbstractExecuteSupport {

    @Override
    protected String doApply(ExecuteCommandEntity requestParameter, DefaultAutoAgentExecuteStrategyFactory.DynamicContext dynamicContext) throws Exception {
        log.info("\n🎯 === 执行第 {} 步 ===", dynamicContext.getStep());

        // 初始化 Langfuse Trace（一次会话生命周期只初始化一次）
        if (dynamicContext.getTraceId() == null || dynamicContext.getTraceId().isBlank()) {
            Map<String, Object> metadata = new HashMap<>();
            metadata.put("node", "step1_analyzer");
            metadata.put("maxStep", dynamicContext.getMaxStep());
            metadata.put("currentStep", dynamicContext.getStep());
            String traceId = observabilityService.startTrace(requestParameter.getSessionId(), requestParameter.getMessage(), metadata);
            dynamicContext.setTraceId(traceId);
            log.info("📡 Langfuse trace initialized, traceId={}", traceId);
        }

        // 获取配置信息
        AiAgentClientFlowConfigVO aiAgentClientFlowConfigVO = dynamicContext.getAiAgentClientFlowConfigVOMap().get(AiClientTypeEnumVO.TASK_ANALYZER_CLIENT.getCode());
        if (aiAgentClientFlowConfigVO == null) {
            throw new IllegalStateException("未找到任务分析客户端配置，aiAgentId=" + requestParameter.getAiAgentId()
                    + "，请确认智能体流程配置中已添加 TASK_ANALYZER_CLIENT 类型的节点");
        }

        String traceId = dynamicContext.getTraceId();
        Map<String, Object> spanMetadata = new HashMap<>();
        spanMetadata.put("node", "step1_analyzer");
        spanMetadata.put("step", dynamicContext.getStep());
        spanMetadata.put("maxStep", dynamicContext.getMaxStep());
        spanMetadata.put("sessionId", requestParameter.getSessionId());
        spanMetadata.put("historyLength", dynamicContext.getExecutionHistory().length());
        String spanId = observabilityService.startSpan(traceId, "step1_analyzer", spanMetadata);

        try {
            // 第一阶段：任务分析
            log.info("\n📊 阶段1: 任务状态分析");
            String analysisPrompt = String.format(aiAgentClientFlowConfigVO.getStepPrompt(),
                    requestParameter.getMessage(),
                    dynamicContext.getStep(),
                    dynamicContext.getMaxStep(),
                    !dynamicContext.getExecutionHistory().isEmpty() ? dynamicContext.getExecutionHistory().toString() : "[首次执行]",
                    dynamicContext.getCurrentTask()
            );

            ChatClient chatClient = getChatClientByClientId(aiAgentClientFlowConfigVO.getClientId(), 0);

            long startAt = System.currentTimeMillis();
            String analysisResult = chatClient
                    .prompt(analysisPrompt)
                    .advisors(a -> a
                            .param(CHAT_MEMORY_CONVERSATION_ID_KEY, requestParameter.getSessionId())
                            .param(CHAT_MEMORY_RETRIEVE_SIZE_KEY, 1024)
                            .param("trace_id", traceId))
                    .call().content();

            assert analysisResult != null;
            parseAnalysisResult(dynamicContext, analysisResult, requestParameter.getSessionId());

            long latencyMs = System.currentTimeMillis() - startAt;
            Map<String, Object> generationMetadata = new HashMap<>();
            generationMetadata.put("node", "step1_analyzer");
            generationMetadata.put("latencyMs", latencyMs);
            generationMetadata.put("step", dynamicContext.getStep());
            generationMetadata.put("analysisLength", analysisResult.length());
            generationMetadata.put("taskStatus", extractTaskStatus(analysisResult));
            generationMetadata.put("progress", extractProgress(analysisResult));
            Map<String, Object> tokenUsage = new HashMap<>();
            observabilityService.logGeneration(
                    traceId,
                    spanId,
                    aiAgentClientFlowConfigVO.getClientId(),
                    analysisPrompt,
                    analysisResult,
                    generationMetadata,
                    tokenUsage
            );

            // 将分析结果保存到动态上下文中，供下一步使用
            dynamicContext.setValue("analysisResult", analysisResult);

            // 检查是否已完成
            if (analysisResult.contains("任务状态: COMPLETED") ||
                    analysisResult.contains("完成度评估: 100%")) {
                dynamicContext.setCompleted(true);
                log.info("✅ 任务分析显示已完成！");
            }

            // 检查是否需要信息补全（意图识别场景）
            String intentConfidence = extractIntentConfidence(analysisResult);
            dynamicContext.setValue("intentConfidence", intentConfidence);
            log.info("🎯 意图置信度: {}", intentConfidence);

            // 只有当置信度为"低"时才要求补全信息
            if ("低".equals(intentConfidence)) {
                dynamicContext.setValue("intentRecognitionRequired", true);
                log.info("🎯 置信度低于阈值，需要用户补充信息");
                dynamicContext.setValue("missingInfoPrompt", extractMissingInfoPrompt(analysisResult));
            }

            observabilityService.endSpan(spanId, true, null);
            return router(requestParameter, dynamicContext);
        } catch (Exception e) {
            observabilityService.endSpan(spanId, false, e.getMessage());
            throw e;
        }
    }

    @Override
    public StrategyHandler<ExecuteCommandEntity, DefaultAutoAgentExecuteStrategyFactory.DynamicContext, String> get(ExecuteCommandEntity requestParameter, DefaultAutoAgentExecuteStrategyFactory.DynamicContext dynamicContext) throws Exception {
        // 如果需要意图识别（信息补全），直接路由到第四个节点
        if (Boolean.TRUE.equals(dynamicContext.getValue("intentRecognitionRequired"))) {
            return getBean("step4LogExecutionSummaryNode");
        }

        // 如果任务已完成或达到最大步数，进入总结阶段
        if (dynamicContext.isCompleted() || dynamicContext.getStep() > dynamicContext.getMaxStep()) {
            return getBean("step4LogExecutionSummaryNode");
        }

        // 否则继续执行下一步
        return getBean("step2PrecisionExecutorNode");
    }

    /**
     * 从分析结果中提取意图置信度
     */
    private String extractIntentConfidence(String analysisResult) {
        for (String line : analysisResult.split("\\n")) {
            String trimmed = line.trim().replaceAll("^\\*+", "").replaceAll("\\*+$", "");
            if (trimmed.contains("意图置信度评估") || trimmed.contains("意图置信度:")) {
                String value;
                int colonIdx = trimmed.indexOf(":");
                if (colonIdx >= 0) {
                    value = trimmed.substring(colonIdx + 1).trim();
                } else {
                    value = trimmed.replaceAll(".*意图置信度评估", "").trim();
                }
                value = value.replaceAll("[（(].*", "").trim();
                if (value.contains("高")) return "高";
                if (value.contains("中")) return "中";
                if (value.contains("低")) return "低";
                return value;
            }
        }
        return null;
    }

    /**
     * 从分析结果中提取需要补全的信息
     */
    private String extractMissingInfoPrompt(String analysisResult) {
        StringBuilder missingInfo = new StringBuilder();
        boolean inMissingSection = false;

        String[] lines = analysisResult.split("\n");
        for (String line : lines) {
            line = line.trim();
            if (line.contains("信息补全要求:") || line.contains("需要补充信息")) {
                inMissingSection = true;
                missingInfo.append(line).append("\n");
            } else if (inMissingSection) {
                if (line.startsWith("下一步策略:") || line.startsWith("完成度评估:") || line.startsWith("任务状态:")) {
                    // 遇到下一个section，停止收集
                    break;
                }
                missingInfo.append(line).append("\n");
            }
        }

        return missingInfo.length() > 0 ? missingInfo.toString().trim() : null;
    }

    private void parseAnalysisResult(DefaultAutoAgentExecuteStrategyFactory.DynamicContext dynamicContext, String analysisResult, String sessionId) {
        int step = dynamicContext.getStep();
        log.info("\n📊 === 第 {} 步分析结果 ===", step);
        
        String[] lines = analysisResult.split("\n");
        String currentSection = "";
        StringBuilder sectionContent = new StringBuilder();

        for (String line : lines) {
            line = line.trim();
            if (line.isEmpty()) continue;

            if (line.contains("任务状态分析:")) {
                // 发送上一个section的内容
                sendAnalysisSubResult(dynamicContext, currentSection, sectionContent.toString(), sessionId);
                currentSection = "analysis_status";
                sectionContent = new StringBuilder();
                log.info("\n🎯 任务状态分析:");
                continue;
            } else if (line.contains("执行历史评估:")) {
                // 发送上一个section的内容
                sendAnalysisSubResult(dynamicContext, currentSection, sectionContent.toString(), sessionId);
                currentSection = "analysis_history";
                sectionContent = new StringBuilder();
                log.info("\n📈 执行历史评估:");
                continue;
            } else if (line.contains("下一步策略:")) {
                // 发送上一个section的内容
                sendAnalysisSubResult(dynamicContext, currentSection, sectionContent.toString(), sessionId);
                currentSection = "analysis_strategy";
                sectionContent = new StringBuilder();
                log.info("\n🚀 下一步策略:");
                continue;
            } else if (line.contains("完成度评估:")) {
                // 发送上一个section的内容
                sendAnalysisSubResult(dynamicContext, currentSection, sectionContent.toString(), sessionId);
                currentSection = "analysis_progress";
                sectionContent = new StringBuilder();
                String progress = line.substring(line.indexOf(":") + 1).trim();
                log.info("\n📊 完成度评估: {}", progress);
                sectionContent.append(line).append("\n");
                continue;
            } else if (line.contains("任务状态:")) {
                // 发送上一个section的内容
                sendAnalysisSubResult(dynamicContext, currentSection, sectionContent.toString(), sessionId);
                currentSection = "analysis_task_status";
                sectionContent = new StringBuilder();
                String status = line.substring(line.indexOf(":") + 1).trim();
                if (status.equals("COMPLETED")) {
                    log.info("\n✅ 任务状态: 已完成");
                } else {
                    log.info("\n🔄 任务状态: 继续执行");
                }
                sectionContent.append(line).append("\n");
                continue;
            }

            // 收集当前section的内容
            if (!currentSection.isEmpty()) {
                sectionContent.append(line).append("\n");
                switch (currentSection) {
                    case "analysis_status":
                        log.info("   📋 {}", line);
                        break;
                    case "analysis_history":
                        log.info("   📊 {}", line);
                        break;
                    case "analysis_strategy":
                        log.info("   🎯 {}", line);
                        break;
                    default:
                        log.info("   📝 {}", line);
                        break;
                }
            }
        }
        
        // 发送最后一个section的内容
        sendAnalysisSubResult(dynamicContext, currentSection, sectionContent.toString(), sessionId);
    }

    /**
     * 发送分析阶段细分结果到流式输出
     */
    private void sendAnalysisSubResult(DefaultAutoAgentExecuteStrategyFactory.DynamicContext dynamicContext, 
                                      String subType, String content, String sessionId) {
        if (!subType.isEmpty() && !content.isEmpty()) {
            AutoAgentExecuteResultEntity result = AutoAgentExecuteResultEntity.createAnalysisSubResult(
                    dynamicContext.getStep(), subType, content, sessionId);
            sendSseResult(dynamicContext, result);
        }
    }

    private String extractTaskStatus(String analysisResult) {
        for (String line : analysisResult.split("\\n")) {
            String trimmed = line.trim();
            if (trimmed.startsWith("任务状态:")) {
                return trimmed.substring(trimmed.indexOf(":") + 1).trim();
            }
        }
        return "UNKNOWN";
    }

    private Double extractProgress(String analysisResult) {
        for (String line : analysisResult.split("\\n")) {
            String trimmed = line.trim();
            if (trimmed.startsWith("完成度评估:")) {
                String raw = trimmed.substring(trimmed.indexOf(":") + 1).trim().replace("%", "");
                try {
                    return Double.parseDouble(raw);
                } catch (Exception ignore) {
                    return null;
                }
            }
        }
        return null;
    }

}
