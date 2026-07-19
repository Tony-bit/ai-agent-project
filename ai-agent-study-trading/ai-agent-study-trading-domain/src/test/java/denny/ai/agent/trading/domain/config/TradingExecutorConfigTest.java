package denny.ai.agent.trading.domain.config;

import denny.ai.agent.domain.service.auto.step.factory.DefaultAutoAgentExecuteStrategyFactory;
import denny.ai.agent.trading.api.vo.StockAnalysisRequestVO;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TradingExecutorConfigTest {

    private ExecutorService orchestrationExecutor;
    private ExecutorService taskExecutor;
    private ExecutorService exportExecutor;

    @AfterEach
    void tearDown() {
        TradingDriver.clear();
        shutdown(orchestrationExecutor);
        shutdown(taskExecutor);
        shutdown(exportExecutor);
    }

    @Test
    void tradingExecutorsUseDistinctThreadPrefixes() throws Exception {
        TradingExecutorConfig config = new TradingExecutorConfig();
        orchestrationExecutor = config.tradingOrchestrationExecutor();
        taskExecutor = config.tradingTaskExecutor();
        exportExecutor = config.tradingExportExecutor();

        assertThreadNameStartsWith(orchestrationExecutor, "trading-orchestration-");
        assertThreadNameStartsWith(taskExecutor, "trading-task-");
        assertThreadNameStartsWith(exportExecutor, "trading-export-");
    }

    @Test
    void onlyTaskExecutorPropagatesLegacyTradingDriver() throws Exception {
        TradingExecutorConfig config = new TradingExecutorConfig();
        orchestrationExecutor = config.tradingOrchestrationExecutor();
        taskExecutor = config.tradingTaskExecutor();
        exportExecutor = config.tradingExportExecutor();

        TradingStateContext stateContext = new TradingStateContext(
                createRequest(),
                new DefaultAutoAgentExecuteStrategyFactory.DynamicContext(),
                (type, event) -> {
                    return true;
                }
        );
        TradingDriver driver = new TradingDriver(stateContext, new TradingDispatcher());
        TradingDriver.setCurrent(driver);

        assertDriverInExecutor(taskExecutor, driver);
        assertDriverInExecutor(orchestrationExecutor, null);
        assertDriverInExecutor(exportExecutor, null);
    }

    private StockAnalysisRequestVO createRequest() {
        StockAnalysisRequestVO request = new StockAnalysisRequestVO();
        request.setTicker("000001");
        return request;
    }

    private void assertThreadNameStartsWith(ExecutorService executor, String prefix) throws Exception {
        AtomicReference<String> threadName = new AtomicReference<>();
        CountDownLatch done = new CountDownLatch(1);

        executor.execute(() -> {
            threadName.set(Thread.currentThread().getName());
            done.countDown();
        });

        assertTrue(done.await(2, TimeUnit.SECONDS), "executor task should finish");
        assertTrue(threadName.get().startsWith(prefix),
                "thread name should start with " + prefix + " but was " + threadName.get());
    }

    private void assertDriverInExecutor(ExecutorService executor, TradingDriver expectedDriver) throws Exception {
        AtomicReference<TradingDriver> actualDriver = new AtomicReference<>();
        CountDownLatch done = new CountDownLatch(1);

        executor.execute(() -> {
            actualDriver.set(TradingDriver.getCurrent());
            done.countDown();
        });

        assertTrue(done.await(2, TimeUnit.SECONDS), "executor task should finish");
        if (expectedDriver == null) {
            assertNull(actualDriver.get(), "executor should not propagate TradingDriver");
        } else {
            assertSame(expectedDriver, actualDriver.get(), "executor should propagate the legacy TradingDriver");
        }
    }

    private void shutdown(ExecutorService executor) {
        if (executor != null) {
            executor.shutdownNow();
        }
    }
}
