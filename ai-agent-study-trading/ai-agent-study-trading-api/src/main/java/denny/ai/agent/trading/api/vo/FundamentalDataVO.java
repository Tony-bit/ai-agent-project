package denny.ai.agent.trading.api.vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/**
 * 基本面数据值对象。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FundamentalDataVO {

    // === 估值指标 ===
    @Deprecated(since = "1.0", forRemoval = false)
    private Double peRatio;
    @Deprecated(since = "1.0", forRemoval = false)
    private Double pbRatio;
    private Double psRatio;
    private Double pegRatio;
    private Double pe;
    private Double peTtm;
    private Double pb;
    /** Tushare 原始单位：万元。 */
    private BigDecimal totalMv;
    /** Tushare 原始单位：万元。 */
    private BigDecimal circMv;
    private String valuationTradeDate;

    // === 盈利能力（百分数值，例如 12.5 表示 12.5%） ===
    private Double roe;
    private Double roa;
    private Double grossMargin;
    private Double netMargin;

    // === 财务数据 ===
    private BigDecimal revenue;
    private BigDecimal netIncome;
    private BigDecimal totalAssets;
    private BigDecimal totalDebt;
    private BigDecimal bookValuePerShare;
    private BigDecimal eps;
    private BigDecimal dps;

    // === 增长指标（百分数值） ===
    private Double revenueGrowth;
    private Double earningsGrowth;
    private Double netIncomeGrowth;

    // === 现金流 ===
    private BigDecimal operatingCashFlow;
    private BigDecimal freeCashFlow;

    // === 偿债能力 ===
    /** 资产负债率（百分数值），对应 Tushare debt_to_assets。 */
    private Double debtToAssets;
    private Double currentRatio;

    // === 股东回报 ===
    @Deprecated(since = "1.0", forRemoval = false)
    private BigDecimal marketCap;
    private Double dividendYield;

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
