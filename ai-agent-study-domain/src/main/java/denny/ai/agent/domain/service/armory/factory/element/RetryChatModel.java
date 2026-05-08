package denny.ai.agent.domain.service.armory.factory.element;

import denny.ai.agent.domain.model.valobj.AiClientModelVO.RetryConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

public class RetryChatModel implements ChatModel {

    private static final Logger log = LoggerFactory.getLogger(RetryChatModel.class);

    private final ChatModel delegate;
    private final RetryConfig retryConfig;
    private final Set<String> retryableErrorCodes;
    private final Set<String> nonRetryableErrorCodes;

    public RetryChatModel(ChatModel delegate, RetryConfig retryConfig) {
        this.delegate = delegate;
        this.retryConfig = retryConfig;
        this.retryableErrorCodes = toSet(retryConfig.getRetryableErrorCodes());
        this.nonRetryableErrorCodes = toSet(retryConfig.getNonRetryableErrorCodes());
    }

    @Override
    public ChatResponse call(Prompt prompt) {
        if (retryConfig.getMaxAttempts() <= 0) {
            return delegate.call(prompt);
        }
        int attempt = 0;
        long interval = retryConfig.getInitialIntervalMs();
        Exception lastException = null;

        while (attempt < retryConfig.getMaxAttempts()) {
            attempt++;
            try {
                return delegate.call(prompt);
            } catch (Exception e) {
                lastException = e;
                String errorCode = extractErrorCode(e);
                if (nonRetryableErrorCodes.contains(errorCode)) {
                    log.warn("[Retry] Blacklist matched, skip retry, errorCode={}, attempt={}, ex={}",
                            errorCode, attempt, e.getMessage());
                    throw e instanceof RuntimeException ? (RuntimeException) e : new RuntimeException(e);
                }
                if (retryableErrorCodes.contains(errorCode) || isRetryable(e)) {
                    if (attempt >= retryConfig.getMaxAttempts()) {
                        log.error("[Retry] Max attempts {} reached, giving up, attempt={}, errorCode={}, ex={}",
                                retryConfig.getMaxAttempts(), attempt, errorCode, e.getMessage());
                        throw e instanceof RuntimeException ? (RuntimeException) e : new RuntimeException(e);
                    }
                    log.warn("[Retry] attempt {}/{} failed, retry after {}ms, errorCode={}, ex={}",
                            attempt, retryConfig.getMaxAttempts(), interval, errorCode, e.getMessage());
                    sleep(interval);
                    interval = Math.min((long) (interval * retryConfig.getMultiplier()), retryConfig.getMaxIntervalMs());
                } else {
                    log.warn("[Retry] Non-retryable exception, rethrow directly, errorCode={}, attempt={}, ex={}",
                            errorCode, attempt, e.getMessage());
                    throw e instanceof RuntimeException ? (RuntimeException) e : new RuntimeException(e);
                }
            }
        }
        throw lastException != null ? (lastException instanceof RuntimeException ? (RuntimeException) lastException : new RuntimeException(lastException))
                : new IllegalStateException("exhausted all attempts");
    }

    @Override
    public Flux<ChatResponse> stream(Prompt prompt) {
        int attempt = 0;
        long interval = retryConfig.getInitialIntervalMs();
        Exception lastException = null;

        while (attempt < retryConfig.getMaxAttempts()) {
            attempt++;
            try {
                return delegate.stream(prompt);
            } catch (Exception e) {
                lastException = e;
                String errorCode = extractErrorCode(e);
                boolean shouldRetry = retryableErrorCodes.contains(errorCode) || isRetryable(e);
                if (!shouldRetry || attempt >= retryConfig.getMaxAttempts()) {
                    if (attempt >= retryConfig.getMaxAttempts()) {
                        log.error("[RetryStream] Max attempts {} reached, giving up, attempt={}, errorCode={}, ex={}",
                                retryConfig.getMaxAttempts(), attempt, errorCode, e.getMessage());
                    } else {
                        log.warn("[RetryStream] Non-retryable exception, rethrow directly, errorCode={}, attempt={}, ex={}",
                                errorCode, attempt, e.getMessage());
                    }
                    return Flux.error(e instanceof RuntimeException ? e : new RuntimeException(e));
                }
                log.warn("[RetryStream] attempt {}/{} failed, retry after {}ms, errorCode={}, ex={}",
                        attempt, retryConfig.getMaxAttempts(), interval, errorCode, e.getMessage());
                sleep(interval);
                interval = Math.min((long) (interval * retryConfig.getMultiplier()), retryConfig.getMaxIntervalMs());
            }
        }
        return Flux.error(lastException != null ? (lastException instanceof RuntimeException ? lastException : new RuntimeException(lastException))
                : new IllegalStateException("stream exhausted all attempts"));
    }

    private String extractErrorCode(Exception e) {
        String msg = e.getMessage() != null ? e.getMessage() : "";

        // 1. Zhipu format: {"error":{"code":"1002","message":"..."}}
        Pattern zhipuPattern = Pattern.compile("\"error\"\\s*:\\s*\\{\\s*\"code\"\\s*:\\s*\"(\\d+)\"", Pattern.CASE_INSENSITIVE);
        var m = zhipuPattern.matcher(msg);
        if (m.find()) {
            return m.group(1).toLowerCase();
        }

        // 2. OpenAI format: {"error":{"code":"rate_limit_exceeded","message":"..."}}
        Pattern openaiPattern = Pattern.compile("\"error\"\\s*:\\s*\\{\\s*\"code\"\\s*:\\s*\"([^\"]+)\"", Pattern.CASE_INSENSITIVE);
        m = openaiPattern.matcher(msg);
        if (m.find()) {
            return m.group(1).toLowerCase();
        }

        // 3. Infer from exception class name
        String cn = e.getClass().getSimpleName().toLowerCase();
        if (cn.contains("ratelimit") || cn.contains("rate_limit")) return "429";
        if (cn.contains("timeout") || cn.contains("timedout")) return "timeout";
        if (cn.contains("authexception") || cn.contains("authentication") || cn.contains("unauthorized")) return "401";
        if (cn.contains("forbidden") || cn.contains("accessdenied")) return "403";
        if (cn.contains("internalservererror")) return "500";
        if (cn.contains("badgateway")) return "502";
        if (cn.contains("serviceunavailable")) return "503";
        if (cn.contains("gatewaytimeout")) return "504";
        if (cn.contains("overload") || cn.contains("overloaded")) return "529";
        if (cn.contains("context") && cn.contains("overflow")) return "context_overflow";

        // 4. HTTP status code digits (with word boundaries to avoid matching port numbers)
        Pattern httpCode = Pattern.compile("\\b(400|401|403|408|409|429|500|502|503|504|529)\\b");
        m = httpCode.matcher(msg);
        if (m.find()) {
            return m.group(1);
        }

        // 5. Fallback: take meaningful message fragment, lowercased
        String fallback = msg.trim();
        if (fallback.isEmpty()) {
            return "unknown";
        }
        int colonIdx = fallback.indexOf(':');
        fallback = colonIdx >= 0 && colonIdx < fallback.length() - 1
                ? fallback.substring(colonIdx + 1).trim()
                : fallback;
        return fallback.length() > 64 ? fallback.substring(0, 64).toLowerCase() : fallback.toLowerCase();
    }

    private boolean isRetryable(Exception e) {
        String className = e.getClass().getName();

        if (className.contains("TransientAiException")) {
            return true;
        }
        if (className.contains("TimeoutException")
                || className.contains("SocketTimeoutException")
                || className.contains("ResourceAccessException")) {
            return true;
        }
        if (e.getMessage() != null) {
            String msg = e.getMessage().toLowerCase();
            if (msg.contains("econnreset") || msg.contains("epipec")
                    || msg.contains("connection reset") || msg.contains("connection refused")
                    || msg.contains("connection timed out")) {
                return true;
            }
        }
        return false;
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
}
