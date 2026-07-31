package denny.ai.agent.domain.service.armory.stream;

import lombok.extern.slf4j.Slf4j;
import org.reactivestreams.Publisher;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.ClientRequest;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.ExchangeFilterFunction;
import org.springframework.web.reactive.function.client.ExchangeFunction;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Scheduler;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

@Slf4j
public final class SseChunkTimeoutFilter implements ExchangeFilterFunction {

    private final Scheduler scheduler;
    private final Consumer<StallObserver.StallEvent> stallEventConsumer;

    public SseChunkTimeoutFilter(Scheduler scheduler) {
        this(scheduler, SseChunkTimeoutFilter::logStall);
    }

    public SseChunkTimeoutFilter(Scheduler scheduler,
                                 Consumer<StallObserver.StallEvent> stallEventConsumer) {
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler must not be null");
        this.stallEventConsumer = Objects.requireNonNull(
                stallEventConsumer, "stallEventConsumer must not be null");
    }

    @Override
    public Mono<ClientResponse> filter(ClientRequest request, ExchangeFunction next) {
        if (!isStreamingRequest(request)) {
            return next.exchange(request);
        }
        return Mono.deferContextual(contextView -> {
            StreamChunkTimeoutPolicy policy = StreamTimeoutContext.findPolicy(contextView)
                    .orElseThrow(MissingStreamTimeoutPolicyException::new);
            long requestStartedAt = nowNanos();
            long configuredFirstDeadline = addWithSaturation(
                    requestStartedAt, policy.firstChunkTimeout().toNanos());
            long firstChunkDeadline = Math.min(
                    configuredFirstDeadline, policy.attemptDeadlineNanos());

            return next.exchange(request)
                    .timeout(firstChunkSignal(policy, requestStartedAt, firstChunkDeadline, 0))
                    .map(response -> wrapSuccessfulBody(response, policy,
                            requestStartedAt, firstChunkDeadline));
        });
    }

    private ClientResponse wrapSuccessfulBody(ClientResponse response,
                                              StreamChunkTimeoutPolicy policy,
                                              long requestStartedAt,
                                              long firstChunkDeadline) {
        if (!response.statusCode().is2xxSuccessful()) {
            return response;
        }
        return response.mutate()
                .body(original -> watchBody(original, policy,
                        requestStartedAt, firstChunkDeadline))
                .build();
    }

    private Flux<DataBuffer> watchBody(Flux<DataBuffer> original,
                                       StreamChunkTimeoutPolicy policy,
                                       long requestStartedAt,
                                       long firstChunkDeadline) {
        return Flux.defer(() -> {
            StallObserver stallObserver = new StallObserver(policy.stallThreshold(), scheduler,
                    policy.logicalCallId(), policy.modelId(), stallEventConsumer);
            Flux<DataBuffer> monitored = original.handle((buffer, sink) -> {
                long now = nowNanos();
                if (policy.isAttemptExpired(now)) {
                    DataBufferUtils.release(buffer);
                    sink.error(queryAttemptTimeout(policy, now,
                            stallObserver.observedChunkCount()));
                    return;
                }
                stallObserver.onChunk();
                sink.next(buffer);
            });
            return monitored
                    .timeout(firstChunkSignal(policy, requestStartedAt,
                                    firstChunkDeadline, stallObserver.observedChunkCount()),
                            ignored -> nextChunkSignal(policy, nowNanos(),
                                    stallObserver.observedChunkCount()))
                    .doFinally(signalType -> stallObserver.terminate());
        });
    }

    private Publisher<?> firstChunkSignal(StreamChunkTimeoutPolicy policy,
                                          long requestStartedAt,
                                          long deadline,
                                          long observedChunkCount) {
        return timeoutSignal(deadline, () -> {
            long now = nowNanos();
            if (policy.isAttemptExpired(now)) {
                return queryAttemptTimeout(policy, now, observedChunkCount);
            }
            Duration effective = positiveDuration(deadline - requestStartedAt);
            return new FirstStreamChunkTimeoutException(policy.firstChunkTimeout(), effective,
                    TimeoutDeadlineOwner.FIRST_CHUNK,
                    elapsed(requestStartedAt, now), observedChunkCount,
                    policy.logicalCallId(), policy.modelId());
        });
    }

    private Publisher<?> nextChunkSignal(StreamChunkTimeoutPolicy policy,
                                         long lastChunkAt,
                                         long observedChunkCount) {
        long configuredDeadline = addWithSaturation(
                lastChunkAt, policy.chunkIdleTimeout().toNanos());
        long deadline = Math.min(configuredDeadline, policy.attemptDeadlineNanos());
        return timeoutSignal(deadline, () -> {
            long now = nowNanos();
            if (policy.isAttemptExpired(now)) {
                return queryAttemptTimeout(policy, now, observedChunkCount);
            }
            Duration effective = positiveDuration(deadline - lastChunkAt);
            return new StreamChunkIdleTimeoutException(policy.chunkIdleTimeout(), effective,
                    TimeoutDeadlineOwner.CHUNK_IDLE, elapsed(lastChunkAt, now),
                    observedChunkCount, policy.logicalCallId(), policy.modelId());
        });
    }

    private Publisher<?> timeoutSignal(long deadline,
                                       java.util.function.Supplier<? extends Throwable> errorSupplier) {
        long delay = Math.max(1, deadline - nowNanos());
        return Mono.delay(Duration.ofNanos(delay), scheduler)
                .flatMap(ignored -> Mono.error(errorSupplier.get()));
    }

    private LlmQueryAttemptTimeoutException queryAttemptTimeout(
            StreamChunkTimeoutPolicy policy, long now, long observedChunkCount) {
        return new LlmQueryAttemptTimeoutException(policy.queryAttemptTimeout(),
                policy.queryAttemptTimeout(), TimeoutDeadlineOwner.QUERY_ATTEMPT,
                elapsed(policy.attemptStartedAtNanos(), now), observedChunkCount,
                policy.logicalCallId(), policy.modelId());
    }

    private boolean isStreamingRequest(ClientRequest request) {
        return request.headers().getAccept().stream()
                .anyMatch(mediaType -> mediaType.isCompatibleWith(MediaType.TEXT_EVENT_STREAM));
    }

    private long nowNanos() {
        return scheduler.now(TimeUnit.NANOSECONDS);
    }

    private Duration elapsed(long startedAt, long now) {
        return Duration.ofNanos(Math.max(0, now - startedAt));
    }

    private Duration positiveDuration(long nanos) {
        return Duration.ofNanos(Math.max(1, nanos));
    }

    private long addWithSaturation(long value, long increment) {
        if (increment > 0 && value > Long.MAX_VALUE - increment) {
            return Long.MAX_VALUE;
        }
        return value + increment;
    }

    private static void logStall(StallObserver.StallEvent event) {
        log.warn("llm_stream_stall | logicalCallId={} | modelId={} "
                        + "| configuredThresholdMs={} | elapsedSinceLastChunkMs={} "
                        + "| observedChunkCount={}",
                event.logicalCallId(), event.modelId(),
                event.configuredThreshold().toMillis(),
                event.elapsedSinceLastChunk().toMillis(),
                event.observedChunkCount());
    }
}
