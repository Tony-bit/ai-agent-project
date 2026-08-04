package denny.ai.agent.domain.service.auto.step.routing;

import denny.ai.agent.domain.model.entity.AutoAgentExecuteResultEntity;
import denny.ai.agent.domain.model.entity.ExecuteCommandEntity;
import denny.ai.agent.domain.model.entity.RoutingConversationContext;
import denny.ai.agent.domain.model.valobj.AiAgentClientFlowConfigVO;
import denny.ai.agent.domain.model.valobj.MultiIntentRoutingResult;
import denny.ai.agent.domain.model.valobj.StockSlot;
import denny.ai.agent.domain.model.valobj.SubTask;
import denny.ai.agent.domain.model.valobj.enums.AiClientTypeEnumVO;
import denny.ai.agent.domain.model.valobj.enums.ConfidenceEnum;
import denny.ai.agent.domain.model.valobj.enums.IntentTypeEnum;
import denny.ai.agent.domain.service.auto.step.chat.GeneralChatNode;
import denny.ai.agent.domain.service.auto.step.factory.DefaultAutoAgentExecuteStrategyFactory;
import denny.ai.agent.domain.service.auto.step.pe.Step1AnalyzerNode;
import denny.ai.agent.domain.service.auto.step.react.IntelligentInspection;
import denny.ai.agent.domain.service.chatmemory.ConversationContextProvider;
import denny.ai.agent.domain.service.observability.ObservabilityService;
import denny.ai.agent.trading.api.cache.TradingNamespaceKeyFactory;
import denny.ai.agent.trading.api.context.TradingTargetContextKeys;
import denny.ai.agent.trading.api.provider.IStockDataProvider;
import denny.ai.agent.trading.api.vo.FundamentalDataVO;
import denny.ai.agent.trading.api.vo.NewsItemVO;
import denny.ai.agent.trading.api.vo.OHLCVBarVO;
import denny.ai.agent.trading.api.vo.SentimentDataVO;
import denny.ai.agent.trading.api.vo.StockAnalysisRequestVO;
import denny.ai.agent.trading.api.vo.StockIdentityVO;
import denny.ai.agent.trading.api.vo.StockInfoVO;
import denny.ai.agent.trading.api.vo.StockSearchResultVO;
import denny.ai.agent.trading.api.vo.TargetContext;
import denny.ai.agent.trading.api.vo.TechnicalIndicatorsVO;
import denny.ai.agent.trading.domain.config.TradingDispatcher;
import denny.ai.agent.trading.domain.config.TradingPhase;
import denny.ai.agent.trading.domain.config.TradingStarter;
import denny.ai.agent.trading.domain.config.TradingStateContext;
import denny.ai.agent.trading.domain.node.TradingRequestNode;
import denny.ai.agent.trading.domain.pipeline.TradingPipeline;
import denny.ai.agent.trading.domain.prompt.TradingPromptRecord;
import denny.ai.agent.trading.domain.prompt.TradingPromptRenderer;
import denny.ai.agent.trading.domain.prompt.TradingPromptRepository;
import denny.ai.agent.trading.domain.prompt.TradingPromptSnapshotFactory;
import denny.ai.agent.trading.domain.service.AnalysisTypeMapper;
import denny.ai.agent.trading.domain.service.TargetContextFactory;
import denny.ai.agent.trading.domain.vo.TradingContextVO;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationContext;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyEmitter;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TradingRoutingConsolidationIntegrationTest {

    @Test
    void routesResolvedNameThroughOneIdentityLookupIntoTrading() throws Exception {
        Fixture fixture = new Fixture();
        DefaultAutoAgentExecuteStrategyFactory.DynamicContext context = fixture.context();

        fixture.route(context, stockResult("603259", "药明康德"));

        StockAnalysisRequestVO request = context.getValue(TradingRequestNode.TRADING_REQUEST_KEY);
        TargetContext target = context.getValue(TradingTargetContextKeys.TARGET_CONTEXT);
        TradingContextVO trading = context.getValue("trading_context");
        assertEquals("603259.SH", request.getTicker());
        assertEquals("药明康德", request.getStockName());
        assertEquals("603259.SH", target.targetId());
        assertSame(target, trading.getTargetContext());
        assertEquals(1, fixture.provider.identityLookups.get());
        assertEquals(1, fixture.provider.stockInfoLookups.get());
    }

    @Test
    void routesCodeOnlyAndUsesAuthoritativeStockName() throws Exception {
        Fixture fixture = new Fixture();
        DefaultAutoAgentExecuteStrategyFactory.DynamicContext context = fixture.context();

        fixture.route(context, stockResult("000001", null));

        StockAnalysisRequestVO request = context.getValue(TradingRequestNode.TRADING_REQUEST_KEY);
        TargetContext target = context.getValue(TradingTargetContextKeys.TARGET_CONTEXT);
        assertEquals("000001.SZ", request.getTicker());
        assertEquals("平安银行", request.getStockName());
        assertEquals("平安银行", target.stockName());
        assertEquals(1, fixture.provider.identityLookups.get());
    }

    @Test
    void emptyStockSlotProducesClarificationAndCompleteSseFrames() throws Exception {
        Fixture fixture = new Fixture();
        IntentRoutingService routingService = mock(IntentRoutingService.class);
        ConversationContextProvider history = mock(ConversationContextProvider.class);
        ExposedIntentRoutingNode routingNode = new ExposedIntentRoutingNode();
        ReflectionTestUtils.setField(routingNode, "intentRoutingService", routingService);
        ReflectionTestUtils.setField(routingNode, "analysisDepthFollowUpResolver", new AnalysisDepthFollowUpResolver());
        ReflectionTestUtils.setField(routingNode, "conversationContextProvider", history);
        ReflectionTestUtils.setField(routingNode, "taskGraphValidator", new TaskGraphValidator());
        ReflectionTestUtils.setField(routingNode, "routingResultHandler", fixture.handler);
        lenient().when(history.getRoutingContext(anyString()))
                .thenReturn(RoutingConversationContext.builder().historyMessages(List.of()).build());

        DefaultAutoAgentExecuteStrategyFactory.DynamicContext context = fixture.context();
        ResponseBodyEmitter emitter = mock(ResponseBodyEmitter.class);
        context.setValue("emitter", emitter);
        when(routingService.routeUnified(anyString(), any(), any(AiAgentClientFlowConfigVO.class), anyString()))
                .thenReturn(stockResult(null, null));

        String response = routingNode.invoke(fixture.command, context);

        assertEquals("请提供完整 A 股名称或 6 位代码", response);
        assertEquals("CLARIFICATION", context.getValue("routingTerminalKind"));
        ArgumentCaptor<Object> frames = ArgumentCaptor.forClass(Object.class);
        verify(emitter, times(2)).send(frames.capture());
        assertTrue(frames.getAllValues().get(0).toString().contains("clarification"));
        assertTrue(frames.getAllValues().get(1).toString().contains("complete"));
        assertEquals(0, fixture.provider.identityLookups.get());
    }

    @Test
    void consecutiveDifferentAndSameStockRunsStayIsolatedWhileRawCacheKeysReuse() throws Exception {
        Fixture fixture = new Fixture();
        DefaultAutoAgentExecuteStrategyFactory.DynamicContext context = fixture.context();

        fixture.route(context, stockResult("000001", "平安银行"));
        TargetContext first = context.getValue(TradingTargetContextKeys.TARGET_CONTEXT);
        TradingContextVO firstTrading = context.getValue("trading_context");

        fixture.route(context, stockResult("600000", "浦发银行"));
        TargetContext second = context.getValue(TradingTargetContextKeys.TARGET_CONTEXT);
        TradingContextVO secondTrading = context.getValue("trading_context");

        fixture.route(context, stockResult("600000", "浦发银行"));
        TargetContext third = context.getValue(TradingTargetContextKeys.TARGET_CONTEXT);
        TradingContextVO thirdTrading = context.getValue("trading_context");

        assertNotEquals(first.runId(), second.runId());
        assertNotEquals(first.targetId(), second.targetId());
        assertNotEquals(second.runId(), third.runId());
        assertEquals(second.targetId(), third.targetId());
        assertNotSame(firstTrading, secondTrading);
        assertNotSame(secondTrading, thirdTrading);
        assertEquals(third.runId(), context.getValue("trading_run_id"));
        assertEquals(third.targetId(), context.getValue("trading_target_id"));

        String secondMemory = TradingNamespaceKeyFactory.chatMemory(
                "session-1", second, "FundamentalAnalystNode");
        String thirdMemory = TradingNamespaceKeyFactory.chatMemory(
                "session-1", third, "FundamentalAnalystNode");
        assertNotEquals(secondMemory, thirdMemory);
        String secondRaw = TradingNamespaceKeyFactory.rawData(
                "tushare", "daily", second.targetId(), "20260801-20260803", Map.of(), "v1");
        String thirdRaw = TradingNamespaceKeyFactory.rawData(
                "tushare", "daily", third.targetId(), "20260801-20260803", Map.of(), "v1");
        assertEquals(secondRaw, thirdRaw);
        assertTrue(!secondRaw.contains(second.runId()) && !thirdRaw.contains(third.runId()));
        assertEquals(3, fixture.provider.identityLookups.get());
        assertEquals(3, fixture.provider.stockInfoLookups.get());
    }

    private static MultiIntentRoutingResult stockResult(String stockCode, String stockName) {
        Map<String, Object> intentSlots = new HashMap<>();
        if (stockCode != null || stockName != null) {
            intentSlots.put("stockSlot", StockSlot.builder()
                    .stockCode(stockCode)
                    .stockName(stockName)
                    .stockQueryType("ALL")
                    .build());
        }
        SubTask task = SubTask.builder()
                .taskId("stock-1")
                .taskIndex(1)
                .totalTasks(1)
                .content("分析股票")
                .intent(IntentTypeEnum.STOCK_ANALYSIS)
                .confidence(ConfidenceEnum.HIGH)
                .executorNode("tradingRequestNode")
                .slots(Map.of("intentSpecificSlots", intentSlots))
                .dependsOn(List.of())
                .taskType(0)
                .status(SubTask.SubTaskStatus.PENDING)
                .build();
        return MultiIntentRoutingResult.builder()
                .multiTask(false)
                .needsClarification(false)
                .reasoning("stock route")
                .taskList(List.of(task))
                .build();
    }

    private static class ExposedIntentRoutingNode extends IntentRoutingNode {
        String invoke(ExecuteCommandEntity request,
                      DefaultAutoAgentExecuteStrategyFactory.DynamicContext context) throws Exception {
            return doApply(request, context);
        }
    }

    private static class Fixture {
        private final CountingStockDataProvider provider = new CountingStockDataProvider();
        private final RoutingResultHandler handler;
        private final ExecuteCommandEntity command = ExecuteCommandEntity.builder()
                .sessionId("session-1")
                .message("分析股票")
                .build();

        private Fixture() {
            TradingStarter starter = new TradingStarter();
            TargetContextFactory targetFactory = new TargetContextFactory(provider);
            ReflectionTestUtils.setField(starter, "dataProvider", provider);
            ReflectionTestUtils.setField(starter, "targetContextFactory", targetFactory);
            ReflectionTestUtils.setField(starter, "promptSnapshotFactory",
                    new TradingPromptSnapshotFactory(new StubPromptRepository(), new TradingPromptRenderer()));
            ReflectionTestUtils.setField(starter, "tradingPipeline", new CompletingPipeline());
            ReflectionTestUtils.setField(starter, "tradingDispatcher", new TradingDispatcher());

            TradingRequestNode tradingNode = new TradingRequestNode();
            ReflectionTestUtils.setField(tradingNode, "analysisTypeMapper", new AnalysisTypeMapper());
            ReflectionTestUtils.setField(tradingNode, "targetContextFactory", targetFactory);
            ReflectionTestUtils.setField(tradingNode, "starter", starter);

            ApplicationContext applicationContext = mock(ApplicationContext.class);
            when(applicationContext.getBean("tradingRequestNode")).thenReturn(tradingNode);
            handler = new RoutingResultHandler(
                    mock(Step1AnalyzerNode.class),
                    mock(IntelligentInspection.class),
                    mock(GeneralChatNode.class),
                    mock(MultiTaskExecutionNode.class),
                    applicationContext,
                    mock(ObservabilityService.class));
        }

        private DefaultAutoAgentExecuteStrategyFactory.DynamicContext context() {
            DefaultAutoAgentExecuteStrategyFactory.DynamicContext context =
                    new DefaultAutoAgentExecuteStrategyFactory.DynamicContext();
            context.setAiAgentClientFlowConfigVOMap(Map.of(
                    AiClientTypeEnumVO.INTENT_ROUTING.getCode(),
                    AiAgentClientFlowConfigVO.builder().clientId("3201").build()));
            context.setValue("emitter", mock(ResponseBodyEmitter.class));
            return context;
        }

        private void route(DefaultAutoAgentExecuteStrategyFactory.DynamicContext context,
                           MultiIntentRoutingResult result) throws Exception {
            handler.handle(command, context, result);
        }
    }

    private static class CompletingPipeline extends TradingPipeline {
        private CompletingPipeline() {
            super(List.of());
        }

        @Override
        public void execute(TradingStateContext context) {
            context.getTradingContext().setFinalDecision(TradingContextVO.FinalTradeDecisionVO.builder()
                    .decision("HOLD")
                    .confidence("MEDIUM")
                    .reasoning("integration stub")
                    .build());
            context.transitionTo(TradingPhase.FINAL_REPORT);
        }
    }

    private static class CountingStockDataProvider implements IStockDataProvider {
        private final AtomicInteger identityLookups = new AtomicInteger();
        private final AtomicInteger stockInfoLookups = new AtomicInteger();
        private final Map<String, StockIdentityVO> identities = Map.of(
                "603259", new StockIdentityVO("603259.SH", "药明康德", "医药"),
                "000001", new StockIdentityVO("000001.SZ", "平安银行", "银行"),
                "600000", new StockIdentityVO("600000.SH", "浦发银行", "银行"));

        @Override
        public List<StockIdentityVO> findStockIdentities(String candidate) {
            identityLookups.incrementAndGet();
            String code = candidate.substring(0, 6);
            StockIdentityVO identity = identities.get(code);
            return identity == null ? List.of() : List.of(identity);
        }

        @Override
        public StockInfoVO getStockInfo(String ticker) {
            stockInfoLookups.incrementAndGet();
            StockIdentityVO identity = identities.get(ticker.substring(0, 6));
            return StockInfoVO.builder()
                    .ticker(ticker)
                    .name(identity.stockName())
                    .exchange(ticker.endsWith(".SH") ? "SSE" : "SZSE")
                    .currentPrice(BigDecimal.TEN)
                    .build();
        }

        @Override public List<OHLCVBarVO> getHistoricalBars(String ticker, String startDate, String endDate) { return List.of(); }
        @Override public TechnicalIndicatorsVO getTechnicalIndicators(String ticker, String startDate, String endDate) { return null; }
        @Override public FundamentalDataVO getFundamentalData(String ticker) { return null; }
        @Override public List<NewsItemVO> getNews(String ticker, int limit) { return List.of(); }
        @Override public SentimentDataVO getSentiment(String ticker) { return null; }
        @Override public List<StockSearchResultVO> searchByName(String name) { return List.of(); }
    }

    private static class StubPromptRepository implements TradingPromptRepository {
        @Override
        public List<TradingPromptRecord> findVersionSet(Set<String> promptIds, int promptType, int version) {
            return List.of();
        }

        @Override
        public List<TradingPromptRecord> findActiveSet(Set<String> promptIds, int promptType) {
            TradingPromptRenderer renderer = new TradingPromptRenderer();
            return promptIds.stream()
                    .map(id -> new TradingPromptRecord(Long.valueOf(id), id, 2, 1,
                            renderer.requiredPlaceholders(id).stream()
                                    .map(name -> "{{" + name + "}}")
                                    .collect(Collectors.joining("\n")), true))
                    .toList();
        }

        @Override public void deactivateAll(Set<String> promptIds, int promptType) { }
        @Override public int activateVersion(Set<String> promptIds, int promptType, int version) { return promptIds.size(); }
    }
}
