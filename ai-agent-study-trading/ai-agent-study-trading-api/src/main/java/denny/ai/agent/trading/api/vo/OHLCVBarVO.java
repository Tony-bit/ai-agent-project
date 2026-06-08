package denny.ai.agent.trading.api.vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/**
 * K线（OHLCV）数据值对象。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OHLCVBarVO {

    /**
     * 交易日期，格式 yyyy-MM-dd
     */
    private String date;

    /**
     * 开盘价
     */
    private BigDecimal open;

    /**
     * 最高价
     */
    private BigDecimal high;

    /**
     * 最低价
     */
    private BigDecimal low;

    /**
     * 收盘价
     */
    private BigDecimal close;

    /**
     * 成交量
     */
    private Long volume;

    /**
     * 成交额
     */
    private BigDecimal amount;

    /**
     * 涨跌额
     */
    private BigDecimal change;

    /**
     * 涨跌幅（百分比）
     */
    private Double pctChg;

    /**
     * 调整后收盘价（用于计算指标）
     */
    private BigDecimal adjustedClose;
}
