package denny.ai.agent.trading.api.vo;

import lombok.Builder;
import lombok.Data;

/**
 * 辩论轮次 SSE 事件数据。
 */
@Data
@Builder
public class DebateRoundSseVO {

    /**
     * 当前轮次
     */
    private Integer round;

    /**
     * 最大轮次
     */
    private Integer maxRounds;

    /**
     * 多头观点
     */
    private String bullOpinion;

    /**
     * 空头观点
     */
    private String bearOpinion;

    /**
     * 研究主管判断
     */
    private String judgeDecision;

    /**
     * 综合评分 (-2 ~ 2)
     */
    private Double overallScore;
}
