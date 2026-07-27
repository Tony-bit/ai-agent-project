package denny.ai.agent.trading.domain.execution;

import com.fasterxml.jackson.databind.ObjectMapper;
import denny.ai.agent.trading.api.vo.payload.FundamentalAnalystPayload;
import denny.ai.agent.trading.api.vo.payload.PortfolioDecisionPayload;
import denny.ai.agent.trading.api.vo.payload.RecommendationPayload;
import denny.ai.agent.trading.api.vo.payload.RiskAssessmentPayload;
import denny.ai.agent.trading.api.vo.payload.TechnicalAnalystPayload;
import denny.ai.agent.trading.api.vo.payload.ResearchArgumentPayload;
import denny.ai.agent.trading.api.vo.payload.ResearchManagerPayload;
import jakarta.validation.Validation;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StructuredPayloadCodecTest {

    private final StructuredPayloadCodec codec = new StructuredPayloadCodec(
            new ObjectMapper().findAndRegisterModules(),
            Validation.buildDefaultValidatorFactory().getValidator());

    @Test
    void parsesCompletePayloadAndKeepsPercentagePointEvidence() {
        String json = """
                {
                  "rating": 4,
                  "keyFindings": ["ROE为12.5%"],
                  "riskWarnings": [],
                  "summary": "基本面保持稳定",
                  "targetEcho": {"ticker":"601318","stockName":"中国平安"}
                }
                """;

        FundamentalAnalystPayload payload = codec.parse(json, FundamentalAnalystPayload.class);

        assertEquals("ROE为12.5%", payload.keyFindings().get(0));
        assertTrue(codec.outputContract(FundamentalAnalystPayload.class).contains("keyFindings"));
    }

    @Test
    void rejectsMissingUnknownOutOfRangeAndTrailingContent() {
        assertThrows(StructuredPayloadException.class,
                () -> codec.parse("{\"rating\":4}", FundamentalAnalystPayload.class));
        assertThrows(StructuredPayloadException.class,
                () -> codec.parse("""
                        {"rating":4,"keyFindings":["x"],"riskWarnings":[],
                         "summary":"ok","unknown":"value"}
                        """, FundamentalAnalystPayload.class));
        assertThrows(StructuredPayloadException.class,
                () -> codec.parse("""
                        {"rating":9,"keyFindings":["x"],"riskWarnings":[],"summary":"ok"}
                        """, FundamentalAnalystPayload.class));
        assertThrows(StructuredPayloadException.class,
                () -> codec.parse("""
                        {"rating":4,"keyFindings":["x"],"riskWarnings":[],"summary":"ok"} trailing
                        """, FundamentalAnalystPayload.class));
    }

    @Test
    void rejectsInvalidTechnicalEnum() {
        assertThrows(StructuredPayloadException.class,
                () -> codec.parse("""
                        {"rating":3,"trendSignal":"上涨趋势","keyPatterns":["MA向上"],"summary":"ok"}
                        """, TechnicalAnalystPayload.class));
    }

    @Test
    void validatesDebatePayloadsAndAllowsExplicitInsufficientData() {
        assertThrows(StructuredPayloadException.class,
                () -> codec.parse("""
                        {"stance":"NEUTRAL","keyEvidence":[{"type":"FACT","confidence":"HIGH","claim":"x"}],
                         "risks":[],"summary":"x"}
                        """, ResearchArgumentPayload.class));

        ResearchManagerPayload manager = codec.parse("""
                {"status":"INSUFFICIENT_DATA","overallScore":null,"needMoreDebate":false,
                 "decisiveFactors":[],"dataQualityWarnings":["缺少基本面"],
                 "conclusion":"数据不足，停止补充事实"}
                """, ResearchManagerPayload.class);
        assertEquals("INSUFFICIENT_DATA", manager.status());
    }

    @Test
    void rejectsInvalidRiskPerspectiveAndTrailingFreeText() {
        assertThrows(StructuredPayloadException.class,
                () -> codec.parse("""
                        {"perspective":"BOLD","riskScore":3,"riskItems":["x"],
                         "mitigations":["y"],"summary":"z"}
                        """, RiskAssessmentPayload.class));
        assertThrows(StructuredPayloadException.class,
                () -> codec.parse("""
                        {"perspective":"AGGRESSIVE","riskScore":3,"riskItems":["x"],
                         "mitigations":["y"],"summary":"z"} use this result
                        """, RiskAssessmentPayload.class));
    }

    @Test
    void rejectsRecommendationPositionRatioOutsideUnitInterval() {
        assertThrows(StructuredPayloadException.class,
                () -> codec.parse("""
                        {"action":"BUY","positionRatio":1.2,"entryPriceRange":"50-52",
                         "stopLossPrice":"48","takeProfitPrice":"60","holdingPeriod":"3 months",
                         "riskRewardRatio":2.0,"rationale":"x"}
                        """, RecommendationPayload.class));
    }

    @Test
    void rejectsInvalidPortfolioDecisionAndConfidence() {
        assertThrows(StructuredPayloadException.class,
                () -> codec.parse("""
                        {"decision":"WAIT","confidence":"CERTAIN","overallRating":3.0,
                         "reasoning":"x","warnings":[]}
                        """, PortfolioDecisionPayload.class));
    }
}
