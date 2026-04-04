package denny.ai.agent.domain.service.auto.step;

import cn.bugstack.wrench.design.framework.tree.StrategyHandler;
import denny.ai.agent.domain.model.entity.AutoAgentExecuteResultEntity;
import denny.ai.agent.domain.model.entity.ExecuteCommandEntity;
import denny.ai.agent.domain.model.valobj.AiAgentClientFlowConfigVO;
import denny.ai.agent.domain.model.valobj.enums.AiClientTypeEnumVO;
import denny.ai.agent.domain.service.auto.step.factory.DefaultAutoAgentExecuteStrategyFactory;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.Map;

/**
 * 执行总结节点
 *
 * @author denny
 * 2025/7/27 16:45
 */
@Slf4j
@Service
public class Step4LogExecutionSummaryNode extends AbstractExecuteSupport {

    @Override
    protected String doApply(ExecuteCommandEntity requestParameter, DefaultAutoAgentExecuteStrategyFactory.DynamicContext dynamicContext) throws Exception {
        log.info("\n📊 === 执行第 {} 步 ===", dynamicContext.getStep());

        // 检查是否为意图识别场景（需要用户补充信息）
        if (Boolean.TRUE.equals(dynamicContext.getValue("intentRecognitionRequired"))) {
            log.info("\n🎯 检测到意图识别场景：需要用户补充信息");
            handleIntentRecognition(requestParameter, dynamicContext);
            return "intent recognition completed!";
        }

        // 第四阶段：执行总结
        log.info("\n📊 阶段4: 执行总结分析");
        
        // 记录执行总结
        logExecutionSummary(dynamicContext.getMaxStep(), dynamicContext.getExecutionHistory(), dynamicContext.isCompleted());
        
        // 生成最终总结报告（无论任务是否完成都需要生成）
        generateFinalReport(requestParameter, dynamicContext);
        
        log.info("\n🏁 === 动态多轮执行结束 ====");
        
        return "ai agent execution summary completed!";
    }

    @Override
    public StrategyHandler<ExecuteCommandEntity, DefaultAutoAgentExecuteStrategyFactory.DynamicContext, String> get(ExecuteCommandEntity requestParameter, DefaultAutoAgentExecuteStrategyFactory.DynamicContext dynamicContext) throws Exception {
        // 总结节点是最后一个节点，返回null表示执行结束
        return defaultStrategyHandler;
    }
    
    /**
     * 记录执行总结
     */
    private void logExecutionSummary(int maxSteps, StringBuilder executionHistory, boolean isCompleted) {
        log.info("\n📊 === 动态多轮执行总结 ====");

        int actualSteps = Math.min(maxSteps, executionHistory.toString().split("=== 第").length - 1);
        log.info("📈 总执行步数: {} 步", actualSteps);

        if (isCompleted) {
            log.info("✅ 任务完成状态: 已完成");
        } else {
            log.info("⏸️ 任务完成状态: 未完成（达到最大步数限制）");
        }

        // 计算执行效率
        double efficiency = isCompleted ? 100.0 : (double) actualSteps / maxSteps * 100;
        log.info("📊 执行效率: {}%", efficiency);
    }

    /**
     * 处理意图识别场景：生成信息补全提示
     */
    private void handleIntentRecognition(ExecuteCommandEntity requestParameter, DefaultAutoAgentExecuteStrategyFactory.DynamicContext dynamicContext) {
        String traceId = dynamicContext.getTraceId();
        Map<String, Object> spanMetadata = new HashMap<>();
        spanMetadata.put("node", "step4_intent_recognition");
        spanMetadata.put("step", dynamicContext.getStep());
        spanMetadata.put("sessionId", requestParameter.getSessionId());
        String spanId = StringUtils.isNotBlank(traceId)
                ? observabilityService.startSpan(traceId, "step4_intent_recognition", spanMetadata)
                : "";

        try {
            // 从上下文中获取分析结果中的缺失信息描述
            String analysisResult = dynamicContext.getValue("analysisResult");
            String missingInfoPrompt = dynamicContext.getValue("missingInfoPrompt");

            // 获取意图识别客户端配置（使用 RESPONSE_ASSISTANT 作为意图识别的大模型）
            AiAgentClientFlowConfigVO aiAgentClientFlowConfigVO = dynamicContext.getAiAgentClientFlowConfigVOMap()
                    .get(AiClientTypeEnumVO.RESPONSE_ASSISTANT.getCode());

            // 使用大模型生成用户友好的信息补全提示
            String intentPrompt = buildIntentPrompt(aiAgentClientFlowConfigVO, requestParameter, analysisResult, missingInfoPrompt);

            // 获取对话客户端
            ChatClient chatClient = getChatClientByClientId(aiAgentClientFlowConfigVO.getClientId(), 0);

            long startAt = System.currentTimeMillis();
            String userFriendlyPrompt = chatClient
                    .prompt(intentPrompt)
                    .advisors(a -> a
                            .param(CHAT_MEMORY_CONVERSATION_ID_KEY, requestParameter.getSessionId())
                            .param(CHAT_MEMORY_RETRIEVE_SIZE_KEY, 50)
                            .param("trace_id", traceId))
                    .call().content();

            assert userFriendlyPrompt != null;
            log.info("\n🎯 意图识别 - 用户友好提示: {}", userFriendlyPrompt);

            long latencyMs = System.currentTimeMillis() - startAt;
            if (StringUtils.isNotBlank(traceId) && StringUtils.isNotBlank(spanId)) {
                Map<String, Object> generationMetadata = new HashMap<>();
                generationMetadata.put("node", "step4_intent_recognition");
                generationMetadata.put("latencyMs", latencyMs);
                generationMetadata.put("step", dynamicContext.getStep());
                generationMetadata.put("promptLength", userFriendlyPrompt.length());
                Map<String, Object> tokenUsage = new HashMap<>();
                observabilityService.logGeneration(
                        traceId,
                        spanId,
                        aiAgentClientFlowConfigVO.getClientId(),
                        intentPrompt,
                        userFriendlyPrompt,
                        generationMetadata,
                        tokenUsage
                );
            }

            // 保存意图识别结果到上下文
            dynamicContext.setValue("intentRecognitionResult", userFriendlyPrompt);

            // 发送意图识别结果给前端（作为普通的对话回复）
            sendSummaryResult(dynamicContext, userFriendlyPrompt, requestParameter.getSessionId());

            if (StringUtils.isNotBlank(traceId)) {
                Map<String, Object> traceMetadata = new HashMap<>();
                traceMetadata.put("node", "step4_intent_recognition");
                traceMetadata.put("intentType", "information_request");
                traceMetadata.put("step", dynamicContext.getStep());
                traceMetadata.put("sessionId", requestParameter.getSessionId());
                observabilityService.endTrace(traceId, userFriendlyPrompt, traceMetadata);
            }

            if (StringUtils.isNotBlank(spanId)) {
                observabilityService.endSpan(spanId, true, null);
            }

        } catch (Exception e) {
            log.error("处理意图识别时出现异常: {}", e.getMessage(), e);
            if (StringUtils.isNotBlank(traceId)) {
                observabilityService.endTrace(traceId, "", null);
            }
            if (StringUtils.isNotBlank(spanId)) {
                observabilityService.endSpan(spanId, false, e.getMessage());
            }
            // 发送错误结果给前端
            sendSummaryResult(dynamicContext, "系统繁忙，请稍后再试", requestParameter.getSessionId());
        }
    }

    /**
     * 构建意图识别 Prompt
     */
    private String buildIntentPrompt(AiAgentClientFlowConfigVO aiAgentClientFlowConfigVO,
                                      ExecuteCommandEntity requestParameter,
                                      String analysisResult,
                                      String missingInfoPrompt) {
        // 如果有专门的意图识别 Prompt 模板，使用它
        if (aiAgentClientFlowConfigVO != null && aiAgentClientFlowConfigVO.getStepPrompt() != null) {
            return String.format(aiAgentClientFlowConfigVO.getStepPrompt(),
                    requestParameter.getMessage(),
                    analysisResult != null ? analysisResult : "");
        }

        // 默认 Prompt 模板 - 优化版：直接提取并输出"下一步策略"中的具体补全要求
        String nextStepStrategy = extractNextStepStrategy(analysisResult);
        String specificRequirement = extractSpecificRequirement(nextStepStrategy);

        return String.format("""
                **用户原始问题:**
                %s

                **分析结果:**
                %s

                **任务要求:**
                基于上述分析结果，直接向用户输出需要补充的信息提示。

                %s

                请直接生成给用户的提示语，格式如下：
                【需要用户补全信息】<具体的补全要求，包含需要询问用户的具体内容>

                示例：
                输入：天气查询缺少地点信息
                输出：【需要用户补全信息】为了帮您查询天气，请告诉我您想查询哪个城市的天气？
                """,
                requestParameter.getMessage(),
                analysisResult != null ? analysisResult : "",
                StringUtils.isNotBlank(specificRequirement)
                        ? "**\"下一步策略\"中要求的补全内容：**\n" + specificRequirement
                        : "");
    }

    /**
     * 从分析结果中提取"下一步策略"部分的内容
     */
    private String extractNextStepStrategy(String analysisResult) {
        if (analysisResult == null || analysisResult.isBlank()) {
            return "";
        }

        StringBuilder strategy = new StringBuilder();
        boolean inStrategySection = false;

        String[] lines = analysisResult.split("\n");
        for (String line : lines) {
            line = line.trim();
            if (line.contains("下一步策略:")) {
                inStrategySection = true;
                strategy.append(line).append("\n");
            } else if (inStrategySection) {
                // 遇到下一个section，停止收集
                if (line.startsWith("完成度评估:") || line.startsWith("任务状态:")) {
                    break;
                }
                strategy.append(line).append("\n");
            }
        }

        return strategy.toString().trim();
    }

    /**
     * 从"下一步策略"中提取具体的补全要求
     */
    private String extractSpecificRequirement(String nextStepStrategy) {
        if (nextStepStrategy == null || nextStepStrategy.isBlank()) {
            return "";
        }

        // 提取"下一步策略"中提到的具体补全内容
        // 例如：要求用户提供城市名称、需要用户提供XX等
        StringBuilder requirement = new StringBuilder();

        String[] lines = nextStepStrategy.split("\n");
        for (String line : lines) {
            line = line.trim();
            // 跳过标题行
            if (line.startsWith("下一步策略:")) {
                continue;
            }
            // 收集包含具体补全要求的行
            if (line.contains("需要用户提供") ||
                    line.contains("需要用户提供") ||
                    line.contains("必须提供") ||
                    line.contains("请提供") ||
                    line.contains("请告诉我") ||
                    line.contains("请输入") ||
                    line.contains("需要明确") ||
                    line.contains("缺少") ||
                    line.contains("需要补充") ||
                    (line.contains("才能") && line.contains("调用"))) {
                requirement.append(line).append("\n");
            }
        }

        return requirement.toString().trim();
    }

    /**
     * 生成最终总结报告
     */
    private void generateFinalReport(ExecuteCommandEntity requestParameter, DefaultAutoAgentExecuteStrategyFactory.DynamicContext dynamicContext) {
        String traceId = dynamicContext.getTraceId();
        Map<String, Object> spanMetadata = new HashMap<>();
        spanMetadata.put("node", "step4_execution_summary");
        spanMetadata.put("step", dynamicContext.getStep());
        spanMetadata.put("maxStep", dynamicContext.getMaxStep());
        spanMetadata.put("sessionId", requestParameter.getSessionId());
        String spanId = StringUtils.isNotBlank(traceId)
                ? observabilityService.startSpan(traceId, "step4_execution_summary", spanMetadata)
                : "";

        try {
            boolean isCompleted = dynamicContext.isCompleted();
            log.info("\n--- 生成{}任务的最终答案 ---", isCompleted ? "已完成" : "未完成");

            AiAgentClientFlowConfigVO aiAgentClientFlowConfigVO = dynamicContext.getAiAgentClientFlowConfigVOMap().get(AiClientTypeEnumVO.RESPONSE_ASSISTANT.getCode());

            String summaryPrompt = getSummaryPrompt(aiAgentClientFlowConfigVO, requestParameter, dynamicContext, isCompleted);

            // 获取对话客户端 - 使用任务分析客户端进行总结
            ChatClient chatClient = getChatClientByClientId(aiAgentClientFlowConfigVO.getClientId(), 0);

            long startAt = System.currentTimeMillis();
            String summaryResult = chatClient
                    .prompt(summaryPrompt)
                    .advisors(a -> a
                            .param(CHAT_MEMORY_CONVERSATION_ID_KEY, requestParameter.getSessionId() + "-summary")
                            .param(CHAT_MEMORY_RETRIEVE_SIZE_KEY, 50)
                            .param("trace_id", traceId))
                    .call().content();

            assert summaryResult != null;
            logFinalReport(dynamicContext, summaryResult, requestParameter.getSessionId());

            long latencyMs = System.currentTimeMillis() - startAt;
            if (StringUtils.isNotBlank(traceId) && StringUtils.isNotBlank(spanId)) {
                Map<String, Object> generationMetadata = new HashMap<>();
                generationMetadata.put("node", "step4_execution_summary");
                generationMetadata.put("latencyMs", latencyMs);
                generationMetadata.put("step", dynamicContext.getStep());
                generationMetadata.put("completed", isCompleted);
                generationMetadata.put("summaryLength", summaryResult.length());
                generationMetadata.put("historyLength", dynamicContext.getExecutionHistory().length());
                Map<String, Object> tokenUsage = new HashMap<>();
                observabilityService.logGeneration(
                        traceId,
                        spanId,
                        aiAgentClientFlowConfigVO.getClientId(),
                        summaryPrompt,
                        summaryResult,
                        generationMetadata,
                        tokenUsage
                );
            }

            // 将总结结果保存到动态上下文中
            dynamicContext.setValue("finalSummary", summaryResult);

            if (StringUtils.isNotBlank(traceId)) {
                Map<String, Object> traceMetadata = new HashMap<>();
                traceMetadata.put("node", "step4_execution_summary");
                traceMetadata.put("completed", dynamicContext.isCompleted());
                traceMetadata.put("step", dynamicContext.getStep());
                traceMetadata.put("maxStep", dynamicContext.getMaxStep());
                traceMetadata.put("sessionId", requestParameter.getSessionId());
                log.info("endTrce: traceMetaData{},   summaryResult:{}",traceMetadata,summaryResult);
                observabilityService.endTrace(traceId, summaryResult, traceMetadata);
            }

            if (StringUtils.isNotBlank(spanId)) {
                observabilityService.endSpan(spanId, true, null);
            }

        } catch (Exception e) {
            log.error("生成最终总结报告时出现异常: {}", e.getMessage(), e);
            String currentTraceId = dynamicContext.getTraceId();
            if (StringUtils.isNotBlank(currentTraceId)) {
                Map<String, Object> traceMetadata = new HashMap<>();
                traceMetadata.put("node", "step4_execution_summary");
                traceMetadata.put("completed", dynamicContext.isCompleted());
                traceMetadata.put("step", dynamicContext.getStep());
                traceMetadata.put("maxStep", dynamicContext.getMaxStep());
                traceMetadata.put("sessionId", requestParameter.getSessionId());
                traceMetadata.put("error", e.getMessage());
                log.info("endTrceError: traceMetaData{},   summaryResult:{}",traceMetadata,null);

                observabilityService.endTrace(currentTraceId, "", traceMetadata);
            }
            if (StringUtils.isNotBlank(spanId)) {
                observabilityService.endSpan(spanId, false, e.getMessage());
            }
        }
    }

    private static String getSummaryPrompt(AiAgentClientFlowConfigVO aiAgentClientFlowConfigVO, ExecuteCommandEntity requestParameter, DefaultAutoAgentExecuteStrategyFactory.DynamicContext dynamicContext, boolean isCompleted) {
        String summaryPrompt;
        if (isCompleted) {
            summaryPrompt = String.format(aiAgentClientFlowConfigVO.getStepPrompt(),
                    requestParameter.getMessage(),
                    dynamicContext.getExecutionHistory().toString());
        } else {
            summaryPrompt = String.format("""
                    虽然任务未完全执行完成，但请基于已有的执行过程，尽力回答用户的原始问题：
                    
                    **用户原始问题:** %s
                    
                    **已执行的过程和获得的信息:**
                    %s
                    
                    **要求:**
                    1. 基于已有信息，尽力回答用户的原始问题
                    2. 如果信息不足，说明哪些部分无法完成并给出原因
                    3. 提供已能确定的部分答案
                    4. 给出完成剩余部分的具体建议
                    5. 以MD语法的表格形式，优化展示结果数据
                    
                    请基于现有信息给出用户问题的答案：
                    """,
                    requestParameter.getMessage(),
                    dynamicContext.getExecutionHistory().toString());
        }
        return summaryPrompt;
    }

    /**
     * 输出最终总结报告
     */
    private void logFinalReport(DefaultAutoAgentExecuteStrategyFactory.DynamicContext dynamicContext, String summaryResult, String sessionId) {
        boolean isCompleted = dynamicContext.isCompleted();
        log.info("\n📋 === {}任务最终总结报告 ===", isCompleted ? "已完成" : "未完成");

        String[] lines = summaryResult.split("\n");
        String currentSection = "summary_overview";
        StringBuilder sectionContent = new StringBuilder();
        
        for (String line : lines) {
            line = line.trim();
            if (line.isEmpty()) continue;
            
            // 检测是否开始新的总结部分
            String newSection = detectSummarySection(line);
            if (newSection != null && !newSection.equals(currentSection)) {
                // 发送前一个部分的内容
                if (!sectionContent.isEmpty()) {
                    sendSummarySubResult(dynamicContext, currentSection, sectionContent.toString(), sessionId);
                }
                currentSection = newSection;
                sectionContent.setLength(0);
            }
            
            // 收集当前部分的内容
            if (!sectionContent.isEmpty()) {
                sectionContent.append("\n");
            }
            sectionContent.append(line);
            
            // 根据内容类型添加不同图标
            if (line.contains("已完成") || line.contains("完成的工作")) {
                log.info("✅ {}", line);
            } else if (line.contains("未完成") || line.contains("原因")) {
                log.info("❌ {}", line);
            } else if (line.contains("建议") || line.contains("推荐")) {
                log.info("💡 {}", line);
            } else if (line.contains("评估") || line.contains("效果")) {
                log.info("📊 {}", line);
            } else {
                log.info("📝 {}", line);
            }
        }
        
        // 发送最后一个部分的内容
        if (!sectionContent.isEmpty()) {
            sendSummarySubResult(dynamicContext, currentSection, sectionContent.toString(), sessionId);
        }
        
        // 发送完整的总结结果
        sendSummaryResult(dynamicContext, summaryResult, sessionId);
        
        // 发送完成标识
        sendCompleteResult(dynamicContext, sessionId);
    }
    
    /**
     * 发送总结结果到流式输出
     */
    private void sendSummaryResult(DefaultAutoAgentExecuteStrategyFactory.DynamicContext dynamicContext, 
                                 String summaryResult, String sessionId) {
        AutoAgentExecuteResultEntity result = AutoAgentExecuteResultEntity.createSummaryResult(
                 summaryResult, sessionId);
        sendSseResult(dynamicContext, result);
    }
    
    /**
     * 发送总结阶段细分结果到流式输出
     */
    private void sendSummarySubResult(DefaultAutoAgentExecuteStrategyFactory.DynamicContext dynamicContext, 
                                     String subType, String content, String sessionId) {
        AutoAgentExecuteResultEntity result = AutoAgentExecuteResultEntity.createSummarySubResult(
                subType, content, sessionId);
        sendSseResult(dynamicContext, result);
    }
    
    /**
     * 发送完成标识到流式输出
     */
    private void sendCompleteResult(DefaultAutoAgentExecuteStrategyFactory.DynamicContext dynamicContext, String sessionId) {
        AutoAgentExecuteResultEntity result = AutoAgentExecuteResultEntity.createCompleteResult(sessionId);
        sendSseResult(dynamicContext, result);
        log.info("✅ 已发送完成标识");
    }
    
    /**
     * 检测总结部分标识
     */
    private String detectSummarySection(String content) {
        if (content.contains("已完成的工作") || content.contains("完成的工作") || content.contains("工作内容和成果")) {
            return "completed_work";
        } else if (content.contains("未完成的原因") || content.contains("未完成原因")) {
            return "incomplete_reasons";
        } else if (content.contains("关键因素") || content.contains("完成的关键因素")) {
            return "key_factors";
        } else if (content.contains("执行效率") || content.contains("执行效率和质量")) {
            return "efficiency_quality";
        } else if (content.contains("完成剩余任务的建议") || content.contains("建议") || content.contains("优化建议") || content.contains("经验总结")) {
            return "suggestions";
        } else if (content.contains("整体执行效果") || content.contains("评估")) {
            return "evaluation";
        }
        return null;
    }

}
