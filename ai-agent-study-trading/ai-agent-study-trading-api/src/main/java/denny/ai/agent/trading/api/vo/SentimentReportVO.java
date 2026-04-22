package denny.ai.agent.trading.api.vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 情绪面分析报告值对象。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SentimentReportVO {

    /**
     * 评分 1-5
     */
    private Integer rating;

    /**
     * 情绪得分，-1~1
     */
    private Double sentimentScore;

    /**
     * 主要情绪
     */
    private List<String> keySentiments;

    /**
     * 分析总结
     */
    private String summary;
}
