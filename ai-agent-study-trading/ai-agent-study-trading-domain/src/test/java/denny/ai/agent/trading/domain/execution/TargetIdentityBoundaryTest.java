package denny.ai.agent.trading.domain.execution;

import denny.ai.agent.trading.api.provider.IStockDataProvider;
import denny.ai.agent.trading.api.vo.FundamentalDataVO;
import denny.ai.agent.trading.api.vo.TargetContext;
import denny.ai.agent.trading.domain.vo.TradingContextVO;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TargetIdentityBoundaryTest {

    @Test
    void providerFacadeAlwaysUsesCanonicalTargetId() {
        IStockDataProvider provider = mock(IStockDataProvider.class);
        TargetContext target = target();
        FundamentalDataVO expected = FundamentalDataVO.builder().roe(12.0).build();
        when(provider.getFundamentalData(target.targetId())).thenReturn(expected);

        TargetBoundStockDataProvider bound = TargetBoundStockDataProvider.bind(provider, target);

        assertEquals(expected, bound.getFundamentalData());
        assertEquals(target.targetId(), bound.effectiveTicker("001309"));
        verify(provider).getFundamentalData("601318.SH");
    }

    @Test
    void finalDecisionMustCarryJavaBoundIdentity() {
        TargetContext target = target();
        TradingContextVO.FinalTradeDecisionVO valid = TradingContextVO.FinalTradeDecisionVO.builder()
                .targetId(target.targetId()).stockName(target.stockName()).decision("HOLD").build();
        TradingContextVO.FinalTradeDecisionVO invalid = TradingContextVO.FinalTradeDecisionVO.builder()
                .targetId("001309.SZ").stockName("德明利").decision("BUY").build();

        FinalDecisionIdentityGuard.requireBound(target, valid);
        assertThrows(IllegalStateException.class,
                () -> FinalDecisionIdentityGuard.requireBound(target, invalid));
    }

    private TargetContext target() {
        return new TargetContext(UUID.randomUUID().toString(), "601318.SH",
                "中国平安", "保险", LocalDate.of(2026, 7, 28));
    }
}
