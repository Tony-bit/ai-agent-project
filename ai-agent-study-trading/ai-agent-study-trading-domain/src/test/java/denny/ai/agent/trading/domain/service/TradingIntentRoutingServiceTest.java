package denny.ai.agent.trading.domain.service;

import denny.ai.agent.trading.api.vo.ConfidenceEnum;
import denny.ai.agent.trading.api.vo.IntentEnumVO;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class TradingIntentRoutingServiceTest {

    @Test
    void parsesEnhancedIntentRoutingContract() {
        TradingIntentRoutingService service = new TradingIntentRoutingService();
        String response = """
                {
                  "intent": "STOCK_ANALYSIS",
                  "confidence": "MEDIUM",
                  "entityMention": "平安",
                  "ticker": null,
                  "analysisType": "ALL",
                  "resolutionStatus": "AMBIGUOUS",
                  "candidates": [
                    {"ticker": "000001", "name": "平安银行"},
                    {"ticker": "601318", "name": "中国平安"}
                  ],
                  "nextAction": "ASK_DISAMBIGUATION",
                  "clarificationQuestion": "你想分析平安银行还是中国平安？",
                  "reasoning": "用户提到的简称存在多个候选，需要用户确认"
                }
                """;

        TradingIntentRoutingService.IntentRoutingResult result = service.parseResponse(response);

        assertEquals(IntentEnumVO.STOCK_ANALYSIS, result.getIntent());
        assertEquals(ConfidenceEnum.MEDIUM, result.getConfidence());
        assertEquals("平安", result.getEntityMention());
        assertNull(result.getTicker());
        assertEquals("ALL", result.getAnalysisType());
        assertEquals("AMBIGUOUS", result.getResolutionStatus());
        assertEquals("ASK_DISAMBIGUATION", result.getNextAction());
        assertEquals("你想分析平安银行还是中国平安？", result.getClarificationQuestion());
        assertEquals(2, result.getCandidates().size());
        assertEquals("000001", result.getCandidates().get(0).getTicker());
        assertEquals("平安银行", result.getCandidates().get(0).getName());
    }
}
