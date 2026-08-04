package denny.ai.agent.trading.domain.service;

import denny.ai.agent.trading.api.provider.IStockDataProvider;
import denny.ai.agent.trading.api.vo.StockIdentityVO;
import denny.ai.agent.trading.api.vo.TargetContext;
import denny.ai.agent.trading.domain.exception.StockIdentityNotFoundException;
import denny.ai.agent.trading.domain.exception.StockIdentityProviderException;
import denny.ai.agent.trading.domain.exception.StockIdentityValidationException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TargetContextFactoryTest {

    private static final LocalDate AS_OF_DATE = LocalDate.of(2026, 7, 22);

    @Test
    void productionConstructorIsMarkedForSpringInjection() throws NoSuchMethodException {
        assertTrue(TargetContextFactory.class
                .getConstructor(IStockDataProvider.class)
                .isAnnotationPresent(Autowired.class));
    }

    @Test
    void createsAuthoritativeTargetForChinaPingAn() {
        TargetContextFactory factory = factory(List.of(
                new StockIdentityVO("601318.SH", "中国平安", "保险")));

        TargetContext target = factory.create("601318", AS_OF_DATE);

        assertEquals("601318.SH", target.targetId());
        assertEquals("中国平安", target.stockName());
        assertEquals("保险", target.industry());
        assertEquals(AS_OF_DATE, target.asOfDate());
    }

    @Test
    void rejectsEmptyMultipleInvalidAndMismatchedIdentityResults() {
        assertThrows(StockIdentityNotFoundException.class, () -> factory(List.of()).create("601318", AS_OF_DATE));
        assertThrows(StockIdentityValidationException.class, () -> factory(List.of(
                new StockIdentityVO("601318.SH", "中国平安", "保险"),
                new StockIdentityVO("601318.SH", "中国平安", "保险")))
                .create("601318", AS_OF_DATE));
        assertThrows(StockIdentityValidationException.class, () -> factory(List.of(
                new StockIdentityVO("invalid", "中国平安", "保险")))
                .create("601318", AS_OF_DATE));
        assertThrows(StockIdentityValidationException.class, () -> factory(List.of(
                new StockIdentityVO("001309.SZ", "德明利", "半导体")))
                .create("601318", AS_OF_DATE));
    }

    @Test
    void validatesRequestedNameButAllowsCodeInputWithoutName() {
        TargetContextFactory factory = factory(List.of(
                new StockIdentityVO("603259.SH", "药明康德", "医药")));

        assertEquals("药明康德", factory.create("603259", null, AS_OF_DATE).stockName());
        assertEquals("603259.SH", factory.create(" 603259.sh ", " 药明康德 ", AS_OF_DATE).targetId());
        assertThrows(StockIdentityValidationException.class,
                () -> factory.create("603259", "兆易创新", AS_OF_DATE));
    }

    @Test
    void wrapsProviderFailureAndPreservesCause() {
        RuntimeException cause = new RuntimeException("timeout");
        IStockDataProvider provider = new StubStockDataProvider() {
            @Override
            public List<StockIdentityVO> findStockIdentities(String ticker) {
                throw cause;
            }
        };

        StockIdentityProviderException thrown = assertThrows(StockIdentityProviderException.class,
                () -> new TargetContextFactory(provider).create("603259", AS_OF_DATE));

        assertEquals(cause, thrown.getCause());
    }

    @Test
    void createsNewRunIdForEveryTarget() {
        TargetContextFactory factory = factory(List.of(
                new StockIdentityVO("601318.SH", "中国平安", "保险")));

        assertNotEquals(factory.create("601318", AS_OF_DATE).runId(),
                factory.create("601318", AS_OF_DATE).runId());
    }

    private TargetContextFactory factory(List<StockIdentityVO> identities) {
        IStockDataProvider provider = new StubStockDataProvider() {
            @Override
            public List<StockIdentityVO> findStockIdentities(String ticker) {
                return identities;
            }
        };
        return new TargetContextFactory(provider, UUID::randomUUID);
    }
}
