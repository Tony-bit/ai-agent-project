package denny.ai.agent.trading.infra.provider;

import denny.ai.agent.trading.api.vo.FundamentalDataVO;
import denny.ai.agent.trading.infra.calculator.TechnicalIndicatorCalculator;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 使用真实 Tushare API 验证基本面字段权限和映射。 */
class TushareFundamentalDataIntegrationTest {

    @Test
    void getFundamentalData_realApi_populatesSupportedFields() {
        String token = System.getenv("TUSHARE_TOKEN");
        Assumptions.assumeTrue(token != null && !token.isBlank(),
                "需要 TUSHARE_TOKEN 才能运行真实 Tushare 集成测试");
        TushareStockDataProvider provider = new TushareStockDataProvider(
                new TushareApiClient(token),
                new TechnicalIndicatorCalculator(),
                (keyword, limit) -> List.of());

        FundamentalDataVO result = provider.getFundamentalData("600285.SH");

        assertAll(
                () -> assertNotNull(result.getPe(), "pe"),
                () -> assertNotNull(result.getPeTtm(), "peTtm"),
                () -> assertNotNull(result.getPb(), "pb"),
                () -> assertNotNull(result.getPsRatio(), "psRatio"),
                () -> assertNotNull(result.getTotalMv(), "totalMv"),
                () -> assertNotNull(result.getCircMv(), "circMv"),
                () -> assertNotNull(result.getValuationTradeDate(), "valuationTradeDate"),
                () -> assertNotNull(result.getPegRatio(), "pegRatio"),
                () -> assertNotNull(result.getRoe(), "roe"),
                () -> assertNotNull(result.getRoa(), "roa"),
                () -> assertNotNull(result.getGrossMargin(), "grossMargin"),
                () -> assertNotNull(result.getNetMargin(), "netMargin"),
                () -> assertNotNull(result.getRevenue(), "revenue"),
                () -> assertNotNull(result.getNetIncome(), "netIncome"),
                () -> assertNotNull(result.getTotalAssets(), "totalAssets"),
                () -> assertNotNull(result.getTotalDebt(), "totalDebt"),
                () -> assertNotNull(result.getBookValuePerShare(), "bookValuePerShare"),
                () -> assertNotNull(result.getEps(), "eps"),
                () -> assertNotNull(result.getRevenueGrowth(), "revenueGrowth"),
                () -> assertNotNull(result.getEarningsGrowth(), "earningsGrowth"),
                () -> assertNotNull(result.getNetIncomeGrowth(), "netIncomeGrowth"),
                () -> assertNotNull(result.getOperatingCashFlow(), "operatingCashFlow"),
                () -> assertNotNull(result.getFreeCashFlow(), "freeCashFlow"),
                () -> assertNotNull(result.getDebtToAssets(), "debtToAssets"),
                () -> assertNotNull(result.getCurrentRatio(), "currentRatio"),
                () -> assertNotNull(result.getDividendYield(), "dividendYield"));
        assertTrue(result.getTotalAssets().compareTo(result.getTotalDebt()) > 0,
                "总资产应大于负债合计");
        assertEquals(result.getNetIncomeGrowth(), result.getEarningsGrowth(),
                "两个既有净利润增长字段应使用同一口径");
        assertTrue(result.getFreeCashFlow().compareTo(result.getOperatingCashFlow()) <= 0,
                "扣除资本开支后的自由现金流不应大于经营现金流");
    }
}
