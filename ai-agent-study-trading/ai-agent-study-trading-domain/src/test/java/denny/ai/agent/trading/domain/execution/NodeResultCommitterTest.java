package denny.ai.agent.trading.domain.execution;

import denny.ai.agent.trading.domain.config.TradingPhase;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NodeResultCommitterTest {

    private static final Instant NOW = Instant.parse("2026-07-19T00:00:00Z");
    private final NodeResultCommitter committer = new NodeResultCommitter();

    @Test
    void should_commit_success_when_scope_deadline_and_phase_are_valid() {
        NodeExecutionScope scope = scope(NOW.plusSeconds(10), false);
        AtomicReference<String> context = new AtomicReference<>();

        boolean committed = committer.commit(NodeExecutionResult.success("report", scope),
                TradingPhase.INIT, () -> TradingPhase.INIT, context::set);

        assertTrue(committed);
        assertEquals("report", context.get());
        assertEquals(NodeExecutionState.COMMITTED, scope.state());
    }

    @Test
    void should_reject_failed_result_without_context_write() {
        NodeExecutionScope scope = scope(NOW.plusSeconds(10), false);
        AtomicReference<String> context = new AtomicReference<>();

        boolean committed = committer.commit(NodeExecutionResult.failed(
                        new IllegalStateException("failed"), scope),
                TradingPhase.INIT, () -> TradingPhase.INIT, context::set);

        assertFalse(committed);
        assertEquals(null, context.get());
        assertEquals(NodeExecutionState.FAILED, scope.state());
    }

    @Test
    void should_reject_success_when_deadline_has_elapsed() {
        NodeExecutionScope scope = scope(NOW, false);

        boolean committed = committer.commit(NodeExecutionResult.success("late", scope),
                TradingPhase.INIT, () -> TradingPhase.INIT, ignored -> { });

        assertFalse(committed);
        assertEquals(NodeExecutionState.TIMED_OUT, scope.state());
    }

    @Test
    void should_reject_success_after_request_cancel() {
        NodeExecutionScope scope = scope(NOW.plusSeconds(10), true);

        boolean committed = committer.commit(NodeExecutionResult.success("late", scope),
                TradingPhase.INIT, () -> TradingPhase.INIT, ignored -> { });

        assertFalse(committed);
        assertEquals(NodeExecutionState.CANCELLED, scope.state());
    }

    @Test
    void should_not_allow_timeout_to_replace_commit_in_progress() throws Exception {
        NodeExecutionScope scope = scope(NOW.plusSeconds(10), false);
        CountDownLatch writerEntered = new CountDownLatch(1);
        CountDownLatch releaseWriter = new CountDownLatch(1);

        Thread thread = new Thread(() -> committer.commit(
                NodeExecutionResult.success("report", scope), TradingPhase.INIT,
                () -> TradingPhase.INIT, value -> {
                    writerEntered.countDown();
                    try {
                        releaseWriter.await();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }));
        thread.start();
        assertTrue(writerEntered.await(1, java.util.concurrent.TimeUnit.SECONDS));

        assertFalse(scope.markTimedOut());
        releaseWriter.countDown();
        thread.join(1000L);
        assertEquals(NodeExecutionState.COMMITTED, scope.state());
    }

    private NodeExecutionScope scope(Instant deadline, boolean cancelled) {
        return new NodeExecutionScope(deadline, () -> cancelled,
                Clock.fixed(NOW, ZoneOffset.UTC));
    }
}
