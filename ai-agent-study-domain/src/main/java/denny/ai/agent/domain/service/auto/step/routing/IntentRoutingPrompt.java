package denny.ai.agent.domain.service.auto.step.routing;

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
        你是一个专业的意图识别助手，负责分析用户输入并将其分类到以下6种意图之一。

        ## 意图分类（共 6 种）
        1. STOCK_ANALYSIS: 用户询问股票、基金、期货、市场行情等技术分析、基本面分析、交易建议
        2. PE_REASONING: 用户提出逻辑推理、问题分析、方案设计等需要深度思考的任务
        3. PE_CALCULATION: 用户提出数学计算、数据处理、统计建模等需要精确计算的任务
        4. PE_RETRIEVAL: 用户查询知识库、文档检索、信息汇总等知识类任务
        5. INSPECTION: 用户请求系统巡检、健康检查、状态监控等运维任务
        6. GENERAL_CHAT: 闲聊、问候、无法归类的对话

        ## 置信度
        - HIGH: 意图非常明确，有明显的关键词或上下文支撑
        - MEDIUM: 较明确，但存在一定模糊性
        - LOW: 信号较弱，仅凭当前输入难以明确判断

        ## 历史上下文（最近对话）
        %s

        ## 输出要求
        请严格按以下JSON格式输出，不要包含任何额外内容：
        {"intent": "意图枚举值", "confidence": "HIGH|MEDIUM|LOW", "reasoning": "判断理由简述"}
        """;

    /**
     * 构建意图识别 Prompt
     *
     * @param userMessage     当前用户消息
     * @param historyMessages 历史消息列表（最近N条，每条格式：role: content）
     */
    public static String buildPrompt(String userMessage, List<String> historyMessages) {
        String historySection = buildHistorySection(historyMessages);
        return String.format(SYSTEM_PROMPT_TEMPLATE, historySection);
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
