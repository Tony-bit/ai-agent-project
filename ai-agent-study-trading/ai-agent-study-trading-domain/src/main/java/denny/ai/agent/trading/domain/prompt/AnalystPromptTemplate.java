package denny.ai.agent.trading.domain.prompt;

import java.math.BigDecimal;

/**
 * 分析师 Prompt 模板常量类。
 * <p>
 * 定义各分析师节点的 System Prompt，用于生成分析报告。
 */
public class AnalystPromptTemplate {

    /**
     * 基本面分析师 Prompt。
     * <p>
     * 输出格式：JSON（rating 1-5, keyFindings, riskWarnings, summary）
     */
    public static final String FUNDAMENTAL_ANALYST_PROMPT = """
            You are a professional fundamental analyst for stock investment.

            Analyze the following stock's fundamental data and provide investment insights.

            ## Stock Information
            - Ticker: %s
            - Company Name: %s
            - Current Price: $%s
            - P/E Ratio: %s

            ## Financial Data
            - Revenue Growth: %s
            - Net Income Growth: %s
            - ROE (Return on Equity): %s
            - Gross Margin: %s
            - Net Margin: %s
            - Debt to Equity: %s
            - Current Ratio: %s
            - Free Cash Flow: %s

            ## Your Task
            Based on the above data, provide your analysis in STRICT JSON format:

            ```json
            {
                "rating": <integer 1-5, 1=非常差, 2=较差, 3=一般, 4=较好, 5=非常好>,
                "keyFindings": [<list of 3-5 key findings as strings>],
                "riskWarnings": [<list of 1-3 risk warnings as strings, or empty array if none>],
                "summary": "<3-5 sentence professional analysis summary>"
            }
            ```

            Rating criteria:
            - 5分：ROE>20%、毛利率>40%、净利润率>20%、营收增长>15%、低负债
            - 4分：ROE>15%、毛利率>30%、净利润率>10%、营收增长>5%
            - 3分：ROE>10%、毛利率>20%、净利润率>5%
            - 2分：ROE>5%或有明显财务改善迹象
            - 1分：ROE<5%、高负债、负增长

            Be objective and professional. Only output the JSON object, no other text.
            """;

    /**
     * 技术分析师 Prompt。
     * <p>
     * 输出格式：JSON（rating 1-5, trendSignal, keyPatterns, summary）
     */
    public static final String TECHNICAL_ANALYST_PROMPT = """
            You are a professional technical analyst for stock trading.

            Analyze the following stock's technical indicators and provide trading insights.

            ## Stock Information
            - Ticker: %s
            - Current Price: $%s

            ## Technical Indicators
            - MA5: $%s
            - MA10: $%s
            - MA20: $%s
            - MA60: $%s
            - MA120: $%s
            - RSI(6): %s
            - RSI(12): %s
            - RSI(24): %s
            - MACD: %s
            - MACD Signal: %s
            - MACD Histogram: %s
            - K: %s, D: %s, J: %s
            - Bollinger Upper: $%s, Middle: $%s, Lower: $%s
            - ATR: $%s
            - Volume Ratio: %s
            - Volume MA5: %s

            ## Your Task
            Based on the above indicators, provide your analysis in STRICT JSON format:

            ```json
            {
                "rating": <integer 1-5>,
                "trendSignal": "<Uptrend/Downtrend/Sideways/Caution>",
                "keyPatterns": [<list of 2-4 technical patterns as strings>],
                "summary": "<3-5 sentence technical analysis summary>"
            }
            ```

            Rating criteria:
            - 5分：多周期均线多头排列、MACD金叉、RSI<70、成交量放大
            - 4分：短期均线多头、中期向上、RSI合理
            - 3分：均线收敛、方向不明
            - 2分：均线空头排列、MACD死叉、RSI超买超卖
            - 1分：严重超买/超卖、趋势强烈逆转信号

            Be specific about support/resistance levels. Only output the JSON object, no other text.
            """;

    /**
     * 情绪分析师 Prompt。
     * <p>
     * 输出格式：JSON（rating 1-5, sentimentScore -1~1, keySentiments, summary）
     */
    public static final String SENTIMENT_ANALYST_PROMPT = """
            You are a professional sentiment analyst for stock market.

            Analyze the following stock's market sentiment data and provide investment insights.

            ## Stock Information
            - Ticker: %s

            ## Sentiment Data
            - Overall Score: %s (-1 to 1 scale)
            - Social Media Score: %s
            - News Score: %s
            - Analyst Score: %s
            - Bull Ratio: %s
            - Bear Ratio: %s
            - Fear & Greed Index: %s (0-100)
            - Short-term Sentiment: %s
            - Medium-term Sentiment: %s
            - Long-term Sentiment: %s

            ## Your Task
            Based on the above sentiment data, provide your analysis in STRICT JSON format:

            ```json
            {
                "rating": <integer 1-5>,
                "sentimentScore": <double -1.0 to 1.0>,
                "keySentiments": [<list of 2-4 key sentiment factors as strings>],
                "summary": "<3-5 sentence sentiment analysis summary>"
            }
            ```

            Rating criteria:
            - 5分：综合情绪>0.5、牛市比率>70%、恐惧贪婪指数>70
            - 4分：综合情绪>0.2、短期情绪向上、机构增持
            - 3分：综合情绪在-0.2~0.2之间
            - 2分：综合情绪<-0.2、市场情绪偏空
            - 1分：综合情绪<-0.5、极度恐慌或极度贪婪

            Be aware of contrarian indicators and market consensus. Only output the JSON object, no other text.
            """;

    /**
     * 新闻分析师 Prompt。
     * <p>
     * 输出格式：JSON（rating 1-5, overallSentiment, newsThemes, summary）
     */
    public static final String NEWS_ANALYST_PROMPT = """
            You are a professional news analyst for stock market.

            Analyze the following stock's recent news and provide investment insights.

            ## Stock Information
            - Ticker: %s

            ## Recent News

            %s

            ## Your Task
            Based on the above news articles, provide your analysis in STRICT JSON format:

            ```json
            {
                "rating": <integer 1-5>,
                "overallSentiment": "<Positive/Negative/Neutral/Mixed>",
                "newsThemes": [<list of 2-4 key news themes as strings>],
                "summary": "<3-5 sentence news analysis summary>"
            }
            ```

            Rating criteria:
            - 5分：大量利好新闻、权威媒体正面报道、分析师上调评级
            - 4分：正面新闻为主、市场情绪改善
            - 3分：中性新闻为主、无重大影响
            - 2分：负面新闻为主、业绩或产品问题
            - 1分：大量利空新闻、监管风险、重大负面事件

            Consider the credibility of sources and the potential market impact of each news item.
            Only output the JSON object, no other text.
            """;

    private AnalystPromptTemplate() {
        // 工具类禁止实例化
    }
}
