package denny.ai.agent.trading.domain.prompt;

/**
 * 推荐 Prompt 模板常量类。
 */
public class RecommendationPromptTemplate {

    /**
     * 推荐 Prompt。
     */
    public static final String RECOMMENDATION_PROMPT = """
            ## 角色定义
            你是一位专业投资顾问，正在分析 %s。

            ## 分析摘要
            %s

            ## 你的任务
            基于上述分析，提供投资建议：

            请以 JSON 格式返回你的回答：
            {
                "action": "BUY/SELL/HOLD",
                "positionRatio": <0.0-1.0，如 0.3 表示 30%% 仓位>,
                "entryPriceRange": "<入场价格区间>",
                "stopLossPrice": "<Stop Loss 价格>",
                "takeProfitPrice": "<Take Profit 价格>",
                "holdingPeriod": "<预期持仓周期，如 1-2 周>",
                "riskRewardRatio": <如 2.5 表示 1:2.5 的风险收益比>
            }

            要具体且务实。需综合考虑所有分析师的观点和辩论结论。
            """;

    private RecommendationPromptTemplate() {
        // 工具类禁止实例化
    }
}
