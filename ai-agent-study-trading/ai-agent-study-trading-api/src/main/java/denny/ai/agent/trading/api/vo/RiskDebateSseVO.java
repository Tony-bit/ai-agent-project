package denny.ai.agent.trading.api.vo;

import lombok.Builder;
import lombok.Data;

/**
 * 风控辩论 SSE 事件数据。
 */
@Data
@Builder
public class RiskDebateSseVO {

    /**
     * 当前轮次
     */
    private Integer round;

    /**
     * 激进风控意见
     */
    private String aggressiveOpinion;

    /**
     * 保守风控意见
     */
    private String conservativeOpinion;

    /**
     * 中性风控意见
     */
    private String neutralOpinion;

    /**
     * 综合风险等级
     */
    private String riskLevel;

    /**
     * 综合风险评分 (1-5)
     */
    private Integer riskScore;
}
