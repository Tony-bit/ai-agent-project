package denny.ai.agent.domain.service.runtime;

import denny.ai.agent.domain.model.entity.ChatMessageEntity;
import denny.ai.agent.domain.model.valobj.runtime.RetryRuntimeContext;
import denny.ai.agent.domain.model.valobj.runtime.SessionRuntimeContext;
import denny.ai.agent.domain.model.valobj.runtime.TurnRuntimeContext;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public class RetryRuntimeContextHolderTest {

    @Test
    public void scopeIsRemovedWhenSupplierThrows() {
        RetryRuntimeContext context = context("outer");

        assertThrows(IllegalStateException.class, () ->
                RetryRuntimeContextHolder.withContext(context, () -> {
                    assertSame(context, RetryRuntimeContextHolder.current());
                    throw new IllegalStateException("boom");
                }));

        assertNull(RetryRuntimeContextHolder.current());
    }

    @Test
    public void nestedScopeRestoresOuterContext() {
        RetryRuntimeContext outer = context("outer");
        RetryRuntimeContext inner = outer.forCompressionCall();

        RetryRuntimeContextHolder.withContext(outer, () -> {
            assertSame(outer, RetryRuntimeContextHolder.current());
            RetryRuntimeContextHolder.withContext(inner, () -> {
                assertSame(inner, RetryRuntimeContextHolder.current());
                assertTrue(RetryRuntimeContextHolder.current().isCompressionCall());
                return null;
            });
            assertSame(outer, RetryRuntimeContextHolder.current());
            assertFalse(RetryRuntimeContextHolder.current().isCompressionCall());
            return null;
        });

        assertNull(RetryRuntimeContextHolder.current());
    }

    @Test
    public void contextIsThreadIsolated() throws Exception {
        RetryRuntimeContext context = context("outer");
        AtomicReference<RetryRuntimeContext> childValue = new AtomicReference<>();

        RetryRuntimeContextHolder.withContext(context, () -> {
            Thread child = new Thread(() -> childValue.set(RetryRuntimeContextHolder.current()));
            child.start();
            try {
                child.join();
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(error);
            }
            assertSame(context, RetryRuntimeContextHolder.current());
            return null;
        });

        assertNull(childValue.get());
    }

    @Test
    public void fromTurnCreatesFilteredImmutableSnapshot() {
        ChatMessageEntity first = ChatMessageEntity.builder().content("first").build();
        ChatMessageEntity second = ChatMessageEntity.builder().content("second").build();
        List<ChatMessageEntity> source = new ArrayList<>();
        source.add(first);
        source.add(null);
        SessionRuntimeContext session = SessionRuntimeContext.builder().recentMessages(source).build();
        TurnRuntimeContext turn = TurnRuntimeContext.builder()
                .sessionId("session-1")
                .traceId("trace-1")
                .sessionRuntimeContext(session)
                .build();

        RetryRuntimeContext context = RetryRuntimeContext.from(turn);
        source.add(second);

        assertEquals("session-1", context.getSessionId());
        assertEquals("trace-1", context.getTraceId());
        assertEquals(List.of(first), context.getRecentMessages());
        assertThrows(UnsupportedOperationException.class,
                () -> context.getRecentMessages().add(second));
    }

    private RetryRuntimeContext context(String sessionId) {
        return RetryRuntimeContext.builder()
                .sessionId(sessionId)
                .traceId("trace")
                .build();
    }
}
