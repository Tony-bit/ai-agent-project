package denny.ai.agent.trading.domain.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.extern.slf4j.Slf4j;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 交易 Agent 指标服务。
 * <p>
 * 使用 Micrometer 暴露指标到 Prometheus 等监控系统。
 * <p>
 * 注意：MeterRegistry 为可选依赖，当不存在时自动降级，所有指标操作变为空操作。
 */
@Slf4j
public class TradingMetrics {

    private final MeterRegistry meterRegistry;
    private final boolean enabled;

    // 分析师耗时定时器（按分析师类型分组）
    private final ConcurrentHashMap<String, Timer> analystTimers = new ConcurrentHashMap<>();

    // 辩论轮次 Gauge
    private final AtomicInteger debateRounds = new AtomicInteger(0);

    // 决策计数器（按决策类型分组）
    private final ConcurrentHashMap<String, Counter> decisionCounters = new ConcurrentHashMap<>();

    // 各维度评分 Gauge
    private final ConcurrentHashMap<String, AtomicInteger> ratingGauges = new ConcurrentHashMap<>();

    public TradingMetrics() {
        this(null);
    }

    public TradingMetrics(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
        this.enabled = (meterRegistry != null);

        if (enabled) {
            // 初始化辩论轮次 Gauge
            meterRegistry.gauge("sta.debate.rounds", debateRounds);

            // 初始化评分 Gauges
            meterRegistry.gauge("sta.rating.fundamental", ratingGauges.computeIfAbsent("fundamental", k -> new AtomicInteger(0)));
            meterRegistry.gauge("sta.rating.technical", ratingGauges.computeIfAbsent("technical", k -> new AtomicInteger(0)));
            meterRegistry.gauge("sta.rating.sentiment", ratingGauges.computeIfAbsent("sentiment", k -> new AtomicInteger(0)));
            meterRegistry.gauge("sta.rating.news", ratingGauges.computeIfAbsent("news", k -> new AtomicInteger(0)));

            log.info("TradingMetrics initialized with MeterRegistry");
        } else {
            log.warn("TradingMetrics: MeterRegistry not available, metrics recording is disabled");
        }
    }

    /**
     * 记录分析师执行耗时
     */
    public Timer.Sample startAnalystTimer(String analystType) {
        if (!enabled) return null;
        return Timer.start(meterRegistry);
    }

    /**
     * 停止分析师定时器并记录
     */
    public void stopAnalystTimer(String analystType, Timer.Sample sample) {
        if (!enabled || sample == null) return;
        Timer timer = analystTimers.computeIfAbsent(analystType, type ->
                Timer.builder("sta.analyst.duration")
                        .tag("analyst_type", type)
                        .description("分析师执行耗时")
                        .register(meterRegistry)
        );
        sample.stop(timer);
        log.debug("Recorded analyst duration: type={}", analystType);
    }

    /**
     * 记录辩论轮次
     */
    public void recordDebateRound(int round) {
        debateRounds.set(round);
        log.debug("Recorded debate round: {}", round);
    }

    /**
     * 记录决策
     */
    public void recordDecision(String decision) {
        if (!enabled) return;
        Counter counter = decisionCounters.computeIfAbsent(decision, d ->
                Counter.builder("sta.decision.count")
                        .tag("decision", d)
                        .description("决策计数")
                        .register(meterRegistry)
        );
        counter.increment();
        log.debug("Recorded decision: {}", decision);
    }

    /**
     * 记录分析师评分
     */
    public void recordAnalystRating(String analystType, int rating) {
        if (!enabled) return;
        AtomicInteger gauge = ratingGauges.computeIfAbsent(analystType, type -> {
            AtomicInteger gaugeValue = new AtomicInteger(0);
            meterRegistry.gauge("sta.rating." + type, gaugeValue);
            return gaugeValue;
        });
        gauge.set(rating);
        log.debug("Recorded analyst rating: type={}, rating={}", analystType, rating);
    }

    /**
     * 记录分析开始
     */
    public void recordAnalystStart(String analystType) {
        if (!enabled) return;
        Counter.builder("sta.analyst.start")
                .tag("analyst_type", analystType)
                .register(meterRegistry)
                .increment();
    }

    /**
     * 记录分析完成
     */
    public void recordAnalystComplete(String analystType, int rating) {
        if (!enabled) return;
        recordAnalystRating(analystType, rating);
        Counter.builder("sta.analyst.complete")
                .tag("analyst_type", analystType)
                .register(meterRegistry)
                .increment();
    }

    /**
     * 记录错误
     */
    public void recordError(String nodeType, String errorType) {
        if (!enabled) return;
        Counter.builder("sta.error.count")
                .tag("node_type", nodeType)
                .tag("error_type", errorType)
                .register(meterRegistry)
                .increment();
    }

    /**
     * 记录延迟
     */
    public void recordLatency(String nodeType, long latencyMs) {
        if (!enabled) return;
        Timer.builder("sta.latency")
                .tag("node_type", nodeType)
                .description("节点延迟")
                .register(meterRegistry)
                .record(java.time.Duration.ofMillis(latencyMs));
    }
}
