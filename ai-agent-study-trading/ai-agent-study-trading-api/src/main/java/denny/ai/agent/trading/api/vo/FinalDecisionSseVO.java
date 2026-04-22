package denny.ai.agent.trading.api.vo;

import lombok.Builder;
import lombok.Data;

/**
 * 最终交易决策 SSE 事件数据。
 */
@Data
@Builder
public class FinalDecisionSseVO {

    /**
     * 交易决策
     */
    private String decision;

    /**
     * 置信度
     */
    private String confidence;

    /**
     * 综合评分 (1-5)
     */
    private Double overallRating;

    /**
     * 决策理由
     */
    private String reasoning;

    /**
     * 建议操作
     */
    private String suggestedAction;

    /**
     * 建议仓位比例
     */
    private Double suggestedPositionRatio;

    /**
     * 止损价格
     */
    private String stopLossPrice;

    /**
     * 止盈价格
     */
    private String takeProfitPrice;

    /**
     * 警告信息
     */
    private java.util.List<String> warnings;
}
