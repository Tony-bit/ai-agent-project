package denny.ai.agent.trading.domain.validation;

import com.fasterxml.jackson.databind.ObjectMapper;
import denny.ai.agent.trading.api.vo.StockIdentityVO;
import denny.ai.agent.trading.api.vo.TargetContext;
import denny.ai.agent.trading.api.vo.payload.FundamentalAnalystPayload;
import denny.ai.agent.trading.api.vo.payload.TargetEchoPayload;
import denny.ai.agent.trading.domain.execution.NodeResultEnvelope;
import jakarta.validation.Validation;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NodeResultValidatorTest {

    private final NodeResultValidator validator = new NodeResultValidator(
            new ObjectMapper().findAndRegisterModules(),
            Validation.buildDefaultValidatorFactory().getValidator());
    private final TargetContext target = new TargetContext(
            UUID.randomUUID().toString(), "601318.SH", "中国平安", "保险", LocalDate.of(2026, 7, 22));

    @Test
    void acceptsMatchingEnvelopeTargetEntityAndPercentagePointFact() {
        FundamentalAnalystPayload payload = payload(
                List.of("中国平安 ROE 12.5%"), new TargetEchoPayload("601318", "中国平安"));

        NodeValidationResult result = validator.validate(
                NodeResultEnvelope.wrap(target, "fundamental_analyst", payload),
                context(allowedEntities(), List.of(roeFact())));

        assertTrue(result.isValid(), () -> result.errors().toString());
    }

    @Test
    void rejectsEnvelopeAndTargetEchoMismatch() {
        TargetContext otherRun = new TargetContext(
                UUID.randomUUID().toString(), target.targetId(), target.stockName(), target.industry(), target.asOfDate());
        FundamentalAnalystPayload payload = payload(
                List.of("ROE 12.5%"), new TargetEchoPayload("001309", "德明利"));

        NodeValidationResult result = validator.validate(
                NodeResultEnvelope.wrap(otherRun, "technical_analyst", payload),
                context(allowedEntities(), List.of(roeFact())));

        assertTrue(hasCode(result, ValidationErrorCode.ENVELOPE_MISMATCH));
        assertTrue(hasCode(result, ValidationErrorCode.TARGET_MISMATCH));
    }

    @Test
    void rejectsUnauthorizedStockCodeAndName() {
        AllowedEntitySet entities = AllowedEntitySet.forTarget(target)
                .registerKnownStock(new StockIdentityVO("001309.SZ", "德明利", "半导体"))
                .build();
        FundamentalAnalystPayload payload = payload(
                List.of("001309 德明利五连跌停"), null);

        NodeValidationResult result = validator.validate(
                NodeResultEnvelope.wrap(target, "fundamental_analyst", payload),
                context(entities, List.of()));

        assertFalse(result.isValid());
        assertEquals(2, result.errors().stream()
                .filter(error -> error.code() == ValidationErrorCode.FOREIGN_ENTITY).count());
    }

    @Test
    void allowsRelatedCompaniesExplicitlyAuthorizedByInput() {
        AllowedEntitySet entities = AllowedEntitySet.forTarget(target)
                .allowStock(new StockIdentityVO("601601.SH", "中国太保", "保险"))
                .allowStock(new StockIdentityVO("601336.SH", "新华保险", "保险"))
                .allowGeneralEntity("上证指数")
                .build();
        FundamentalAnalystPayload payload = payload(
                List.of("中国太保 601601、新华保险 601336 与上证指数均来自新闻输入"), null);

        NodeValidationResult result = validator.validate(
                NodeResultEnvelope.wrap(target, "fundamental_analyst", payload),
                context(entities, List.of()));

        assertTrue(result.isValid(), () -> result.errors().toString());
    }

    @Test
    void rejectsCurrentPriceConflictAndPercentageUnitDrift() {
        FundamentalAnalystPayload payload = payload(
                List.of("当前价 482 元，ROE 0.125%，净资产收益率 1250%"), null);
        List<NumericInputFact> facts = List.of(
                NumericInputFact.exact("currentPrice", new BigDecimal("52.89"),
                        NumericInputFact.Unit.CNY, "当前价"),
                roeFact());

        NodeValidationResult result = validator.validate(
                NodeResultEnvelope.wrap(target, "fundamental_analyst", payload),
                context(allowedEntities(), facts));

        assertEquals(3, result.errors().stream()
                .filter(error -> error.code() == ValidationErrorCode.INPUT_DATA_CONFLICT).count());
    }

    @Test
    void rejectsMissingPercentUnitAndInvalidBeanSchema() {
        FundamentalAnalystPayload payload = new FundamentalAnalystPayload(
                9, List.of("ROE 12.5"), List.of(), "summary", null);

        NodeValidationResult result = validator.validate(
                NodeResultEnvelope.wrap(target, "fundamental_analyst", payload),
                context(allowedEntities(), List.of(roeFact())));

        assertTrue(hasCode(result, ValidationErrorCode.INVALID_SCHEMA));
        assertTrue(hasCode(result, ValidationErrorCode.DATA_QUALITY));
    }

    @Test
    void doesNotTreatAuthoritativeNumericRawDataAsAnUnqualifiedTextClaim() {
        denny.ai.agent.trading.api.vo.FundamentalReportVO report =
                denny.ai.agent.trading.api.vo.FundamentalReportVO.builder()
                        .rating(4).keyFindings(List.of("盈利稳定")).riskWarnings(List.of())
                        .summary("summary")
                        .rawData(denny.ai.agent.trading.api.vo.FundamentalDataVO.builder()
                                .roe(12.5).build())
                        .build();

        NodeValidationResult result = validator.validate(
                NodeResultEnvelope.wrap(target, "fundamental_analyst", report),
                context(allowedEntities(), List.of(roeFact())));

        assertTrue(result.isValid(), () -> result.errors().toString());
    }

    private FundamentalAnalystPayload payload(List<String> findings, TargetEchoPayload echo) {
        return new FundamentalAnalystPayload(4, findings, List.of(), "summary", echo);
    }

    private NodeValidationContext context(AllowedEntitySet entities, List<NumericInputFact> facts) {
        return new NodeValidationContext(target, "fundamental_analyst", entities, facts);
    }

    private AllowedEntitySet allowedEntities() {
        return AllowedEntitySet.forTarget(target).build();
    }

    private NumericInputFact roeFact() {
        return NumericInputFact.exact("roe", new BigDecimal("12.5"),
                NumericInputFact.Unit.PERCENTAGE_POINT, "ROE", "净资产收益率");
    }

    private boolean hasCode(NodeValidationResult result, ValidationErrorCode code) {
        return result.errors().stream().anyMatch(error -> error.code() == code);
    }
}
