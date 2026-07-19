package denny.ai.agent.domain.service.auto.step;

import org.junit.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public class StreamingChatResponseCollectorTest {

    private final StreamingChatResponseCollector collector = new StreamingChatResponseCollector();

    @Test
    public void should_collect_chunks_in_order_when_stream_completes() {
        String result = collector.collect(Flux.just("a", "", "b", "c"),
                "test", Flux.never());

        assertEquals("abc", result);
    }

    @Test
    public void should_return_empty_string_when_stream_is_empty() {
        assertEquals("", collector.collect(Flux.empty(), "empty", Flux.never()));
    }

    @Test
    public void should_not_return_partial_content_when_stream_fails() {
        Flux<String> source = Flux.concat(Flux.just("partial"),
                Flux.error(new IllegalStateException("failed")));

        assertThrows(IllegalStateException.class,
                () -> collector.collect(source, "failure", Flux.never()));
    }

    @Test
    public void should_cancel_upstream_and_discard_partial_content_when_request_is_cancelled() {
        Sinks.One<Void> cancellation = Sinks.one();
        AtomicBoolean upstreamCancelled = new AtomicBoolean(false);
        Flux<String> source = Flux.concat(Flux.just("partial"), Flux.<String>never())
                .doOnCancel(() -> upstreamCancelled.set(true));

        Thread cancellationThread = new Thread(() -> {
            try {
                Thread.sleep(50L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            cancellation.tryEmitEmpty();
        });
        cancellationThread.start();

        assertThrows(ClientDisconnectedException.class,
                () -> collector.collect(source, "cancel", cancellation.asMono()));
        assertTrue(upstreamCancelled.get());
    }
}
