package denny.ai.agent.trading.domain.vo;

import denny.ai.agent.trading.api.vo.FundamentalReportVO;
import denny.ai.agent.trading.api.vo.NewsReportVO;
import denny.ai.agent.trading.api.vo.SentimentReportVO;
import denny.ai.agent.trading.api.vo.StockInfoVO;
import denny.ai.agent.trading.api.vo.TechnicalReportVO;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 交易上下文值对象。
 * <p>
 * 贯穿整个交易 Agent 执行流程，承载各阶段的分析结果。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TradingContextVO {

    /**
     * 股票基本信息
     */
    private StockInfoVO stockInfo;

    /**
     * 基本面分析报告（nullable）
     */
    private FundamentalReportVO fundamentalReport;

    /**
     * 技术面分析报告（nullable）
     */
    private TechnicalReportVO technicalReport;

    /**
     * 情绪面分析报告（nullable）
     */
    private SentimentReportVO sentimentReport;

    /**
     * 新闻面分析报告（nullable）
     */
    private NewsReportVO newsReport;

    /**
     * 投资辩论结果
     */
    private InvestmentDebateVO investmentDebate;

    /**
     * 投资计划
     */
    private InvestmentPlanVO investmentPlan;

    /**
     * 风险辩论结果
     */
    private RiskDebateVO riskDebate;

    /**
     * 最终交易决策
     */
    private FinalTradeDecisionVO finalDecision;

    /**
     * 创建空的交易上下文
     */
    public static TradingContextVO empty() {
        return TradingContextVO.builder().build();
    }

    /**
     * 投资辩论值对象（多空双方观点汇总）。
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class InvestmentDebateVO {
        /**
         * 当前辩论轮次
         */
        private int currentRound;

        /**
         * 最大辩论轮次
         */
        private int maxRounds;

        /**
         * 多头研究员论点历史
         */
        @Builder.Default
        private java.util.List<String> bullHistory = new java.util.ArrayList<>();

        /**
         * 空头研究员论点历史
         */
        @Builder.Default
        private java.util.List<String> bearHistory = new java.util.ArrayList<>();

        /**
         * 完整辩论历史
         */
        @Builder.Default
        private java.util.List<String> history = new java.util.ArrayList<>();

        /**
         * 研究主管判断结果
         */
        private String judgeDecision;

        /**
         * 当前多头观点摘要
         */
        private String bullOpinion;

        /**
         * 当前空头观点摘要
         */
        private String bearOpinion;

        /**
         * 最新发言者（BULL/BEAR/RESEARCH_MANAGER）
         */
        private String latestSpeaker;

        /**
         * 综合评分，-2~2（负=偏空，正=偏多）
         */
        private Double overallScore;

        /**
         * 辩论结论
         */
        private String conclusion;

        /**
         * 是否需要继续辩论
         */
        private boolean needMoreDebate;

        /**
         * 添加多头论点
         */
        public void addBullArgument(String argument) {
            if (this.bullHistory == null) {
                this.bullHistory = new java.util.ArrayList<>();
            }
            this.bullHistory.add(argument);
            this.bullOpinion = argument;
        }

        /**
         * 添加空头论点
         */
        public void addBearArgument(String argument) {
            if (this.bearHistory == null) {
                this.bearHistory = new java.util.ArrayList<>();
            }
            this.bearHistory.add(argument);
            this.bearOpinion = argument;
        }

        /**
         * 添加到完整历史
         */
        public void addToHistory(String entry) {
            if (this.history == null) {
                this.history = new java.util.ArrayList<>();
            }
            this.history.add(entry);
        }

        /**
         * 推进辩论轮次
         */
        public void nextRound() {
            this.currentRound++;
        }

        /**
         * 判断辩论是否结束
         */
        public boolean isDebateComplete() {
            return this.currentRound >= this.maxRounds || !this.needMoreDebate;
        }

        /**
         * 获取总交锋次数（多头论点 + 空头论点之和）
         */
        public int getTotalExchangeCount() {
            int bullCount = (this.bullHistory == null) ? 0 : this.bullHistory.size();
            int bearCount = (this.bearHistory == null) ? 0 : this.bearHistory.size();
            return bullCount + bearCount;
        }

        /**
         * 设置最新发言者
         */
        public void setLatestSpeaker(String speaker) {
            this.latestSpeaker = speaker;
        }

        /**
         * 获取最新发言者
         */
        public String getLatestSpeaker() {
            return this.latestSpeaker;
        }

        /**
         * 创建新的辩论
         */
        public static InvestmentDebateVO createNew(int maxRounds) {
            return InvestmentDebateVO.builder()
                    .currentRound(0)
                    .maxRounds(maxRounds)
                    .bullHistory(new java.util.ArrayList<>())
                    .bearHistory(new java.util.ArrayList<>())
                    .history(new java.util.ArrayList<>())
                    .needMoreDebate(true)
                    .build();
        }
    }

    /**
     * 投资计划值对象。
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class InvestmentPlanVO {
        /**
         * 建议操作（买入/卖出/持有）
         */
        private String action;

        /**
         * 建议仓位比例
         */
        private Double positionRatio;

        /**
         * 入场价格区间
         */
        private String entryPriceRange;

        /**
         * 止损价格
         */
        private String stopLossPrice;

        /**
         * 止盈价格
         */
        private String takeProfitPrice;

        /**
         * 持仓周期
         */
        private String holdingPeriod;

        /**
         * 风险收益比
         */
        private Double riskRewardRatio;
    }

    /**
     * 风险辩论值对象。
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class RiskDebateVO {
        /**
         * 最大风控辩论轮次
         */
        private int maxRounds;

        /**
         * 最新发言者（AGGRESSIVE/CONSERVATIVE/NEUTRAL）
         */
        private String latestSpeaker;

        /**
         * 识别到的风险项
         */
        private java.util.List<String> riskItems;

        /**
         * 风险等级（低/中/高）
         */
        private String riskLevel;

        /**
         * 缓解措施
         */
        private java.util.List<String> mitigations;

        /**
         * 综合风险评分，1-5
         */
        private Integer riskScore;

        /**
         * 激进分析师历史意见
         */
        @Builder.Default
        private java.util.List<String> aggressiveHistory = new java.util.ArrayList<>();

        /**
         * 保守分析师历史意见
         */
        @Builder.Default
        private java.util.List<String> conservativeHistory = new java.util.ArrayList<>();

        /**
         * 中性分析师历史意见
         */
        @Builder.Default
        private java.util.List<String> neutralHistory = new java.util.ArrayList<>();

        /**
         * 组合经理最终决策
         */
        private String judgeDecision;

        /**
         * 经调整后的计划
         */
        private InvestmentPlanVO adjustedPlan;

        /**
         * 获取总交锋次数（激进 + 保守 + 中性历史之和）
         */
        public int getTotalExchangeCount() {
            int aggressiveCount = (this.aggressiveHistory == null) ? 0 : this.aggressiveHistory.size();
            int conservativeCount = (this.conservativeHistory == null) ? 0 : this.conservativeHistory.size();
            int neutralCount = (this.neutralHistory == null) ? 0 : this.neutralHistory.size();
            return aggressiveCount + conservativeCount + neutralCount;
        }
    }

    /**
     * 最终交易决策值对象。
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class FinalTradeDecisionVO {
        /**
         * 决策（BUY/SELL/HOLD/SKIP）
         */
        private String decision;

        /**
         * 置信度（高/中/低）
         */
        private String confidence;

        /**
         * 综合评分，1-5
         */
        private Double overallRating;

        /**
         * 决策理由
         */
        private String reasoning;

        /**
         * 警告信息（如果有）
         */
        private java.util.List<String> warnings;
    }
}
