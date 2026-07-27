package denny.ai.agent.trading.api.vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import denny.ai.agent.trading.api.vo.payload.TargetEchoPayload;

/**
 * 基本面分析报告值对象。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FundamentalReportVO {

    /**
     * 评分 1-5
     */
    private Integer rating;

    /**
     * 主要发现
     */
    private List<String> keyFindings;

    /**
     * 风险提示
     */
    private List<String> riskWarnings;

    /**
     * 分析总结
     */
    private String summary;

    /**
     * 原始财务数据
     */
    private FundamentalDataVO rawData;

    private TargetEchoPayload targetEcho;
}
