package denny.ai.agent.trading.api.vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/**
 * 股票基本信息值对象。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class StockInfoVO {

    /**
     * 股票代码，如 NVDA
     */
    private String ticker;

    /**
     * 股票名称，如 NVIDIA Corporation
     */
    private String name;

    /**
     * 交易所，如 NASDAQ
     */
    private String exchange;

    /**
     * 当前价格
     */
    private BigDecimal currentPrice;

    /**
     * 市盈率
     */
    private Double peRatio;

    /**
     * 市净率
     */
    private Double pbRatio;

    /**
     * 市值（单位：十亿美元）
     */
    private BigDecimal marketCap;

    /**
     * 日成交量
     */
    private Long volume;

    /**
     * 52周最高价
     */
    private BigDecimal week52High;

    /**
     * 52周最低价
     */
    private BigDecimal week52Low;
}
