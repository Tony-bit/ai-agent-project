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

### 5.1 可实施的数据来源

armory 的 `AiClientLoadDataStrategy` 当前只加载 `ArmoryCommandEntity.commandIdList` 对应的客户端，不读取 workflow 的 `RuntimeContextKeys.FLOW_CONFIG_MAP`。本方案不跨生命周期复用 workflow context，而是在 armory 数据加载阶段增加独立查询：

```java
List<AiAgentClientFlowConfigVO> queryActiveFlowConfigsByClientType(String clientType);
```

Infrastructure DAO 查询所有 Agent 的压缩 flow 记录，条件为：

```text
client_type = "COMPRESSION_ASSISTANT"
```

注意：当前 `ai_agent_flow_config` 表没有 status 字段，不能使用 `status=1` 过滤；是否有效由记录是否存在决定。

`AiClientLoadDataStrategy` 在启动现有并行查询前执行以下步骤：

1. 调用上述 repository 方法加载所有启用的压缩 flow。
2. 按 `clientId` 去重；0 个直接失败，超过 1 个 distinct clientId 直接失败。
3. 多条记录指向同一个 clientId 时允许，说明多个 Agent 共享同一系统压缩助手；`sequence` 不参与选择。
4. 将唯一 clientId 写入 armory `DynamicContext` 专用 key：

```java
public static final String GLOBAL_COMPRESSION_CLIENT_ID = "globalCompressionClientId";
```

5. 将该 clientId 合并进 `commandIdList` 的副本后，再由现有 `AiClientLoadDataStrategy` 加载对应 API、Model、Prompt、Advisor 和 `AiClientVO`。不修改原 command 对象的集合。

该 key 的 value 是唯一 clientId，不使用 `COMPRESSION_ASSISTANT` 作为 key，也不把整个 flowConfigMap 写入 armory DynamicContext。

### 5.2 clientId + taskType 匹配

`AiClientNode` 从 `dynamicContext.getValue(GLOBAL_COMPRESSION_CLIENT_ID)` 取得唯一 clientId，并在已加载的 `AiClientVO` 中筛选：

```text
aiClientVO.clientId == globalCompressionClientId
&& aiClientVO.taskType == 1
```

匹配结果必须恰好为 1：0 个表示压缩助手配置不完整；超过 1 个表示同一 clientId 存在重复 taskType=1 模型关联，两种情况都拒绝装配。`sequence` 不用于选择 ChatClient，避免装配顺序改变结果。

匹配成功后保留原普通名称，同时通过现有 `registerBean(...)` 向 `ArmoryObjectRegistry` 注册稳定别名：

```text
compressionChatClient
```

### 5.3 Registry 查找契约

动态装配对象不是 Spring Bean。`DefaultPromptCompressionService` 必须注入 `ArmoryObjectRegistry`，并按以下顺序解析：

```text
1. armoryObjectRegistry.get("compressionChatClient")
2. applicationContext.getBean("compressionChatClient", ChatClient.class)
3. 两者均不存在时抛出明确异常
```

Registry 是生产动态装配的主路径；ApplicationContext 只用于兼容静态 Bean 和已有测试。服务不再使用 `aiClientCOMPRESSION_ASSISTANTtaskType1`，也不再通过 compressionModelId 临时构建 ChatClient。

压缩助手底层模型继续由现有客户端、模型和关联表配置。ChatClient 装配完成后必须使用 `armoryObjectRegistry.contains("compressionChatClient")` 校验稳定别名；不能使用 `ApplicationContext.containsBean()` 校验动态对象。

### 5.4 全局唯一性与并发

`ArmoryObjectRegistry` 是全局 Registry，因此 `compressionChatClient` 被定义为系统级唯一别名，而不是 Agent 级别别名。每次 armory 装配都必须先执行全量 distinct clientId 校验：

- 所有 Agent 指向同一个 clientId：允许重复注册同一逻辑配置。
- 任意两个 Agent 指向不同 clientId：装配失败，不按 sequence 选择，也不允许最后写入覆盖前值。
- Registry 已存在别名且本次构建的 clientId 与已记录的全局 clientId 不同：在 put 前失败。

为支持最后一条校验，Registry 额外保存不可变映射 `globalCompressionClientId -> clientId`，或者将 clientId 与 ChatClient 封装为注册值；实施计划选择前者，便于诊断和测试。

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

- 没有启用的压缩 flow、存在多个 distinct clientId、clientId+taskType=1 匹配不唯一：armory 装配立即失败。
- Registry 中已有不同全局 clientId：在覆盖稳定别名前失败。
- 压缩结果未缩短：抛 `CompressionExhaustedException`。
- 压缩模型返回 1261：包装领域异常并保留 cause。
- 没有可压缩历史：抛出包含 `no compressible history` 的异常。
- DB JSON 非法：retry 关闭，compression 使用默认值，不允许关闭强制保护机制。

## 10. 验收标准

- CompressionConfig/CompressionPolicy 不包含 enabled 或 compressionModelId。
- DB 无压缩参数时使用 160000/3/2000。
- 旧扁平 Retry JSON 保持有效并自动获得默认压缩能力。
- 压缩服务按 ArmoryObjectRegistry -> ApplicationContext 顺序解析 `compressionChatClient`。
- 装配校验检查真实 Registry，缺失时立即失败。
- 压缩 flow 由 AiClientLoadDataStrategy 通过 repository 的 clientType 查询加载，并写入 `globalCompressionClientId`。
- 多 Agent 只能共享同一个压缩 clientId；多个 distinct clientId 不按 sequence 选择，直接拒绝。
- DB 无 7001 时使用代码默认提示词，有 7001 时覆盖且不重复。
- threshold=1 时 Query 先压缩再调用原 LLM。
- 原模型返回 1261 时压缩并完成第二次调用。
- 不新增数据库 schema、application.yml 或生产 Mock 组件。

## 11. 数据库准备

完整脚本：`docs/dev-ops/mysql/sql/dml/002-mandatory-context-compression-config.sql`

执行前需要按环境确认脚本顶部三个变量：

```sql
SET @compression_agent_id = '3';
SET @compression_client_id = '3202';
SET @compression_model_id = '2003';
```

### 11.1 必需数据

| 表 | 必需记录 | 说明 |
|------|------|------|
| `ai_agent_flow_config` | `client_type='COMPRESSION_ASSISTANT'` 且全库 distinct client_id 只有一个 | 系统级唯一压缩助手来源 |
| `ai_client` | `client_id=@compression_client_id` 且 `status=1` | 完整压缩 ChatClient |
| `ai_client_model` | `model_id=@compression_model_id` 且 `status=1` | 压缩助手底层模型，API 配置必须可用 |
| `ai_client_config` | client -> model，`task_type=1` 且 `status=1` | AiClientNode 的精确匹配依据 |

### 11.2 可选数据

| 表 | 可选记录 | 缺失行为 |
|------|------|------|
| `ai_client_system_prompt` | `prompt_id='7001'` | 使用代码默认压缩提示词 |
| `ai_client_config` | client -> prompt 7001 | 不关联时使用代码默认提示词 |
| `ai_client_model.ext_param` | 复合 JSON 中的 compressionConfig | 使用 160000/3/2000 默认值 |

SQL 不创建 API Key 或新的模型记录，因为 `base_url`、`api_key`、模型名称属于环境敏感配置。脚本会先验证 `@compression_model_id` 及其 API 是否已启用；若验证结果不是 1 行，应先由你选择现有可用模型，或按本环境的模型/API 管理流程创建。

### 11.3 操作顺序

1. 执行脚本第 1 段冲突检查；如果 distinct client_id 超过一个，先统一数据，不继续写入。
2. 确认目标 model/API 验证结果均为 1。
3. 执行事务内的 client、flow、client-model 关联幂等写入。
4. 执行末尾验收查询，确认唯一 clientId、task_type=1 唯一关联和模型/API 启用。
5. 7001 提示词仅在需要数据库覆盖代码默认模板时配置。

## 12. 发布与回滚

现有扁平 ext_param 无需迁移。只有需要调整默认压缩参数的模型才改成复合 JSON。

发布前必须确认 flow 配置包含 `COMPRESSION_ASSISTANT` 且 taskType=1 ChatClient 可装配。回滚旧版本前，已改成复合结构的 ext_param 需要恢复为旧扁平 Retry JSON。
