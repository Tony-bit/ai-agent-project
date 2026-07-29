package denny.ai.agent.trading.domain.config;

import denny.ai.agent.domain.model.entity.AutoAgentExecuteResultEntity;
import denny.ai.agent.domain.service.auto.step.factory.DefaultAutoAgentExecuteStrategyFactory;
import denny.ai.agent.domain.service.sse.SseEventSink;
import denny.ai.agent.domain.service.sse.SseSessionState;
import denny.ai.agent.trading.api.context.TradingTargetContextKeys;
import denny.ai.agent.trading.api.provider.IStockDataProvider;
import denny.ai.agent.trading.api.vo.*;
import denny.ai.agent.trading.domain.pipeline.TradingPipeline;
import denny.ai.agent.trading.domain.service.TargetContextFactory;
import denny.ai.agent.trading.domain.prompt.*;
import denny.ai.agent.trading.domain.vo.TradingContextVO;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyEmitter;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;

import static org.junit.jupiter.api.Assertions.*;

class TradingStarterPipelineTest {

    @Test
    void startCreatesAndExposesOneRunIdentity() {
        TradingStarter starter = createStarter(new CompletingPipeline(true));
        DefaultAutoAgentExecuteStrategyFactory.DynamicContext first =
                new DefaultAutoAgentExecuteStrategyFactory.DynamicContext();
        DefaultAutoAgentExecuteStrategyFactory.DynamicContext second =
                new DefaultAutoAgentExecuteStrategyFactory.DynamicContext();

        starter.start(createRequest(), first, (type, event) -> true);
        starter.start(createRequest(), second, (type, event) -> true);

        TargetContext firstTarget = first.getValue(TradingTargetContextKeys.TARGET_CONTEXT);
        TargetContext secondTarget = second.getValue(TradingTargetContextKeys.TARGET_CONTEXT);
        TradingContextVO firstTrading = first.getValue("trading_context");
        assertNotNull(firstTarget);
        assertEquals("000001.SZ", firstTarget.targetId());
        assertEquals(firstTarget.runId(), first.getValue("trading_run_id"));
        assertEquals(firstTarget.targetId(), first.getValue("trading_target_id"));
        TradingPromptSnapshot snapshot = first.getValue("trading_prompt_snapshot");
        assertEquals(firstTarget.runId(), snapshot.runId());
        assertSame(firstTarget, firstTrading.getTargetContext());
        assertNotEquals(firstTarget.runId(), secondTarget.runId());
    }

    @Test
    void embeddedTradingDoesNotCompleteOuterEmitter() {
        TradingStarter starter = createStarter(new CompletingPipeline(true));
        DefaultAutoAgentExecuteStrategyFactory.DynamicContext dynamicContext =
                new DefaultAutoAgentExecuteStrategyFactory.DynamicContext();
        CountingEmitter emitter = new CountingEmitter();
        dynamicContext.setValue("emitter", emitter);
        List<AutoAgentExecuteResultEntity> events = new ArrayList<>();

        starter.start(createRequest(), dynamicContext, (type, event) -> events.add((AutoAgentExecuteResultEntity) event));

        assertNull(dynamicContext.getValue("taskLatch"), "pipeline path must not create legacy taskLatch");
        assertEquals(0, emitter.completeCount,
                "TradingStarter must not complete an outer auto_agent emitter");
        assertEquals(1, events.stream().filter(event -> "trading_complete".equals(event.getSubType())).count());
        assertNotNull(((TradingContextVO) dynamicContext.getValue("trading_context")).getFinalDecision());
        assertNull(TradingDriver.getCurrent());
    }

    @Test
    void dedicatedTradingCompletesOwnedSinkButNotRawEmitter() {
        TradingStarter starter = createStarter(new CompletingPipeline(true));
        DefaultAutoAgentExecuteStrategyFactory.DynamicContext dynamicContext =
                new DefaultAutoAgentExecuteStrategyFactory.DynamicContext();
        CountingEmitter emitter = new CountingEmitter();
        CountingSink sink = new CountingSink();
        dynamicContext.setValue("emitter", emitter);
        dynamicContext.setValue("sseEventSink", sink);

        starter.start(createRequest(), dynamicContext, (type, event) -> true);

        assertEquals(1, sink.completeCount);
        assertEquals(0, emitter.completeCount);
    }

    @Test
    void startForSubTaskUsesPipelineAndReturnsResultText() {
        TradingStarter starter = createStarter(new CompletingPipeline(true));
        DefaultAutoAgentExecuteStrategyFactory.DynamicContext dynamicContext =
                new DefaultAutoAgentExecuteStrategyFactory.DynamicContext();

        String result = starter.startForSubTask(
                "分析平安银行",
                Map.of("stockCode", "000001"),
                dynamicContext
        );

        assertNull(dynamicContext.getValue("taskLatch"), "subtask pipeline path must not create legacy taskLatch");
        assertTrue(result.contains("【股票信息】"));
        assertTrue(result.contains("【最终决策】"));
        assertNull(TradingDriver.getCurrent());
    }

    @Test
    void stockSubTasksDoNotReuseRunOrMutableTradingContext() {
        TradingStarter starter = createStarter(new CompletingPipeline(true));
        DefaultAutoAgentExecuteStrategyFactory.DynamicContext dynamicContext =
                new DefaultAutoAgentExecuteStrategyFactory.DynamicContext();

        starter.startForSubTask("分析平安银行", Map.of("stockCode", "000001"), dynamicContext);
        TargetContext firstTarget = dynamicContext.getValue(TradingTargetContextKeys.TARGET_CONTEXT);
        TradingContextVO firstContext = dynamicContext.getValue("trading_context");

        starter.startForSubTask("分析浦发银行", Map.of("stockCode", "600000"), dynamicContext);
        TargetContext secondTarget = dynamicContext.getValue(TradingTargetContextKeys.TARGET_CONTEXT);
        TradingContextVO secondContext = dynamicContext.getValue("trading_context");

        assertNotEquals(firstTarget.runId(), secondTarget.runId());
        assertNotEquals(firstTarget.targetId(), secondTarget.targetId());
        assertNotSame(firstContext, secondContext);
        assertSame(firstTarget, firstContext.getTargetContext());
        assertSame(secondTarget, secondContext.getTargetContext());
    }

    @Test
    void startReturnsWhenLegacyLatchWouldRemainUnreleased() {
        TradingStarter starter = createStarter(new CompletingPipeline(false));
        DefaultAutoAgentExecuteStrategyFactory.DynamicContext dynamicContext =
                new DefaultAutoAgentExecuteStrategyFactory.DynamicContext();
        dynamicContext.setValue("taskLatch", new CountDownLatch(1));

        starter.start(createRequest(), dynamicContext, (type, event) -> {
            return true;
        });

        assertEquals(1L, ((CountDownLatch) dynamicContext.getValue("taskLatch")).getCount(),
                "pre-existing legacy latch should not be awaited or counted down by pipeline path");
    }

    private TradingStarter createStarter(TradingPipeline pipeline) {
        TradingStarter starter = new TradingStarter();
        ReflectionTestUtils.setField(starter, "dataProvider", new StubStockDataProvider());
        ReflectionTestUtils.setField(starter, "targetContextFactory",
                new TargetContextFactory(new StubStockDataProvider()));
        ReflectionTestUtils.setField(starter, "promptSnapshotFactory",
                new TradingPromptSnapshotFactory(
                        new StubPromptRepository(), new TradingPromptRenderer()));
        ReflectionTestUtils.setField(starter, "tradingPipeline", pipeline);
        ReflectionTestUtils.setField(starter, "tradingDispatcher", new TradingDispatcher());
        return starter;
    }

    private StockAnalysisRequestVO createRequest() {
        StockAnalysisRequestVO request = new StockAnalysisRequestVO();
        request.setTicker("000001");
        return request;
    }

    private static class CompletingPipeline extends TradingPipeline {
        private final boolean expectNoLatch;

        private CompletingPipeline(boolean expectNoLatch) {
            super(List.of());
            this.expectNoLatch = expectNoLatch;
        }

        @Override
        public void execute(TradingStateContext context) {
            if (expectNoLatch) {
                assertNull(context.getDynamicContext().getValue("taskLatch"),
                        "TradingStarter should not create taskLatch before invoking pipeline");
            }
            context.getTradingContext().setFinalDecision(TradingContextVO.FinalTradeDecisionVO.builder()
                    .decision("HOLD")
                    .confidence("MEDIUM")
                    .reasoning("stub")
                    .build());
            context.transitionTo(TradingPhase.FINAL_REPORT);
        }
    }

    private static class CountingEmitter extends ResponseBodyEmitter {
        private int completeCount;

        @Override
        public synchronized void complete() {
            completeCount++;
        }
    }

    private static class CountingSink implements SseEventSink {
        private int completeCount;

        @Override
        public boolean sendBusiness(String eventName, Object payload) {
            return true;
        }

        @Override
        public boolean trySendHeartbeat() {
            return true;
        }

        @Override
        public void complete() {
            completeCount++;
        }

        @Override
        public void markDisconnected(Throwable cause) {
        }

        @Override
        public boolean isDisconnected() {
            return false;
        }

        @Override
        public boolean shouldContinue() {
            return true;
        }

        @Override
        public SseSessionState state() {
            return SseSessionState.OPEN;
        }
    }

    private static class StubStockDataProvider implements IStockDataProvider {
        @Override
        public StockInfoVO getStockInfo(String ticker) {
            return StockInfoVO.builder()
                    .ticker(ticker)
                    .name("平安银行")
                    .exchange("SZSE")
                    .currentPrice(BigDecimal.TEN)
                    .build();
        }

        @Override
        public List<OHLCVBarVO> getHistoricalBars(String ticker, String startDate, String endDate) {
            return List.of();
        }

        @Override
        public TechnicalIndicatorsVO getTechnicalIndicators(String ticker, String startDate, String endDate) {
            return null;
        }

        @Override
        public FundamentalDataVO getFundamentalData(String ticker) {
            return null;
        }

        @Override
        public List<NewsItemVO> getNews(String ticker, int limit) {
            return List.of();
        }

        @Override
        public SentimentDataVO getSentiment(String ticker) {
            return null;
        }

        @Override
        public List<StockSearchResultVO> searchByName(String name) {
            return List.of();
        }
    }

    private static class StubPromptRepository implements TradingPromptRepository {
        @Override
        public List<TradingPromptRecord> findVersionSet(
                java.util.Set<String> promptIds, int promptType, int version) {
            return List.of();
        }

        @Override
        public List<TradingPromptRecord> findActiveSet(
                java.util.Set<String> promptIds, int promptType) {
            return promptIds.stream()
                    .map(id -> new TradingPromptRecord(Long.valueOf(id), id, 2, 1,
                            new TradingPromptRenderer().requiredPlaceholders(id).stream()
                                    .map(name -> "{{" + name + "}}")
                                    .collect(java.util.stream.Collectors.joining("\n")), true))
                    .toList();
        }

        @Override public void deactivateAll(java.util.Set<String> promptIds, int promptType) { }
        @Override public int activateVersion(java.util.Set<String> promptIds, int promptType, int version) {
            return promptIds.size();
        }
    }
}
