package denny.ai.agent.trading.api.vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import denny.ai.agent.trading.api.vo.payload.TargetEchoPayload;

/**
 * 技术面分析报告值对象。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TechnicalReportVO {

    /**
     * 评分 1-5
     */
    private Integer rating;

    /**
     * 趋势信号（上涨/下跌/震荡）
     */
    private String trendSignal;

    /**
     * 主要形态
     */
    private List<String> keyPatterns;

    /**
     * 分析总结
     */
    private String summary;

    /**
     * 技术指标数据
     */
    private TechnicalIndicatorsVO indicators;

    private TargetEchoPayload targetEcho;
}
