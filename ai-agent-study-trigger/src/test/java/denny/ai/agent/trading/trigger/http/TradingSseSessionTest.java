package denny.ai.agent.trading.trigger.http;

import denny.ai.agent.domain.model.entity.AutoAgentExecuteResultEntity;
import denny.ai.agent.domain.service.sse.SseSessionState;
import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyEmitter;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class TradingSseSessionTest {

    @Test
    void sendBusinessIsSerializedByWriterAndKeepsPayloadShape() throws Exception {
        RecordingEmitter emitter = new RecordingEmitter(1);
        TradingSseSession session = new TradingSseSession(emitter, "req-1", "session-1", "000001", 8);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            session.startWriter(executor);

            boolean accepted = session.sendBusiness("progress", AutoAgentExecuteResultEntity.builder()
                    .type("progress")
                    .subType("analysis_start")
                    .content("开始分析")
                    .timestamp(1L)
                    .build());

            assertTrue(accepted);
            assertTrue(emitter.awaitSend());
            assertEquals(1, emitter.sent.size());
            String frame = emitter.sent.get(0);
            assertTrue(frame.startsWith("event: progress\ndata: {"));
            assertTrue(frame.contains("\"type\":\"progress\""));
            assertTrue(frame.contains("\"subType\":\"analysis_start\""));
            assertFalse(frame.contains("\"payload\""));
        } finally {
            session.markDisconnected(null);
            executor.shutdownNow();
        }
    }

    @Test
    void heartbeatIsSkippedWhenQueueIsFull() {
        RecordingEmitter emitter = new RecordingEmitter(0);
        TradingSseSession session = new TradingSseSession(emitter, "req-1", "session-1", "000001", 1);

        assertTrue(session.sendBusiness("progress", AutoAgentExecuteResultEntity.builder()
                .type("progress")
                .subType("analysis_start")
                .content("开始分析")
                .build()));
        assertFalse(session.trySendHeartbeat());
        assertEquals(1, session.heartbeatSkipCount());
        assertEquals(1, session.queueSize());
    }

    @Test
    void completeIsIdempotentAndClosesEmitterOnce() throws Exception {
        RecordingEmitter emitter = new RecordingEmitter(0);
        TradingSseSession session = new TradingSseSession(emitter, "req-1", "session-1", "000001", 8);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            session.startWriter(executor);

            session.complete();
            session.complete();

            assertTrue(emitter.awaitComplete());
            assertEquals(1, emitter.completeCount);
            assertEquals(SseSessionState.CLOSED, session.state());
            assertFalse(session.shouldContinue());
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void writerSendFailureMarksDisconnected() throws Exception {
        FailingEmitter emitter = new FailingEmitter();
        TradingSseSession session = new TradingSseSession(emitter, "req-1", "session-1", "000001", 8);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            session.startWriter(executor);

            assertTrue(session.sendBusiness("progress", AutoAgentExecuteResultEntity.builder()
                    .type("progress")
                    .subType("analysis_start")
                    .content("开始分析")
                    .build()));

            assertTrue(emitter.awaitSendAttempt());
            awaitState(session, SseSessionState.DISCONNECTED);
            assertFalse(session.shouldContinue());
            assertEquals(1, session.sendFailureCount());
        } finally {
            executor.shutdownNow();
        }
    }

    private static void awaitState(TradingSseSession session, SseSessionState expected) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 2000L;
        while (System.currentTimeMillis() < deadline) {
            if (session.state() == expected) {
                return;
            }
            Thread.sleep(20L);
        }
        fail("Expected state " + expected + " but was " + session.state());
    }

    private static class RecordingEmitter extends ResponseBodyEmitter {
        private final List<String> sent = new ArrayList<>();
        private final CountDownLatch sendLatch;
        private final CountDownLatch completeLatch = new CountDownLatch(1);
        private int completeCount;

        private RecordingEmitter(int expectedSends) {
            this.sendLatch = new CountDownLatch(expectedSends);
        }

        @Override
        public synchronized void send(Object object) throws IOException {
            sent.add(String.valueOf(object));
            sendLatch.countDown();
        }

        @Override
        public synchronized void complete() {
            completeCount++;
            completeLatch.countDown();
        }

        private boolean awaitSend() throws InterruptedException {
            return sendLatch.await(2, TimeUnit.SECONDS);
        }

        private boolean awaitComplete() throws InterruptedException {
            return completeLatch.await(2, TimeUnit.SECONDS);
        }
    }

    private static class FailingEmitter extends ResponseBodyEmitter {
        private final CountDownLatch sendAttemptLatch = new CountDownLatch(1);

        @Override
        public synchronized void send(Object object) throws IOException {
            sendAttemptLatch.countDown();
            throw new IOException("Broken pipe");
        }

        private boolean awaitSendAttempt() throws InterruptedException {
            return sendAttemptLatch.await(2, TimeUnit.SECONDS);
        }
    }
}
