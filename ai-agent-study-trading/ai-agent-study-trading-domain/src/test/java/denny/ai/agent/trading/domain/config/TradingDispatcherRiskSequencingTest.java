package denny.ai.agent.trading.domain.config;

import denny.ai.agent.domain.model.entity.ExecuteCommandEntity;
import denny.ai.agent.domain.service.auto.step.factory.DefaultAutoAgentExecuteStrategyFactory;
import denny.ai.agent.trading.api.vo.StockAnalysisRequestVO;
import denny.ai.agent.trading.domain.node.AggressiveRiskAnalystNode;
import denny.ai.agent.trading.domain.node.ConservativeRiskAnalystNode;
import denny.ai.agent.trading.domain.node.NeutralRiskAnalystNode;
import denny.ai.agent.trading.domain.node.PortfolioManagerNode;
import denny.ai.agent.trading.domain.vo.TradingContextVO;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TradingDispatcherRiskSequencingTest {

    private ThreadPoolExecutor executor;

    @BeforeEach
    void setUp() {
        TradingDriver.clear();
        executor = new ThreadPoolExecutor(
                4,
                4,
                30L,
                TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(),
                r -> new Thread(r, "risk-sequence-test-" + r.hashCode())
        ) {
            @Override
            public void execute(Runnable command) {
                TradingDriver capturedDriver = TradingDriver.getCurrent();
                super.execute(() -> {
                    try {
                        if (capturedDriver != null) {
                            TradingDriver.setCurrent(capturedDriver);
                        }
                        command.run();
                    } finally {
                        TradingDriver.clear();
                    }
                });
            }
        };
    }

    @AfterEach
    void tearDown() {
        TradingDriver.clear();
        if (executor != null) {
            executor.shutdownNow();
        }
    }

    @Test
    void riskNodesDoNotAdvanceFromFutureCallbackBeforeCurrentNodeCompletes() throws Exception {
        List<String> events = Collections.synchronizedList(new ArrayList<>());
        CountDownLatch conservativeStarted = new CountDownLatch(1);
        CountDownLatch releaseConservative = new CountDownLatch(1);
        CountDownLatch neutralStarted = new CountDownLatch(1);
        CountDownLatch flowDone = new CountDownLatch(1);

        TradingDispatcher dispatcher = new TradingDispatcher();
        ReflectionTestUtils.setField(dispatcher, "tradingTaskExecutor", executor);
        ReflectionTestUtils.setField(dispatcher, "aggressiveRiskAnalystNode", new SelfReportingAggressive(events));
        ReflectionTestUtils.setField(dispatcher, "conservativeRiskAnalystNode",
                new BlockingConservative(events, conservativeStarted, releaseConservative));
        ReflectionTestUtils.setField(dispatcher, "neutralRiskAnalystNode",
                new SelfReportingNeutral(events, neutralStarted));
        ReflectionTestUtils.setField(dispatcher, "portfolioManagerNode", new PortfolioStub(events));

        DefaultAutoAgentExecuteStrategyFactory.DynamicContext dynamicContext =
                new DefaultAutoAgentExecuteStrategyFactory.DynamicContext();
        dynamicContext.setValue("taskLatch", flowDone);

        TradingStateContext stateContext = new TradingStateContext(
                createRequest(),
                dynamicContext,
                (type, event) -> events.add("sse:" + type)
        );
        stateContext.getTradingContext().setRiskDebate(new TradingContextVO.RiskDebateVO());
        stateContext.getTradingContext().getRiskDebate().setMaxRounds(1);
        dynamicContext.setValue("trading_context", stateContext.getTradingContext());

        TradingDriver driver = new TradingDriver(stateContext, dispatcher);
        TradingDriver.setCurrent(driver);

        stateContext.transitionTo(TradingPhase.RECOMMENDATION_DECISION);
        dispatcher.onEvent(TradingEvent.RECOMMENDATION_COMPLETE, stateContext);

        assertTrue(conservativeStarted.await(5, TimeUnit.SECONDS),
                "conservative risk node should start after aggressive completes");
        assertFalse(neutralStarted.await(300, TimeUnit.MILLISECONDS),
                "neutral risk node must not start while conservative is still running");

        releaseConservative.countDown();

        assertTrue(neutralStarted.await(5, TimeUnit.SECONDS),
                "neutral risk node should start after conservative reports completion");
        assertTrue(events.indexOf("conservative:end") < events.indexOf("neutral:start"),
                "neutral should start only after conservative has fully completed");
        assertTrue(flowDone.await(5, TimeUnit.SECONDS),
                "portfolio completion should count down the trading task latch");
    }

    @Test
    void sendErrorCountsDownTaskLatchSoSseCanClose() throws Exception {
        CountDownLatch flowDone = new CountDownLatch(1);
        DefaultAutoAgentExecuteStrategyFactory.DynamicContext dynamicContext =
                new DefaultAutoAgentExecuteStrategyFactory.DynamicContext();
        dynamicContext.setValue("taskLatch", flowDone);

        TradingStateContext stateContext = new TradingStateContext(
                createRequest(),
                dynamicContext,
                (type, event) -> {
                }
        );

        stateContext.sendError("节点执行异常: Read timed out");

        assertEquals(TradingPhase.ERROR, stateContext.getCurrentPhase());
        assertTrue(flowDone.await(1, TimeUnit.SECONDS),
                "error flow should release the outer TradingStarter latch");
    }

    private StockAnalysisRequestVO createRequest() {
        StockAnalysisRequestVO request = new StockAnalysisRequestVO();
        request.setTicker("000001");
        request.setMaxRiskRounds(1);
        return request;
    }

    private static class SelfReportingAggressive extends AggressiveRiskAnalystNode {
        private final List<String> events;

        private SelfReportingAggressive(List<String> events) {
            this.events = events;
        }

        @Override
        public String doApply(ExecuteCommandEntity requestParameter,
                              DefaultAutoAgentExecuteStrategyFactory.DynamicContext dynamicContext) {
            events.add("aggressive:start");
            TradingContextVO context = dynamicContext.getValue("trading_context");
            context.getRiskDebate().getAggressiveHistory().add("aggressive opinion");
            events.add("aggressive:end");
            TradingDriver.getCurrent().riskDebateComplete();
            return "aggressive_done";
        }
    }

    private static class BlockingConservative extends ConservativeRiskAnalystNode {
        private final List<String> events;
        private final CountDownLatch started;
        private final CountDownLatch release;

        private BlockingConservative(List<String> events, CountDownLatch started, CountDownLatch release) {
            this.events = events;
            this.started = started;
            this.release = release;
        }

        @Override
        public String doApply(ExecuteCommandEntity requestParameter,
                              DefaultAutoAgentExecuteStrategyFactory.DynamicContext dynamicContext) throws Exception {
            events.add("conservative:start");
            started.countDown();
            assertTrue(release.await(5, TimeUnit.SECONDS), "test should release conservative node");
            TradingContextVO context = dynamicContext.getValue("trading_context");
            context.getRiskDebate().getConservativeHistory().add("conservative opinion");
            events.add("conservative:end");
            TradingDriver.getCurrent().riskDebateComplete();
            return "conservative_done";
        }
    }

    private static class SelfReportingNeutral extends NeutralRiskAnalystNode {
        private final List<String> events;
        private final CountDownLatch started;

        private SelfReportingNeutral(List<String> events, CountDownLatch started) {
            this.events = events;
            this.started = started;
        }

        @Override
        public String doApply(ExecuteCommandEntity requestParameter,
                              DefaultAutoAgentExecuteStrategyFactory.DynamicContext dynamicContext) {
            events.add("neutral:start");
            started.countDown();
            TradingContextVO context = dynamicContext.getValue("trading_context");
            context.getRiskDebate().getNeutralHistory().add("neutral opinion");
            events.add("neutral:end");
            TradingDriver.getCurrent().riskDebateComplete();
            return "neutral_done";
        }
    }

    private static class PortfolioStub extends PortfolioManagerNode {
        private final List<String> events;

        private PortfolioStub(List<String> events) {
            this.events = events;
        }

        @Override
        public String doApply(ExecuteCommandEntity requestParameter,
                              DefaultAutoAgentExecuteStrategyFactory.DynamicContext dynamicContext) {
            events.add("portfolio:start");
            return "portfolio_done";
        }
    }
}
