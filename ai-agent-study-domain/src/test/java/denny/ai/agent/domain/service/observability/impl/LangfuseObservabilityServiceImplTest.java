package denny.ai.agent.domain.service.observability.impl;

import denny.ai.agent.domain.service.observability.LangfuseProperties;
import org.junit.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

public class LangfuseObservabilityServiceImplTest {

    @Test
    public void springSelectsProductionConstructor() {
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            context.registerBean(LangfuseProperties.class);
            context.register(LangfuseObservabilityServiceImpl.class);
            context.refresh();

            assertNotNull(context.getBean(LangfuseObservabilityServiceImpl.class));
        }
    }

    @Test
    public void businessMethodsOnlyEnqueueEvents() {
        List<List<Map<String, Object>>> deliveries = new ArrayList<>();
        LangfuseObservabilityServiceImpl service = service(defaultProperties(), deliveries, false);
        try {
            String traceId = service.startTrace("session-1", "question", Map.of("traceName", "auto-agent"));
            String spanId = service.startSpan(traceId, "routing", Map.of("stage", "intent"));
            service.logGeneration(traceId, spanId, "model", "prompt", "answer",
                    Map.of("observationName", "unified-routing"), Map.of());

            assertTrue(deliveries.isEmpty());
            assertEquals(3, service.pendingEventCount());

            service.flushOnce();
            assertEquals(1, deliveries.size());
            assertEquals(3, deliveries.get(0).size());
            Map<String, Object> generation = body(deliveries.get(0).get(2));
            assertEquals("unified-routing", generation.get("name"));
            assertEquals("model", generation.get("model"));
        } finally {
            service.shutdown();
        }
    }

    @Test
    public void retriesBatchWithStableEventIds() {
        LangfuseProperties properties = defaultProperties();
        properties.setMaxRetries(1);
        properties.setRetryBackoffMs(0);
        properties.setMaxRetryBackoffMs(0);
        List<List<Map<String, Object>>> attempts = new ArrayList<>();
        AtomicInteger calls = new AtomicInteger();
        LangfuseObservabilityServiceImpl.BatchSender sender = events -> {
            attempts.add(copyEvents(events));
            if (calls.getAndIncrement() == 0) {
                throw new IllegalStateException("temporary");
            }
        };
        LangfuseObservabilityServiceImpl service =
                new LangfuseObservabilityServiceImpl(properties, sender, false);
        try {
            service.startTrace("session-1", "question", Map.of());
            service.startSpan("trace-1", "routing", Map.of());
            service.flushOnce();

            assertEquals(2, attempts.size());
            assertEquals(eventIds(attempts.get(0)), eventIds(attempts.get(1)));
            assertEquals(0, service.droppedEventCount());
        } finally {
            service.shutdown();
        }
    }

    @Test
    public void endTraceResendsCompleteSnapshotAfterInitialDeliveryFailure() {
        LangfuseProperties properties = defaultProperties();
        properties.setMaxRetries(0);
        List<List<Map<String, Object>>> deliveries = new ArrayList<>();
        AtomicInteger calls = new AtomicInteger();
        LangfuseObservabilityServiceImpl.BatchSender sender = events -> {
            deliveries.add(copyEvents(events));
            if (calls.getAndIncrement() == 0) {
                throw new IllegalStateException("initial trace delivery failed");
            }
        };
        LangfuseObservabilityServiceImpl service =
                new LangfuseObservabilityServiceImpl(properties, sender, false);
        try {
            Map<String, Object> initialMetadata = new LinkedHashMap<>();
            initialMetadata.put("traceName", "auto-agent");
            initialMetadata.put("userId", "guest-1");
            String traceId = service.startTrace("session-1", "question", initialMetadata);
            service.flushOnce();

            service.endTrace(traceId, "answer", Map.of("intent", "GENERAL_CHAT"));
            service.flushOnce();

            assertEquals(2, deliveries.size());
            Map<String, Object> finalEvent = deliveries.get(1).get(0);
            assertEquals("trace-create", finalEvent.get("type"));
            Map<String, Object> body = body(finalEvent);
            assertEquals(traceId, body.get("id"));
            assertEquals("session-1", body.get("sessionId"));
            assertEquals("question", body.get("input"));
            assertEquals("answer", body.get("output"));
            assertEquals("auto-agent", body.get("name"));
            Map<String, Object> metadata = metadata(body);
            assertEquals("guest-1", metadata.get("userId"));
            assertEquals("GENERAL_CHAT", metadata.get("intent"));
        } finally {
            service.shutdown();
        }
    }

    @Test
    public void unknownTraceMetadataAndEndDoNotCreateBlankEvents() {
        List<List<Map<String, Object>>> deliveries = new ArrayList<>();
        LangfuseObservabilityServiceImpl service = service(defaultProperties(), deliveries, false);
        try {
            service.updateTraceMetadata("missing", Map.of("intent", "STOCK_ANALYSIS"));
            service.endTrace("missing", "answer", Map.of());

            assertEquals(0, service.pendingEventCount());
            service.flushOnce();
            assertTrue(deliveries.isEmpty());
        } finally {
            service.shutdown();
        }
    }

    @Test
    public void fullQueueDropsWithoutCallingSender() {
        LangfuseProperties properties = defaultProperties();
        properties.setQueueCapacity(1);
        properties.setMaxBatchSize(50);
        List<List<Map<String, Object>>> deliveries = new ArrayList<>();
        LangfuseObservabilityServiceImpl service = service(properties, deliveries, false);
        try {
            service.startTrace("session-1", "first", Map.of());
            service.startTrace("session-2", "second", Map.of());

            assertTrue(deliveries.isEmpty());
            assertEquals(1, service.pendingEventCount());
            assertEquals(1, service.droppedEventCount());
        } finally {
            service.shutdown();
        }
    }

    @Test
    public void shutdownDrainsAcceptedEvents() {
        List<List<Map<String, Object>>> deliveries = new ArrayList<>();
        LangfuseObservabilityServiceImpl service = service(defaultProperties(), deliveries, false);
        service.startTrace("session-1", "question", Map.of());

        service.shutdown();

        assertEquals(1, deliveries.size());
        assertEquals(1, deliveries.get(0).size());
        assertEquals(0, service.pendingEventCount());
    }

    @Test
    public void finalTraceUpsertReplacesOlderEventWhenQueueIsFull() {
        LangfuseProperties properties = defaultProperties();
        properties.setQueueCapacity(1);
        properties.setMaxBatchSize(50);
        List<List<Map<String, Object>>> deliveries = new ArrayList<>();
        LangfuseObservabilityServiceImpl service = service(properties, deliveries, false);
        try {
            String traceId = service.startTrace("session-1", "question", Map.of("traceName", "auto-agent"));

            service.endTrace(traceId, "answer", Map.of("intent", "GENERAL_CHAT"));
            service.flushOnce();

            assertEquals(1, deliveries.size());
            assertEquals(1, deliveries.get(0).size());
            Map<String, Object> finalBody = body(deliveries.get(0).get(0));
            assertEquals("question", finalBody.get("input"));
            assertEquals("answer", finalBody.get("output"));
            assertEquals(1, service.droppedEventCount());
        } finally {
            service.shutdown();
        }
    }

    private LangfuseObservabilityServiceImpl service(LangfuseProperties properties,
                                                      List<List<Map<String, Object>>> deliveries,
                                                      boolean startWorker) {
        return new LangfuseObservabilityServiceImpl(properties,
                events -> deliveries.add(copyEvents(events)), startWorker);
    }

    private LangfuseProperties defaultProperties() {
        LangfuseProperties properties = new LangfuseProperties();
        properties.setEnabled(true);
        properties.setHost("http://langfuse.invalid");
        properties.setPublicKey("public");
        properties.setSecretKey("secret");
        properties.setQueueCapacity(20);
        properties.setMaxBatchSize(20);
        properties.setFlushIntervalMs(60_000);
        properties.setShutdownDrainTimeoutMs(100);
        return properties;
    }

    private static List<Map<String, Object>> copyEvents(
            List<LangfuseObservabilityServiceImpl.QueuedEvent> events) {
        List<Map<String, Object>> result = new ArrayList<>();
        for (LangfuseObservabilityServiceImpl.QueuedEvent event : events) {
            result.add(event.toMap());
        }
        return result;
    }

    private static List<Object> eventIds(List<Map<String, Object>> events) {
        return events.stream().map(event -> event.get("id")).toList();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> body(Map<String, Object> event) {
        Object value = event.get("body");
        assertFalse(value == null);
        return (Map<String, Object>) value;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> metadata(Map<String, Object> body) {
        return (Map<String, Object>) body.get("metadata");
    }
}
