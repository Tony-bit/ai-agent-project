package denny.ai.agent.domain.service.auto.step.routing;

import denny.ai.agent.domain.model.entity.IntentFewshotSample;

import java.util.List;

/**
 * 意图识别 Prompt 构建器
 *
 * @author denny
 * 2026/5/10
 */
public class IntentRoutingPrompt {

    public static final String SYSTEM_PROMPT_TEMPLATE = """
        ## 角色
        你是一个专业的意图识别助手，负责分析用户输入并将其分类到以下8种意图之一。

        ## 意图分类（共 8 种）
        1. STOCK_ANALYSIS: 用户询问股票、基金、期货、市场行情等技术分析、基本面分析、交易建议
        2. PE_REASONING: 用户提出逻辑推理、问题分析、方案设计等需要深度思考的任务
        3. PE_CALCULATION: 用户提出数学计算、数据处理、统计建模等需要精确计算的任务
        4. PE_RETRIEVAL: 用户查询知识库、文档检索、信息汇总等知识类任务
        5. INSPECTION: 用户请求系统巡检、健康检查、状态监控等运维任务
        6. GENERAL_CHAT: 闲聊、问候、记忆查询（如询问个人偏好、之前聊过的内容）、无法归类的对话
        7. AMBIGUOUS: 意图模糊或复合语义，需要进一步澄清
        8. UNKNOWN: 无法明确判断

        ## 置信度
        - HIGH: 意图非常明确，有明显的关键词或上下文支撑
        - MEDIUM: 较明确，但存在一定模糊性
        - LOW: 信号较弱，仅凭当前输入难以明确判断

        ## 槽位说明
        - baseSlot: 所有意图通用槽位（topic: 主题, sentiment: 情感 positive/negative/neutral）
        - intentSpecificSlots: 意图专属槽位
          - STOCK_ANALYSIS: {stockCode(股票代码), stockQueryType(查询类型), timeRange(时间范围), exchange(交易所)}

        ## 历史上下文（最近对话）
        %s

        ## 输出要求
        请严格按以下JSON格式输出，不要包含任何额外内容：
        {"intent": "意图枚举值", "confidence": "HIGH|MEDIUM|LOW", "reasoning": "判断理由简述",
         "baseSlot": {"topic": "主题", "sentiment": "positive|negative|neutral"},
         "intentSpecificSlots": {...}}
        """;

    /**
     * 构建意图识别 Prompt（无 Few-Shot）
     *
     * @param userMessage     当前用户消息
     * @param historyMessages 历史消息列表（最近N条，每条格式：role: content）
     */
    public static String buildPrompt(String userMessage, List<String> historyMessages) {
        return buildPrompt(userMessage, historyMessages, List.of());
    }

    /**
     * 构建意图识别 Prompt（支持动态 Few-Shot）
     *
     * @param userMessage     当前用户消息
     * @param historyMessages 历史消息列表
     * @param fewshotSamples  Few-Shot 样本列表（从 PGvector 检索的 Top-K）
     */
    public static String buildPrompt(String userMessage, List<String> historyMessages,
                                     List<IntentFewshotSample> fewshotSamples) {
        String historySection = buildHistorySection(historyMessages);
        String prompt = String.format(SYSTEM_PROMPT_TEMPLATE, historySection);

        if (fewshotSamples != null && !fewshotSamples.isEmpty()) {
            StringBuilder exampleSection = new StringBuilder("\n## 参考示例\n");
            for (IntentFewshotSample sample : fewshotSamples) {
                exampleSection.append("用户: ").append(sample.getQueryText()).append("\n");
                exampleSection.append("输出: ").append(sample.getExampleJson()).append("\n\n");
            }
            prompt = prompt + exampleSection;
        }

        return prompt + "用户: " + userMessage + "\n输出:";
    }

    private static String buildHistorySection(List<String> historyMessages) {
        if (historyMessages == null || historyMessages.isEmpty()) {
            return "（无历史对话）";
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < historyMessages.size(); i++) {
            sb.append(i + 1).append(". ").append(historyMessages.get(i)).append("\n");
        }
        return sb.toString();
    }
}
