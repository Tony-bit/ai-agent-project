package denny.ai.agent.trading.api.vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import denny.ai.agent.trading.api.vo.payload.TargetEchoPayload;

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

    /**
     * LLM confidence, 0.0 to 1.0.
     */
    private Double confidence;

    /**
     * Structured news themes identified by the news analyst.
     */
    private List<NewsThemeVO> newsThemes;

    /**
     * LLM deduplicated event list. Each event groups semantically equivalent news.
     */
    private List<NewsEventVO> deduplicatedEvents;

    /**
     * Structured risk warnings identified by the news analyst.
     */
    private List<NewsRiskWarningVO> riskWarnings;

    /**
     * Data quality note, including duplicate or missing-data observations.
     */
    private String dataQuality;

    /**
     * News item ids that were enhanced with full text or authoritative verification.
     */
    private List<Integer> enhancedSourceNewsIds;

    private TargetEchoPayload targetEcho;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class NewsThemeVO {

        private String theme;

        private String sentiment;

        private String impactLevel;

        private List<Integer> evidenceIds;

        private List<Integer> enhancedSourceNewsIds;

        private String evidenceLevel;

        private String evidenceQuality;

        private String reason;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class NewsEventVO {

        private String eventType;

        private String eventTitle;

        private String sentiment;

        private String impactLevel;

        private List<Integer> sourceNewsIds;

        private List<Integer> enhancedSourceNewsIds;

        private String evidenceLevel;

        private String evidenceQuality;

        private String summary;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class NewsRiskWarningVO {

        private String risk;

        private String impactLevel;

        private List<Integer> evidenceIds;

        private List<Integer> enhancedSourceNewsIds;

        private String evidenceLevel;

        private String evidenceQuality;

        private String reason;
    }
}
