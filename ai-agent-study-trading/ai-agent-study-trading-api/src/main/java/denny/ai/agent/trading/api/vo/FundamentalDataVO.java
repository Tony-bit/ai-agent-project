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
    private Double peRatio;
    private Double pbRatio;
    private Double psRatio;
    private Double pegRatio;

    // === 盈利能力 ===
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

    // === 增长指标 ===
    private Double revenueGrowth;
    private Double earningsGrowth;
    private Double netIncomeGrowth;

    // === 现金流 ===
    private BigDecimal operatingCashFlow;
    private BigDecimal freeCashFlow;

    // === 偿债能力 ===
    private Double debtToEquity;
    private Double currentRatio;

    // === 股东回报 ===
    private BigDecimal marketCap;
    private Double dividendYield;
}
