package denny.ai.agent.domain.service.armory.factory.element;

import denny.ai.agent.domain.model.valobj.AiClientModelVO.RetryConfig;
import denny.ai.agent.domain.model.valobj.runtime.RetryRuntimeContext;
import denny.ai.agent.domain.service.armory.AiStreamingProperties;
import denny.ai.agent.domain.service.armory.stream.LlmQueryAttemptTimeoutException;
import denny.ai.agent.domain.service.armory.stream.LlmTimeoutException;
import denny.ai.agent.domain.service.armory.stream.StreamChunkTimeoutPolicy;
import denny.ai.agent.domain.service.armory.stream.StreamTimeoutType;
import denny.ai.agent.domain.service.armory.stream.StreamTimeoutContext;
import denny.ai.agent.domain.service.armory.stream.TimeoutDeadlineOwner;
import denny.ai.agent.domain.service.compression.PromptCompressionService;
import denny.ai.agent.domain.service.compression.CompressionExhaustedException;
import denny.ai.agent.domain.service.runtime.RetryRuntimeContextHolder;
import denny.ai.agent.domain.util.TokenCountUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import reactor.core.scheduler.Schedulers;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

@Slf4j
public class RetryChatModel implements ChatModel {

    private final ChatModel delegate;
    private final RetryConfig retryConfig;
    private final CompressionPolicy compressionPolicy;
    private final PromptCompressionService compressionService;
    private final AiErrorCodeExtractor errorCodeExtractor;
    private final AiStreamingProperties.StreamingTimeouts streamingTimeouts;
    private final String modelId;

    public RetryChatModel(ChatModel delegate, RetryConfig retryConfig) {
        this(delegate, retryConfig, null, null, null);
    }

    public RetryChatModel(ChatModel delegate,
                          RetryConfig retryConfig,
                          CompressionPolicy compressionPolicy,
                          PromptCompressionService compressionService,
                          AiErrorCodeExtractor errorCodeExtractor) {
        this(delegate, retryConfig, compressionPolicy, compressionService, errorCodeExtractor,
                new AiStreamingProperties().resolve(null));
    }

    public RetryChatModel(ChatModel delegate,
                          RetryConfig retryConfig,
                          CompressionPolicy compressionPolicy,
                          PromptCompressionService compressionService,
                          AiErrorCodeExtractor errorCodeExtractor,
                          AiStreamingProperties.StreamingTimeouts streamingTimeouts) {
        this(delegate, retryConfig, compressionPolicy, compressionService, errorCodeExtractor,
                streamingTimeouts, null);
    }

    public RetryChatModel(ChatModel delegate,
                          RetryConfig retryConfig,
                          CompressionPolicy compressionPolicy,
                          PromptCompressionService compressionService,
                          AiErrorCodeExtractor errorCodeExtractor,
                          AiStreamingProperties.StreamingTimeouts streamingTimeouts,
                          String modelId) {
        this.delegate = Objects.requireNonNull(delegate, "delegate must not be null");
        this.retryConfig = Objects.requireNonNull(retryConfig, "retryConfig must not be null");
        this.compressionPolicy = compressionPolicy;
        this.compressionService = compressionService;
        this.errorCodeExtractor = errorCodeExtractor != null ? errorCodeExtractor : new AiErrorCodeExtractor();
        this.streamingTimeouts = Objects.requireNonNull(streamingTimeouts, "streamingTimeouts must not be null");
        this.modelId = modelId;
    }

    @Override
    public ChatResponse call(Prompt prompt) {
        RetryRuntimeContext context = RetryRuntimeContextHolder.current();
        return new CallRetryStrategy(context).execute(prompt);
    }

    @Override
    public Flux<ChatResponse> stream(Prompt prompt) {
        RetryRuntimeContext capturedContext = RetryRuntimeContextHolder.current();
        Flux<ChatResponse> logicalCall = Flux.defer(() -> {
            StreamState state = new StreamState(prompt, capturedContext);
            try {
                state.compressProactivelyIfRequired();
                return streamAttempt(state);
            } catch (RuntimeException error) {
                return Flux.error(error);
            }
        });
        return streamingTimeouts.timeoutMode() == AiStreamingProperties.TimeoutMode.LEGACY
                ? logicalCall.timeout(streamingTimeouts.totalTimeout())
                : logicalCall;
    }

    private Flux<ChatResponse> streamAttempt(StreamState state) {
        return Flux.defer(() -> {
            if (state.modelCalls >= state.maxModelCalls) {
                return Flux.error(new IllegalStateException(
                        "model call safety limit exhausted"));
            }
            state.modelCalls++;
            int attemptNumber = state.modelCalls;
            Sinks.One<Void> terminated = Sinks.one();
            Flux<ChatResponse> attempt = streamingTimeouts.timeoutMode()
                    == AiStreamingProperties.TimeoutMode.LEGACY
                    ? legacyStreamAttempt(state, attemptNumber, terminated)
                    : layeredStreamAttempt(state, attemptNumber, terminated);
            return attempt
                    .onErrorResume(error -> terminated.asMono()
                            .thenMany(Flux.defer(() ->
                                    resumeAfterStreamError(state, error))));
        });
    }

    private Flux<ChatResponse> legacyStreamAttempt(StreamState state, int attemptNumber,
                                                   Sinks.One<Void> terminated) {
        AtomicReference<StreamPhase> phase = new AtomicReference<>(StreamPhase.AWAITING_RESPONSE);
        AtomicLong lastContentAtNanos = new AtomicLong();
        return Flux.defer(() -> {
                    long attemptStartedAtNanos = schedulerNowNanos();
                    lastContentAtNanos.set(attemptStartedAtNanos);
                    List<ChatResponse> responses = new ArrayList<>();
                    return delegateStream(state, terminated)
                            .timeout(Mono.delay(streamingTimeouts.firstContentTimeout()), response -> {
                                long now = schedulerNowNanos();
                                if (hasEffectiveContent(response)) {
                                    phase.set(StreamPhase.CONTENT_OBSERVED);
                                    lastContentAtNanos.set(now);
                                    return Mono.delay(streamingTimeouts.idleTimeout());
                                }
                                phase.compareAndSet(StreamPhase.AWAITING_RESPONSE,
                                        StreamPhase.RESPONSE_OBSERVED);
                                Duration remaining = phase.get() == StreamPhase.CONTENT_OBSERVED
                                        ? remaining(streamingTimeouts.idleTimeout(),
                                                lastContentAtNanos.get(), now)
                                        : remaining(streamingTimeouts.firstContentTimeout(),
                                                attemptStartedAtNanos, now);
                                return Mono.delay(remaining);
                            })
                            .doOnNext(responses::add)
                            .thenMany(Flux.defer(() -> Flux.fromIterable(responses)))
                            .doOnError(error -> {
                                logAttemptFailure(attemptNumber, attemptStartedAtNanos,
                                        responses, error);
                                responses.clear();
                            })
                            .doFinally(signal -> responses.clear());
                });
    }

    private Flux<ChatResponse> layeredStreamAttempt(StreamState state, int attemptNumber,
                                                    Sinks.One<Void> terminated) {
        return Flux.defer(() -> {
            long attemptStartedAtNanos = schedulerNowNanos();
            long attemptDeadlineNanos = addWithSaturation(attemptStartedAtNanos,
                    streamingTimeouts.queryAttemptTimeout().toNanos());
            StreamChunkTimeoutPolicy policy = new StreamChunkTimeoutPolicy(
                    streamingTimeouts.firstChunkTimeout(), streamingTimeouts.stallThreshold(),
                    streamingTimeouts.chunkIdleTimeout(), streamingTimeouts.queryAttemptTimeout(),
                    attemptStartedAtNanos, attemptDeadlineNanos,
                    state.logicalCallId, modelId);
            List<ChatResponse> responses = new ArrayList<>();

            Mono<List<ChatResponse>> completion = delegateStream(state, terminated)
                    .doOnNext(responses::add)
                    .then(Mono.fromSupplier(() -> List.copyOf(responses)))
                    .contextWrite(StreamTimeoutContext.withPolicy(policy))
                    .timeout(Mono.delay(streamingTimeouts.queryAttemptTimeout())
                            .flatMap(ignored -> Mono.error(queryAttemptTimeout(
                                    policy, responses.size()))));

            return completion.flatMapMany(Flux::fromIterable)
                    .doOnError(error -> {
                        if (error instanceof LlmTimeoutException timeout) {
                            logStreamTimeout(timeout);
                        }
                        logAttemptFailure(attemptNumber, attemptStartedAtNanos,
                                responses, error);
                        responses.clear();
                    })
                    .doFinally(signal -> responses.clear());
        });
    }

    private Flux<ChatResponse> delegateStream(StreamState state,
                                              Sinks.One<Void> terminated) {
        return Flux.defer(() -> {
            try {
                return delegate.stream(state.currentPrompt)
                        .doFinally(signal -> terminated.tryEmitEmpty());
            } catch (Throwable error) {
                terminated.tryEmitEmpty();
                return Flux.error(error);
            }
        });
    }

    private Flux<ChatResponse> resumeAfterStreamError(StreamState state, Throwable error) {
        Exception exception = error instanceof Exception value
                ? value : new RuntimeException(error);
        String errorCode = errorCodeExtractor.extract(exception);
        StreamTimeoutType timeoutType = state.retryClassifier
                .streamTimeoutType(error).orElse(null);

        if (state.retryClassifier.isSafetyExcluded(error)) {
            return Flux.error(error);
        }
        if (state.retryClassifier.matchesNonRetryableCode(error)) {
            return Flux.error(error);
        }
        if (AiErrorCodes.isContextOverflow(errorCode)) {
            return compressAndRetryOrPropagate(state, error, errorCode);
        }
        if (state.retryClassifier.isDefiniteNonRetryable4xx(error)) {
            return Flux.error(error);
        }
        if (timeoutType != null) {
            return retryStreamTimeoutOrPropagate(state, error);
        }
        if (state.retryClassifier.isOrdinaryRetryable(exception)) {
            return retryOrdinaryOrPropagate(state, error);
        }
        return Flux.error(error);
    }

    private Flux<ChatResponse> compressAndRetryOrPropagate(
            StreamState state, Throwable error, String errorCode) {
        if (!state.compressionEnabled()) {
            return Flux.error(error);
        }
        if (state.compressionAttempts >= state.maxCompressionAttempts) {
            return Flux.error(new CompressionExhaustedException(
                    "context overflow after " + state.compressionAttempts
                            + " compression attempts", error));
        }
        try {
            state.currentPrompt = state.compress(state.currentPrompt, errorCode);
            return nextAttempt(state, Duration.ZERO);
        } catch (RuntimeException compressionError) {
            return Flux.error(compressionError);
        }
    }

    private Flux<ChatResponse> retryStreamTimeoutOrPropagate(
            StreamState state, Throwable error) {
        if (!retryConfig.isEnabled() || !retryConfig.isRetryOnStreamTimeout()) {
            return Flux.error(error);
        }
        return retryOrdinaryOrPropagate(state, error);
    }

    private Flux<ChatResponse> retryOrdinaryOrPropagate(
            StreamState state, Throwable error) {
        if (state.ordinaryRetriesRemaining <= 0) {
            return Flux.error(error);
        }
        state.ordinaryRetriesRemaining--;
        state.ordinaryRetriesUsed++;
        long delay = state.nextDelay();
        return nextAttempt(state, Duration.ofMillis(delay));
    }

    private Flux<ChatResponse> nextAttempt(StreamState state, Duration delay) {
        Duration schedulingDelay = delay.isZero() ? Duration.ofMillis(1) : delay;
        return Mono.delay(schedulingDelay)
                .thenMany(Flux.defer(() -> streamAttempt(state)));
    }

    private LlmQueryAttemptTimeoutException queryAttemptTimeout(
            StreamChunkTimeoutPolicy policy, long observedChunkCount) {
        long now = schedulerNowNanos();
        Duration elapsed = Duration.ofNanos(Math.max(0,
                now - policy.attemptStartedAtNanos()));
        return new LlmQueryAttemptTimeoutException(policy.queryAttemptTimeout(),
                policy.queryAttemptTimeout(), TimeoutDeadlineOwner.QUERY_ATTEMPT,
                elapsed, observedChunkCount, policy.logicalCallId(), policy.modelId());
    }

    private void logStreamTimeout(LlmTimeoutException timeout) {
        log.warn("llm_stream_timeout | timeoutType={} | configuredTimeoutMs={} "
                        + "| effectiveTimeoutMs={} | deadlineOwner={} | elapsedMs={} "
                        + "| observedChunkCount={} | logicalCallId={} | modelId={}",
                timeout.getClass().getSimpleName(),
                timeout.getConfiguredTimeout().toMillis(),
                timeout.getEffectiveTimeout().toMillis(),
                timeout.getDeadlineOwner(), timeout.getElapsed().toMillis(),
                timeout.getObservedChunkCount(), timeout.getLogicalCallId(),
                timeout.getModelId());
    }

    private long addWithSaturation(long value, long increment) {
        if (increment > 0 && value > Long.MAX_VALUE - increment) {
            return Long.MAX_VALUE;
        }
        return value + increment;
    }

    private void logAttemptFailure(int attemptNumber,
                                   long startedAtNanos,
                                   List<ChatResponse> responses,
                                   Throwable error) {
        int partialLength = responses.stream().mapToInt(this::contentLength).sum();
        String errorCode = error instanceof Exception exception
                ? errorCodeExtractor.extract(exception) : AiErrorCodes.UNKNOWN;
        long durationMs = TimeUnit.NANOSECONDS.toMillis(
                Math.max(0, schedulerNowNanos() - startedAtNanos));
        log.warn("LLM stream query attempt failed | attemptNumber={} | durationMs={} "
                        + "| chunkCount={} | partialLength={} | errorType={} | errorCode={}",
                attemptNumber, durationMs, responses.size(), partialLength,
                error.getClass().getName(), errorCode);
    }

    private int contentLength(ChatResponse response) {
        if (response == null || response.getResult() == null
                || response.getResult().getOutput() == null
                || response.getResult().getOutput().getText() == null) {
            return 0;
        }
        return response.getResult().getOutput().getText().length();
    }

    private boolean hasEffectiveContent(ChatResponse response) {
        if (response == null || response.getResult() == null
                || response.getResult().getOutput() == null) {
            return false;
        }
        String text = response.getResult().getOutput().getText();
        return text != null && !text.isBlank();
    }

    private Duration remaining(Duration limit, long startedAtNanos, long nowNanos) {
        long remainingNanos = limit.toNanos() - Math.max(0, nowNanos - startedAtNanos);
        return Duration.ofNanos(Math.max(1, remainingNanos));
    }

    private long schedulerNowNanos() {
        return Schedulers.parallel().now(TimeUnit.NANOSECONDS);
    }

    private enum StreamPhase {
        AWAITING_RESPONSE,
        RESPONSE_OBSERVED,
        CONTENT_OBSERVED
    }

    private final class StreamState {
        private Prompt currentPrompt;
        private final RetryRuntimeContext runtimeContext;
        private final int maxCompressionAttempts;
        private final int maxModelCalls;
        private final StreamQueryRetryClassifier retryClassifier;
        private final double multiplier;
        private int ordinaryRetriesRemaining;
        private int ordinaryRetriesUsed;
        private int compressionAttempts;
        private int modelCalls;
        private long interval;
        private final long maxInterval;
        private final String logicalCallId;

        private StreamState(Prompt prompt, RetryRuntimeContext runtimeContext) {
            this.currentPrompt = prompt;
            this.runtimeContext = runtimeContext;
            this.logicalCallId = logicalCallId(runtimeContext);
            int ordinaryAttempts = retryConfig.isEnabled()
                    ? Math.max(1, Math.min(retryConfig.getMaxAttempts(), 10)) : 1;
            this.maxCompressionAttempts = compressionEnabled()
                    ? Math.max(1, Math.min(compressionPolicy.getMaxCompressionAttempts(), 3)) : 0;
            this.maxModelCalls = ordinaryAttempts + maxCompressionAttempts;
            this.ordinaryRetriesRemaining = ordinaryAttempts - 1;
            this.retryClassifier = new StreamQueryRetryClassifier(retryConfig);
            this.multiplier = retryConfig.getMultiplier() <= 0
                    ? 1.0 : retryConfig.getMultiplier();
            this.maxInterval = Math.max(0, retryConfig.getMaxIntervalMs());
            this.interval = Math.min(Math.max(0, retryConfig.getInitialIntervalMs()), maxInterval);
        }

        private String logicalCallId(RetryRuntimeContext context) {
            if (context != null && context.getTraceId() != null
                    && !context.getTraceId().isBlank()) {
                return context.getTraceId();
            }
            return UUID.randomUUID().toString();
        }

        private void compressProactivelyIfRequired() {
            if (compressionEnabled()
                    && compressionPolicy.getProactiveThresholdTokens() > 0
                    && TokenCountUtils.estimate(currentPrompt.toString())
                    > compressionPolicy.getProactiveThresholdTokens()) {
                currentPrompt = compress(currentPrompt, "proactive");
            }
        }

        private Prompt compress(Prompt prompt, String trigger) {
            if (compressionService == null) {
                throw new CompressionExhaustedException("compression service is unavailable");
            }
            compressionAttempts++;
            int beforeTokens = TokenCountUtils.estimate(prompt.toString());
            Prompt compressed = compressionService.compress(prompt, runtimeContext, compressionPolicy);
            int afterTokens = TokenCountUtils.estimate(compressed.toString());
            if (afterTokens >= beforeTokens) {
                throw new CompressionExhaustedException("compressed prompt must be smaller than original prompt");
            }
            return compressed;
        }

        private boolean compressionEnabled() {
            return compressionPolicy != null
                    && (runtimeContext == null || !runtimeContext.isCompressionCall());
        }

        private boolean isOrdinaryRetryable(Exception error) {
            return retryClassifier.isRetryable(error);
        }

        private long nextDelay() {
            long current = interval;
            interval = (long) Math.min(maxInterval, Math.max(0, interval * multiplier));
            return current;
        }
    }

    private class CallRetryStrategy extends RetryStrategy<ChatResponse> {

        CallRetryStrategy(RetryRuntimeContext runtimeContext) {
            super(RetryChatModel.this.delegate, RetryChatModel.this.retryConfig,
                    RetryChatModel.this.compressionPolicy, RetryChatModel.this.compressionService,
                    runtimeContext, RetryChatModel.this.errorCodeExtractor);
        }

        @Override
        protected ChatResponse doExecute(Prompt prompt) {
            ChatResponse response = delegate.call(prompt);
            ChatResponseValidator validator = ResponseValidationContext.currentValidator();
            if (validator != null) {
                validator.validate(response);
            }
            return response;
        }

        @Override
        protected ChatResponse onExhausted(RuntimeException error) {
            if (error == null) {
                throw new IllegalStateException("exhausted all retry attempts without exception");
            }
            throw error;
        }
    }
}
