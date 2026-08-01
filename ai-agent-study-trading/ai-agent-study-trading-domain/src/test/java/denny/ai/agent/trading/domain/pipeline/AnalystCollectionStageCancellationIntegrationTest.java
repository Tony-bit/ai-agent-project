package denny.ai.agent.trading.domain.pipeline;

import denny.ai.agent.domain.service.auto.step.factory.DefaultAutoAgentExecuteStrategyFactory;
import denny.ai.agent.domain.service.sse.SseEventSink;
import denny.ai.agent.domain.service.sse.SseSessionState;
import denny.ai.agent.trading.api.vo.AnalystTypeEnum;
import denny.ai.agent.trading.api.vo.FundamentalReportVO;
import denny.ai.agent.trading.api.vo.StockAnalysisRequestVO;
import denny.ai.agent.trading.domain.config.TradingAgentProperties;
import denny.ai.agent.trading.domain.config.TradingStateContext;
import denny.ai.agent.trading.domain.guard.DataSanityGuard;
import denny.ai.agent.trading.domain.node.FundamentalAnalystNode;
import denny.ai.agent.trading.domain.node.NewsAnalystNode;
import denny.ai.agent.trading.domain.node.SentimentAnalystNode;
import denny.ai.agent.trading.domain.node.TechnicalAnalystNode;
import denny.ai.agent.trading.domain.vo.TradingContextVO;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import reactor.core.publisher.Flux;
import reactor.core.Disposable;
import reactor.core.publisher.SignalType;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AnalystCollectionStageCancellationIntegrationTest {

    private ExecutorService workerExecutor;

    @AfterEach
    void tearDown() {
        if (workerExecutor != null) {
            workerExecutor.shutdownNow();
        }
    }

    @Test
    void should_interrupt_worker_and_cancel_stream_when_node_deadline_expires()
            throws Exception {
        BlockingFundamental analyst = new BlockingFundamental();
        AnalystCollectionStage stage = stage(analyst, Duration.ofMillis(500));
        Thread parent = new Thread(() -> stage.execute(context(null)), "stage-deadline");
        parent.start();
        assertTrue(analyst.subscribed.await(2, TimeUnit.SECONDS));

        parent.join(2000);

        assertFalse(parent.isAlive());
        assertCancelled(analyst);
    }

    @Test
    void should_interrupt_worker_and_cancel_stream_when_parent_is_interrupted()
            throws Exception {
        BlockingFundamental analyst = new BlockingFundamental();
        AnalystCollectionStage stage = stage(analyst, Duration.ofSeconds(5));
        Thread parent = new Thread(() -> stage.execute(context(null)), "stage-parent");
        parent.start();
        assertTrue(analyst.subscribed.await(2, TimeUnit.SECONDS));

        parent.interrupt();
        parent.join(1000);

        assertFalse(parent.isAlive());
        assertCancelled(analyst);
    }

    @Test
    void should_cancel_worker_stream_promptly_when_client_disconnects() throws Exception {
        BlockingFundamental analyst = new BlockingFundamental();
        MutableSink sink = new MutableSink();
        AnalystCollectionStage stage = stage(analyst, Duration.ofSeconds(5));
        Thread parent = new Thread(() -> stage.execute(context(sink)), "stage-client");
        parent.start();
        assertTrue(analyst.subscribed.await(2, TimeUnit.SECONDS));

        sink.continueRequest.set(false);
        parent.join(1000);

        assertFalse(parent.isAlive());
        assertCancelled(analyst);
    }

    private AnalystCollectionStage stage(BlockingFundamental analyst, Duration timeout) {
        workerExecutor = Executors.newSingleThreadExecutor();
        AnalystCollectionStage stage = new AnalystCollectionStage(
                analyst, new TechnicalAnalystNode(), new SentimentAnalystNode(),
                new NewsAnalystNode(), workerExecutor, new DataSanityGuard());
        TradingAgentProperties properties = new TradingAgentProperties();
        properties.setNodeTimeout(timeout);
        ReflectionTestUtils.setField(stage, "tradingAgentProperties", properties);
        return stage;
    }

    private TradingStateContext context(SseEventSink sink) {
        StockAnalysisRequestVO request = new StockAnalysisRequestVO();
        request.setTicker("000001");
        request.setSelectedAnalysts(List.of(AnalystTypeEnum.FUNDAMENTAL));
        DefaultAutoAgentExecuteStrategyFactory.DynamicContext dynamic =
                new DefaultAutoAgentExecuteStrategyFactory.DynamicContext();
        if (sink != null) {
            dynamic.setValue("sseEventSink", sink);
        }
        TradingStateContext context = denny.ai.agent.trading.domain.support.TestTargets.stateContext(
                request, dynamic, (type, event) -> true);
        dynamic.setValue("trading_context", context.getTradingContext());
        return context;
    }

    private void assertCancelled(BlockingFundamental analyst) throws Exception {
        assertTrue(await(() -> analyst.active.get() == 0, Duration.ofSeconds(1)));
        assertTrue(analyst.workerInterrupted.get());
        assertEquals(1, analyst.subscriptions.get());
        assertEquals(1, analyst.cancelled.get());
        assertEquals(SignalType.CANCEL, analyst.finalSignal.get());
        assertFalse(analyst.returnedResult.get());
        Thread.sleep(100);
        assertEquals(1, analyst.subscriptions.get());
    }

    private boolean await(java.util.function.BooleanSupplier condition, Duration timeout)
            throws InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return true;
            }
            Thread.sleep(10);
        }
        return condition.getAsBoolean();
    }

    private static final class BlockingFundamental extends FundamentalAnalystNode {
        private final CountDownLatch subscribed = new CountDownLatch(1);
        private final AtomicInteger subscriptions = new AtomicInteger();
        private final AtomicInteger cancelled = new AtomicInteger();
        private final AtomicInteger active = new AtomicInteger();
        private final AtomicReference<SignalType> finalSignal = new AtomicReference<>();
        private final AtomicBoolean workerInterrupted = new AtomicBoolean();
        private final AtomicBoolean returnedResult = new AtomicBoolean();

        @Override
        public FundamentalReportVO prepare(
                TradingContextVO context,
                DefaultAutoAgentExecuteStrategyFactory.DynamicContext dynamicContext) {
            Disposable subscription = Flux.concat(Flux.just("partial"), Flux.<String>never())
                    .doOnSubscribe(ignored -> {
                        subscriptions.incrementAndGet();
                        active.incrementAndGet();
                        subscribed.countDown();
                    })
                    .doOnCancel(cancelled::incrementAndGet)
                    .doFinally(signal -> {
                        finalSignal.set(signal);
                        active.decrementAndGet();
                    })
                    .subscribe();
            try {
                new CountDownLatch(1).await();
                returnedResult.set(true);
                return FundamentalReportVO.builder().rating(3).build();
            } catch (InterruptedException interrupted) {
                workerInterrupted.set(true);
                subscription.dispose();
                Thread.currentThread().interrupt();
                throw new RuntimeException("analyst interrupted", interrupted);
            }
        }
    }

    private static final class MutableSink implements SseEventSink {
        private final AtomicBoolean continueRequest = new AtomicBoolean(true);

        @Override
        public boolean sendBusiness(String eventName, Object payload) {
            return continueRequest.get();
        }

        @Override
        public boolean trySendHeartbeat() {
            return continueRequest.get();
        }

        @Override
        public void complete() {
        }

        @Override
        public void markDisconnected(Throwable cause) {
            continueRequest.set(false);
        }

        @Override
        public boolean isDisconnected() {
            return !continueRequest.get();
        }

        @Override
        public boolean shouldContinue() {
            return continueRequest.get();
        }

        @Override
        public SseSessionState state() {
            return null;
        }
    }
}
