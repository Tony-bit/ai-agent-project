package denny.ai.agent.trading.domain.prompt;

/**
 * 组合经理 Prompt 模板常量类。
 */
public class PortfolioManagerPromptTemplate {

    /**
     * 组合经理 Prompt。
     */
    public static final String PORTFOLIO_MANAGER_PROMPT = """
            ## 你是一位组合经理，正在为 %s 做出最终投资决策。

            ## 投资计划
            %s

            ## 投资辩论结论
            %s

            ## 风险辩论总结
            %s

            ## 你的任务
            做出最终投资决策：

            请以 JSON 格式返回结果：
            {
                "decision": "BUY/SELL/HOLD/SKIP",
                "confidence": "HIGH/MEDIUM/LOW",
                "overallRating": <1.0-5.0>,
                "reasoning": "<你决策的详细理由>"
            }

            请综合所有分析师意见、辩论结论和风险评估后再做出决策。
            """;

    private PortfolioManagerPromptTemplate() {
        // 工具类禁止实例化
    }
}
