package denny.ai.agent.domain.service.armory.stream;

import reactor.core.Disposable;
import reactor.core.scheduler.Scheduler;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

public final class StallObserver {

    private final Duration threshold;
    private final Scheduler scheduler;
    private final String logicalCallId;
    private final String modelId;
    private final Consumer<StallEvent> observer;

    private long generation;
    private long loggedGeneration = -1;
    private long observedChunkCount;
    private long lastChunkAtNanos;
    private Disposable scheduledTask;
    private boolean terminated;

    public StallObserver(Duration threshold,
                         Scheduler scheduler,
                         String logicalCallId,
                         String modelId,
                         Consumer<StallEvent> observer) {
        this.threshold = Objects.requireNonNull(threshold, "threshold must not be null");
        if (threshold.isZero() || threshold.isNegative()) {
            throw new IllegalArgumentException("threshold must be positive");
        }
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler must not be null");
        this.logicalCallId = logicalCallId;
        this.modelId = modelId;
        this.observer = Objects.requireNonNull(observer, "observer must not be null");
    }

    public void onChunk() {
        long currentGeneration;
        synchronized (this) {
            if (terminated) {
                return;
            }
            disposeScheduledTask();
            observedChunkCount++;
            generation++;
            currentGeneration = generation;
            lastChunkAtNanos = nowNanos();
            scheduledTask = scheduler.schedule(() -> recordIfCurrent(currentGeneration),
                    threshold.toNanos(), TimeUnit.NANOSECONDS);
        }
    }

    public void terminate() {
        synchronized (this) {
            if (terminated) {
                return;
            }
            terminated = true;
            generation++;
            disposeScheduledTask();
        }
    }

    public synchronized boolean isTerminated() {
        return terminated;
    }

    public synchronized long observedChunkCount() {
        return observedChunkCount;
    }

    private void recordIfCurrent(long taskGeneration) {
        StallEvent event = null;
        synchronized (this) {
            if (!terminated && generation == taskGeneration
                    && loggedGeneration != taskGeneration) {
                loggedGeneration = taskGeneration;
                long elapsedNanos = Math.max(0, nowNanos() - lastChunkAtNanos);
                event = new StallEvent(threshold, Duration.ofNanos(elapsedNanos),
                        observedChunkCount, logicalCallId, modelId);
            }
        }
        if (event != null) {
            observer.accept(event);
        }
    }

    private long nowNanos() {
        return scheduler.now(TimeUnit.NANOSECONDS);
    }

    private void disposeScheduledTask() {
        if (scheduledTask != null) {
            scheduledTask.dispose();
            scheduledTask = null;
        }
    }

    public record StallEvent(Duration configuredThreshold,
                             Duration elapsedSinceLastChunk,
                             long observedChunkCount,
                             String logicalCallId,
                             String modelId) {
    }
}
