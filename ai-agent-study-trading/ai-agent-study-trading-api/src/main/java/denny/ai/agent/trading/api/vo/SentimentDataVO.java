package denny.ai.agent.trading.api.vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

/**
 * 情绪数据值对象。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SentimentDataVO {

    /**
     * 综合情绪得分，-1（极度负面）到 1（极度正面）
     */
    private Double overallScore;

    /**
     * 社交媒体情绪得分
     */
    private Double socialMediaScore;

    /**
     * 新闻情绪得分
     */
    private Double newsScore;

    /**
     * 分析师评级情绪
     */
    private Double analystScore;

    /**
     * 短期情绪（7天）
     */
    private Double shortTermScore;

    /**
     * 中期情绪（30天）
     */
    private Double mediumTermScore;

    /**
     * 长期情绪（90天）
     */
    private Double longTermScore;

    /**
     * 看涨比例，0-1
     */
    private Double bullRatio;

    /**
     * 看跌比例，0-1
     */
    private Double bearRatio;

    /**
     * 社交媒体讨论热度（相对值）
     */
    private Double socialBuzz;

    /**
     * 恐惧贪婪指数，0-100
     */
    private Integer fearGreedIndex;

    /**
     * 机构持仓变化，positive=增持，negative=减持
     */
    private Double institutionalHoldingChange;

    /**
     * 主要社交平台情绪明细
     */
    private Map<String, Double> platformSentiments;
}
