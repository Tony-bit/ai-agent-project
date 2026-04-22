package denny.ai.agent.trading.api.vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 股票分析请求值对象。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class StockAnalysisRequestVO {

    /**
     * 股票代码，如 NVDA、AAPL
     */
    private String ticker;

    /**
     * 分析日期，格式 yyyy-MM-dd
     */
    private String tradeDate;

    /**
     * 启用的分析师类型列表，null 表示使用默认全部
     */
    private List<AnalystTypeEnum> selectedAnalysts;

    /**
     * 辩论轮次，默认 2
     */
    @Builder.Default
    private int maxDebateRounds = 2;

    /**
     * 会话 ID，用于关联历史上下文
     */
    private String sessionId;

    /**
     * 快速构建请求
     */
    public static StockAnalysisRequestVO of(String ticker) {
        return StockAnalysisRequestVO.builder()
                .ticker(ticker)
                .maxDebateRounds(2)
                .build();
    }

    /**
     * 快速构建请求（带日期）
     */
    public static StockAnalysisRequestVO of(String ticker, String tradeDate) {
        return StockAnalysisRequestVO.builder()
                .ticker(ticker)
                .tradeDate(tradeDate)
                .maxDebateRounds(2)
                .build();
    }
}
