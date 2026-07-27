package denny.ai.agent.trading.domain.guard;

import denny.ai.agent.trading.api.vo.FundamentalDataVO;
import denny.ai.agent.trading.api.vo.FundamentalReportVO;
import denny.ai.agent.trading.api.vo.StockInfoVO;
import denny.ai.agent.trading.domain.vo.TradingContextVO;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DataSanityGuardTest {

    private final DataSanityGuard guard = new DataSanityGuard();

    @Test
    void treatsTushareRatioAsPercentagePoints() {
        TradingContextVO context = context("保险", FundamentalDataVO.builder()
                .roe(12.5)
                .grossMargin(20.0)
                .debtToAssets(65.0)
                .build());

        assertTrue(guard.check(context).isEmpty(), "12.5 must mean 12.5%, not 1250%");
    }

    @Test
    void supportsChineseIndustryClassification() {
        TradingContextVO context = context("保险", FundamentalDataVO.builder()
                .roe(67.65)
                .grossMargin(45.0)
                .debtToAssets(5.0)
                .build());

        List<String> warnings = guard.check(context);

        assertFalse(warnings.isEmpty());
        assertTrue(warnings.stream().anyMatch(message -> message.contains("保险行业")));
        assertTrue(warnings.stream().allMatch(message -> !message.contains("TARGET_MISMATCH")),
                "industry heuristics are warnings, not identity validation");
    }

    private TradingContextVO context(String industry, FundamentalDataVO rawData) {
        return TradingContextVO.builder()
                .stockInfo(StockInfoVO.builder()
                        .ticker("601318")
                        .name("中国平安")
                        .industry(industry)
                        .build())
                .fundamentalReport(FundamentalReportVO.builder().rawData(rawData).build())
                .build();
    }
}
