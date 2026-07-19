package denny.ai.agent.domain.service.auto.step;

import lombok.extern.slf4j.Slf4j;
import org.reactivestreams.Publisher;
import org.springframework.stereotype.Component;
import reactor.core.Exceptions;
import reactor.core.publisher.Flux;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

@Slf4j
@Component
public class StreamingChatResponseCollector {

    public String collect(Flux<String> content,
                          String operation,
                          Publisher<Void> cancellationSignal) {
        long startedAt = System.nanoTime();
        AtomicLong firstContentAt = new AtomicLong(0L);
        AtomicInteger chunkCount = new AtomicInteger();
        AtomicBoolean cancelled = new AtomicBoolean(false);
        StringBuilder result = new StringBuilder();

        Flux<Void> effectiveCancellation = cancellationSignal == null
                ? Flux.never()
                : Flux.from(cancellationSignal).doOnComplete(() -> cancelled.set(true));
        try {
            content.takeUntilOther(effectiveCancellation)
                    .doOnNext(chunk -> {
                        if (chunk != null && !chunk.isEmpty()) {
                            firstContentAt.compareAndSet(0L, System.nanoTime());
                            chunkCount.incrementAndGet();
                            result.append(chunk);
                        }
                    })
                    .blockLast();
            if (cancelled.get()) {
                throw new ClientDisconnectedException(
                        "Streaming response cancelled: " + operation);
            }
            logCompletion(operation, startedAt, firstContentAt.get(), chunkCount.get(),
                    result.length(), "completed");
            return result.toString();
        } catch (RuntimeException error) {
            Throwable cause = Exceptions.unwrap(error);
            if (Thread.currentThread().isInterrupted()) {
                throw new ClientDisconnectedException(
                        "Streaming response interrupted: " + operation, cause);
            }
            logCompletion(operation, startedAt, firstContentAt.get(), chunkCount.get(),
                    result.length(), cancelled.get() ? "cancelled" : "error");
            if (cause instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            throw new RuntimeException(cause);
        } finally {
            result.setLength(0);
        }
    }

    private void logCompletion(String operation,
                               long startedAt,
                               long firstContentAt,
                               int chunkCount,
                               int responseLength,
                               String completionState) {
        long completedAt = System.nanoTime();
        long firstContentLatencyMs = firstContentAt == 0L
                ? -1L : (firstContentAt - startedAt) / 1_000_000L;
        long totalLatencyMs = (completedAt - startedAt) / 1_000_000L;
        log.info("LLM streaming aggregation | operation={} | firstContentLatencyMs={} "
                        + "| totalLatencyMs={} | chunkCount={} | responseLength={} | completionState={}",
                operation, firstContentLatencyMs, totalLatencyMs, chunkCount,
                responseLength, completionState);
    }
}
