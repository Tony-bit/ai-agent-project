package denny.ai.agent.trading.domain.node;

import denny.ai.agent.trading.api.vo.FundamentalReportVO;
import denny.ai.agent.trading.api.vo.TargetContext;
import denny.ai.agent.trading.api.vo.TechnicalReportVO;
import denny.ai.agent.trading.api.vo.payload.ResearchArgumentPayload;
import denny.ai.agent.trading.domain.validation.NodeValidationAudit;
import denny.ai.agent.trading.domain.validation.NodeValidationRegistry;
import denny.ai.agent.trading.domain.validation.TradingValidationError;
import denny.ai.agent.trading.domain.validation.ValidationErrorCode;
import denny.ai.agent.trading.domain.vo.TradingContextVO;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ResearchManagerInputFactoryTest {

    @Test
    void includesOnlyValidatedReportsAndDebateArgumentsWithStatuses() {
        TargetContext target = new TargetContext(UUID.randomUUID().toString(),
                "601318.SH", "中国平安", "保险", LocalDate.of(2026, 7, 22));
        TradingContextVO context = TradingContextVO.forTarget(target);
        FundamentalReportVO fundamental = FundamentalReportVO.builder()
                .rating(4).summary("fundamental").build();
        TechnicalReportVO unvalidatedTechnical = TechnicalReportVO.builder()
                .rating(3).summary("must not leak").build();
        context.setFundamentalReport(fundamental);
        context.setTechnicalReport(unvalidatedTechnical);
        context.setDataWarnings(List.of("news source incomplete"));

        TradingContextVO.InvestmentDebateVO debate = TradingContextVO.InvestmentDebateVO.createNew(1);
        ResearchArgumentPayload bull = argument("BULL", "validated bull");
        ResearchArgumentPayload invalidBear = argument("BEAR", "invalid bear must not leak");
        debate.addBullArgument(bull);
        debate.addBearArgument(invalidBear);
        context.setInvestmentDebate(debate);

        NodeValidationRegistry registry = new NodeValidationRegistry();
        registry.markValid("FundamentalAnalystNode");
        registry.markValid("BullResearcherNode");
        registry.markInvalid("BearResearcherNode", List.of(new TradingValidationError(
                ValidationErrorCode.FOREIGN_ENTITY, "foreign entity", "payload")));

        ResearchManagerInput input = new ResearchManagerInputFactory().create(context, registry, 1);

        assertSame(fundamental, input.validatedAnalystReports().get("fundamental"));
        assertFalse(input.validatedAnalystReports().containsKey("technical"));
        assertEquals(List.of(bull), input.validatedBullHistory());
        assertTrue(input.validatedBearHistory().isEmpty());
        assertEquals(NodeValidationAudit.Status.MISSING,
                input.validationStatuses().get("TechnicalAnalystNode").status());
        assertEquals(NodeValidationAudit.Status.INVALID,
                input.validationStatuses().get("BearResearcherNode").status());
        assertEquals(List.of("news source incomplete"), input.dataQualityWarnings());
        assertEquals(target, input.targetContext());
        assertEquals(1, input.currentRound());
    }

    private ResearchArgumentPayload argument(String stance, String summary) {
        return new ResearchArgumentPayload(stance,
                List.of(new ResearchArgumentPayload.EvidenceArgument("FACT", "HIGH", summary)),
                List.of(), summary, null);
    }
}
