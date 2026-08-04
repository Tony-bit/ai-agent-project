package denny.ai.agent.trading.api.vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.List;

/**
 * 股票分析请求值对象。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class StockAnalysisRequestVO {

    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    /**
     * 股票代码，如 NVDA、AAPL
     */
    private String ticker;

    /**
     * 路由阶段解析出的股票名称。代码输入场景允许为空。
     */
    private String stockName;

    /**
     * 分析日期，格式 yyyy-MM-dd，默认当天
     */
    @Builder.Default
    private String tradeDate = LocalDate.now().format(DATE_FORMATTER);

    /**
     * 启用的分析师类型列表，null 表示使用默认全部
     */
    @Builder.Default
    private List<AnalystTypeEnum> selectedAnalysts = Arrays.asList(
            AnalystTypeEnum.FUNDAMENTAL,
            AnalystTypeEnum.TECHNICAL,
            AnalystTypeEnum.SENTIMENT,
            AnalystTypeEnum.NEWS
    );

    /**
     * 辩论轮次，默认 2
     */
    @Builder.Default
    private int maxDebateRounds = 2;

    /**
     * 风控辩论轮次，默认 1
     */
    @Builder.Default
    private int maxRiskRounds = 1;

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
                .build();
    }

    /**
     * 快速构建请求（带日期）
     */
    public static StockAnalysisRequestVO of(String ticker, String tradeDate) {
        return StockAnalysisRequestVO.builder()
                .ticker(ticker)
                .tradeDate(tradeDate)
                .build();
    }
}
