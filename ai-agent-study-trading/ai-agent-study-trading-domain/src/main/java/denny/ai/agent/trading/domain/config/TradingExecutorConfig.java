package denny.ai.agent.trading.domain.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadPoolExecutor;

/**
 * 交易 Agent 专用线程池配置。
 * <p>
 * 使用 Runnable 包装将主线程的 TradingDriver ThreadLocal 传递到子线程，
 * 解决跨线程传递问题。同时限制线程数 + CallerRunsPolicy 饱和策略，防止资源耗尽。
 */
@Configuration
public class TradingExecutorConfig {

    @Bean("tradingTaskExecutor")
    public ExecutorService tradingTaskExecutor() {
        return new ThreadPoolExecutor(
                8,                          // corePoolSize
                16,                          // maximumPoolSize
                60L,                        // keepAliveTime
                java.util.concurrent.TimeUnit.SECONDS,
                new java.util.concurrent.LinkedBlockingQueue<>(2), // queueCapacity
                r -> new Thread(r, "trading-task-" + r.hashCode()), // threadFactory
                new ThreadPoolExecutor.CallerRunsPolicy()           // 队列满时由调用线程执行，防止任务丢失
        ) {
            @Override
            public void execute(Runnable command) {
                super.execute(decorateWithDriver(command));
            }
        };
    }

    @Bean("tradingOrchestrationExecutor")
    public ExecutorService tradingOrchestrationExecutor() {
        return new ThreadPoolExecutor(
                4,
                8,
                60L,
                java.util.concurrent.TimeUnit.SECONDS,
                new java.util.concurrent.LinkedBlockingQueue<>(16),
                r -> new Thread(r, "trading-orchestration-" + r.hashCode()),
                new ThreadPoolExecutor.CallerRunsPolicy()
        );
    }

    @Bean("tradingExportExecutor")
    public ExecutorService tradingExportExecutor() {
        return new ThreadPoolExecutor(
                2,
                4,
                60L,
                java.util.concurrent.TimeUnit.SECONDS,
                new java.util.concurrent.LinkedBlockingQueue<>(32),
                r -> new Thread(r, "trading-export-" + r.hashCode()),
                new ThreadPoolExecutor.CallerRunsPolicy()
        );
    }

    @Bean("tradingSseWriterExecutor")
    public ExecutorService tradingSseWriterExecutor() {
        return new ThreadPoolExecutor(
                8,
                32,
                60L,
                java.util.concurrent.TimeUnit.SECONDS,
                new java.util.concurrent.LinkedBlockingQueue<>(128),
                r -> new Thread(r, "trading-sse-writer-" + r.hashCode()),
                new ThreadPoolExecutor.AbortPolicy()
        );
    }

    @Bean("tradingSseHeartbeatExecutor")
    public ScheduledExecutorService tradingSseHeartbeatExecutor() {
        ScheduledThreadPoolExecutor executor = new ScheduledThreadPoolExecutor(
                2,
                r -> new Thread(r, "trading-sse-heartbeat-" + r.hashCode())
        );
        executor.setRemoveOnCancelPolicy(true);
        return executor;
    }

    /**
     * 将主线程的 TradingDriver 传递到子线程。
     */
    private Runnable decorateWithDriver(Runnable runnable) {
        TradingDriver driver = TradingDriver.getCurrent();
        return () -> {
            try {
                if (driver != null) {
                    TradingDriver.setCurrent(driver);
                }
                runnable.run();
            } finally {
                TradingDriver.clear();
            }
        };
    }
}
