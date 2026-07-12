# Compression Configuration Bugfix Design

## 1. 背景

上下文压缩重试链路已经迁移到 `RetryChatModel` 内部，但模型装配数据仍存在两个缺口：

1. `AgentRepository` 只把 `ai_client_model.ext_param` 解析为扁平的 `RetryConfig`，没有构造 `CompressionConfig`，因此生产装配时压缩功能无法从 DB 启用。
2. 旧 `CompressionContextNode` 删除后，其代码内置默认压缩提示词也被删除。新链路在 DB 中没有 `prompt_id=7001` 时会向 fallback ChatClient 传入空 system prompt。

这两个问题不会影响普通重试，却会导致主动压缩和 1261 压缩恢复在生产配置下不可用或缺少有效压缩指令。

## 2. 目标

- 让 DB 中的模型扩展配置可以同时承载 retry 与 compression 配置。
- 兼容已有扁平 Retry JSON，避免存量模型配置失效。
- 恢复代码内置的默认压缩提示词，DB 提示词仅作为覆盖项。
- 使用组件级 Mock 验证用户 Query 从超限检测、Prompt 压缩到原 LLM 再调用的完整闭环。
- 不新增数据库字段，不修改表结构，不把配置迁移到 `application.yml`。

## 3. 非目标

- 不调用真实 LLM。
- 不新增测试 HTTP 接口或开发环境 Mock Controller。
- 不修改 Retry/Compression 状态机预算语义。
- 不重新引入 `CompressionContextNode`、`CompressionRequiredException` 或 armory 请求态字段。

## 4. 方案比较

### 方案 A：复合 ext_param，兼容旧扁平 JSON（采用）

新配置使用 `retryConfig` 和 `compressionConfig` 两个子对象。解析时先识别复合结构；不存在这两个键时，继续按旧 RetryConfig 解析。

优点：不改表结构、职责清晰、支持平滑迁移。缺点：repository 需要一段兼容解析逻辑。

### 方案 B：继续使用扁平 JSON

把 retry 和 compression 字段混在同一层，并分别反序列化两次。

不采用原因：两个配置都存在 `enabled`，语义冲突，无法独立开关。

### 方案 C：新增 compression_config 数据库字段

不采用原因：需要 schema 变更、数据迁移和回滚脚本，本次缺陷无需扩大到数据库结构调整。

## 5. 配置契约

### 5.1 新复合结构

```json
{
  "retryConfig": {
    "enabled": true,
    "maxAttempts": 3,
    "initialIntervalMs": 1000,
    "multiplier": 2.0,
    "maxIntervalMs": 10000,
    "retryableErrorCodes": ["429", "500", "502", "503", "504"],
    "nonRetryableErrorCodes": ["400", "401", "403"]
  },
  "compressionConfig": {
    "enabled": true,
    "compressionModelId": "compression-model-id",
    "proactiveThresholdTokens": 160000,
    "maxCompressionAttempts": 2,
    "maxSummaryTokens": 2000
  }
}
```

### 5.2 旧结构兼容

以下存量 JSON 仍按 RetryConfig 解析，`compressionConfig=null`：

```json
{
  "enabled": true,
  "maxAttempts": 3,
  "retryableErrorCodes": ["429", "500"]
}
```

### 5.3 缺失与非法配置

- `ext_param` 为空：retry 和 compression 均返回 null。
- 复合结构只有 retryConfig：只启用 retry。
- 复合结构只有 compressionConfig：只启用 compression，原模型普通调用预算为 1。
- JSON 非法：记录 modelId 和解析错误，不记录敏感配置正文；两个配置均返回 null，模型保持未装饰行为。
- CompressionConfig 数值归一化继续由状态机负责：压缩次数 1 到 3，Retry 最大尝试 10。

## 6. 组件设计

### 6.1 ModelExtensionConfig

在 domain 模型中增加仅用于承载 DB 复合 JSON 的值对象：

```java
@Data
@NoArgsConstructor
public class ModelExtensionConfig {
    private RetryConfig retryConfig;
    private CompressionConfig compressionConfig;
}
```

它不保存运行时 session、trace 或 history。

### 6.2 AgentRepository 解析

repository 将原 `parseRetryConfig` 替换为一次解析：

```text
ext_param 为空
  -> retry=null, compression=null

JSON 包含 retryConfig 或 compressionConfig
  -> 解析 ModelExtensionConfig

否则
  -> 按旧 RetryConfig 解析
  -> compression=null
```

构造 `AiClientModelVO` 时同时写入两个配置。解析只发生一次，不对同一 JSON 做两个互相冲突的扁平映射。

### 6.3 默认压缩提示词

`DefaultPromptCompressionService` 提供不可变常量 `DEFAULT_COMPRESSION_PROMPT_TEMPLATE`，内容沿用旧节点的压缩规则和响应格式。

策略生成顺序：

1. DB `systemPromptMap["7001"]` 存在且内容非空：使用 DB 内容。
2. 否则：使用代码默认提示词。

完整 `COMPRESSION_ASSISTANT` ChatClient 存在时，继续使用其已装配的 system prompt，request 中不重复追加模板。只有按 `compressionModelId` fallback 构建 ChatClient 时，才显式设置选定的模板。

### 6.4 压缩模型解析

保持当前优先级：

1. 完整 Bean `aiClientCOMPRESSION_ASSISTANTtaskType1`。
2. `compressionModelId` 对应的 `ai_client_model_<id>` ChatModel。
3. 两者都不存在时抛出包含 beanName 和 modelId 的 `IllegalStateException`。

## 7. 用户 Query Mock 闭环

新增组件级集成测试，不启动 Spring 容器：

```text
User Query + recentMessages
  -> RetryRuntimeContextHolder
  -> RetryChatModel
  -> proactiveThresholdTokens=1，主动超限必为 true
  -> Mock PromptCompressionService.compress
  -> 捕获 originalPrompt/runtimeContext/policy
  -> 返回更短的 compressedPrompt
  -> Mock delegate.call(compressedPrompt)
  -> 返回 successResponse
```

主动压缩场景中，delegate 只调用一次，并且只接收 compressedPrompt。另设被动场景：delegate 第一次接收 originalPrompt 并返回 1261，压缩后第二次接收 compressedPrompt 并成功。

Mock 只替代压缩 LLM 和原 LLM 的外部响应；`RetryChatModel`、上下文 holder、策略判断和 Prompt 替换使用真实组件。

## 8. 错误处理

- 压缩输出不比原 Prompt 短：`CompressionExhaustedException`，不调用原 LLM。
- 压缩模型返回 1261：包装为 `CompressionExhaustedException` 并保留 cause。
- 压缩模型返回空摘要：沿用现有 Prompt 重建规则，由压缩后 Token 校验阻止无效替换。
- runtimeContext 缺少可压缩历史：明确抛出 `no compressible history`，不伪造摘要。
- 非法 DB JSON：不阻断模型 Bean 装配，按无 retry/compression 配置处理并记录告警。

## 9. 验收标准

- 新复合 JSON 能同时装配 retryConfig 和 compressionConfig。
- 旧扁平 Retry JSON 行为保持不变。
- DB 未配置 `prompt_id=7001` 时，fallback 压缩客户端使用代码默认提示词。
- DB 配置 7001 时覆盖默认提示词，且完整 ChatClient 路径不重复 system prompt。
- 主动阈值设置为 1 时，用户 Query 必然进入压缩，随后使用 compressedPrompt 调用原 LLM。
- 原模型返回 1261 时，压缩后在同一 RetryChatModel 调用中完成第二次原 LLM 调用。
- 不引入数据库 schema、application.yml 或生产 Mock 组件变更。

## 10. 发布与回滚

代码上线前，现有扁平 ext_param 无需迁移。需要启用压缩的模型改为复合 JSON；可分模型逐步启用。

回滚代码时，已改成复合 JSON 的模型在旧版本中会被当作 RetryConfig 解析为默认关闭，因此回滚前应将这些模型恢复为旧扁平 Retry JSON。该操作风险需在上线清单中明确记录。
