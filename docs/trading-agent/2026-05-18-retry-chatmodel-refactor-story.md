# Story: RetryChatModel 代码优化

| 字段 | 内容 |
|------|------|
| 创建日期 | 2026-05-18 |
| 状态 | pending |
| 负责人 | - |
| 关联需求 | AI Client 重试机制优化 |
| 优先级 | P1 |

---

## 1. 背景与目标

### 1.1 问题与解决方案

`RetryChatModel` 存在代码质量问题，需要通过重构提升可维护性和可测试性：

| 问题 | 现状 | 解决方案 |
|------|------|---------|
| 代码重复 | call()/stream() 重复约80行重试逻辑 | 抽象类 + 模板方法模式 |
| Magic Numbers | 硬编码11个HTTP状态码 + 特殊错误码 | `AiErrorCodes` 常量类 |
| 错误码提取复杂 | `extractErrorCode()` 47行，5种匹配模式混在一起 | `AiErrorCodeExtractor` 服务类 |
| isRetryable 可读性差 | 异常类型判断使用字符串拼接 | `RetryableExceptionTypes` 类型集 |

### 1.2 重构目标

1. 抽取公共重试逻辑，消除 `call()` 和 `stream()` 的代码重复
2. 抽取 Magic Numbers 为常量类
3. 拆分 `extractErrorCode()` 为多个小型方法
4. 提高代码可维护性、可测试性
5. **保持与现有 extParam JSON 配置完全兼容**

---

## 2. 现有配置说明

### 2.1 extParam JSON 结构

```json
{
  "enabled": true,
  "maxAttempts": 3,
  "initialIntervalMs": 1000,
  "multiplier": 2.0,
  "maxIntervalMs": 10000,
  "retryableErrorCodes": ["1302", "429", "rate_limit"],
  "nonRetryableErrorCodes": []
}
```

### 2.2 RetryConfig 字段说明

| 字段 | 类型 | 说明 |
|------|------|------|
| enabled | boolean | 是否启用重试 |
| maxAttempts | int | 最大重试次数 |
| initialIntervalMs | long | 初始重试间隔（毫秒） |
| multiplier | double | 重试间隔倍数 |
| maxIntervalMs | long | 最大重试间隔（毫秒） |
| retryableErrorCodes | List<String> | 可重试错误码列表 |
| nonRetryableErrorCodes | List<String> | 不可重试错误码黑名单 |

---

## 3. 技术方案

### 3.1 架构设计

```
┌─────────────────────────────────────────────────────────────────┐
│                        RetryChatModel                            │
│  ┌─────────────────────────────────────────────────────────┐   │
│  │  private final RetryStrategy<ChatResponse> callStrategy   │   │
│  │  private final RetryStrategy<Flux<ChatResponse>> stream  │   │
│  └─────────────────────────────────────────────────────────┘   │
│                              │                                   │
│                              ▼                                   │
│  ┌─────────────────────────────────────────────────────────┐   │
│  │              RetryStrategy<T> (抽象类)                    │   │
│  │  ★ 使用 AiErrorCodes 常量                                │   │
│  │  ★ 使用 AiErrorCodeExtractor 提取错误码                  │   │
│  └─────────────────────────────────────────────────────────┘   │
└─────────────────────────────────────────────────────────────────┘
                              │
          ┌───────────────────┼───────────────────┐
          ▼                   ▼                   ▼
┌─────────────────┐ ┌─────────────────────┐ ┌──────────────────────┐
│  AiErrorCodes   │ │AiErrorCodeExtractor │ │ RetryableExceptionTypes│
│   (常量类)       │ │     (错误码提取服务)  │ │     (异常类型集)     │
└─────────────────┘ └─────────────────────┘ └──────────────────────┘
```

---

## 4. 文件变更清单

### 4.1 新增文件

| 文件路径 | 说明 | status |
|----------|------|--------|
| `ai-agent-study-domain/.../AiErrorCodes.java` | 错误码常量类 | pending |
| `ai-agent-study-domain/.../AiErrorCodeExtractor.java` | 错误码提取服务 | pending |
| `ai-agent-study-domain/.../RetryableExceptionTypes.java` | 可重试异常类型集 | pending |
| `ai-agent-study-domain/.../RetryStrategy.java` | 重试策略抽象类 | pending |

### 4.2 修改文件

| 文件路径 | 修改内容 | status |
|----------|----------|--------|
| `ai-agent-study-domain/.../RetryChatModel.java` | 重构为使用上述组件 | pending |

---

## 5. 代码设计

### 5.1 AiErrorCodes.java（新增）

```java
package denny.ai.agent.domain.service.armory.factory.element;

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
```

### 5.2 AiErrorCodeExtractor.java（新增）

```java
package denny.ai.agent.domain.service.armory.factory.element;

import org.springframework.stereotype.Component;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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
            "\\b(400|401|403|408|409|429|500|502|503|504|529)\\b"
    );

    public String extract(Exception e) {
        String msg = e.getMessage() != null ? e.getMessage() : "";

        String zhipuCode = extractZhipuCode(msg);
        if (zhipuCode != null) return zhipuCode;

        String openaiCode = extractOpenAICode(msg);
        if (openaiCode != null) return openaiCode;

        String classNameCode = extractFromClassName(e);
        if (classNameCode != null) return classNameCode;

        String httpCode = extractHttpCode(msg);
        if (httpCode != null) return httpCode;

        String fallback = extractFallbackCode(msg);
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

    private String extractFromClassName(Exception e) {
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
                ? fallback.substring(colonIdx + 1).trim() : fallback;
        fallback = maskSensitiveInfo(fallback);
        return fallback.length() > 64 ? fallback.substring(0, 64).toLowerCase() : fallback.toLowerCase();
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
```

### 5.3 RetryableExceptionTypes.java（新增）

```java
package denny.ai.agent.domain.service.armory.factory.element;

import java.util.Set;

public final class RetryableExceptionTypes {

    private RetryableExceptionTypes() {}

    public static final String TRANSIENT_AI_EXCEPTION = "TransientAiException";

    public static final Set<String> TIMEOUT_PREFIXES = Set.of(
            "java.net.SocketTimeoutException",
            "java.util.concurrent.TimeoutException"
    );
    public static final Set<String> CONNECTION_PREFIXES = Set.of(
            "org.springframework.web.client.ResourceAccessException"
    );
    public static final Set<String> CONNECTION_ERROR_KEYWORDS = Set.of(
            "econnreset", "epipec", "connection reset",
            "connection refused", "connection timed out"
    );

    public boolean isRetryable(Exception e) {
        String className = e.getClass().getName();
        if (className.contains(TRANSIENT_AI_EXCEPTION)) return true;
        if (matchesAnyPrefix(className, TIMEOUT_PREFIXES) || matchesAnyPrefix(className, CONNECTION_PREFIXES)) return true;
        if (e.getMessage() != null && containsAnyKeyword(e.getMessage().toLowerCase(), CONNECTION_ERROR_KEYWORDS)) return true;
        return false;
    }

    private boolean matchesAnyPrefix(String className, Set<String> prefixes) {
        for (String prefix : prefixes) {
            if (className.contains(prefix)) return true;
        }
        return false;
    }

    private boolean containsAnyKeyword(String msg, Set<String> keywords) {
        for (String keyword : keywords) {
            if (msg.contains(keyword)) return true;
        }
        return false;
    }
}
```

### 5.4 RetryStrategy.java（新增）

```java
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

@Slf4j
public abstract class RetryStrategy<T> {

    private static final RetryableExceptionTypes RETRYABLE_EXCEPTION_TYPES = new RetryableExceptionTypes();

    private final ChatModel delegate;
    private final RetryConfig retryConfig;
    private final CompressionConfig compressionConfig;
    private final DynamicContext dynamicContext;
    private final AiErrorCodeExtractor errorCodeExtractor;
    private final Set<String> retryableErrorCodes;
    private final Set<String> nonRetryableErrorCodes;

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

    /** 模板方法：执行带重试的调用 */
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
                if (result.shouldRethrow()) return onExhausted(result.getException());
                if (result.shouldContinue()) {
                    lastRuntimeException = toRuntimeException(result.getException());
                    interval = Math.min((long)(interval * retryConfig.getMultiplier()), retryConfig.getMaxIntervalMs());
                }
            }
        }
        return onExhausted(lastRuntimeException);
    }

    protected abstract T doExecute(Prompt prompt);
    protected abstract T onExhausted(RuntimeException e);
    protected T onCompressionTriggered() { return null; }

    private static class HandleResult {
        private final Exception exception;
        private final Action action;
        private enum Action { RETRY, RETHROW }

        private HandleResult(Exception exception, Action action) {
            this.exception = exception;
            this.action = action;
        }
        static HandleResult retry(Exception e) { return new HandleResult(e, Action.RETRY); }
        static HandleResult rethrow(Exception e) { return new HandleResult(e, Action.RETHROW); }
        boolean shouldContinue() { return action == Action.RETRY; }
        boolean shouldRethrow() { return action == Action.RETHROW; }
        Exception getException() { return exception; }
    }

    private HandleResult handleException(Exception e, String errorCode, int attempt, long interval) {
        if (nonRetryableErrorCodes.contains(errorCode)) {
            log.warn("[Retry] Blacklist matched, skip retry, errorCode={}, attempt={}, ex={}", errorCode, attempt, e.getMessage());
            return HandleResult.rethrow(e);
        }
        boolean isRetryable = retryableErrorCodes.contains(errorCode) || RETRYABLE_EXCEPTION_TYPES.isRetryable(e);
        if (isRetryable) {
            log.warn("[Retry] attempt {}/{} failed, retry after {}ms, errorCode={}, ex={}",
                    attempt, retryConfig.getMaxAttempts(), interval, errorCode, e.getMessage());
            sleep(interval);
            return HandleResult.retry(e);
        }
        log.warn("[Retry] Non-retryable exception, rethrow directly, errorCode={}, attempt={}, ex={}", errorCode, attempt, e.getMessage());
        return HandleResult.rethrow(e);
    }

    private RuntimeException toRuntimeException(Exception e) {
        return e instanceof RuntimeException ? (RuntimeException) e : new RuntimeException(e);
    }

    private void checkProactiveCompression(Prompt prompt) {
        if (compressionConfig == null || !compressionConfig.isEnabled()) return;
        int tokenCount = TokenCountUtils.estimate(prompt.toString());
        if (tokenCount > compressionConfig.getProactiveThresholdTokens()) {
            log.info("[Compression] Proactive compression triggered, tokenCount={}, threshold={}",
                    tokenCount, compressionConfig.getProactiveThresholdTokens());
            triggerCompression(prompt);
        }
    }

    private void checkPassiveCompression(Prompt prompt, String errorCode) {
        if (compressionConfig == null || !compressionConfig.isEnabled()) return;
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

    private Set<String> toSet(List<String> list) { return list == null ? Set.of() : Set.copyOf(list); }

    private void sleep(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
    }
}
```

### 5.5 RetryChatModel.java（修改）

```java
package denny.ai.agent.domain.service.armory.factory.element;

import denny.ai.agent.domain.model.valobj.AiClientModelVO.CompressionConfig;
import denny.ai.agent.domain.model.valobj.AiClientModelVO.RetryConfig;
import denny.ai.agent.domain.service.armory.factory.DynamicContext;
import denny.ai.agent.domain.util.TokenCountUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import reactor.core.publisher.Flux;
import reactor.util.retry.Retry;
import java.time.Duration;

/**
 * 带重试机制的 ChatModel 包装类
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
        this.delegate = delegate;
        this.retryConfig = retryConfig;
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
        if (shouldDegradeToCall(prompt)) {
            log.info("[Stream] Token count exceeds threshold, degrading to call()");
            return Flux.just(call(prompt));
        }
        return new StreamRetryStrategy().execute(prompt);
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

    // ===== StreamRetryStrategy =====
    private class StreamRetryStrategy extends RetryStrategy<Flux<ChatResponse>> {

        StreamRetryStrategy() {
            super(RetryChatModel.this.delegate, RetryChatModel.this.retryConfig,
                    RetryChatModel.this.compressionConfig, RetryChatModel.this.dynamicContext,
                    RetryChatModel.this.errorCodeExtractor);
        }

        @Override
        protected Flux<ChatResponse> doExecute(Prompt prompt) {
            int maxRetries = retryConfig.getMaxAttempts() - 1;
            long initialInterval = retryConfig.getInitialIntervalMs();
            long maxInterval = retryConfig.getMaxIntervalMs();
            double multiplier = retryConfig.getMultiplier();

            return Flux.defer(() -> delegate.stream(prompt))
                    .retryWhen(Retry.backoff(maxRetries, Duration.ofMillis(initialInterval))
                            .maxBackoff(Duration.ofMillis(maxInterval))
                            .multiplier(multiplier)
                            .doBeforeRetry(signal -> log.warn("[StreamRetry] attempt {}/{}, retry after {}ms, error={}",
                                    signal.totalRetries() + 1, retryConfig.getMaxAttempts(),
                                    signal.backoff().toMillis(), signal.failure().getMessage())));
        }

        @Override
        protected Flux<ChatResponse> onExhausted(RuntimeException e) {
            if (e == null) {
                return Flux.error(new IllegalStateException("stream exhausted all retry attempts without exception"));
            }
            return Flux.error(e);
        }
    }
}
```

---

## 6. 兼容性保证

| 检查项 | 说明 | 状态 |
|--------|------|------|
| extParam JSON 结构 | 完全兼容，所有字段解析逻辑不变 | pending |
| RetryConfig 字段 | enabled、maxAttempts、initialIntervalMs、multiplier、maxIntervalMs 全部保留 | pending |
| 错误码匹配 | 使用 AiErrorCodeExtractor，保持原有匹配逻辑 | pending |
| 压缩触发条件 | 1261 错误码使用 AiErrorCodes.CONTEXT_OVERFLOW 常量 | pending |
| 指数退避算法 | interval = min(interval * multiplier, maxIntervalMs) 保持不变 | pending |

---

## 7. 测试计划

### 7.1 现有测试覆盖分析

| 测试类 | 覆盖内容 | 测试用例数 |
|--------|----------|------------|
| `RetryChatModelTest.java` | 黑名单、白名单、默认规则、边界条件 | 16个 |
| `RetryChatModelCompressionTest.java` | 主动/被动压缩触发 | 8个 |
| `AiClientModelNodeRetryTest.java` | 配置启用/禁用、日志、重试次数 | 5个 |

**覆盖缺口识别：**
- AiErrorCodes - 文档要求测试常量值，但无专门测试类
- AiErrorCodeExtractor - 核心逻辑未被独立测试
- RetryableExceptionTypes - 未被独立测试
- stream() 方法 - 仅部分场景有测试，降级逻辑未充分覆盖
- Corner Cases - 错误码提取边界条件、异常嵌套、空值处理等

### 7.2 新增测试类设计

#### 7.2.1 AiErrorCodesTest.java（常量类测试）

##### TC-AEC-01: 常量值正确性验证

| 用例ID | 测试场景 | 输入 | 预期结果 | Corner |
|--------|----------|------|----------|--------|
| TC-AEC-01-01 | 特殊业务错误码 | AiErrorCodes.CONTEXT_OVERFLOW | "1261" | |
| TC-AEC-01-02 | 未知错误码常量 | AiErrorCodes.UNKNOWN | "unknown" | |
| TC-AEC-01-03 | HTTP状态码常量 | AiErrorCodes.HTTP_429 | "429" | |
| TC-AEC-01-04 | rate_limit常量 | AiErrorCodes.RATE_LIMIT | "rate_limit" | |
| TC-AEC-01-05 | timeout常量 | AiErrorCodes.TIMEOUT | "timeout" | |
| TC-AEC-01-06 | HTTP_STATUS_CODES集合 | Set.of(...) | 包含所有11个状态码 | ★ Corner |
| TC-AEC-01-07 | 节点名称常量 | AiErrorCodes.NODE_AI_CLIENT_MODEL | "aiClientModelNode" | |

##### TC-AEC-02: 不可实例化验证

| 用例ID | 测试场景 | 输入 | 预期结果 |
|--------|----------|------|----------|
| TC-AEC-02-01 | 私有构造函数 | new AiErrorCodes() | 编译失败或抛出异常 |

#### 7.2.2 AiErrorCodeExtractorTest.java（错误码提取测试 - 核心）

##### TC-EX-01: Zhipu 格式解析

| 用例ID | 测试场景 | 输入 | 预期结果 | Corner |
|--------|----------|------|----------|--------|
| TC-EX-01-01 | 标准Zhipu格式 | {"error":{"code":"1002","message":"..."}} | "1002" | |
| TC-EX-01-02 | 数字错误码转小写 | {"error":{"code":"500","message":"..."}} | "500" | |
| TC-EX-01-03 | 带空格的JSON | {"error": { "code" : "1302" }} | "1302" | ★ Corner |
| TC-EX-01-04 | 多层嵌套JSON | {"error":{"code":"1211","inner":{"msg":"..."}}} | "1211" | |
| TC-EX-01-05 | 大写CODE键名 | {"ERROR":{"CODE":"1261"}} | "1261" | ★ Corner |
| TC-EX-01-06 | error_code下划线格式 | {"error_code":"1301"} | 不匹配，fallback | ★ Corner |

##### TC-EX-02: OpenAI 格式解析

| 用例ID | 测试场景 | 输入 | 预期结果 | Corner |
|--------|----------|------|----------|--------|
| TC-EX-02-01 | rate_limit格式 | {"error":{"code":"rate_limit_exceeded","message":"..."}} | "rate_limit_exceeded" | |
| TC-EX-02-02 | 模型不存在的错误 | {"error":{"code":"model_not_found"}} | "model_not_found" | |
| TC-EX-02-03 | 内含特殊字符 | {"error":{"code":"invalid_request-error"}} | "invalid_request-error" | |

##### TC-EX-03: 异常类名推断（按优先级递减）

| 用例ID | 测试场景 | 类名 | 预期结果 | Corner |
|--------|----------|------|----------|--------|
| TC-EX-03-01 | RateLimitException | RateLimitException | "429" | |
| TC-EX-03-02 | SocketTimeoutException | java.net.SocketTimeoutException | "timeout" | |
| TC-EX-03-03 | ReadTimeoutException | ReadTimeoutException | "timeout" | |
| TC-EX-03-04 | AuthException | AuthException | "401" | |
| TC-EX-03-05 | AccessDeniedException | AccessDeniedException | "403" | |
| TC-EX-03-06 | InternalServerErrorException | InternalServerErrorException | "500" | |
| TC-EX-03-07 | BadGatewayException | BadGatewayException | "502" | |
| TC-EX-03-08 | ServiceUnavailableException | ServiceUnavailableException | "503" | |
| TC-EX-03-09 | GatewayTimeoutException | GatewayTimeoutException | "504" | |
| TC-EX-03-10 | ServiceOverloadedException | ServiceOverloadedException | "529" | |
| TC-EX-03-11 | 匿名内部类 | class name = "" (empty) | fallback处理 | ★ Corner |

##### TC-EX-04: HTTP 状态码提取

| 用例ID | 测试场景 | 输入 | 预期结果 | Corner |
|--------|----------|------|----------|--------|
| TC-EX-04-01 | HTTP状态码在消息中 | "HTTP 500 Internal Server Error" | "500" | |
| TC-EX-04-02 | 多个状态码取第一个 | "Got 429 then 500" | "429" | ★ Corner |
| TC-EX-04-03 | 端口号不误匹配 | "Connecting to 192.168.1.1:429" | "429" | ★ Corner |
| TC-EX-04-04 | URL中的状态码 | "https://api.example.com/error/500" | "500" | |
| TC-EX-04-05 | 带空格的HTTP | "HTTP  429" | "429" | ★ Corner |

##### TC-EX-05: Fallback 消息截取

| 用例ID | 测试场景 | 输入 | 预期结果 | Corner |
|--------|----------|------|----------|--------|
| TC-EX-05-01 | 带冒号分隔 | "java.lang.Error: Connection refused" | "connection refused" | |
| TC-EX-05-02 | 无冒号 | "simple error message" | "simple error message" | |
| TC-EX-05-03 | 超长消息截断 | 100字符消息 | 截取前64字符并小写 | ★ Corner |
| TC-EX-05-04 | 纯空白消息 | "   " | "unknown" | ★ Corner |
| TC-EX-05-05 | null消息 | null | "unknown" | ★ Corner |

##### TC-EX-06: 敏感信息脱敏

| 用例ID | 测试场景 | 输入 | 预期结果 | Corner |
|--------|----------|------|----------|--------|
| TC-EX-06-01 | JWT Token脱敏 | "token=eyJhbGciOiJIUzI1NiJ9xxx" | "token=eyJ***" | ★ Corner |
| TC-EX-06-02 | Bearer Token脱敏 | "Authorization: Bearer sk-xxx" | "Authorization: Bearer sk-***" | |
| TC-EX-06-03 | API Key脱敏 | "api_key=ak-xxxxx" | "api_key=ak-***" | |
| TC-EX-06-04 | Password脱敏 | "password=secret123" | "password=***" | |
| TC-EX-06-05 | Secret脱敏 | "client_secret=xxx" | "client_secret=***" | |
| TC-EX-06-06 | URL参数脱敏 | "https://api?key=xxx&secret=y" | key=***&secret=*** | |

##### TC-EX-07: 优先级验证（提取顺序）

| 用例ID | 测试场景 | 输入 | 预期结果 |
|--------|----------|------|----------|
| TC-EX-07-01 | Zhipu优先于OpenAI | 符合两种格式的消息 | Zhipu结果 |
| TC-EX-07-02 | 异常类名优先于HTTP码 | 类名含timeout，消息含500 | 类名推断结果 |
| TC-EX-07-03 | 全部不匹配时fallback | 无任何匹配的消息 | fallback结果 |

##### TC-EX-08: 空值与边界条件

| 用例ID | 测试场景 | 输入 | 预期结果 | Corner |
|--------|----------|------|----------|--------|
| TC-EX-08-01 | 空消息字符串 | "" | "unknown" | ★ Corner |
| TC-EX-08-02 | 仅空白字符 | "   \n\t  " | "unknown" | ★ Corner |
| TC-EX-08-03 | 异常类名为null | 匿名内部类 | 使用getClass().getName() | ★ Corner |
| TC-EX-08-04 | 仅冒号结尾 | "error:" | "unknown" | ★ Corner |
| TC-EX-08-05 | 冒号在开头 | ":error message" | ":error message" | ★ Corner |

#### 7.2.3 RetryableExceptionTypesTest.java（异常类型测试）

##### TC-RET-01: TransientAiException 识别

| 用例ID | 测试场景 | 异常类名包含 | 预期结果 |
|--------|----------|-------------|----------|
| TC-RET-01-01 | 标准类名 | "TransientAiException" | true |
| TC-RET-01-02 | 包名+类名 | "denny.ai.agent.TransientAiException" | true |
| TC-RET-01-03 | 子类 | "TransientAiExceptionImpl" | true |

##### TC-RET-02: 超时异常识别

| 用例ID | 测试场景 | 异常类名 | 预期结果 | Corner |
|--------|----------|----------|----------|--------|
| TC-RET-02-01 | SocketTimeoutException | "java.net.SocketTimeoutException" | true | |
| TC-RET-02-02 | TimeoutException | "java.util.concurrent.TimeoutException" | true | |
| TC-RET-02-03 | 自定义超时 | "MyTimeoutException" | false | ★ Corner |
| TC-RET-02-04 | 子类 | "ReadTimedOutException" | false | ★ Corner |

##### TC-RET-03: 连接异常识别

| 用例ID | 测试场景 | 异常类名 | 预期结果 |
|--------|----------|----------|----------|
| TC-RET-03-01 | ResourceAccessException | "org.springframework.web.client.ResourceAccessException" | true |
| TC-RET-03-02 | RestClientException | "RestClientException" | false |

##### TC-RET-04: 异常消息关键词识别

| 用例ID | 测试场景 | 消息内容 | 预期结果 | Corner |
|--------|----------|----------|----------|--------|
| TC-RET-04-01 | econnreset | "Connection reset by peer" | true | |
| TC-RET-04-02 | ECONNRESET大写 | "ECONNRESET" | true | ★ Corner |
| TC-RET-04-03 | epipe | "Broken pipe" | true | |
| TC-RET-04-04 | connection reset | "connection reset" | true | |
| TC-RET-04-05 | connection refused | "Connection refused" | true | |
| TC-RET-04-06 | connection timed out | "Connection timed out" | true | |
| TC-RET-04-07 | 无关消息 | "Invalid parameter" | false | |
| TC-RET-04-08 | 混合关键词 | "Error: econnreset occurred" | true | ★ Corner |
| TC-RET-04-09 | 关键词在URL中 | "https://api.com?err=econnreset" | true | ★ Corner |
| TC-RET-04-10 | null消息 | null | false | ★ Corner |

##### TC-RET-05: 非可重试异常

| 用例ID | 测试场景 | 异常类型 | 预期结果 |
|--------|----------|----------|----------|
| TC-RET-05-01 | IllegalArgumentException | IllegalArgumentException | false |
| TC-RET-05-02 | NullPointerException | NullPointerException | false |
| TC-RET-05-03 | 自定义业务异常 | BusinessException | false |

##### TC-RET-06: 组合场景

| 用例ID | 测试场景 | 异常 | 预期结果 |
|--------|----------|------|----------|
| TC-RET-06-01 | 既匹配类名又匹配消息 | 类含timeout，消息含econnreset | true（首次匹配即返回）|
| TC-RET-06-02 | null类名 | getClass().getName()返回null | 正常处理 | ★ Corner |

#### 7.2.4 RetryStrategyTest.java（重试策略测试）

##### TC-RST-01: 基础重试流程

| 用例ID | 测试场景 | 输入 | 预期结果 | Corner |
|--------|----------|------|----------|--------|
| TC-RST-01-01 | 重试成功-首次 | 无异常 | 1次调用 | |
| TC-RST-01-02 | 重试成功-1次重试后 | 第2次成功 | 2次调用 | |
| TC-RST-01-03 | 重试成功-2次重试后 | 第3次成功 | 3次调用 | |
| TC-RST-01-04 | 重试耗尽 | 全部失败 | 抛出最终异常 | |
| TC-RST-01-05 | 无重试次数 | maxAttempts=0 | 1次调用，无重试 | ★ Corner |

##### TC-RST-02: 错误码匹配

| 用例ID | 测试场景 | 错误码 | retryable列表 | 预期 |
|--------|----------|--------|---------------|------|
| TC-RST-02-01 | 白名单匹配 | "1302" | ["1302"] | 重试 |
| TC-RST-02-02 | 白名单不匹配 | "1302" | ["500", "503"] | 不重试 |
| TC-RST-02-03 | 黑名单优先 | "401" | ["401"] | 不重试 |
| TC-RST-02-04 | 优先级:黑>白 | "401" | ["401"],非["401"] | 不重试 |
| TC-RST-02-05 | 空白名单 | "500" | [] | isRetryable判断 |
| TC-RST-02-06 | null黑名单 | "401" | [], null黑名单 | 不重试 | ★ Corner |

##### TC-RST-03: 重试间隔退避

| 用例ID | 测试场景 | 参数 | 预期间隔序列 | Corner |
|--------|----------|------|-------------|--------|
| TC-RST-03-01 | 指数退避 | 初始1000,倍率2.0 | 1000→2000→4000 | |
| TC-RST-03-02 | 达到最大间隔 | 初始1000,倍率2.0,max=3000 | 1000→2000→3000→3000 | ★ Corner |
| TC-RST-03-03 | 倍率为1.0 | 倍率=1.0 | 固定间隔 | ★ Corner |
| TC-RST-03-04 | 倍率为0 | 倍率=0 | 立即重试 | ★ Corner |
| TC-RST-03-05 | 负倍率 | 倍率=-1 | 异常/固定间隔 | ★ Corner |

##### TC-RST-04: 压缩触发

| 用例ID | 测试场景 | 触发条件 | 预期 |
|--------|----------|----------|------|
| TC-RST-04-01 | 主动压缩-超阈值 | token>threshold | CompressionRequiredException |
| TC-RST-04-02 | 主动压缩-未超阈值 | token<=threshold | 正常调用 |
| TC-RST-04-03 | 被动压缩-1261错误 | errorCode="1261" | CompressionRequiredException |
| TC-RST-04-04 | 压缩禁用 | enabled=false | 不触发 |
| TC-RST-04-05 | compressionRequired已为true | 已设置 | 不重复触发 |

##### TC-RST-05: 异常转换

| 用例ID | 测试场景 | 输入异常 | 预期抛出 | Corner |
|--------|----------|----------|----------|--------|
| TC-RST-05-01 | RuntimeException | RuntimeException | 原异常 | |
| TC-RST-05-02 | Checked Exception | IOException | RuntimeException包装 | |
| TC-RST-05-03 | null异常 | null | IllegalStateException | ★ Corner |

##### TC-RST-06: 中断处理

| 用例ID | 测试场景 | 输入 | 预期 | Corner |
|--------|----------|------|----------|--------|
| TC-RST-06-01 | sleep被中断 | Thread.sleep()中断 | 恢复中断标志 | ★ Corner |

##### TC-RST-07: 并发安全

| 用例ID | 测试场景 | 输入 | 预期 | Corner |
|--------|----------|------|----------|--------|
| TC-RST-07-01 | 多线程同时调用 | 并发数=10 | 各线程独立重试 | ★ Corner |
| TC-RST-07-02 | 共享配置修改 | 一线程改配置 | 不影响其他 | ★ Corner |

#### 7.2.5 RetryChatModelStreamTest.java（stream方法专项测试）

##### TC-STR-01: stream基础流程

| 用例ID | 测试场景 | 输入 | 预期结果 | Corner |
|--------|----------|------|----------|--------|
| TC-STR-01-01 | stream成功 | 无异常 | Flux<ChatResponse> | |
| TC-STR-01-02 | stream降级call | token超阈值 | Flux.just(call()) | ★ Corner |
| TC-STR-01-03 | stream降级call | 压缩启用+超阈值 | 降级到call | |
| TC-STR-01-04 | 降级后正常完成 | 降级+成功 | 正常返回结果 | |

##### TC-STR-02: stream重试行为

| 用例ID | 测试场景 | 异常 | 预期 |
|--------|----------|------|------|
| TC-STR-02-01 | stream可重试异常 | 500错误 | 重试后返回Flux |
| TC-STR-02-02 | stream黑名单 | 401错误 | 立即返回Flux.error |
| TC-STR-02-03 | stream重试耗尽 | 全部失败 | Flux.error(最终异常) |

##### TC-STR-03: stream降级边界

| 用例ID | 测试场景 | 参数 | 预期 | Corner |
|--------|----------|------|----------|--------|
| TC-STR-03-01 | 阈值边界-等于 | token=threshold | 不降级 | ★ Corner |
| TC-STR-03-02 | 阈值边界-略超 | token=threshold+1 | 降级 | ★ Corner |
| TC-STR-03-03 | 压缩未启用 | enabled=false | 不检查阈值 | |
| TC-STR-03-04 | compressionConfig=null | null | 不检查阈值 | ★ Corner |

##### TC-STR-04: stream与call一致性

| 用例ID | 测试场景 | 配置 | 预期 |
|--------|----------|------|------|
| TC-STR-04-01 | 同配置同结果 | 同RetryConfig | call和stream行为一致 |
| TC-STR-04-02 | 同错误同决策 | 同错误码 | 两者处理逻辑相同 |

##### TC-STR-05: Flux消费异常

| 用例ID | 测试场景 | 异常时机 | 预期 | Corner |
|--------|----------|----------|------|--------|
| TC-STR-05-01 | subscribe后异常 | Flux消费中 | 不触发重试 | ★ Corner |
| TC-STR-05-02 | onErrorResume使用 | 异常后 | 正确处理 | ★ Corner |

#### 7.2.6 RetryChatModelCornerTest.java（Corner Cases专项）

##### TC-CRN-01: 空值与Null安全

| 用例ID | 测试场景 | 输入 | 预期 | Corner |
|--------|----------|------|----------|--------|
| TC-CRN-01-01 | retryConfig=null | 构造函数 | 抛出NPE或空指针 | ★ Corner |
| TC-CRN-01-02 | delegate=null | 构造函数 | 抛出NPE | ★ Corner |
| TC-CRN-01-03 | retryableErrorCodes=null | 配置 | 当作空集合 | ★ Corner |
| TC-CRN-01-04 | nonRetryableErrorCodes=null | 配置 | 当作空集合 | ★ Corner |
| TC-CRN-01-05 | initialIntervalMs=null | 配置 | 使用默认值0? | ★ Corner |
| TC-CRN-01-06 | multiplier=null | 配置 | 使用默认值1.0? | ★ Corner |

##### TC-CRN-02: 配置边界值

| 用例ID | 测试场景 | 参数值 | 预期 | Corner |
|--------|----------|--------|------|--------|
| TC-CRN-02-01 | maxAttempts=Integer.MAX | 极大值 | 可能无限循环/溢出 | ★ Corner |
| TC-CRN-02-02 | maxAttempts=-1 | 负数 | 不重试 | ★ Corner |
| TC-CRN-02-03 | initialIntervalMs=0 | 零延迟 | 立即重试 | ★ Corner |
| TC-CRN-02-04 | initialIntervalMs=负数 | 负延迟 | 异常或退化为0 | ★ Corner |
| TC-CRN-02-05 | maxIntervalMs=0 | 零最大间隔 | 间隔恒为0 | ★ Corner |
| TC-CRN-02-06 | maxIntervalMs=Long.MAX | 极大值 | 不限制 | ★ Corner |
| TC-CRN-02-07 | multiplier=0 | 零倍率 | 固定间隔 | ★ Corner |
| TC-CRN-02-08 | multiplier<0 | 负倍率 | 可能异常 | ★ Corner |
| TC-CRN-02-09 | multiplier=Double.MAX | 极大倍率 | 可能溢出 | ★ Corner |

##### TC-CRN-03: 错误码提取Corner

| 用例ID | 测试场景 | 输入 | 预期 | Corner |
|--------|----------|------|----------|--------|
| TC-CRN-03-01 | 嵌套JSON歧义 | {"error":{"code":"500"},"code":"200"} | 匹配内层 | ★ Corner |
| TC-CRN-03-02 | 数组中的code | [{"code":"500"}] | 不匹配 | ★ Corner |
| TC-CRN-03-03 | Unicode转义 | \u0065rror | 大多数正则不匹配 | ★ Corner |
| TC-CRN-03-04 | 超长错误码 | "1"*1000 | 截断处理 | ★ Corner |
| TC-CRN-03-05 | 特殊字符错误码 | "error\"code" | 正确提取 | ★ Corner |

##### TC-CRN-04: 异常处理Corner

| 用例ID | 测试场景 | 异常类型 | 预期 | Corner |
|--------|----------|----------|------|--------|
| TC-CRN-04-01 | 异常链 | cause exception | 提取顶层还是根因 | ★ Corner |
| TC-CRN-04-02 | 异常无消息 | new Exception() | fallback处理 | ★ Corner |
| TC-CRN-04-03 | 异常消息过长 | 10MB消息 | 内存溢出风险 | ★ Corner |
| TC-CRN-04-04 | 循环异常 | 异常A->B->A | 栈溢出风险 | ★ Corner |
| TC-CRN-04-05 | 异步线程异常 | 子线程异常 | 正确传播 | ★ Corner |

##### TC-CRN-05: 性能与资源

| 用例ID | 测试场景 | 参数 | 预期 | Corner |
|--------|----------|------|----------|--------|
| TC-CRN-05-01 | 快速重试风暴 | interval=1ms | CPU占用高 | ★ Corner |
| TC-CRN-05-02 | 嵌套重试调用 | A调用B，B调用A | 栈溢出 | ★ Corner |
| TC-CRN-05-03 | 内存泄漏 | 大量大Prompt | OOM风险 | ★ Corner |
| TC-CRN-05-04 | 正则DoS | 恶意输入 | ReDoS风险 | ★ Corner |

### 7.4 测试文件清单

| 文件路径 | 说明 | 用例数 | status |
|---------|------|--------|--------|
| `ai-agent-study-domain/.../AiErrorCodesTest.java` | 错误码常量测试 | 9 | pending |
| `ai-agent-study-domain/.../AiErrorCodeExtractorTest.java` | 错误码提取测试 | 50+ | pending |
| `ai-agent-study-domain/.../RetryableExceptionTypesTest.java` | 可重试异常测试 | 20+ | pending |
| `ai-agent-study-domain/.../RetryStrategyTest.java` | 重试策略测试 | 30+ | pending |
| `ai-agent-study-domain/.../RetryChatModelStreamTest.java` | stream方法专项测试 | 15+ | pending |
| `ai-agent-study-domain/.../RetryChatModelCornerTest.java` | Corner Cases专项测试 | 25+ | pending |
| `ai-agent-study-domain/.../RetryChatModelTest.java` | 现有测试-集成测试 | 16 | existing |
| `ai-agent-study-domain/.../RetryChatModelCompressionTest.java` | 现有测试-压缩测试 | 8 | existing |
| `ai-agent-study-domain/.../AiClientModelNodeRetryTest.java` | 现有测试-节点测试 | 5 | existing |

**说明：**
- existing：已存在的测试类，无需新增
- pending：需要新增的测试类
- 用例数含 Corner Cases（★标记）

### 7.5 测试场景汇总表

#### 7.5.1 按组件分类

| 组件 | 测试类 | 用例数 | 关键Corner |
|------|--------|--------|------------|
| AiErrorCodes | AiErrorCodesTest | 9 | 集合完整性 |
| AiErrorCodeExtractor | AiErrorCodeExtractorTest | 50+ | 优先级/脱敏/空值 |
| RetryableExceptionTypes | RetryableExceptionTypesTest | 20+ | 关键词匹配/null |
| RetryStrategy | RetryStrategyTest | 30+ | 退避算法/中断 |
| RetryChatModel.call | 现有测试 | 16 | 充分覆盖 |
| RetryChatModel.stream | RetryChatModelStreamTest | 15+ | 降级逻辑 |
| Corner Cases | RetryChatModelCornerTest | 25+ | 全边界 |

#### 7.5.2 按测试类型分类

| 测试类型 | 用例数 | 说明 |
|----------|--------|------|
| 正常路径 | ~20 | Happy Path |
| 异常路径 | ~30 | 各种异常场景 |
| 边界条件 | ~40 | 边界值、Corner Cases |
| 特殊字符 | ~15 | Unicode、脱敏、空格 |
| 并发安全 | ~5 | 多线程场景 |
| 性能压力 | ~5 | 极端输入 |

### 7.6 测试执行计划

#### 7.6.1 执行顺序

| 顺序 | 测试类 | 依赖 |
|------|--------|------|
| 1 | AiErrorCodesTest | 无 |
| 2 | AiErrorCodeExtractorTest | 无 |
| 3 | RetryableExceptionTypesTest | 无 |
| 4 | RetryStrategyTest | 1, 2, 3 |
| 5 | RetryChatModelStreamTest | 1, 2, 3, 4 |
| 6 | RetryChatModelCornerTest | 全部 |

#### 7.6.2 Mock策略

| Mock对象 | 策略 |
|----------|------|
| ChatModel delegate | 始终 Mock，不调用真实 AI 服务 |
| AiErrorCodeExtractor | 在 RetryStrategy 测试中可 Mock 或使用真实实现 |
| DynamicContext | 使用真实对象或 Mock |
| TokenCountUtils | Mock estimate 方法 |

#### 7.6.3 测试隔离

- 每个测试用例独立，不依赖执行顺序
- 测试后验证 mock 调用次数
- 使用 `@Rule` 或 `AfterEach` 清理共享状态

---

## 8. 任务清单

| 序号 | 任务 | 状态 | 依赖 |
|------|------|------|------|
| 1 | 创建 `AiErrorCodes.java` 常量类 | pending | 无 |
| 2 | 创建 `AiErrorCodeExtractor.java` 错误码提取服务 | pending | 1 |
| 3 | 创建 `RetryableExceptionTypes.java` 异常类型集 | pending | 无 |
| 4 | 创建 `RetryStrategy.java` 抽象类 | pending | 1, 2, 3 |
| 5 | 重构 `RetryChatModel.java` 使用策略模式 | pending | 4 |
| 6 | 编写 `AiErrorCodesTest.java` | pending | 1 |
| 7 | 编写 `AiErrorCodeExtractorTest.java` | pending | 2 |
| 8 | 编写 `RetryableExceptionTypesTest.java` | pending | 3 |
| 9 | 编写 `RetryStrategyTest.java` | pending | 4, 5 |
| 10 | 编写 `RetryChatModelStreamTest.java` | pending | 4, 5 |
| 11 | 编写 `RetryChatModelCornerTest.java` | pending | 4, 5 |
| 12 | 编译验证 | pending | 1-11 |
| 13 | 运行所有测试 | pending | 12 |

---

## 9. 预期效果

| 指标 | 优化前 | 优化后 |
|------|--------|--------|
| 重复代码 | ~80行 | 0行 |
| call()/stream() | ~115行 | ~23行 |
| 错误码提取 | 47行方法 | 独立可测试的服务 |
| Magic Numbers | 散落各处 | 集中在常量类 |
| 新增文件 | 0 | 4个组件类 |

---

## 10. 风险与注意事项

1. **向后兼容性**：extParam JSON 结构必须保持不变
2. **压缩触发**：压缩逻辑依赖 `dynamicContext`，需确保非空时行为正确
3. **流式降级**：stream 模式下的压缩降级逻辑需保持原有行为
4. **异常处理**：确保所有异常都能正确传播，不丢失堆栈信息
5. **测试覆盖**：所有新增组件必须编写单元测试
6. **Stream 重试范围**：重试仅在 `delegate.stream(prompt)` 调用时生效，Flux 消费中的异常（如 429/500 等）不触发重试，直接传播
