package denny.ai.agent.trading.api.vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/**
 * 技术指标数据值对象。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TechnicalIndicatorsVO {

    /**
     * 股票代码
     */
    private String ticker;

    // === 均线 ===
    private BigDecimal ma5;
    private BigDecimal ma10;
    private BigDecimal ma20;
    private BigDecimal ma60;
    private BigDecimal ma120;

    // === MACD ===
    private BigDecimal macd;
    private BigDecimal macdSignal;
    private BigDecimal macdHistogram;

    // === RSI ===
    private Double rsi6;
    private Double rsi12;
    private Double rsi24;

    // === KDJ ===
    private Double k;
    private Double d;
    private Double j;

    // === 布林带 ===
    private BigDecimal bollUpper;
    private BigDecimal bollMiddle;
    private BigDecimal bollLower;

    // === 成交量指标 ===
    private Double volumeRatio;
    private BigDecimal volumeMa5;

    // === 其他 ===
    private BigDecimal atr;
    private Double adx;
}
