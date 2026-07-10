package denny.ai.agent.trading.trigger.http;

import com.alibaba.fastjson.JSON;
import denny.ai.agent.domain.service.sse.SseEventSink;
import denny.ai.agent.domain.service.sse.SseSessionState;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyEmitter;

import java.io.IOException;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

@Slf4j
public class TradingSseSession implements SseEventSink {

    static final int DEFAULT_QUEUE_CAPACITY = 512;
    static final long BUSINESS_OFFER_TIMEOUT_MS = 200L;
    private static final long COMPLETE_OFFER_TIMEOUT_MS = 200L;
    private static final long WRITER_POLL_TIMEOUT_MS = 500L;
    private static final long HEARTBEAT_INITIAL_DELAY_SECONDS = 5L;
    private static final long HEARTBEAT_INTERVAL_SECONDS = 10L;

    private final ResponseBodyEmitter emitter;
    private final BlockingQueue<SseOutboundEvent> queue;
    private final AtomicReference<SseSessionState> state = new AtomicReference<>(SseSessionState.OPEN);
    private final AtomicLong eventIdGenerator = new AtomicLong(0L);
    private final AtomicLong lastWriteAt = new AtomicLong(0L);
    private final AtomicLong lastBusinessAt = new AtomicLong(0L);
    private final AtomicLong lastHeartbeatAt = new AtomicLong(0L);
    private final AtomicLong heartbeatSkipCount = new AtomicLong(0L);
    private final AtomicLong businessQueueFullCount = new AtomicLong(0L);
    private final AtomicLong sendFailureCount = new AtomicLong(0L);
    private final AtomicLong clientDisconnectedCount = new AtomicLong(0L);
    private final AtomicLong sessionClosedDueToBackpressureCount = new AtomicLong(0L);

    private final String requestId;
    private final String sessionId;
    private final String ticker;
    private volatile Future<?> writerFuture;
    private volatile ScheduledFuture<?> heartbeatFuture;

    public TradingSseSession(ResponseBodyEmitter emitter, String requestId, String sessionId, String ticker) {
        this(emitter, requestId, sessionId, ticker, DEFAULT_QUEUE_CAPACITY);
    }

    TradingSseSession(ResponseBodyEmitter emitter,
                      String requestId,
                      String sessionId,
                      String ticker,
                      int queueCapacity) {
        this.emitter = Objects.requireNonNull(emitter, "emitter");
        this.requestId = requestId != null ? requestId : UUID.randomUUID().toString();
        this.sessionId = sessionId != null ? sessionId : UUID.randomUUID().toString();
        this.ticker = ticker;
        this.queue = new LinkedBlockingQueue<>(queueCapacity);
    }

    public void startWriter(ExecutorService writerExecutor) {
        if (writerFuture != null) {
            return;
        }
        try {
            writerFuture = writerExecutor.submit(this::runWriterLoop);
        } catch (RejectedExecutionException e) {
            transitionToFailed(e, true);
            throw e;
        }
    }

    public void startHeartbeat(ScheduledExecutorService scheduler) {
        if (heartbeatFuture != null) {
            return;
        }
        heartbeatFuture = scheduler.scheduleAtFixedRate(() -> {
            try {
                trySendHeartbeat();
            } catch (Exception e) {
                log.warn("交易分析SSE heartbeat 入队异常: ticker={}, sessionId={}, error={}",
                        ticker, sessionId, e.getMessage());
            }
        }, HEARTBEAT_INITIAL_DELAY_SECONDS, HEARTBEAT_INTERVAL_SECONDS, TimeUnit.SECONDS);
    }

    @Override
    public boolean sendBusiness(String eventName, Object payload) {
        if (state.get() != SseSessionState.OPEN) {
            return false;
        }
        SseOutboundEvent event = SseOutboundEvent.business(
                eventName,
                payload,
                requestId,
                sessionId,
                null,
                eventIdGenerator.incrementAndGet()
        );
        try {
            boolean offered = queue.offer(event, BUSINESS_OFFER_TIMEOUT_MS, TimeUnit.MILLISECONDS);
            if (offered) {
                lastBusinessAt.set(System.currentTimeMillis());
                return true;
            }
            businessQueueFullCount.incrementAndGet();
            sessionClosedDueToBackpressureCount.incrementAndGet();
            transitionToFailed(new IllegalStateException("SSE queue full"), true);
            log.warn("交易分析SSE队列背压关闭: ticker={}, sessionId={}, queueSize={}",
                    ticker, sessionId, queue.size());
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            transitionToFailed(e, true);
            return false;
        }
    }

    @Override
    public boolean trySendHeartbeat() {
        if (state.get() != SseSessionState.OPEN) {
            return false;
        }
        SseOutboundEvent event = SseOutboundEvent.heartbeat(
                requestId,
                sessionId,
                eventIdGenerator.incrementAndGet()
        );
        boolean offered = queue.offer(event);
        if (!offered) {
            heartbeatSkipCount.incrementAndGet();
            return false;
        }
        lastHeartbeatAt.set(System.currentTimeMillis());
        return true;
    }

    @Override
    public void complete() {
        if (!state.compareAndSet(SseSessionState.OPEN, SseSessionState.CLOSING)) {
            return;
        }
        stopHeartbeat();
        SseOutboundEvent event = SseOutboundEvent.complete(
                requestId,
                sessionId,
                eventIdGenerator.incrementAndGet()
        );
        try {
            boolean offered = queue.offer(event, COMPLETE_OFFER_TIMEOUT_MS, TimeUnit.MILLISECONDS);
            if (!offered) {
                forceCompleteFromNonWriter("complete event queue offer timed out");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            forceCompleteFromNonWriter("complete event interrupted");
        }
    }

    @Override
    public void markDisconnected(Throwable cause) {
        SseSessionState current = state.get();
        while (current == SseSessionState.OPEN || current == SseSessionState.CLOSING) {
            if (state.compareAndSet(current, SseSessionState.DISCONNECTED)) {
                clientDisconnectedCount.incrementAndGet();
                cleanup(true, true);
                if (cause != null) {
                    log.warn("交易分析SSE连接断开: ticker={}, sessionId={}, error={}",
                            ticker, sessionId, cause.getMessage());
                }
                return;
            }
            current = state.get();
        }
    }

    @Override
    public boolean isDisconnected() {
        return state.get() == SseSessionState.DISCONNECTED;
    }

    @Override
    public boolean shouldContinue() {
        return state.get() == SseSessionState.OPEN;
    }

    @Override
    public SseSessionState state() {
        return state.get();
    }

    public int queueSize() {
        return queue.size();
    }

    long heartbeatSkipCount() {
        return heartbeatSkipCount.get();
    }

    long businessQueueFullCount() {
        return businessQueueFullCount.get();
    }

    long sendFailureCount() {
        return sendFailureCount.get();
    }

    long clientDisconnectedCount() {
        return clientDisconnectedCount.get();
    }

    long sessionClosedDueToBackpressureCount() {
        return sessionClosedDueToBackpressureCount.get();
    }

    long lastWriteAt() {
        return lastWriteAt.get();
    }

    long lastBusinessAt() {
        return lastBusinessAt.get();
    }

    long lastHeartbeatAt() {
        return lastHeartbeatAt.get();
    }

    private void runWriterLoop() {
        try {
            while (true) {
                SseOutboundEvent event = queue.poll(WRITER_POLL_TIMEOUT_MS, TimeUnit.MILLISECONDS);
                if (event == null) {
                    if (state.get() == SseSessionState.CLOSING && queue.isEmpty()) {
                        completeFromWriter();
                        return;
                    }
                    if (state.get() == SseSessionState.CLOSED
                            || state.get() == SseSessionState.DISCONNECTED
                            || state.get() == SseSessionState.FAILED) {
                        return;
                    }
                    continue;
                }
                if (event.type() == SseOutboundType.COMPLETE) {
                    completeFromWriter();
                    return;
                }
                emitter.send(serialize(event));
                lastWriteAt.set(System.currentTimeMillis());
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            if (state.get() == SseSessionState.CLOSING) {
                completeFromWriter();
            }
        } catch (Throwable e) {
            sendFailureCount.incrementAndGet();
            markDisconnectedFromWriter(e);
        }
    }

    private String serialize(SseOutboundEvent event) {
        if (event.type() == SseOutboundType.HEARTBEAT) {
            return ": " + event.comment() + "\n\n";
        }
        StringBuilder builder = new StringBuilder();
        if (event.eventName() != null && !event.eventName().isBlank()) {
            builder.append("event: ").append(event.eventName()).append('\n');
        }
        builder.append("data: ").append(JSON.toJSONString(event.payload())).append("\n\n");
        return builder.toString();
    }

    private void completeFromWriter() {
        stopHeartbeat();
        try {
            emitter.complete();
        } catch (IllegalStateException e) {
            log.debug("交易分析SSE emitter 已关闭: ticker={}, sessionId={}, error={}",
                    ticker, sessionId, e.getMessage());
        } finally {
            state.set(SseSessionState.CLOSED);
            cleanup(false, false);
        }
    }

    private void forceCompleteFromNonWriter(String reason) {
        log.warn("交易分析SSE强制收口: ticker={}, sessionId={}, reason={}", ticker, sessionId, reason);
        queue.clear();
        cancelWriter();
        try {
            emitter.complete();
        } catch (IllegalStateException e) {
            log.debug("交易分析SSE emitter 已关闭，强制收口跳过: ticker={}, sessionId={}, error={}",
                    ticker, sessionId, e.getMessage());
        } finally {
            state.set(SseSessionState.CLOSED);
            cleanup(false, false);
        }
    }

    private void transitionToFailed(Throwable cause, boolean completeEmitter) {
        SseSessionState current = state.get();
        while (current == SseSessionState.OPEN || current == SseSessionState.CLOSING) {
            if (state.compareAndSet(current, SseSessionState.FAILED)) {
                cleanup(true, true);
                if (completeEmitter) {
                    try {
                        emitter.completeWithError(cause);
                    } catch (Exception e) {
                        log.debug("交易分析SSE completeWithError 失败: ticker={}, sessionId={}, error={}",
                                ticker, sessionId, e.getMessage());
                    }
                }
                return;
            }
            current = state.get();
        }
    }

    private void markDisconnectedFromWriter(Throwable cause) {
        SseSessionState current = state.get();
        while (current == SseSessionState.OPEN || current == SseSessionState.CLOSING) {
            if (state.compareAndSet(current, SseSessionState.DISCONNECTED)) {
                clientDisconnectedCount.incrementAndGet();
                cleanup(false, true);
                log.warn("交易分析SSE写出失败，标记断连: ticker={}, sessionId={}, error={}, exClass={}",
                        ticker, sessionId, cause.getMessage(), cause.getClass().getName());
                return;
            }
            current = state.get();
        }
    }

    private void stopHeartbeat() {
        ScheduledFuture<?> future = heartbeatFuture;
        if (future != null) {
            future.cancel(false);
            heartbeatFuture = null;
        }
    }

    private void cleanup(boolean cancelWriter, boolean clearQueue) {
        stopHeartbeat();
        if (clearQueue) {
            queue.clear();
        }
        if (cancelWriter) {
            cancelWriter();
        }
    }

    private void cancelWriter() {
        Future<?> future = writerFuture;
        if (future != null && !future.isDone()) {
            future.cancel(true);
        }
    }

    public static boolean isClientDisconnect(Throwable throwable) {
        Throwable current = throwable;
        while (current != null) {
            String className = current.getClass().getName();
            String message = current.getMessage();
            if (className.contains("AsyncRequestNotUsableException")
                    || className.contains("ClientAbortException")
                    || current instanceof IllegalStateException && message != null
                    && message.contains("ResponseBodyEmitter has already completed")
                    || current instanceof IOException
                    || containsAny(message,
                    "ServletOutputStream failed to flush",
                    "Broken pipe",
                    "Connection reset",
                    "你的主机中的软件中止了一个已建立的连接")) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private static boolean containsAny(String value, String... patterns) {
        if (value == null) {
            return false;
        }
        for (String pattern : patterns) {
            if (value.contains(pattern)) {
                return true;
            }
        }
        return false;
    }
}
