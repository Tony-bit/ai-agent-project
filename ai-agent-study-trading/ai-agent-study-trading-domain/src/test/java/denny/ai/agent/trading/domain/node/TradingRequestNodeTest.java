package denny.ai.agent.trading.domain.node;

import denny.ai.agent.domain.model.entity.ExecuteCommandEntity;
import denny.ai.agent.domain.model.valobj.StockSlot;
import denny.ai.agent.domain.service.auto.step.factory.DefaultAutoAgentExecuteStrategyFactory;
import denny.ai.agent.trading.api.vo.AnalystTypeEnum;
import denny.ai.agent.trading.api.vo.StockAnalysisRequestVO;
import denny.ai.agent.trading.api.vo.TargetContext;
import denny.ai.agent.trading.domain.config.TradingStarter;
import denny.ai.agent.trading.domain.exception.StockIdentityNotFoundException;
import denny.ai.agent.trading.domain.exception.StockIdentityProviderException;
import denny.ai.agent.trading.domain.exception.StockIdentityValidationException;
import denny.ai.agent.trading.domain.service.AnalysisTypeMapper;
import denny.ai.agent.trading.domain.service.TargetContextFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TradingRequestNodeTest {

    private TradingRequestNode node;
    private TargetContextFactory targetContextFactory;
    private TradingStarter starter;
    private DefaultAutoAgentExecuteStrategyFactory.DynamicContext context;
    private ExecuteCommandEntity request;

    @BeforeEach
    void setUp() {
        node = new TradingRequestNode();
        targetContextFactory = mock(TargetContextFactory.class);
        starter = mock(TradingStarter.class);
        ReflectionTestUtils.setField(node, "analysisTypeMapper", new AnalysisTypeMapper());
        ReflectionTestUtils.setField(node, "targetContextFactory", targetContextFactory);
        ReflectionTestUtils.setField(node, "starter", starter);
        context = new DefaultAutoAgentExecuteStrategyFactory.DynamicContext();
        request = ExecuteCommandEntity.builder().sessionId("session-1").message("分析药明康德").build();
    }

    @Test
    void buildsCanonicalRequestAndStartsWithValidatedTarget() throws Exception {
        context.setValue("stockSlot", StockSlot.builder()
                .stockCode(" 603259.sh ")
                .stockName("药明康德")
                .stockQueryType("FUNDAMENTAL,NEWS")
                .exchange("SZ")
                .build());
        TargetContext target = new TargetContext(UUID.randomUUID().toString(), "603259.SH",
                "药明康德", "医药", LocalDate.now());
        when(targetContextFactory.create(eq("603259.SH"), eq("药明康德"), any(LocalDate.class)))
                .thenReturn(target);

        node.doApply(request, context);

        StockAnalysisRequestVO tradingRequest = context.getValue("trading_request");
        assertEquals("603259.SH", tradingRequest.getTicker());
        assertEquals("药明康德", tradingRequest.getStockName());
        assertEquals(List.of(AnalystTypeEnum.FUNDAMENTAL, AnalystTypeEnum.NEWS),
                tradingRequest.getSelectedAnalysts());
        verify(starter).start(eq(tradingRequest), eq(target), eq(context), any());
    }

    @Test
    void invalidTickerRegistersClarificationWithoutProviderOrStarterCall() throws Exception {
        context.setValue("stockSlot", StockSlot.builder().stockCode("12345").build());

        node.doApply(request, context);

        assertEquals("CLARIFICATION", context.getValue("routingTerminalKind"));
        assertEquals("请提供完整 A 股名称或 6 位代码", context.getValue("routingTerminalResponse"));
        assertNull(context.getValue("trading_request"));
        verify(targetContextFactory, never()).create(any(), any(), any());
        verify(starter, never()).start(any(), any(), any(), any());
    }

    @Test
    void missingIdentityRegistersClarificationWithoutStartingTrading() throws Exception {
        context.setValue("stockSlot", StockSlot.builder().stockCode("603259").build());
        when(targetContextFactory.create(eq("603259"), eq(null), any(LocalDate.class)))
                .thenThrow(new StockIdentityNotFoundException("603259"));

        node.doApply(request, context);

        assertEquals("CLARIFICATION", context.getValue("routingTerminalKind"));
        verify(starter, never()).start(any(), any(), any(), any());
    }

    @Test
    void providerFailureRegistersErrorWithoutStartingTrading() throws Exception {
        context.setValue("stockSlot", StockSlot.builder().stockCode("603259").build());
        RuntimeException cause = new RuntimeException("timeout");
        when(targetContextFactory.create(eq("603259"), eq(null), any(LocalDate.class)))
                .thenThrow(new StockIdentityProviderException("603259", cause));

        node.doApply(request, context);

        assertEquals("ERROR", context.getValue("routingTerminalKind"));
        assertEquals("股票数据服务暂时不可用，请稍后重试",
                context.getValue("routingTerminalResponse"));
        verify(starter, never()).start(any(), any(), any(), any());
    }

    @Test
    void identityMismatchRegistersErrorWithoutStartingTrading() throws Exception {
        context.setValue("stockSlot", StockSlot.builder()
                .stockCode("603259").stockName("兆易创新").build());
        when(targetContextFactory.create(eq("603259"), eq("兆易创新"), any(LocalDate.class)))
                .thenThrow(new StockIdentityValidationException("name mismatch"));

        node.doApply(request, context);

        assertEquals("ERROR", context.getValue("routingTerminalKind"));
        assertEquals("股票身份校验失败，本次分析已停止",
                context.getValue("routingTerminalResponse"));
        verify(starter, never()).start(any(), any(), any(), any());
    }

    @Test
    void emptySlotAndNonAShareSuffixRegisterClarificationBeforeProviderLookup() throws Exception {
        node.doApply(request, context);
        assertEquals("CLARIFICATION", context.getValue("routingTerminalKind"));

        context = new DefaultAutoAgentExecuteStrategyFactory.DynamicContext();
        context.setValue("stockSlot", StockSlot.builder().stockCode("603259.HK").build());
        node.doApply(request, context);

        assertEquals("CLARIFICATION", context.getValue("routingTerminalKind"));
        verify(targetContextFactory, never()).create(any(), any(), any());
        verify(starter, never()).start(any(), any(), any(), any());
    }
}
