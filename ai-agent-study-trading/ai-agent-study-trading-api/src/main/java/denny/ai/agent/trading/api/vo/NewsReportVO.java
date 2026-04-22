package denny.ai.agent.trading.api.vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 新闻面分析报告值对象。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class NewsReportVO {

    /**
     * 评分 1-5
     */
    private Integer rating;

    /**
     * 新闻条目列表
     */
    private List<NewsItemVO> newsItems;

    /**
     * 整体情绪描述
     */
    private String overallSentiment;

    /**
     * 分析总结
     */
    private String summary;
}
