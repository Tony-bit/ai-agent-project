# Mandatory Context Compression Bugfix Design

## 1. 背景

当前压缩重试状态机已经完成，但生产装配还有三个缺口：

1. `AgentRepository` 只读取扁平 `RetryConfig`，没有加载压缩阈值等参数。
2. 旧压缩节点删除后，代码默认压缩提示词丢失。
3. 压缩服务查找的硬编码 Bean 名与 `AiClientNode` 按 clientId 注册的实际名称可能不一致。

本设计确认：上下文压缩是所有业务模型必须具备的保护机制，不提供独立开关，也不要求每个业务模型配置压缩模型 ID。

## 2. 目标与边界

- 所有业务模型始终由具备压缩能力的 `RetryChatModel` 装饰。
- `CompressionConfig` 只保存阈值、最大压缩次数、摘要 Token 上限。
- 系统统一使用现有 `COMPRESSION_ASSISTANT` ChatClient。
- DB 缺少压缩参数或提示词时使用代码默认值。
- 兼容旧扁平 Retry JSON，不新增字段、不改表、不迁移到 application.yml。
- 不调用真实 LLM，不增加生产 Mock 接口，不支持每个业务模型选择不同压缩模型。

## 3. CompressionConfig

删除 `enabled` 和 `compressionModelId`：

```java
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public static class CompressionConfig {
    @Builder.Default
    private int proactiveThresholdTokens = 160000;

    @Builder.Default
    private int maxCompressionAttempts = 3;

    @Builder.Default
    private int maxSummaryTokens = 2000;
}
```

DB 没有 compressionConfig 时也必须生成非 null 默认对象。压缩次数继续在状态机中归一化为 1 到 3。

## 4. ext_param 契约

新结构：

```json
{
  "retryConfig": {
    "enabled": true,
    "maxAttempts": 3,
    "retryableErrorCodes": ["429", "500", "502", "503", "504"]
  },
  "compressionConfig": {
    "proactiveThresholdTokens": 160000,
    "maxCompressionAttempts": 3,
    "maxSummaryTokens": 2000
  }
}
```

旧扁平 Retry JSON 保持兼容：

```json
{
  "enabled": true,
  "maxAttempts": 3,
  "retryableErrorCodes": ["429", "500"]
}
```

解析规则：

```text
ext_param 为空
  -> retry=null, compression=默认值

包含 retryConfig 或 compressionConfig
  -> 按复合结构解析
  -> compression 缺失时补默认值

否则
  -> 按旧扁平 RetryConfig 解析
  -> compression=默认值

JSON 非法
  -> 记录 modelId 和错误，不记录 JSON 正文
  -> retry=null, compression=默认值
```

## 5. 统一压缩助手

`AiClientNode` 根据现有 flowConfigMap 中 `COMPRESSION_ASSISTANT` 对应的 clientId 找到完整 ChatClient。保留原普通 Bean 名，同时注册稳定别名：

```text
compressionChatClient
```

`DefaultPromptCompressionService` 只解析该别名，不再使用 `aiClientCOMPRESSION_ASSISTANTtaskType1`，也不再通过 compressionModelId 临时构建 ChatClient。

压缩助手底层模型继续由现有客户端、模型和关联表配置。ChatClient 装配完成后必须校验稳定别名存在；缺失时立即抛出包含 flow key、clientId 和别名的 `IllegalStateException`。

## 6. 默认压缩提示词

恢复代码常量 `DEFAULT_COMPRESSION_PROMPT_TEMPLATE`，沿用旧节点的压缩规则和 `<分析>/<摘要>` 协议。

- DB 有非空 `prompt_id=7001`：覆盖代码默认提示词。
- DB 没有 7001：压缩助手使用代码默认提示词。
- 完整 ChatClient 已携带 system prompt，压缩 request 不重复拼接模板。

因此提示词不要求必须配置到数据库。

## 7. RetryChatModel 语义

- 所有业务模型都创建 `RetryChatModel`，compression policy 永远启用。
- retry 为空或关闭时，普通模型预算为 1，但主动超限和 1261 压缩恢复仍有效。
- 压缩助手调用使用 `compressionCall=true`，跳过递归压缩但保留普通瞬时错误重试。
- `CompressionPolicy` 同步删除 enabled 和 compressionModelId。

## 8. 用户 Query Mock 闭环

组件测试不启动 Spring 容器：

```text
User Query + recentMessages
  -> RetryRuntimeContextHolder
  -> RetryChatModel
  -> proactiveThresholdTokens=1，必然主动超限
  -> Mock PromptCompressionService.compress
  -> 捕获 originalPrompt/runtimeContext/policy
  -> 返回更短的 compressedPrompt
  -> Mock delegate.call(compressedPrompt)
  -> 返回 successResponse
```

主动场景中原模型只接收 compressedPrompt。被动场景中原模型先接收 originalPrompt 并返回 1261，压缩后第二次接收 compressedPrompt 并成功。

Mock 只替代压缩 LLM 和原 LLM 的输出；holder、阈值判断、预算和 Prompt 替换使用真实组件。

## 9. 错误处理

- 压缩助手未装配：armory 装配阶段失败。
- 压缩结果未缩短：抛 `CompressionExhaustedException`。
- 压缩模型返回 1261：包装领域异常并保留 cause。
- 没有可压缩历史：抛出包含 `no compressible history` 的异常。
- DB JSON 非法：retry 关闭，compression 使用默认值，不允许关闭强制保护机制。

## 10. 验收标准

- CompressionConfig/CompressionPolicy 不包含 enabled 或 compressionModelId。
- DB 无压缩参数时使用 160000/3/2000。
- 旧扁平 Retry JSON 保持有效并自动获得默认压缩能力。
- 压缩服务只解析 `compressionChatClient`，缺失时装配失败。
- DB 无 7001 时使用代码默认提示词，有 7001 时覆盖且不重复。
- threshold=1 时 Query 先压缩再调用原 LLM。
- 原模型返回 1261 时压缩并完成第二次调用。
- 不新增数据库 schema、application.yml 或生产 Mock 组件。

## 11. 发布与回滚

现有扁平 ext_param 无需迁移。只有需要调整默认压缩参数的模型才改成复合 JSON。

发布前必须确认 flow 配置包含 `COMPRESSION_ASSISTANT` 且 taskType=1 ChatClient 可装配。回滚旧版本前，已改成复合结构的 ext_param 需要恢复为旧扁平 Retry JSON。
