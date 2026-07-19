package denny.ai.agent.domain.service.armory.factory.element;

import java.util.Set;

/**
 * 可重试异常类型集合
 * <p>
 * 判断异常是否应该重试，支持多种异常类型匹配：
 * <ul>
 *   <li>TransientAiException</li>
 *   <li>超时异常 (SocketTimeoutException, TimeoutException)</li>
 *   <li>连接异常 (ResourceAccessException)</li>
 *   <li>异常消息关键词 (econnreset, connection reset 等)</li>
 * </ul>
 */
public final class RetryableExceptionTypes {

    private RetryableExceptionTypes() {
    }

    public static final String TRANSIENT_AI_EXCEPTION = "TransientAiException";

    public static final Set<String> TIMEOUT_PREFIXES = Set.of(
            "SocketTimeoutException",
            "HttpConnectTimeoutException",
            "TimeoutException"
    );
    public static final Set<String> CONNECTION_PREFIXES = Set.of(
            "ResourceAccessException"
    );
    public static final Set<String> CONNECTION_ERROR_KEYWORDS = Set.of(
            "econnreset", "epipec", "connection reset",
            "connection refused", "connection timed out"
    );

    public static boolean isRetryable(Exception e) {
        if (e == null) {
            return false;
        }

        Throwable current = e;
        int depth = 0;
        while (current != null && depth < 8) {
            if (isRetryableSingle(current)) {
                return true;
            }
            current = current.getCause();
            depth++;
        }
        return false;
    }

    private static boolean isRetryableSingle(Throwable e) {
        if (e instanceof ResponseValidationException) {
            return true;
        }
        String className = e.getClass().getName();
        if (className.contains(TRANSIENT_AI_EXCEPTION)) {
            return true;
        }
        if (matchesAnyPrefix(className, TIMEOUT_PREFIXES) || matchesAnyPrefix(className, CONNECTION_PREFIXES)) {
            return true;
        }
        if (e.getMessage() != null && containsAnyKeyword(e.getMessage().toLowerCase(), CONNECTION_ERROR_KEYWORDS)) {
            return true;
        }
        return false;
    }

    private static boolean matchesAnyPrefix(String className, Set<String> prefixes) {
        String simpleName = className.contains(".") ? className.substring(className.lastIndexOf(".") + 1) : className;
        for (String prefix : prefixes) {
            if (simpleName.equals(prefix)) {
                return true;
            }
            if (!"TimeoutException".equals(prefix) && simpleName.endsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    private static boolean containsAnyKeyword(String msg, Set<String> keywords) {
        for (String keyword : keywords) {
            if (msg.contains(keyword)) {
                return true;
            }
        }
        return false;
    }
}
