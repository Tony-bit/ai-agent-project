package denny.ai.agent.trading.domain.prompt;

/**
 * 组合经理 Prompt 模板常量类。
 */
public class PortfolioManagerPromptTemplate {

    /**
     * 组合经理 Prompt。
     */
    public static final String PORTFOLIO_MANAGER_PROMPT = """
            You are the portfolio manager making the final investment decision for %s.

            ## Investment Plan
            %s

            ## Investment Debate Conclusion
            %s

            ## Risk Debate Summary
            %s

            ## Your Task
            Make the final investment decision:

            Return your response as JSON:
            {
                "decision": "BUY/SELL/HOLD/SKIP",
                "confidence": "HIGH/MEDIUM/LOW",
                "overallRating": <1.0-5.0>,
                "reasoning": "<detailed reasoning for your decision>"
            }

            Consider all analyst opinions, debate conclusions, and risk assessments before making your decision.
            """;

    private PortfolioManagerPromptTemplate() {
        // 工具类禁止实例化
    }
}
