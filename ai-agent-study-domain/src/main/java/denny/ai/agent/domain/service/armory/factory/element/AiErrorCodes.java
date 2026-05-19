package denny.ai.agent.domain.service.armory.factory.element;

import java.util.Set;

/**
 * AI 错误码常量类
 * <p>
 * 集中管理所有 HTTP 状态码和特殊错误码，消除 Magic Numbers
 */
public final class AiErrorCodes {

    private AiErrorCodes() {
    }

    // ===== 特殊业务错误码 =====
    /** 上下文超限错误码（通义千问/智谱等通用） */
    public static final String CONTEXT_OVERFLOW = "1261";
    /** 未知错误码 */
    public static final String UNKNOWN = "unknown";

    // ===== HTTP 状态码 =====
    public static final String HTTP_400 = "400";
    public static final String HTTP_401 = "401";
    public static final String HTTP_403 = "403";
    public static final String HTTP_408 = "408";
    public static final String HTTP_409 = "409";
    public static final String HTTP_429 = "429";
    public static final String HTTP_500 = "500";
    public static final String HTTP_502 = "502";
    public static final String HTTP_503 = "503";
    public static final String HTTP_504 = "504";
    public static final String HTTP_529 = "529";

    // ===== 特殊错误码 =====
    /** 阿里云 DashScope 限流错误码 */
    public static final String RATE_LIMIT = "rate_limit";
    public static final String TIMEOUT = "timeout";

    // ===== 错误码集合 =====
    public static final Set<String> HTTP_STATUS_CODES = Set.of(
            HTTP_400, HTTP_401, HTTP_403, HTTP_408, HTTP_409,
            HTTP_429, HTTP_500, HTTP_502, HTTP_503, HTTP_504, HTTP_529
    );

    // ===== 节点名称 =====
    public static final String NODE_AI_CLIENT_MODEL = "aiClientModelNode";
}
