package denny.ai.agent.domain.service.armory.factory.element;

import org.springframework.stereotype.Component;

import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.Map;
import java.util.HashMap;

/**
 * AI 错误码提取器
 * <p>
 * 从异常消息和类名中提取错误码，支持多种格式：
 * <ul>
 *   <li>OpenAI/DeepSeek 格式: {"error":{"code":"rate_limit_exceeded","message":"..."}}（优先匹配）</li>
 *   <li>Zhipu 格式: {"error":{"code":"1002","message":"..."}}</li>
 *   <li>异常类名推断</li>
 *   <li>HTTP 状态码</li>
 *   <li>Fallback 消息截取</li>
 * </ul>
 */
@Component
public class AiErrorCodeExtractor {

    private static final Pattern ZHIPU_PATTERN = Pattern.compile(
            "\"error\"\\s*:\\s*\\{[^}]*?\"code\"\\s*:\\s*\"(\\d+)\"",
            Pattern.CASE_INSENSITIVE
    );
    private static final Pattern OPENAI_PATTERN = Pattern.compile(
            "\"error\"\\s*:\\s*\\{\\s*\"code\"\\s*:\\s*\"([^\"]+)\"",
            Pattern.CASE_INSENSITIVE
    );
    private static final Pattern HTTP_CODE_PATTERN = Pattern.compile(
            "\\b(400|401|402|403|408|409|422|429|500|502|503|504|529)\\b"
    );

    /**
     * DeepSeek/OpenAI body error code to HTTP status code normalization.
     * Maps string body codes to standard HTTP codes so retry config works uniformly
     * across GLM (numeric codes) and DeepSeek (OpenAI-compatible string codes).
     */
    private static final Map<String, String> CODE_NORMALIZATION = new HashMap<>();
    static {
        CODE_NORMALIZATION.put("invalid_request_error", AiErrorCodes.HTTP_400);
        CODE_NORMALIZATION.put("authentication_error", AiErrorCodes.HTTP_401);
        CODE_NORMALIZATION.put("invalid_api_key", AiErrorCodes.HTTP_401);
        CODE_NORMALIZATION.put("insufficient_balance", AiErrorCodes.HTTP_402);
        CODE_NORMALIZATION.put("insufficient_funds", AiErrorCodes.HTTP_402);
        CODE_NORMALIZATION.put("rate_limit_exceeded", AiErrorCodes.HTTP_429);
        CODE_NORMALIZATION.put("rate_limit_error", AiErrorCodes.HTTP_429);
        CODE_NORMALIZATION.put("server_error", AiErrorCodes.HTTP_500);
        CODE_NORMALIZATION.put("internal_error", AiErrorCodes.HTTP_500);
    }

    public String extract(Exception e) {
        if (e == null) {
            return AiErrorCodes.UNKNOWN;
        }

        String fallback = null;
        Throwable current = e;
        int depth = 0;
        while (current != null && depth < 8) {
            String msg = current.getMessage() != null ? current.getMessage() : "";

            // DeepSeek (OpenAI-compatible) checked first -- priority
            String openaiCode = extractOpenAICode(msg);
            if (openaiCode != null) return normalizeCode(openaiCode);

            // Zhipu/GLM numeric codes -- fallback
            String zhipuCode = extractZhipuCode(msg);
            if (zhipuCode != null) return zhipuCode;

            String classNameCode = extractFromClassName(current);
            if (classNameCode != null) return classNameCode;

            String httpCode = extractHttpCode(msg);
            if (httpCode != null) return httpCode;

            if (fallback == null) {
                fallback = extractFallbackCode(msg);
            }
            current = current.getCause();
            depth++;
        }
        return fallback != null ? fallback : AiErrorCodes.UNKNOWN;
    }

    private String extractZhipuCode(String msg) {
        Matcher m = ZHIPU_PATTERN.matcher(msg);
        return m.find() ? m.group(1).toLowerCase() : null;
    }

    private String extractOpenAICode(String msg) {
        Matcher m = OPENAI_PATTERN.matcher(msg);
        return m.find() ? m.group(1).toLowerCase() : null;
    }

    private String extractFromClassName(Throwable e) {
        String cn = e.getClass().getSimpleName();
        if (cn == null || cn.isEmpty()) cn = e.getClass().getName();
        cn = cn.toLowerCase();

        if (containsAny(cn, "ratelimit", "rate_limit")) return AiErrorCodes.HTTP_429;
        if (containsAny(cn, "timeout", "timedout")) return AiErrorCodes.TIMEOUT;
        if (containsAny(cn, "authexception", "authentication", "unauthorized")) return AiErrorCodes.HTTP_401;
        if (containsAny(cn, "forbidden", "accessdenied")) return AiErrorCodes.HTTP_403;
        if (containsAny(cn, "internalservererror")) return AiErrorCodes.HTTP_500;
        if (containsAny(cn, "badgateway")) return AiErrorCodes.HTTP_502;
        if (containsAny(cn, "serviceunavailable")) return AiErrorCodes.HTTP_503;
        if (containsAny(cn, "gatewaytimeout")) return AiErrorCodes.HTTP_504;
        if (containsAny(cn, "overload", "overloaded")) return AiErrorCodes.HTTP_529;
        return null;
    }

    private String extractHttpCode(String msg) {
        Matcher m = HTTP_CODE_PATTERN.matcher(msg);
        return m.find() ? m.group(1) : null;
    }

    private String extractFallbackCode(String msg) {
        String fallback = msg.trim();
        if (fallback.isEmpty()) return AiErrorCodes.UNKNOWN;
        int colonIdx = fallback.indexOf(':');
        fallback = colonIdx >= 0 && colonIdx < fallback.length() - 1
                ? fallback.substring(colonIdx + 1).trim()
                : fallback;
        fallback = maskSensitiveInfo(fallback);
        return fallback.length() > 64 ? fallback.substring(0, 64).toLowerCase() : fallback.toLowerCase();
    }

    /**
     * Normalize DeepSeek/OpenAI string error codes to standard HTTP status codes.
     * Non-matching codes (including Zhipu numeric codes) pass through unchanged.
     */
    private String normalizeCode(String code) {
        return CODE_NORMALIZATION.getOrDefault(code, code);
    }

    private String maskSensitiveInfo(String text) {
        text = text.replaceAll("eyJ[A-Za-z0-9_-]{3}[A-Za-z0-9_-]+", "eyJ***");
        text = text.replaceAll("([sS]ecret\\s*[:=]\\s*)[^\\s,;]+", "$1***");
        text = text.replaceAll("([pP]assword\\s*[:=]\\s*)[^\\s,;]+", "$1***");
        text = text.replaceAll("([tT]oken\\s*[:=]\\s*)[^\\s,;]+", "$1***");
        text = text.replaceAll("([aA]uthorization\\s*[:=]\\s*)[^\\s,;]+", "$1***");
        text = text.replaceAll("(sk-[A-Za-z0-9_-]+)", "sk-***");
        text = text.replaceAll("(sk2-[A-Za-z0-9_-]+)", "sk2-***");
        text = text.replaceAll("(ak-[A-Za-z0-9_-]+)", "ak-***");
        text = text.replaceAll("([?&][^=]+=)[^&]+", "$1***");
        return text;
    }

    private boolean containsAny(String str, String... keywords) {
        for (String keyword : keywords) {
            if (str.contains(keyword)) return true;
        }
        return false;
    }
}