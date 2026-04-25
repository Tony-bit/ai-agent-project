package denny.ai.agent.trading.api.vo;

import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;

/**
 * 分析师报告 SSE 事件数据。
 */
@Data
@Builder
public class AnalystReportSseVO {

    /**
     * 分析师类型
     */
    private String analystType;

    /**
     * 评级 (1-5)
     */
    private Integer rating;

    /**
     * 摘要
     */
    private String summary;

    /**
     * 置信度
     */
    private String confidence;

    /**
     * 主要观点
     */
    private String keyViewpoints;

    /**
     * 风险提示
     */
    private String riskWarnings;
}
