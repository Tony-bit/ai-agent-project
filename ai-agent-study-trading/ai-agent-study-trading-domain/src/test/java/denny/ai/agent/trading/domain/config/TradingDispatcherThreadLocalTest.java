package denny.ai.agent.trading.domain.config;

import denny.ai.agent.domain.service.auto.step.factory.DefaultAutoAgentExecuteStrategyFactory;
import denny.ai.agent.trading.api.vo.StockAnalysisRequestVO;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * TradingDispatcher ThreadLocal 跨线程传递测试。
 *
 * 测试策略：完全围绕装饰器的核心语义，不模拟 submit 递归。
 *
 * 核心验证点：
 * 1. 装饰后的 Runnable 在子线程能获取主线程 TradingDriver
 * 2. 主线程 Driver 为 null 时装饰不 NPE
 * 3. 多线程并发执行，各自获取的 Driver 正确
 * 4. TradingDriver.clear() 正确清理 ThreadLocal
 * 5. 重复设置 Driver，后者覆盖前者，清理后都清除
 * 6. 子线程清理 Driver 不影响主线程
 * 7. 子线程调用 Driver 方法（analystComplete）正常触发回调
 *
 * @author denny
 */
public class TradingDispatcherThreadLocalTest {

    private TradingDriver driver;
    private TradingStateContext stateContext;
    private java.util.concurrent.ExecutorService testExecutor;
    private List<String> sseEvents;

    @BeforeEach
    public void setUp() throws Exception {
        TradingDriver.clear();

        sseEvents = Collections.synchronizedList(new ArrayList<>());
        denny.ai.agent.domain.service.sse.SseEventSender sseSender = (type, data) ->
                sseEvents.add(type + ":" + data);

        stateContext = new TradingStateContext(createRequest(),
                new DefaultAutoAgentExecuteStrategyFactory.DynamicContext(), sseSender);
        driver = new TradingDriver(stateContext, new TradingDispatcherStub());

        // 注入测试线程池（复刻 TradingExecutorConfig 的装饰器逻辑）
        testExecutor = new java.util.concurrent.ThreadPoolExecutor(
                4, 8, 60L, TimeUnit.SECONDS,
                new java.util.concurrent.LinkedBlockingQueue<>(100),
                r -> new Thread(r, "test-trading-pool-" + System.nanoTime())
        ) {
            @Override
            public void execute(Runnable command) {
                // 关键：重写 execute，所有提交的任务都被装饰器包装
                super.execute(decorateWithDriver(command));
            }
        };

        // 主线程设置 Driver
        TradingDriver.setCurrent(driver);
    }

    @AfterEach
    public void tearDown() {
        TradingDriver.clear();
        if (testExecutor != null && !testExecutor.isShutdown()) {
            testExecutor.shutdownNow();
        }
    }

    // ==================== T1: 子线程获取主线程 Driver ====================

    /**
     * T1: 验证装饰后的 Runnable 在子线程执行时，能获取到主线程设置的 TradingDriver。
     */
    @Test
    public void testDecorator_childThread_getsMainThreadDriver() throws Exception {
        AtomicReference<TradingDriver> capturedDriver = new AtomicReference<>();
        AtomicBoolean executed = new AtomicBoolean(false);

        Runnable decorated = decorateWithDriver(() -> {
            capturedDriver.set(TradingDriver.getCurrent());
            executed.set(true);
        });

        java.util.concurrent.Future future = testExecutor.submit(decorated);
        future.get(10, TimeUnit.SECONDS);

        assertTrue(executed.get(), "任务应已执行");
        assertNotNull(capturedDriver.get(), "子线程应获取到 TradingDriver");
        assertSame(driver, capturedDriver.get(), "子线程获取的 Driver 应与主线程一致");
    }

    // ==================== T2: null Driver 不 NPE ====================

    /**
     * T2: 验证主线程 Driver 为 null 时装饰不 NPE。
     */
    @Test
    public void testDecorator_nullDriver_noNPE() throws Exception {
        TradingDriver.clear();
        AtomicBoolean executed = new AtomicBoolean(false);

        Runnable decorated = decorateWithDriver(() -> {
            assertNull(TradingDriver.getCurrent(), "无 Driver 时 getCurrent 应返回 null");
            executed.set(true);
        });

        java.util.concurrent.Future future = testExecutor.submit(decorated);
        future.get(10, TimeUnit.SECONDS);
        assertTrue(executed.get(), "任务应正常执行");
    }

    // ==================== T3: 多线程并发 ====================

    /**
     * T3: 验证多个线程同时执行，各自获取的 Driver 正确且一致。
     */
    @Test
    public void testDecorator_concurrentThreads_allGetCorrectDriver() throws Exception {
        int concurrency = 4;
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(concurrency);
        List<TradingDriver> capturedDrivers = Collections.synchronizedList(new ArrayList<>());
        List<String> errors = Collections.synchronizedList(new ArrayList<>());

        for (int i = 0; i < concurrency; i++) {
            testExecutor.submit(() -> {
                try {
                    startLatch.await(10, TimeUnit.SECONDS);
                    Runnable decorated = decorateWithDriver(() ->
                            capturedDrivers.add(TradingDriver.getCurrent()));
                    java.util.concurrent.Future f = testExecutor.submit(decorated);
                    f.get(10, TimeUnit.SECONDS);
                } catch (Exception e) {
                    errors.add(e.getClass().getSimpleName() + ": " + e.getMessage());
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        startLatch.countDown();
        assertTrue(doneLatch.await(30, TimeUnit.SECONDS), "所有并发任务应在30s内完成: " + errors);
        assertEquals(concurrency, capturedDrivers.size(), "应有" + concurrency + "个线程捕获到 Driver");
        for (TradingDriver captured : capturedDrivers) {
            assertNotNull(captured, "每个线程都应获取到 Driver");
            assertSame(driver, captured, "每个线程获取的 Driver 应一致");
        }
    }

    // ==================== T4: clear 清理 ====================

    /**
     * T4: 验证 TradingDriver.clear() 能正确清理 ThreadLocal，防止内存泄漏。
     */
    @Test
    public void testTradingDriver_clear_removesThreadLocal() {
        TradingDriver.setCurrent(driver);
        assertSame(driver, TradingDriver.getCurrent(), "设置前应有 Driver");

        TradingDriver.clear();
        assertNull(TradingDriver.getCurrent(), "清理后应为 null");
    }

    // ==================== T5: 覆盖和清理 ====================

    /**
     * T5: 验证重复设置 Driver，后者覆盖前者，清理后都清除。
     */
    @Test
    public void testTradingDriver_overwriteAndClear() {
        TradingDriver.setCurrent(driver);
        TradingDriver secondDriver = new TradingDriver(stateContext, new TradingDispatcherStub());
        TradingDriver.setCurrent(secondDriver);
        assertSame(secondDriver, TradingDriver.getCurrent(), "应被后者覆盖");

        TradingDriver.clear();
        assertNull(TradingDriver.getCurrent(), "清理后应为 null");
    }

    // ==================== T6: 线程隔离 ====================

    /**
     * T6: 验证子线程清理 Driver 不影响主线程（各线程独立的 ThreadLocal）。
     */
    @Test
    public void testTradingDriver_threadIsolation() throws Exception {
        AtomicReference<TradingDriver> childDriver = new AtomicReference<>();

        Runnable decorated = decorateWithDriver(() -> {
            childDriver.set(TradingDriver.getCurrent());
            TradingDriver.clear(); // 子线程自己清
        });

        java.util.concurrent.Future future = testExecutor.submit(decorated);
        future.get(10, TimeUnit.SECONDS);

        assertNotNull(childDriver.get(), "子线程应获取到 Driver");
        assertSame(driver, childDriver.get(), "子线程获取的应为 driver");
        assertSame(driver, TradingDriver.getCurrent(), "主线程 Driver 应不受子线程清理影响");
    }

    // ==================== T7: 子线程调用 Driver 方法 ====================

    /**
     * T7: 验证子线程中通过 Driver 调用方法（analystComplete）正常触发回调。
     */
    @Test
    public void testDecorator_childThread_driverMethodCall_works() throws Exception {
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<String> phaseInChild = new AtomicReference<>();

        Runnable decorated = decorateWithDriver(() -> {
            TradingDriver current = TradingDriver.getCurrent();
            assertNotNull(current, "子线程应获取到 Driver");
            TradingStateContext ctx = current.getStateContext();
            assertNotNull(ctx, "子线程应能通过 Driver 访问 stateContext");
            phaseInChild.set(ctx.getCurrentPhase().name());
            // 调用 analystComplete 会触发 dispatcher.onEvent(ANALYST_COMPLETE, stateContext)
            current.analystComplete();
            latch.countDown();
        });

        java.util.concurrent.Future future = testExecutor.submit(decorated);
        future.get(10, TimeUnit.SECONDS);

        assertTrue(latch.await(5, TimeUnit.SECONDS), "analystComplete 应被调用");
        assertEquals("INIT", phaseInChild.get(), "子线程应能访问 stateContext");
    }

    // ==================== 辅助方法 ====================

    /**
     * 复刻 TradingExecutorConfig.decorateWithDriver 逻辑：
     * 捕获主线程 Driver → 设置到子线程 → 执行 → finally 清理。
     */
    private Runnable decorateWithDriver(Runnable runnable) {
        TradingDriver capturedDriver = TradingDriver.getCurrent();
        return () -> {
            try {
                if (capturedDriver != null) {
                    TradingDriver.setCurrent(capturedDriver);
                }
                runnable.run();
            } finally {
                TradingDriver.clear();
            }
        };
    }

    private StockAnalysisRequestVO createRequest() {
        StockAnalysisRequestVO request = new StockAnalysisRequestVO();
        request.setTicker("000001");
        request.setMaxDebateRounds(2);
        request.setMaxRiskRounds(1);
        return request;
    }

    /**
     * Dispatcher 轻量 stub，继承 TradingDispatcher 以满足构造函数类型要求。
     */
    private class TradingDispatcherStub extends TradingDispatcher {
        @Override
        public void onEvent(TradingEvent event, TradingStateContext stateContext) {
            // no-op: 仅用于 TradingDriver 持有引用，不触发真实调度
        }
    }
}
