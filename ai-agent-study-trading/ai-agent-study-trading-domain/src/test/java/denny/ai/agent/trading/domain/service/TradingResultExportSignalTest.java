package denny.ai.agent.trading.domain.service;

import denny.ai.agent.trading.domain.model.valobj.TradingResultVO;
import denny.ai.agent.trading.domain.signal.V2DecisionSignalFactory;
import denny.ai.agent.trading.domain.vo.TradingContextVO;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TradingResultExportSignalTest {

    @Test
    void unavailableRatingsRenderReasonInsteadOfNullZeroOrSyntheticNeutral() {
        TradingContextVO context = TradingContextVO.empty();
        context.setFundamentalReport(denny.ai.agent.trading.api.vo.FundamentalReportVO.builder()
                .summary("正文").build());
        context.setDecisionSignals(new V2DecisionSignalFactory().fromReports(context));
        TradingResultVO result = TradingResultVO.from(context);

        String markdown = new TradingResultExportService().renderMarkdown(result);

        assertTrue(markdown.contains("N/A（fundamental rating is missing）"));
        assertFalse(markdown.contains("null/5"));
        assertFalse(markdown.contains("0/5"));
        assertFalse(markdown.contains("3/5"));
    }
}
