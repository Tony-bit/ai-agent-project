package denny.ai.agent.domain.service.armory.factory.element;

import denny.ai.agent.domain.model.valobj.AiClientModelVO.RetryConfig;
import denny.ai.agent.domain.model.valobj.runtime.RetryRuntimeContext;
import denny.ai.agent.domain.service.compression.CompressionExhaustedException;
import denny.ai.agent.domain.service.compression.PromptCompressionService;
import denny.ai.agent.domain.util.TokenCountUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.Prompt;

import java.util.List;
import java.util.Set;

@Slf4j
public abstract class RetryStrategy<T> {

    private static final int MAX_SAFE_ATTEMPTS = 10;
    private static final int MAX_SAFE_COMPRESSION_ATTEMPTS = 3;

    private final ChatModel delegate;
    private final RetryConfig retryConfig;
    private final CompressionPolicy compressionPolicy;
    private final PromptCompressionService compressionService;
    private final RetryRuntimeContext runtimeContext;
    private final AiErrorCodeExtractor errorCodeExtractor;
    protected final Set<String> retryableErrorCodes;
    protected final Set<String> nonRetryableErrorCodes;

    protected RetryStrategy(ChatModel delegate,
                            RetryConfig retryConfig,
                            CompressionPolicy compressionPolicy,
                            PromptCompressionService compressionService,
                            RetryRuntimeContext runtimeContext,
                            AiErrorCodeExtractor errorCodeExtractor) {
        this.delegate = delegate;
        this.retryConfig = retryConfig;
        this.compressionPolicy = compressionPolicy;
        this.compressionService = compressionService;
        this.runtimeContext = runtimeContext;
        this.errorCodeExtractor = errorCodeExtractor != null ? errorCodeExtractor : new AiErrorCodeExtractor();
        this.retryableErrorCodes = toSet(retryConfig.getRetryableErrorCodes());
        this.nonRetryableErrorCodes = toSet(retryConfig.getNonRetryableErrorCodes());
    }

    public T execute(Prompt prompt) {
        Prompt currentPrompt = prompt;
        int ordinaryAttemptsLimit = retryConfig.isEnabled()
                ? Math.max(1, Math.min(retryConfig.getMaxAttempts(), MAX_SAFE_ATTEMPTS))
                : 1;
        int maxCompressionAttempts = compressionEnabled()
                ? Math.max(1, Math.min(compressionPolicy.getMaxCompressionAttempts(),
                        MAX_SAFE_COMPRESSION_ATTEMPTS))
                : 0;
        int ordinaryRetriesRemaining = ordinaryAttemptsLimit - 1;
        int maxModelCalls = ordinaryAttemptsLimit + maxCompressionAttempts;
        int modelCalls = 0;
        int compressionAttempts = 0;
        long maxInterval = Math.max(0, retryConfig.getMaxIntervalMs());
        long interval = Math.min(Math.max(0, retryConfig.getInitialIntervalMs()), maxInterval);

        if (shouldCompressProactively(currentPrompt)) {
            currentPrompt = compress(currentPrompt, ++compressionAttempts, "proactive");
        }

        while (modelCalls < maxModelCalls) {
            modelCalls++;
            log.info("[Retry] traceId={}, sessionId={}, modelCall={}/{}, ordinaryRetriesRemaining={}, promptTokens={}",
                    traceId(), sessionId(), modelCalls, maxModelCalls, ordinaryRetriesRemaining,
                    TokenCountUtils.estimate(currentPrompt.toString()));
            try {
                return doExecute(currentPrompt);
            } catch (Exception error) {
                String errorCode = errorCodeExtractor.extract(error);
                log.warn("[Retry] traceId={}, sessionId={}, modelCall={}/{}, errorCode={}",
                        traceId(), sessionId(), modelCalls, maxModelCalls, errorCode);
                if (AiErrorCodes.CONTEXT_OVERFLOW.equals(errorCode)) {
                    if (!compressionEnabled()) {
                        return onExhausted(toRuntimeException(error));
                    }
                    if (compressionAttempts >= maxCompressionAttempts) {
                        throw new CompressionExhaustedException(
                                "context overflow after " + compressionAttempts + " compression attempts", error);
                    }
                    currentPrompt = compress(currentPrompt, ++compressionAttempts, "1261");
                    continue;
                }

                if (isOrdinaryRetryable(error, errorCode) && ordinaryRetriesRemaining > 0) {
                    ordinaryRetriesRemaining--;
                    log.warn("[Retry] retry current prompt after {}ms, errorCode={}, retriesRemaining={}",
                            interval, errorCode, ordinaryRetriesRemaining);
                    sleep(interval);
                    interval = nextInterval(interval, maxInterval);
                    continue;
                }
                return onExhausted(toRuntimeException(error));
            }
        }
        throw new IllegalStateException("model call safety limit exhausted");
    }

    protected abstract T doExecute(Prompt prompt);

    protected abstract T onExhausted(RuntimeException error);

    private Prompt compress(Prompt prompt, int attempt, String trigger) {
        if (compressionService == null) {
            throw new CompressionExhaustedException("compression service is unavailable");
        }
        int beforeTokens = TokenCountUtils.estimate(prompt.toString());
        Prompt compressed = compressionService.compress(prompt, runtimeContext, compressionPolicy);
        int afterTokens = TokenCountUtils.estimate(compressed.toString());
        if (afterTokens >= beforeTokens) {
            throw new CompressionExhaustedException("compressed prompt must be smaller than original prompt");
        }
        int maxAttempts = Math.max(1, Math.min(compressionPolicy.getMaxCompressionAttempts(),
                MAX_SAFE_COMPRESSION_ATTEMPTS));
        log.info("[Compression] traceId={}, sessionId={}, compressionAttempt={}/{}, trigger={}, beforeTokens={}, afterTokens={}",
                traceId(), sessionId(), attempt, maxAttempts, trigger, beforeTokens, afterTokens);
        return compressed;
    }

    private String traceId() {
        return runtimeContext == null ? null : runtimeContext.getTraceId();
    }

    private String sessionId() {
        return runtimeContext == null ? null : runtimeContext.getSessionId();
    }

    private boolean shouldCompressProactively(Prompt prompt) {
        return compressionEnabled()
                && compressionPolicy.getProactiveThresholdTokens() > 0
                && TokenCountUtils.estimate(prompt.toString())
                > compressionPolicy.getProactiveThresholdTokens();
    }

    private boolean compressionEnabled() {
        return compressionPolicy != null
                && compressionPolicy.isEnabled()
                && (runtimeContext == null || !runtimeContext.isCompressionCall());
    }

    private boolean isOrdinaryRetryable(Exception error, String errorCode) {
        if (nonRetryableErrorCodes.contains(errorCode)) {
            return false;
        }
        return retryableErrorCodes.contains(errorCode) || RetryableExceptionTypes.isRetryable(error);
    }

    private RuntimeException toRuntimeException(Exception error) {
        return error instanceof RuntimeException runtime ? runtime : new RuntimeException(error);
    }

    private Set<String> toSet(List<String> values) {
        return values == null ? Set.of() : Set.copyOf(values);
    }

    protected void sleep(long milliseconds) {
        try {
            Thread.sleep(milliseconds);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
        }
    }

    private long nextInterval(long interval, long maxInterval) {
        double multiplier = retryConfig.getMultiplier() <= 0 ? 1.0 : retryConfig.getMultiplier();
        return (long) Math.min(maxInterval, Math.max(0, interval * multiplier));
    }
}
