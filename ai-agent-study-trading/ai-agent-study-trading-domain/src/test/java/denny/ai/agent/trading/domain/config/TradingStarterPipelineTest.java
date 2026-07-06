package denny.ai.agent.trading.domain.config;

import denny.ai.agent.domain.model.entity.AutoAgentExecuteResultEntity;
import denny.ai.agent.domain.service.auto.step.factory.DefaultAutoAgentExecuteStrategyFactory;
import denny.ai.agent.trading.api.provider.IStockDataProvider;
import denny.ai.agent.trading.api.vo.*;
import denny.ai.agent.trading.domain.pipeline.TradingPipeline;
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
    void startUsesPipelineWithoutWaitingLegacyLatchAndCompletesEmitterOnce() {
        TradingStarter starter = createStarter(new CompletingPipeline(true));
        DefaultAutoAgentExecuteStrategyFactory.DynamicContext dynamicContext =
                new DefaultAutoAgentExecuteStrategyFactory.DynamicContext();
        CountingEmitter emitter = new CountingEmitter();
        dynamicContext.setValue("emitter", emitter);
        List<AutoAgentExecuteResultEntity> events = new ArrayList<>();

        starter.start(createRequest(), dynamicContext, (type, event) -> events.add((AutoAgentExecuteResultEntity) event));

        assertNull(dynamicContext.getValue("taskLatch"), "pipeline path must not create legacy taskLatch");
        assertEquals(1, emitter.completeCount);
        assertEquals(1, events.stream().filter(event -> "trading_complete".equals(event.getSubType())).count());
        assertNotNull(((TradingContextVO) dynamicContext.getValue("trading_context")).getFinalDecision());
        assertNull(TradingDriver.getCurrent());
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
    void startReturnsWhenLegacyLatchWouldRemainUnreleased() {
        TradingStarter starter = createStarter(new CompletingPipeline(false));
        DefaultAutoAgentExecuteStrategyFactory.DynamicContext dynamicContext =
                new DefaultAutoAgentExecuteStrategyFactory.DynamicContext();
        dynamicContext.setValue("taskLatch", new CountDownLatch(1));

        starter.start(createRequest(), dynamicContext, (type, event) -> {
        });

        assertEquals(1L, ((CountDownLatch) dynamicContext.getValue("taskLatch")).getCount(),
                "pre-existing legacy latch should not be awaited or counted down by pipeline path");
    }

    private TradingStarter createStarter(TradingPipeline pipeline) {
        TradingStarter starter = new TradingStarter();
        ReflectionTestUtils.setField(starter, "dataProvider", new StubStockDataProvider());
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
}
