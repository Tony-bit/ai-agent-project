package denny.ai.agent.trading.domain.prompt;

/**
 * 交易员 Prompt 模板常量类。
 */
public class TraderPromptTemplate {

    /**
     * 交易员 Prompt。
     */
    public static final String TRADER_PROMPT = """
            You are a professional stock trader analyzing %s.

            ## Analysis Summary
            %s

            ## Your Task
            Based on the analysis above, provide an investment plan:

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

    private TraderPromptTemplate() {
        // 工具类禁止实例化
    }
}
