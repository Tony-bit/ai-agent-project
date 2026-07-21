package denny.ai.agent.domain.service.armory.factory.element;

import denny.ai.agent.domain.model.valobj.AiClientModelVO.RetryConfig;
import denny.ai.agent.domain.model.valobj.runtime.RetryRuntimeContext;
import denny.ai.agent.domain.service.armory.AiStreamingProperties;
import denny.ai.agent.domain.service.compression.PromptCompressionService;
import denny.ai.agent.domain.service.compression.CompressionExhaustedException;
import denny.ai.agent.domain.service.runtime.RetryRuntimeContextHolder;
import denny.ai.agent.domain.util.TokenCountUtils;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

public class RetryChatModel implements ChatModel {

    private final ChatModel delegate;
    private final RetryConfig retryConfig;
    private final CompressionPolicy compressionPolicy;
    private final PromptCompressionService compressionService;
    private final AiErrorCodeExtractor errorCodeExtractor;
    private final AiStreamingProperties.StreamingTimeouts streamingTimeouts;

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
        this.delegate = Objects.requireNonNull(delegate, "delegate must not be null");
        this.retryConfig = Objects.requireNonNull(retryConfig, "retryConfig must not be null");
        this.compressionPolicy = compressionPolicy;
        this.compressionService = compressionService;
        this.errorCodeExtractor = errorCodeExtractor != null ? errorCodeExtractor : new AiErrorCodeExtractor();
        this.streamingTimeouts = Objects.requireNonNull(streamingTimeouts, "streamingTimeouts must not be null");
    }

    @Override
    public ChatResponse call(Prompt prompt) {
        RetryRuntimeContext context = RetryRuntimeContextHolder.current();
        return new CallRetryStrategy(context).execute(prompt);
    }

    @Override
    public Flux<ChatResponse> stream(Prompt prompt) {
        RetryRuntimeContext capturedContext = RetryRuntimeContextHolder.current();
        return Flux.defer(() -> {
            StreamState state = new StreamState(prompt, capturedContext);
            try {
                state.compressProactivelyIfRequired();
                return streamAttempt(state);
            } catch (RuntimeException error) {
                return Flux.error(error);
            }
        }).timeout(streamingTimeouts.totalTimeout());
    }

    private Flux<ChatResponse> streamAttempt(StreamState state) {
        if (state.modelCalls >= state.maxModelCalls) {
            return Flux.error(new IllegalStateException("model call safety limit exhausted"));
        }
        state.modelCalls++;
        AtomicReference<StreamPhase> phase = new AtomicReference<>(StreamPhase.AWAITING_RESPONSE);
        AtomicLong lastContentAtNanos = new AtomicLong();
        return Flux.defer(() -> {
                    long attemptStartedAtNanos = schedulerNowNanos();
                    lastContentAtNanos.set(attemptStartedAtNanos);
                    return delegate.stream(state.currentPrompt)
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
                            });
                })
                .onErrorResume(error -> {
                    if (phase.get() != StreamPhase.AWAITING_RESPONSE) {
                        return Flux.error(error);
                    }
                    Exception exception = error instanceof Exception value
                            ? value : new RuntimeException(error);
                    String errorCode = errorCodeExtractor.extract(exception);
                    if (AiErrorCodes.isContextOverflow(errorCode)) {
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
                            return streamAttempt(state);
                        } catch (RuntimeException compressionError) {
                            return Flux.error(compressionError);
                        }
                    }
                    if (state.isOrdinaryRetryable(exception, errorCode)
                            && state.ordinaryRetriesRemaining > 0) {
                        state.ordinaryRetriesRemaining--;
                        long delay = state.nextDelay();
                        return Mono.delay(Duration.ofMillis(delay))
                                .thenMany(streamAttempt(state));
                    }
                    return Flux.error(error);
                });
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
        private int ordinaryRetriesRemaining;
        private int compressionAttempts;
        private int modelCalls;
        private long interval;
        private final long maxInterval;

        private StreamState(Prompt prompt, RetryRuntimeContext runtimeContext) {
            this.currentPrompt = prompt;
            this.runtimeContext = runtimeContext;
            int ordinaryAttempts = retryConfig.isEnabled()
                    ? Math.max(1, Math.min(retryConfig.getMaxAttempts(), 10)) : 1;
            this.maxCompressionAttempts = compressionEnabled()
                    ? Math.max(1, Math.min(compressionPolicy.getMaxCompressionAttempts(), 3)) : 0;
            this.maxModelCalls = ordinaryAttempts + maxCompressionAttempts;
            this.ordinaryRetriesRemaining = ordinaryAttempts - 1;
            this.maxInterval = Math.max(0, retryConfig.getMaxIntervalMs());
            this.interval = Math.min(Math.max(0, retryConfig.getInitialIntervalMs()), maxInterval);
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

        private boolean isOrdinaryRetryable(Exception error, String errorCode) {
            Set<String> nonRetryable = toSet(retryConfig.getNonRetryableErrorCodes());
            if (nonRetryable.contains(errorCode)) {
                return false;
            }
            return toSet(retryConfig.getRetryableErrorCodes()).contains(errorCode)
                    || RetryableExceptionTypes.isRetryable(error);
        }

        private long nextDelay() {
            long current = interval;
            double multiplier = retryConfig.getMultiplier() <= 0 ? 1.0 : retryConfig.getMultiplier();
            interval = (long) Math.min(maxInterval, Math.max(0, interval * multiplier));
            return current;
        }

        private Set<String> toSet(List<String> values) {
            return values == null ? Set.of() : Set.copyOf(values);
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
