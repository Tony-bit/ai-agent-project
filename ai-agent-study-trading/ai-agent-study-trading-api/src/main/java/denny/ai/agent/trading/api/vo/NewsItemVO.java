package denny.ai.agent.trading.api.vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 新闻条目值对象。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class NewsItemVO {

    /**
     * 新闻标题
     */
    private String title;

    /**
     * 新闻来源，如 Reuters、Bloomberg
     */
    private String source;

    /**
     * 发布时间，格式 yyyy-MM-dd HH:mm
     */
    private String publishTime;

    /**
     * 新闻摘要
     */
    private String summary;

    /**
     * 新闻 URL
     */
    private String url;

    /**
     * Optional full article content. Empty when only title and summary are available.
     */
    private String content;

    /**
     * Whether full article content has been fetched.
     */
    private Boolean fullTextFetched;

    /**
     * Quality of fetched content, such as insufficient, usable, confirmed, or conflicting.
     */
    private String contentQuality;

    /**
     * Source reliability label, such as mainstream_media or authoritative.
     */
    private String sourceReliability;

    /**
     * Evidence depth for this item, such as summary, full_text, or authoritative.
     */
    private String evidenceLevel;

    /**
     * Evidence quality for this item, such as insufficient, usable, confirmed, or conflicting.
     */
    private String evidenceQuality;

    /**
     * 相关股票代码列表
     */
    private String[] relatedTickers;

    /**
     * 情感得分，-1（负面）到 1（正面）
     */
    private Double sentimentScore;
}
