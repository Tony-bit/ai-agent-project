package denny.ai.agent.trading.domain.prompt;

/**
 * 推荐 Prompt 模板常量类。
 */
public class RecommendationPromptTemplate {

    /**
     * 推荐 Prompt。
     */
    public static final String RECOMMENDATION_PROMPT = """
            ## You are a professional investment advisor analyzing %s.

            ## Analysis Summary
            %s

            ## Your Task
            Based on the analysis above, provide an investment recommendation:

            Return your response as JSON:
            {
                "action": "BUY/SELL/HOLD",
                "positionRatio": <0.0-1.0, e.g., 0.3 means 30%% position>,
                "entryPriceRange": "<price range if buying>",
                "stopLossPrice": "<stop loss price>",
                "takeProfitPrice": "<take profit price>",
                "holdingPeriod": "<expected holding period, e.g., 1-2 weeks>",
                "riskRewardRatio": <e.g., 2.5 means 1:2.5 risk-reward>
            }

            Be specific and realistic. Consider all analyst opinions and debate conclusions.
            """;

    private RecommendationPromptTemplate() {
        // 工具类禁止实例化
    }
}
