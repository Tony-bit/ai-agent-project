package denny.ai.agent.domain.service.armory.factory.element;

import denny.ai.agent.domain.model.valobj.AiClientModelVO.CompressionConfig;
import denny.ai.agent.domain.model.valobj.AiClientModelVO.RetryConfig;
import denny.ai.agent.domain.service.armory.CompressionRequiredException;
import denny.ai.agent.domain.service.armory.factory.DynamicContext;
import denny.ai.agent.domain.util.TokenCountUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.Prompt;

import java.util.List;
import java.util.Set;

/**
 * 重试策略抽象类（模板方法模式）
 * <p>
 * 提供通用重试逻辑，子类只需实现 doExecute() 和 onExhausted() 即可
 *
 * @param <T> 返回类型
 */
@Slf4j
public abstract class RetryStrategy<T> {

    private final ChatModel delegate;
    private final RetryConfig retryConfig;
    private final CompressionConfig compressionConfig;
    private final DynamicContext dynamicContext;
    private final AiErrorCodeExtractor errorCodeExtractor;
    protected final Set<String> retryableErrorCodes;
    protected final Set<String> nonRetryableErrorCodes;

    protected RetryStrategy(ChatModel delegate, RetryConfig retryConfig,
                           CompressionConfig compressionConfig,
                           DynamicContext dynamicContext,
                           AiErrorCodeExtractor errorCodeExtractor) {
        this.delegate = delegate;
        this.retryConfig = retryConfig;
        this.compressionConfig = compressionConfig;
        this.dynamicContext = dynamicContext;
        this.errorCodeExtractor = errorCodeExtractor != null ? errorCodeExtractor : new AiErrorCodeExtractor();
        this.retryableErrorCodes = toSet(retryConfig.getRetryableErrorCodes());
        this.nonRetryableErrorCodes = toSet(retryConfig.getNonRetryableErrorCodes());
    }

    /**
     * 模板方法：执行带重试的调用
     */
    public T execute(Prompt prompt) {
        checkProactiveCompression(prompt);
        if (!retryConfig.isEnabled() || retryConfig.getMaxAttempts() <= 0) {
            return doExecute(prompt);
        }

        int attempt = 0;
        long interval = retryConfig.getInitialIntervalMs();
        RuntimeException lastRuntimeException = null;

        while (attempt < retryConfig.getMaxAttempts()) {
            attempt++;
            try {
                return doExecute(prompt);
            } catch (Exception e) {
                String errorCode = errorCodeExtractor.extract(e);
                if (AiErrorCodes.CONTEXT_OVERFLOW.equals(errorCode)) {
                    checkPassiveCompression(prompt, errorCode);
                }
                HandleResult result = handleException(e, errorCode, attempt, interval);
                if (result.shouldRethrow()) {
                    return onExhausted(toRuntimeException(result.getException()));
                }
                if (result.shouldContinue()) {
                    lastRuntimeException = toRuntimeException(result.getException());
                    interval = Math.min((long) (interval * retryConfig.getMultiplier()), retryConfig.getMaxIntervalMs());
                }
            }
        }
        return onExhausted(lastRuntimeException);
    }

    /**
     * 执行实际调用（子类实现）
     */
    protected abstract T doExecute(Prompt prompt);

    /**
     * 重试耗尽时的处理（子类实现）
     */
    protected abstract T onExhausted(RuntimeException e);

    /**
     * 压缩触发时的回调（可选被子类覆盖）
     */
    protected T onCompressionTriggered() {
        return null;
    }

    // ===== HandleResult 内部类 =====
    private static class HandleResult {
        private final Exception exception;
        private final Action action;

        private enum Action {
            RETRY,
            RETHROW
        }

        private HandleResult(Exception exception, Action action) {
            this.exception = exception;
            this.action = action;
        }

        static HandleResult retry(Exception e) {
            return new HandleResult(e, Action.RETRY);
        }

        static HandleResult rethrow(Exception e) {
            return new HandleResult(e, Action.RETHROW);
        }

        boolean shouldContinue() {
            return action == Action.RETRY;
        }

        boolean shouldRethrow() {
            return action == Action.RETHROW;
        }

        Exception getException() {
            return exception;
        }
    }

    // ===== 异常处理逻辑 =====
    private HandleResult handleException(Exception e, String errorCode, int attempt, long interval) {
        if (nonRetryableErrorCodes.contains(errorCode)) {
            log.warn("[Retry] Blacklist matched, skip retry, errorCode={}, attempt={}, ex={}",
                    errorCode, attempt, e.getMessage());
            return HandleResult.rethrow(e);
        }
        boolean isRetryable = retryableErrorCodes.contains(errorCode) || RetryableExceptionTypes.isRetryable(e);
        if (isRetryable) {
            log.warn("[Retry] attempt {}/{} failed, retry after {}ms, errorCode={}, ex={}",
                    attempt, retryConfig.getMaxAttempts(), interval, errorCode, e.getMessage());
            sleep(interval);
            return HandleResult.retry(e);
        }
        log.warn("[Retry] Non-retryable exception, rethrow directly, errorCode={}, attempt={}, ex={}",
                errorCode, attempt, e.getMessage());
        return HandleResult.rethrow(e);
    }

    private RuntimeException toRuntimeException(Exception e) {
        return e instanceof RuntimeException ? (RuntimeException) e : new RuntimeException(e);
    }

    // ===== 压缩相关逻辑 =====
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

    private void checkPassiveCompression(Prompt prompt, String errorCode) {
        if (compressionConfig == null || !compressionConfig.isEnabled()) {
            return;
        }
        if (dynamicContext != null && !dynamicContext.isCompressionRequired()) {
            log.info("[Compression] Passive compression triggered, errorCode={}", errorCode);
            triggerCompression(prompt);
        }
    }

    private void triggerCompression(Prompt prompt) {
        if (dynamicContext != null && !dynamicContext.isCompressionRequired()) {
            dynamicContext.setOriginalPrompt(prompt);
            dynamicContext.setCompressionRequired(true);
            dynamicContext.setReturnNode(AiErrorCodes.NODE_AI_CLIENT_MODEL);
            log.info("[Compression] Triggering compression, routing to compression node");
            throw new CompressionRequiredException(prompt, AiErrorCodes.NODE_AI_CLIENT_MODEL);
        }
    }

    // ===== 工具方法 =====
    private Set<String> toSet(List<String> list) {
        return list == null ? Set.of() : Set.copyOf(list);
    }

    protected void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
    }
}
