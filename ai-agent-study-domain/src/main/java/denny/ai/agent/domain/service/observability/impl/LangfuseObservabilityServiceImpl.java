package denny.ai.agent.domain.service.observability.impl;

import com.alibaba.fastjson2.JSON;
import denny.ai.agent.domain.service.observability.LangfuseProperties;
import denny.ai.agent.domain.service.observability.ObservabilityService;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

@Slf4j
@Service
public class LangfuseObservabilityServiceImpl implements ObservabilityService {

    private static final int MAX_RETRIES_CAP = 10;

    private final LangfuseProperties properties;
    private final BlockingQueue<QueuedEvent> eventQueue;
    private final BatchSender batchSender;
    private final ScheduledExecutorService flushExecutor;
    private final Map<String, TraceSnapshot> traceSnapshots = new ConcurrentHashMap<>();
    private final AtomicBoolean acceptingEvents = new AtomicBoolean(true);
    private final AtomicLong enqueuedCount = new AtomicLong();
    private final AtomicLong sentCount = new AtomicLong();
    private final AtomicLong retriedCount = new AtomicLong();
    private final AtomicLong droppedCount = new AtomicLong();

    @Autowired
    public LangfuseObservabilityServiceImpl(LangfuseProperties properties) {
        this(properties, createHttpSender(properties), true);
    }

    LangfuseObservabilityServiceImpl(LangfuseProperties properties, BatchSender batchSender, boolean startWorker) {
        this.properties = properties;
        this.eventQueue = new LinkedBlockingQueue<>(positive(properties.getQueueCapacity(), 500));
        this.batchSender = batchSender;
        this.flushExecutor = createFlushExecutor();
        if (startWorker) {
            startWorker();
        }
    }

    @Override
    public String startTrace(String sessionId, String input, Map<String, Object> metadata) {
        String traceId = UUID.randomUUID().toString();
        if (!isEnabled()) {
            return traceId;
        }

        TraceSnapshot snapshot = new TraceSnapshot(traceId, Instant.now().toString(), sessionId, input, metadata);
        traceSnapshots.put(traceId, snapshot);
        enqueue("trace-create", snapshot.toPayload(null));
        return traceId;
    }

    @Override
    public String startSpan(String traceId, String spanName, Map<String, Object> metadata) {
        String spanId = UUID.randomUUID().toString();
        if (!isEnabled()) {
            return spanId;
        }

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("id", spanId);
        payload.put("traceId", traceId);
        payload.put("name", spanName);
        payload.put("startTime", Instant.now().toString());
        payload.put("metadata", copy(metadata));
        enqueue("span-create", payload);
        return spanId;
    }

    @Override
    public void logGeneration(String traceId, String spanId, String model, String prompt, String output,
                              Map<String, Object> metadata, Map<String, Object> tokenUsage) {
        if (!isEnabled()) {
            return;
        }

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("id", UUID.randomUUID().toString());
        payload.put("traceId", traceId);
        payload.put("parentObservationId", spanId);
        Object observationName = metadata == null ? null : metadata.get("observationName");
        payload.put("name", observationName == null || StringUtils.isBlank(String.valueOf(observationName))
                ? "llm-generation" : String.valueOf(observationName));
        payload.put("model", model);
        payload.put("startTime", Instant.now().toString());
        payload.put("endTime", Instant.now().toString());
        payload.put("input", prompt);
        payload.put("output", output);
        payload.put("metadata", copy(metadata));
        if (tokenUsage != null && !tokenUsage.isEmpty()) {
            payload.put("usage", copy(tokenUsage));
        }
        enqueue("generation-create", payload);
    }

    @Override
    public void logScore(String traceId, String scoreName, Double value, String comment,
                         Map<String, Object> metadata) {
        if (!isEnabled()) {
            return;
        }

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("id", UUID.randomUUID().toString());
        payload.put("traceId", traceId);
        payload.put("name", scoreName);
        payload.put("value", value);
        payload.put("comment", comment);
        payload.put("timestamp", Instant.now().toString());
        payload.put("metadata", copy(metadata));
        enqueue("score-create", payload);
    }

    @Override
    public void updateTraceMetadata(String traceId, Map<String, Object> metadata) {
        if (!isEnabled() || StringUtils.isBlank(traceId) || metadata == null || metadata.isEmpty()) {
            return;
        }

        TraceSnapshot snapshot = traceSnapshots.get(traceId);
        if (snapshot == null) {
            log.warn("Ignoring Langfuse metadata update for unknown traceId={}", traceId);
            return;
        }
        Map<String, Object> payload = snapshot.updatePayload(metadata);
        if (payload == null) {
            log.warn("Ignoring Langfuse metadata update for completed traceId={}", traceId);
            return;
        }
        enqueue("trace-create", payload);
    }

    @Override
    public void endSpan(String spanId, boolean success, String errorMessage) {
        if (!isEnabled()) {
            return;
        }

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("id", spanId);
        payload.put("endTime", Instant.now().toString());
        payload.put("level", success ? "DEFAULT" : "ERROR");
        if (!success && StringUtils.isNotBlank(errorMessage)) {
            payload.put("statusMessage", errorMessage);
        }
        enqueue("span-update", payload);
    }

    @Override
    public void endTrace(String traceId, String output, Map<String, Object> metadata) {
        if (!isEnabled() || StringUtils.isBlank(traceId)) {
            return;
        }

        TraceSnapshot snapshot = traceSnapshots.get(traceId);
        if (snapshot == null) {
            log.warn("Ignoring Langfuse end for unknown traceId={}", traceId);
            return;
        }
        Map<String, Object> payload = snapshot.completePayload(output, metadata);
        if (payload == null) {
            log.warn("Ignoring duplicate Langfuse end for traceId={}", traceId);
            return;
        }
        if (enqueue("trace-create", payload, true)) {
            traceSnapshots.remove(traceId, snapshot);
        } else {
            snapshot.cancelCompletion();
        }
    }

    @PreDestroy
    public void shutdown() {
        if (!acceptingEvents.compareAndSet(true, false)) {
            return;
        }
        flushExecutor.shutdown();
        long deadlineNanos = System.nanoTime()
                + TimeUnit.MILLISECONDS.toNanos(nonNegative(properties.getShutdownDrainTimeoutMs()));
        while (!eventQueue.isEmpty() && System.nanoTime() <= deadlineNanos) {
            flushOnce();
        }
        int remaining = eventQueue.size();
        if (remaining > 0) {
            droppedCount.addAndGet(remaining);
            eventQueue.clear();
            log.warn("Langfuse shutdown drain timed out, droppedEvents={}", remaining);
        }
        log.info("Langfuse transport stopped: enqueued={}, sent={}, retried={}, dropped={}",
                enqueuedCount.get(), sentCount.get(), retriedCount.get(), droppedCount.get());
    }

    private void startWorker() {
        long intervalMs = Math.max(1L, properties.getFlushIntervalMs());
        flushExecutor.scheduleWithFixedDelay(this::flushSafely, intervalMs, intervalMs, TimeUnit.MILLISECONDS);
    }

    private boolean enqueue(String type, Map<String, Object> body) {
        return enqueue(type, body, false);
    }

    private boolean enqueue(String type, Map<String, Object> body, boolean priority) {
        if (!acceptingEvents.get()) {
            drop(type, "transport is shutting down");
            return false;
        }
        QueuedEvent event = new QueuedEvent(UUID.randomUUID().toString(), type, Instant.now().toString(), body);
        synchronized (eventQueue) {
            if (!eventQueue.offer(event)) {
                QueuedEvent evicted = priority ? eventQueue.poll() : null;
                if (evicted != null) {
                    drop(evicted.type, "evicted to preserve final trace upsert");
                }
                if (!eventQueue.offer(event)) {
                    drop(type, "queue is full");
                    return false;
                }
            }
        }
        enqueuedCount.incrementAndGet();
        if (eventQueue.size() >= maxBatchSize()) {
            try {
                flushExecutor.execute(this::flushSafely);
            } catch (RejectedExecutionException ignored) {
                // Shutdown drains events already accepted into the queue.
            }
        }
        return true;
    }

    private void drop(String type, String reason) {
        long dropped = droppedCount.incrementAndGet();
        log.warn("Dropping Langfuse event: type={}, reason={}, droppedTotal={}", type, reason, dropped);
    }

    private void flushSafely() {
        try {
            flushOnce();
        } catch (Exception e) {
            log.warn("Unexpected Langfuse flush failure: err={}", e.getMessage(), e);
        }
    }

    void flushOnce() {
        List<QueuedEvent> batch = new ArrayList<>(maxBatchSize());
        eventQueue.drainTo(batch, maxBatchSize());
        if (batch.isEmpty()) {
            return;
        }

        int configuredRetries = Math.min(MAX_RETRIES_CAP, Math.max(0, properties.getMaxRetries()));
        int maxAttempts = acceptingEvents.get() ? configuredRetries + 1 : 1;
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                batchSender.send(batch);
                sentCount.addAndGet(batch.size());
                return;
            } catch (Exception e) {
                if (attempt >= maxAttempts) {
                    droppedCount.addAndGet(batch.size());
                    log.warn("Langfuse batch delivery exhausted retries: events={}, attempts={}, err={}",
                            batch.size(), attempt, e.getMessage());
                    return;
                }
                retriedCount.addAndGet(batch.size());
                log.warn("Langfuse batch delivery failed, retrying: events={}, attempt={}/{}, err={}",
                        batch.size(), attempt, maxAttempts, e.getMessage());
                sleepBeforeRetry(attempt);
            }
        }
    }

    private void sleepBeforeRetry(int failedAttempt) {
        long initial = nonNegative(properties.getRetryBackoffMs());
        long cap = Math.max(initial, nonNegative(properties.getMaxRetryBackoffMs()));
        long multiplier = 1L << Math.min(failedAttempt - 1, 20);
        long delay = Math.min(cap, initial > Long.MAX_VALUE / multiplier ? cap : initial * multiplier);
        if (delay == 0) {
            return;
        }
        try {
            Thread.sleep(delay);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static BatchSender createHttpSender(LangfuseProperties properties) {
        Duration timeout = Duration.ofMillis(Math.max(1, properties.getTimeoutMs()));
        HttpClient httpClient = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(timeout)
                .build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(timeout);
        RestTemplate restTemplate = new RestTemplate(requestFactory);

        return events -> {
            Map<String, Object> envelope = new LinkedHashMap<>();
            envelope.put("batch", events.stream().map(QueuedEvent::toMap).toList());
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.set("Authorization", buildBasicAuth(properties));
            HttpEntity<String> request = new HttpEntity<>(JSON.toJSONString(envelope), headers);
            ResponseEntity<String> response = restTemplate.postForEntity(
                    properties.getHost() + "/api/public/ingestion", request, String.class);
            if (!response.getStatusCode().is2xxSuccessful()) {
                throw new IllegalStateException("HTTP " + response.getStatusCodeValue());
            }
        };
    }

    private static ScheduledExecutorService createFlushExecutor() {
        ThreadFactory factory = runnable -> {
            Thread thread = new Thread(runnable, "langfuse-ingestion-worker");
            thread.setDaemon(true);
            return thread;
        };
        return Executors.newSingleThreadScheduledExecutor(factory);
    }

    private static String buildBasicAuth(LangfuseProperties properties) {
        String raw = properties.getPublicKey() + ":" + properties.getSecretKey();
        String token = Base64.getEncoder().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
        return "Basic " + token;
    }

    private boolean isEnabled() {
        return properties.isEnabled()
                && StringUtils.isNotBlank(properties.getHost())
                && StringUtils.isNotBlank(properties.getPublicKey())
                && StringUtils.isNotBlank(properties.getSecretKey());
    }

    private int maxBatchSize() {
        return positive(properties.getMaxBatchSize(), 50);
    }

    private static int positive(int value, int fallback) {
        return value > 0 ? value : fallback;
    }

    private static long nonNegative(long value) {
        return Math.max(0L, value);
    }

    private static Map<String, Object> copy(Map<String, Object> source) {
        return source == null ? null : new LinkedHashMap<>(source);
    }

    int pendingEventCount() {
        return eventQueue.size();
    }

    long droppedEventCount() {
        return droppedCount.get();
    }

    @FunctionalInterface
    interface BatchSender {
        void send(List<QueuedEvent> events) throws Exception;
    }

    static final class QueuedEvent {
        private final String id;
        private final String type;
        private final String timestamp;
        private final Map<String, Object> body;

        private QueuedEvent(String id, String type, String timestamp, Map<String, Object> body) {
            this.id = id;
            this.type = type;
            this.timestamp = timestamp;
            this.body = new LinkedHashMap<>(body);
        }

        Map<String, Object> toMap() {
            Map<String, Object> event = new LinkedHashMap<>();
            event.put("id", id);
            event.put("type", type);
            event.put("timestamp", timestamp);
            event.put("body", new LinkedHashMap<>(body));
            return event;
        }
    }

    private static final class TraceSnapshot {
        private final String id;
        private final String timestamp;
        private final String sessionId;
        private final String input;
        private String name;
        private final Map<String, Object> metadata = new LinkedHashMap<>();
        private boolean completed;

        private TraceSnapshot(String id, String timestamp, String sessionId, String input,
                              Map<String, Object> initialMetadata) {
            this.id = id;
            this.timestamp = timestamp;
            this.sessionId = sessionId;
            this.input = input;
            this.name = initialMetadata == null || initialMetadata.get("traceName") == null
                    ? null : String.valueOf(initialMetadata.get("traceName"));
            mergeMetadata(initialMetadata);
        }

        synchronized void mergeMetadata(Map<String, Object> additionalMetadata) {
            if (additionalMetadata != null) {
                metadata.putAll(additionalMetadata);
                if (additionalMetadata.get("traceName") != null) {
                    name = String.valueOf(additionalMetadata.get("traceName"));
                }
            }
        }

        synchronized Map<String, Object> updatePayload(Map<String, Object> additionalMetadata) {
            if (completed) {
                return null;
            }
            mergeMetadata(additionalMetadata);
            return toPayload(null);
        }

        synchronized Map<String, Object> completePayload(String output, Map<String, Object> additionalMetadata) {
            if (completed) {
                return null;
            }
            mergeMetadata(additionalMetadata);
            completed = true;
            return toPayload(output);
        }

        synchronized void cancelCompletion() {
            completed = false;
        }

        synchronized Map<String, Object> toPayload(String output) {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("id", id);
            payload.put("timestamp", timestamp);
            payload.put("sessionId", sessionId);
            payload.put("input", input);
            if (name != null) {
                payload.put("name", name);
            }
            if (output != null) {
                payload.put("output", output);
            }
            payload.put("metadata", new LinkedHashMap<>(metadata));
            return payload;
        }
    }
}
