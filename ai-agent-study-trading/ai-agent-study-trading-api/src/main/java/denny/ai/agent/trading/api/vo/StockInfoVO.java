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
     * 兼容字段，值优先与 {@link #peTtm} 保持一致。
     */
    @Deprecated(since = "1.0", forRemoval = false)
    private Double peRatio;

    /**
     * 兼容字段，值优先与 {@link #pb} 保持一致。
     */
    @Deprecated(since = "1.0", forRemoval = false)
    private Double pbRatio;

    /**
     * 兼容字段，历史单位为十亿美元，不与 Tushare 万元市值自动换算。
     */
    @Deprecated(since = "1.0", forRemoval = false)
    private BigDecimal marketCap;

    /** 静态市盈率，单位：倍。 */
    private Double pe;

    /** 滚动市盈率，单位：倍。 */
    private Double peTtm;

    /** 市净率，单位：倍。 */
    private Double pb;

    /** 总市值，保留 Tushare 原始单位：万元。 */
    private BigDecimal totalMv;

    /** 流通市值，保留 Tushare 原始单位：万元。 */
    private BigDecimal circMv;

    /** 估值快照交易日，格式 yyyy-MM-dd。 */
    private String valuationTradeDate;

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

    /**
     * Industry classification, e.g. Insurance, Bank, Semiconductor
     */
    private String industry;

    public Double getPeTtm() {
        return peTtm != null ? peTtm : peRatio;
    }

    @Deprecated(since = "1.0", forRemoval = false)
    public Double getPeRatio() {
        return peTtm != null ? peTtm : peRatio;
    }

    public Double getPb() {
        return pb != null ? pb : pbRatio;
    }

    @Deprecated(since = "1.0", forRemoval = false)
    public Double getPbRatio() {
        return pb != null ? pb : pbRatio;
    }
}
