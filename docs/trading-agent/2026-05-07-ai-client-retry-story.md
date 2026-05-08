# Story: AI Client LLM 调用重试能力

## 1. 背景与目标

### 背景

当前 `AiClientModelNode` 在构建 `OpenAiChatModel` 时，直接使用原始模型实例，没有对 LLM 调用过程中可能出现的瞬时错误（如网络超时、Rate Limit 429、服务器 5xx 错误等）做重试处理。这导致线上偶发的网络抖动或 API 限流会直接透传到业务侧，降低系统的容错能力。

而 `ObservabilityAdvisor` 已在 Advisor 层承担了可观测性职责（trace/span 记录），重试作为模型层的弹性能力，应该与 Advisor 解耦，放在更底层的 `ChatModel` 层实现。

### 目标

在 `AiClientModelNode` 层对 `OpenAiChatModel` 应用**装饰器模式**，为指定模型自动包裹一层带指数退避重试能力的 `RetryChatModel`，通过模型配置的 `extParam` 字段灵活控制重试行为。

---

## 2. 技术方案

### 2.1 架构设计

```
AiClientModelNode.doApply()
  └─ OpenAiChatModel.builder()...build()    // 原始模型
      └─ RetryChatModel(chatModel, retryConfig)  // 装饰包装
          └─ registerBean(AI_CLIENT_MODEL, RetryChatModel)
              └─ AiClientNode.doApply()
                  └─ ChatClient.builder(retryChatModel)  // 对上层透明
```

装饰器实现 `ChatModel` + `StreamingChatModel` 双接口，对调用方完全透明。`ObservabilityAdvisor` 运行在装饰器上层，span 语义不受影响。

### 2.2 可重试错误类型

#### 匹配逻辑（两层）

```
Exception 抛出
  └─ 提取 errorCode（如 "rate_limit_exceeded"）
  └─ 黑名单 List.contains(errorCode)？ → 直接抛出
  └─ 白名单 List.contains(errorCode)？ → 重试
  └─ 默认规则 isRetryable(e)？         → 重试
  └─ 其余所有情况                      → 直接抛出
```

#### 黑名单（nonRetryableErrorCodes）

命中后**直接抛出不重试**，优先级最高：

| errorCode | 含义 | 原因 |
|-----------|------|------|
| `401` | HTTP 认证失败（API Key 错误/过期） | 凭证问题，重试无意义 |
| `403` | HTTP 权限不足 | 权限问题，重试无意义 |
| `1000` | 身份验证失败 | 鉴权失败，重试无意义 |
| `1001` | Header 中未收到 Authentication 参数 | 参数缺失 |
| `1002` | Authentication Token 非法 | token 错误 |
| `1003` | Authentication Token 已过期 | token 过期，需重新获取 |
| `1004` | Authentication Token 验证失败 | token 验证失败 |
| `1111` | 账户不存在 | 账户问题不会自恢复 |
| `1112` | 账户已被锁定 | 账户问题不会自恢复 |
| `1210` | API 调用参数有误 | 业务参数问题 |
| `1211` | 模型不存在 | 配置错误 |
| `1212` | 当前模型不支持该调用方式 | 配置错误 |
| `1213` | 未正常接收到参数 | 参数缺失 |
| `1214` | 参数非法 | 业务参数问题 |
| `1215` | 两参数不能同时设置 | 业务参数问题 |
| `1220` | 无权访问 API | 权限问题 |
| `1221` | API 已下线 | 配置错误 |
| `1222` | API 不存在 | 配置错误 |
| `1301` | 内容包含敏感信息 | 模型认为不安全，重试无效 |
| `1311` | 订阅套餐未开放模型权限 | 配置问题 |

#### 白名单（retryableErrorCodes）

命中后**强制重试**，优先级高于默认规则：

| errorCode | 含义 | 原因 |
|-----------|------|------|
| `500` | 内部错误 | 偶发服务器问题，可能自动恢复 |
| `1120` | 无法成功访问账户，请稍后重试 | 明确提示可重试 |
| `1230` | API 调用流程出错 | 偶发流程问题 |
| `1231` | 已有请求（request_id 冲突） | 偶发冲突 |
| `1234` | 网络错误 | 网络抖动，可能恢复 |
| `1261` | Prompt 超长 | 可缩短后重试 |
| `1302` | API 并发数过高 | 降低并发后可恢复 |
| `1303` | API 频率过高 | 降低频率后可恢复 |
| `1304` | API 达日限额 | 等限额重置 |
| `1305` | 触发流量限制 | 等冷却后可恢复 |
| `1308` | 达到使用上限 | 等限额重置 |
| `1309` | GLM Coding Plan 套餐已到期 | 充值后可恢复 |
| `1310` | 达到每周/每月使用上限 | 等限额重置 |
| `1312` | 模型当前访问量过大 | 等负载降低 |
| `1313` | 公平使用策略限制 | 调整使用模式后可恢复 |

#### 默认规则（isRetryable 方法）

不配置白名单/黑名单时，按这个方法兜底。**HTTP 状态码（400/401/403/408/409/500/502/503/504/529）统一由 `extractErrorCode` + 黑/白名单处理，此处不再兜底**：

| 匹配维度 | 规则 | 是否重试 |
|---------|------|---------|
| 类名 | 含 `TransientAiException` | ✅ 重试 |
| 类名 | 含 `TimeoutException` / `SocketTimeoutException` / `ResourceAccessException` | ✅ 重试 |
| 消息 | 含 `ECONNRESET\|EPIPE\|Connection reset\|Connection refused\|Connection timed out` | ✅ 重试 |
| 其他 | 所有未匹配项 | ❌ 不重试 |

> **注意**：
> - `401`/`403` 已移至黑名单，通过 `extractErrorCode` 提取后由黑名单拦截，不再依赖 `isRetryable` 兜底
> - `429` 未加入黑名单，智谱 429 对应多种场景（1302/1303 可重试 vs 余额用完/账户异常 不可重试），统一拦截会误杀合理的限流重试，由内层业务码决定
> - `isRetryable` 仅处理**无法提取 errorCode** 的底层网络异常（Spring AI 瞬时异常、TCP 连接异常等）

### 2.3 配置存储

`ai_client_model` 表新增 `ext_param` 字段（JSON），结构参考 `AiClientModelVO.RetryConfig` 内联类，解析逻辑参照 `AgentRepository` 中已有的 `extParam` 解析模式。

---

## 3. 变更计划

### 3.1 新建文件
status: pending

#### 文件 1：`docs/dev-ops/mysql/sql/ai-agent-station-study.sql`

新增 DDL：

```sql
ALTER TABLE `ai_client_model`
ADD COLUMN `ext_param` text COMMENT '扩展参数，JSON格式，存储retry等配置' AFTER `model_type`;
```

#### 文件 2：`ai-agent-study-infrastructure/src/main/java/denny/ai/agent/infrastructure/dao/po/AiClientModelPO.java`

新增字段：

```java
/**
 * 扩展参数，JSON格式
 */
private String extParam;
```

#### 文件 3：`ai-agent-study-domain/src/main/java/denny/ai/agent/domain/model/valobj/AiClientModelVO.java`

在类末尾追加内联配置类：

```java
/**
 * 重试配置
 */
private RetryConfig retryConfig;

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public static class RetryConfig {

    /**
     * 是否启用
     */
    private boolean enabled;

    /**
     * 最大重试次数
     */
    @Builder.Default
    private int maxAttempts = 3;

    /**
     * 初始重试间隔（毫秒）
     */
    @Builder.Default
    private long initialIntervalMs = 1000;

    /**
     * 重试间隔倍数
     */
    @Builder.Default
    private double multiplier = 2.0;

    /**
     * 最大重试间隔（毫秒）
     */
    @Builder.Default
    private long maxIntervalMs = 10000;

    /**
     * 可重试错误 code 列表，命中则必定重试
     */
    private List<String> retryableErrorCodes;

    /**
     * 不可重试错误 code 黑名单，命中则直接抛出不重试
     */
    private List<String> nonRetryableErrorCodes;
}
```

#### 文件 4：`ai-agent-study-domain/src/main/java/denny/ai/agent/domain/service/armory/factory/element/RetryChatModel.java`

核心装饰器类，实现 `ChatModel` + `StreamingChatModel`：

```java
package denny.ai.agent.domain.service.armory.factory.element;

import denny.ai.agent.domain.model.valobj.AiClientModelVO.RetryConfig;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.MessageAggregator;
import org.springframework.ai.chat.prompt.Prompt;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

@Slf4j
public class RetryChatModel implements ChatModel, StreamingChatModel {

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
        int attempt = 0;
        long interval = retryConfig.getInitialIntervalMs();
        Exception lastException = null;

        while (attempt < retryConfig.getMaxAttempts()) {
            attempt++;
            try {
                return delegate.call(prompt);
            } catch (Exception e) {
                String errorCode = extractErrorCode(e);
                if (nonRetryableErrorCodes.contains(errorCode)) {
                    log.warn("【重试】黑名单匹配不重试，errorCode={}, attempt={}, ex={}",
                            errorCode, attempt, e.getMessage());
                    throw e;
                }
                if (retryableErrorCodes.contains(errorCode) || isRetryable(e)) {
                    if (attempt >= retryConfig.getMaxAttempts()) {
                        log.error("【重试】达到最大重试次数 {}，放弃，attempt={}, errorCode={}, ex={}",
                                retryConfig.getMaxAttempts(), attempt, errorCode, e.getMessage());
                        throw e;
                    }
                    log.warn("【重试】attempt {}/{} 失败，{}ms 后重试，errorCode={}, ex={}",
                            attempt, retryConfig.getMaxAttempts(), interval, errorCode, e.getMessage());
                    sleep(interval);
                    interval = Math.min((long) (interval * retryConfig.getMultiplier()), retryConfig.getMaxIntervalMs());
                } else {
                    log.warn("【重试】非可重试异常直接抛出，errorCode={}, attempt={}, ex={}",
                            errorCode, attempt, e.getMessage());
                    throw e;
                }
            }
        }
        throw lastException != null ? lastException : new IllegalStateException("exhausted all attempts");
    }

    @Override
    public Flux<ChatResponse> stream(Prompt prompt) {
        int attempt = 0;
        long interval = retryConfig.getInitialIntervalMs();
        while (attempt < retryConfig.getMaxAttempts()) {
            attempt++;
            try {
                return aggregateStream(delegate.stream(prompt));
            } catch (Exception e) {
                String errorCode = extractErrorCode(e);
                boolean shouldRetry = retryableErrorCodes.contains(errorCode) || isRetryable(e);
                if (!shouldRetry || attempt >= retryConfig.getMaxAttempts()) {
                    if (attempt >= retryConfig.getMaxAttempts()) {
                        log.error("【重试流式】达到最大重试次数 {}，放弃，attempt={}, errorCode={}, ex={}",
                                retryConfig.getMaxAttempts(), attempt, errorCode, e.getMessage());
                    } else {
                        log.warn("【重试流式】非可重试异常直接抛出，errorCode={}, attempt={}, ex={}",
                                errorCode, attempt, e.getMessage());
                    }
                    return Flux.error(e);
                }
                log.warn("【重试流式】attempt {}/{} 失败，{}ms 后重试，errorCode={}, ex={}",
                        attempt, retryConfig.getMaxAttempts(), interval, errorCode, e.getMessage());
                sleep(interval);
                interval = Math.min((long) (interval * retryConfig.getMultiplier()), retryConfig.getMaxIntervalMs());
            }
        }
        return Flux.error(new IllegalStateException("stream exhausted all attempts"));
    }

    /**
     * 从异常中提取 errorCode（优先级：智谱 JSON 格式 > OpenAI 格式 > 类名推断 > HTTP 状态码）
     * 所有返回值统一小写化后返回，保证黑白名单 contains 比较一致性
     * 智谱格式：{"error":{"code":"1002","message":"..."}}
     * OpenAI 格式：{"error":{"code":"invalid_api_key","message":"..."}}
     */
    private String extractErrorCode(Exception e) {
        String msg = e.getMessage() != null ? e.getMessage() : "";

        // 1. 智谱格式：{"error":{"code":"1002","message":"..."}}
        Pattern zhipuPattern = Pattern.compile("\"error\"\\s*:\\s*\\{\\s*\"code\"\\s*:\\s*\"(\\d+)\"", Pattern.CASE_INSENSITIVE);
        var m = zhipuPattern.matcher(msg);
        if (m.find()) {
            return m.group(1).toLowerCase();
        }

        // 2. OpenAI 格式：{"error":{"code":"rate_limit_exceeded","message":"..."}}
        Pattern openaiPattern = Pattern.compile("\"error\"\\s*:\\s*\\{\\s*\"code\"\\s*:\\s*\"([^\"]+)\"", Pattern.CASE_INSENSITIVE);
        m = openaiPattern.matcher(msg);
        if (m.find()) {
            return m.group(1).toLowerCase();
        }

        // 3. 从异常类名推断（兜底）
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

        // 4. HTTP 状态码数字（前后有边界，防止匹配到端口号等）
        Pattern httpCode = Pattern.compile("\\b(400|401|403|408|409|429|500|502|503|504|529)\\b");
        m = httpCode.matcher(msg);
        if (m.find()) {
            return m.group(1);
        }

        // 5. 纯兜底：截取有意义的消息片段，小写化后返回
        String fallback = msg.trim();
        if (fallback.isEmpty()) {
            return "unknown";
        }
        // 截取冒号后的内容（通常是详细描述），避免描述性文字干扰匹配
        int colonIdx = fallback.indexOf(':');
        fallback = colonIdx >= 0 && colonIdx < fallback.length() - 1
                ? fallback.substring(colonIdx + 1).trim()
                : fallback;
        return fallback.length() > 64 ? fallback.substring(0, 64).toLowerCase() : fallback.toLowerCase();
    }

    /**
     * 默认重试规则兜底，仅处理无法提取 errorCode 的异常场景
     * HTTP 状态码（401/403/429/500/502/503/504/529）统一由 extractErrorCode + 黑/白名单处理
     */
    private boolean isRetryable(Exception e) {
        String className = e.getClass().getName();

        // Spring AI 官方瞬时异常
        if (className.contains("TransientAiException")) {
            return true;
        }
        // 网络超时
        if (className.contains("TimeoutException")
                || className.contains("SocketTimeoutException")
                || className.contains("ResourceAccessException")) {
            return true;
        }
        // 网络连接异常关键词
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

    private Flux<ChatResponse> aggregateStream(Flux<ChatResponse> flux) {
        return new MessageAggregator().aggregate(flux, new ChatResponse());
    }

    @Override
    public ChatModel getDelegate() {
        return delegate;
    }
}
```

### 3.2 修改文件
status: pending

#### 文件 5：`ai-agent-study-infrastructure/src/main/java/denny/ai/agent/infrastructure/adapter/repository/AgentRepository.java`

**改动位置 1**：约第 139-146 行，`AiClientModelVO.builder()` 之前新增解析逻辑。

```java
// 4. 查询该模型关联的tool_mcp配置
List<AiClientConfigPO> toolMcpConfigs = aiClientConfigDao.queryBySourceTypeAndId(
        AiAgentEnumVO.AI_CLIENT_MODEL.getCode(), modelId);
List<String> toolMcpIds = new ArrayList<>();

for (AiClientConfigPO toolMcpConfig : toolMcpConfigs) {
    if (AiAgentEnumVO.AI_CLIENT_TOOL_MCP.getCode().equals(toolMcpConfig.getTargetType())
            && toolMcpConfig.getStatus() == 1) {
        toolMcpIds.add(toolMcpConfig.getTargetId());
    }
}

// 5. 解析 extParam 中的重试配置
AiClientModelVO.RetryConfig retryConfig = parseRetryConfig(model.getExtParam());

AiClientModelVO modelVO = AiClientModelVO.builder()
        .modelId(model.getModelId())
        .apiId(model.getApiId())
        .modelName(model.getModelName())
        .modelType(model.getModelType())
        .toolMcpIds(toolMcpIds)
        .retryConfig(retryConfig)
        .build();
```

**改动位置 2**：类中新增解析方法（放在类末尾 `private` 方法区）：

```java
private AiClientModelVO.RetryConfig parseRetryConfig(String extParam) {
    if (extParam == null || extParam.trim().isEmpty()) {
        return null;
    }
    try {
        return JSON.parseObject(extParam, AiClientModelVO.RetryConfig.class);
    } catch (Exception e) {
        log.warn("解析模型 retry 配置失败，extParam={}, ex={}", extParam, e.getMessage());
        return null;
    }
}
```

#### 文件 6：`ai-agent-study-domain/src/main/java/denny/ai/agent/domain/service/armory/AiClientModelNode.java`

**改动位置 1**：新增 import

```java
import denny.ai.agent.domain.model.valobj.AiClientModelVO.RetryConfig;
```

**改动位置 2**：约第 61-63 行，`registerBean` 之前替换为：

```java
// 应用重试装饰器
ChatModel registeredModel = applyRetryDecorator(chatModel, modelVO.getRetryConfig());
registerBean(getBeanName(modelVO.getModelId()), ChatModel.class, registeredModel);
```

**改动位置 3**：类末尾新增方法

```java
private ChatModel applyRetryDecorator(OpenAiChatModel chatModel, RetryConfig retryConfig) {
    if (retryConfig == null || !retryConfig.isEnabled()) {
        return chatModel;
    }
    log.info("应用重试装饰器，model={}, maxAttempts={}, interval={}ms, multiplier={}",
            chatModel.getDefaultOptions().getModel(),
            retryConfig.getMaxAttempts(),
            retryConfig.getInitialIntervalMs(),
            retryConfig.getMultiplier());
    return new RetryChatModel(chatModel, retryConfig);
}
```

#### 文件 7：`ai-agent-study-domain/src/main/java/denny/ai/agent/domain/service/armory/AiClientNode.java`

**改动位置 1**：新增 import

```java
import org.springframework.ai.chat.model.ChatModel;
```

**改动位置 2**：约第 54 行，类型声明调整

```java
// OpenAiChatModel chatModel = getBean(aiClientVO.getModelBeanName());
ChatModel chatModel = getBean(aiClientVO.getModelBeanName());
```

---

## 4. 任务列表

| 序号 | 任务 | 状态 |
|------|------|------|
| 1 | `ai_client_model` 表新增 `ext_param` 字段 DDL | pending |
| 2 | `AiClientModelPO.java` 新增 `extParam` 字段 | pending |
| 3 | `AiClientModelVO.java` 新增 `RetryConfig` 内联类 | pending |
| 4 | 新建 `RetryChatModel.java` 装饰器类 | pending |
| 5 | `AgentRepository.java` 解析 `extParam` → `RetryConfig` | pending |
| 6 | `AiClientModelNode.java` 应用装饰器 | pending |
| 7 | `AiClientNode.java` 类型声明 `OpenAiChatModel` → `ChatModel` | pending |
| 8 | 编译验证（`mvn compile`） | pending |

执行记录：

| 序号 | 任务 | 状态 | 执行时间 | 备注 |
|------|------|------|---------|------|
| - | - | - | - | - |

---

## 5. 配置示例

在 `ai_client_model` 表的 `ext_param` 字段写入 JSON：

```json
{
  "enabled": true,
  "maxAttempts": 3,
  "initialIntervalMs": 1000,
  "multiplier": 2.0,
  "maxIntervalMs": 10000,
  "retryableErrorCodes": ["500", "1120", "1230", "1231", "1234", "1261", "1302", "1303", "1304", "1305", "1308", "1309", "1310", "1312", "1313"],
  "nonRetryableErrorCodes": ["400", "401", "403", "408", "409", "1000", "1001", "1002", "1003", "1004", "1111", "1112", "1210", "1211", "1212", "1213", "1214", "1215", "1220", "1221", "1222", "1301", "1311"]
}
```

> **说明**：`429`（HTTP 限流）未加入黑名单，因为智谱 429 对应多种场景（并发超额/频率过高 → 可重试；余额用完/账户异常 → 不可重试），统一拦截会导致合理的限流重试被误杀。429 场景由内层业务码决定是否重试。

| 字段 | 说明 | 默认值 |
|------|------|--------|
| `enabled` | `true` 启用装饰器，`false` / `null` 不包装 | `false` |
| `maxAttempts` | 最大重试次数（含首次），超时/失败重试 | `3` |
| `initialIntervalMs` | 首次重试等待毫秒数（指数退避起点） | `1000` |
| `multiplier` | 间隔倍数（1s → 2s → 4s → ...） | `2.0` |
| `maxIntervalMs` | 最大间隔封顶值 | `10000` |
| `retryableErrorCodes` | 白名单 List，命中必定重试（优先级 > 默认规则） | `[]` |
| `nonRetryableErrorCodes` | 黑名单 List，命中直接抛出不重试（优先级最高） | `[]` |

---

## 6. 测试计划

- **单元测试**：用 JUnit Mock `ChatModel` 模拟 `TransientAiException` / `429` / `500`，验证重试次数和间隔符合指数退避预期
- **集成测试**：启动应用，观察日志确认装饰器已应用（`log.info` 输出模型名和参数）
- **正常路径**：发送一次 AI 对话请求，验证正常返回且无重试日志
- **异常路径**：通过 mock server 返回 429 / 503，验证指数退避重试行为和最终抛异常

---

## 7. 风险与回滚

- **风险 1**：正则表达式解析异常被静默忽略（`catch` 打印 warn 后继续），避免影响启动
- **风险 2**：上下文溢出（400）当前直接抛出不重试，如需自动调整 max_tokens，放到下一个 story
- **风险 3**：401/403 不清除缓存（无 sessionId 上下文），如需清除缓存，放到下一个 story
- **回滚**：删除 `RetryChatModel.java`，还原 `AiClientModelNode.java` 中 `registerBean` 调用（`ChatModel.class` → `OpenAiChatModel.class`，去掉 `applyRetryDecorator`），还原 `AiClientNode.java` 类型声明，`ai_client_model` 表 `ext_param` 字段可保留（不影响）
