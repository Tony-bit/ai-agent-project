package denny.ai.agent.domain.service.armory.factory.element;

import denny.ai.agent.domain.model.valobj.AiClientModelVO.CompressionConfig;
import denny.ai.agent.domain.model.valobj.AiClientModelVO.RetryConfig;
import denny.ai.agent.domain.service.armory.CompressionRequiredException;
import denny.ai.agent.domain.service.armory.factory.DynamicContext;
import denny.ai.agent.domain.util.TokenCountUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * 带重试机制的 ChatModel 包装类
 * <p>
 * 使用策略模式分离 call() 和 stream() 的重试逻辑
 */
@Slf4j
public class RetryChatModel implements ChatModel {

    private final ChatModel delegate;
    private final RetryConfig retryConfig;
    private final AiErrorCodeExtractor errorCodeExtractor;

    private CompressionConfig compressionConfig;
    private DynamicContext dynamicContext;

    public RetryChatModel(ChatModel delegate, RetryConfig retryConfig) {
        this(delegate, retryConfig, null);
    }

    public RetryChatModel(ChatModel delegate, RetryConfig retryConfig,
                        AiErrorCodeExtractor errorCodeExtractor) {
        this.delegate = Objects.requireNonNull(delegate, "delegate must not be null");
        this.retryConfig = Objects.requireNonNull(retryConfig, "retryConfig must not be null");
        this.errorCodeExtractor = errorCodeExtractor != null ? errorCodeExtractor : new AiErrorCodeExtractor();
    }

    public void setCompressionConfig(CompressionConfig compressionConfig) {
        this.compressionConfig = compressionConfig;
    }

    public void setDynamicContext(DynamicContext dynamicContext) {
        this.dynamicContext = dynamicContext;
    }

    @Override
    public ChatResponse call(Prompt prompt) {
        return new CallRetryStrategy().execute(prompt);
    }

    @Override
    public Flux<ChatResponse> stream(Prompt prompt) {
        checkProactiveCompression(prompt);

        if (shouldDegradeToCall(prompt)) {
            log.info("[Stream] Token count exceeds threshold, degrading to call()");
            return Flux.just(call(prompt));
        }

        if (!retryConfig.isEnabled() || retryConfig.getMaxAttempts() <= 0) {
            return delegate.stream(prompt);
        }

        int maxAttempts = Math.min(retryConfig.getMaxAttempts(), 10);
        int attempt = 0;
        long maxInterval = Math.max(0, retryConfig.getMaxIntervalMs());
        long interval = Math.min(Math.max(0, retryConfig.getInitialIntervalMs()), maxInterval);
        RuntimeException lastException = null;

        while (attempt < maxAttempts) {
            attempt++;
            try {
                return delegate.stream(prompt);
            } catch (Exception e) {
                lastException = e instanceof RuntimeException ? (RuntimeException) e : new RuntimeException(e);
                String errorCode = errorCodeExtractor.extract(e);

                if (AiErrorCodes.CONTEXT_OVERFLOW.equals(errorCode)) {
                    checkPassiveCompression(prompt, errorCode);
                }

                if (nonRetryableErrorCodes().contains(errorCode)) {
                    log.warn("[RetryStream] Blacklist matched, skip retry, errorCode={}, attempt={}, ex={}",
                            errorCode, attempt, e.getMessage());
                    return Flux.error(lastException);
                }
                if (retryableErrorCodes().contains(errorCode) || RetryableExceptionTypes.isRetryable(e)) {
                    if (attempt >= maxAttempts) {
                        log.error("[RetryStream] Max attempts {} reached, giving up, attempt={}, errorCode={}, ex={}",
                                maxAttempts, attempt, errorCode, e.getMessage());
                        return Flux.error(lastException);
                    }
                    log.warn("[RetryStream] attempt {}/{} failed, retry after {}ms, errorCode={}, ex={}",
                            attempt, maxAttempts, interval, errorCode, e.getMessage());
                    sleep(interval);
                    interval = nextInterval(interval, maxInterval);
                } else {
                    log.warn("[RetryStream] Non-retryable exception, rethrow directly, errorCode={}, attempt={}, ex={}",
                            errorCode, attempt, e.getMessage());
                    return Flux.error(lastException);
                }
            }
        }
        return Flux.error(lastException != null ? lastException
                : new IllegalStateException("stream exhausted all retry attempts"));
    }

    /**
     * 判断是否应该降级到 call()
     */
    private boolean shouldDegradeToCall(Prompt prompt) {
        if (compressionConfig == null || !compressionConfig.isEnabled()) {
            return false;
        }
        int tokenCount = TokenCountUtils.estimate(prompt.toString());
        return tokenCount > compressionConfig.getProactiveThresholdTokens();
    }

    /**
     * 主动压缩检查：token 数量超过阈值时触发压缩
     */
    private void checkProactiveCompression(Prompt prompt) {
        if (compressionConfig == null || !compressionConfig.isEnabled()) {
            return;
        }
        int tokenCount = TokenCountUtils.estimate(prompt.toString());
        if (tokenCount > compressionConfig.getProactiveThresholdTokens()) {
            log.info("[Compression] Proactive compression triggered, tokenCount={}, threshold={}",
                    tokenCount, compressionConfig.getProactiveThresholdTokens());
            triggerCompression(prompt);
        }
    }

    /**
     * 被动压缩检查：收到 1261 错误时触发压缩
     */
    private void checkPassiveCompression(Prompt prompt, String errorCode) {
        if (compressionConfig == null || !compressionConfig.isEnabled()) {
            return;
        }
        if (dynamicContext != null && !dynamicContext.isCompressionRequired()) {
            log.info("[Compression] Passive compression triggered, errorCode={}", errorCode);
            triggerCompression(prompt);
        }
    }

    /**
     * 触发压缩流程
     */
    private void triggerCompression(Prompt prompt) {
        if (dynamicContext != null && !dynamicContext.isCompressionRequired()) {
            dynamicContext.setOriginalPrompt(prompt);
            dynamicContext.setCompressionRequired(true);
            dynamicContext.setReturnNode(AiErrorCodes.NODE_AI_CLIENT_MODEL);
            log.info("[Compression] Triggering compression, routing to compression node");
            throw new CompressionRequiredException(prompt, AiErrorCodes.NODE_AI_CLIENT_MODEL);
        }
    }

    // ===== 辅助方法 =====
    private Set<String> nonRetryableErrorCodes() {
        return toSet(retryConfig.getNonRetryableErrorCodes());
    }

    private Set<String> retryableErrorCodes() {
        return toSet(retryConfig.getRetryableErrorCodes());
    }

    private Set<String> toSet(List<String> list) {
        return list == null ? Set.of() : Set.copyOf(list);
    }

    private void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
    }

    private long nextInterval(long interval, long maxInterval) {
        double multiplier = retryConfig.getMultiplier() <= 0 ? 1.0 : retryConfig.getMultiplier();
        double next = interval * multiplier;
        return (long) Math.min(maxInterval, Math.max(0, next));
    }

    // ===== CallRetryStrategy =====
    private class CallRetryStrategy extends RetryStrategy<ChatResponse> {

        CallRetryStrategy() {
            super(RetryChatModel.this.delegate, RetryChatModel.this.retryConfig,
                    RetryChatModel.this.compressionConfig, RetryChatModel.this.dynamicContext,
                    RetryChatModel.this.errorCodeExtractor);
        }

        @Override
        protected ChatResponse doExecute(Prompt prompt) {
            return delegate.call(prompt);
        }

        @Override
        protected ChatResponse onExhausted(RuntimeException e) {
            if (e == null) {
                throw new IllegalStateException("exhausted all retry attempts without exception");
            }
            throw e;
        }
    }
}
