# Session Runtime Context 优化设计

> **创建时间:** 2026-06-14  
> **状态:** draft  
> **目标范围:** 梳理当前上下文运行结构的问题，并设计一套兼顾并发、ChatClient 全局复用、用户级记忆隔离、会话级上下文复用、单轮请求隔离的运行时上下文优化方案。

---

## 1. 背景

当前自动 Agent 链路已经具备比较完整的模块拆分：

- 启动期通过 armory 链路装配 `ChatClient`、模型、system prompt、advisor、tool callback。
- 请求期通过 `AutoAgentExecuteStrategy` 创建 `DefaultAutoAgentExecuteStrategyFactory.DynamicContext`。
- `RootNode` 根据 `aiAgentId`、`agentType`、intent routing mode 选择下游节点。
- `IntentRoutingNode`、`QueryDecompositionNode`、`TaskRoutingSlotNode`、`GeneralChatNode` 等节点自行读取会话历史、拼接 prompt、调用 `ChatClient`。
- 会话消息通过 `ChatMemoryPersistenceService` 持久化到 MySQL，并回填 Redis 最近窗口。
- 用户画像通过 `UserPersonaCacheServiceImpl` 从 Redis/Mem0 获取。
- trading skills 已经通过 `SkillRegistry`、`SpringAiSkillAdvisor.lazyLoad(true)`、`read_skill` 工具实现渐进式披露。

这说明系统并不是完全粗糙的“一轮请求重建所有对象”。`ChatClient` 已经是应用级装配和复用。但请求期上下文仍然分散在多个节点里临时读取和拼装，缺少一个明确的 session runtime 层，导致同一轮内重复加载、跨轮缺少会话快照、并发语义不够清晰。

---

## 2. 当前问题梳理

### 2.1 ChatClient 复用边界是对的，但容易被误用

当前 `AiClientNode` 在启动装配阶段构造 `ChatClient`，并注册到 `ArmoryObjectRegistry`。业务节点通过 `getChatClientByClientId(clientId, taskType)` 获取。

这个方向是正确的：`ChatClient` 应作为应用级、配置版本级对象复用，不应该按 session 创建。问题在于当前代码没有显式表达“ChatClient 不持有 session 状态”这一边界，后续如果为了减少每轮上下文装配成本，把 session history、persona、skill working set 塞进 ChatClient，就会带来并发串线风险。

### 2.2 DynamicContext 是 turn 级对象，但承担了多种职责

`AutoAgentExecuteStrategy.execute()` 每轮请求都会创建一个新的 `DynamicContext`。这对 SSE emitter、traceId、当前任务、路由结果、临时 metrics 是合理的。

但现在 `DynamicContext` 同时承载：

- turn 级临时数据；
- sessionId、userId；
- flow config map；
- persona；
- sub task results；
- routing metrics；
- downstream 节点之间的共享变量。

它缺少强类型边界，也没有区分 user/session/turn 三种生命周期。长期看会让上下文 key 越来越多，节点间依赖变隐式。

### 2.3 会话历史读取分散且重复

当前历史读取发生在多个节点：

- `IntentRoutingNode.getRecentHistoryMessages(sessionId)`
- `QueryDecompositionNode.history(sessionId)`
- `TaskRoutingSlotNode.history(sessionId)`
- `CompressionContextNode` 也会读取会话历史
- `GeneralChatNode` 通过 advisor 参数触发 chat memory advisor

这会造成两个问题：

1. split routing 模式下，同一轮 query 的 decomposition 和 task slot 阶段可能重复读取同一份 history。
2. 每个节点各自决定 history 表达形式，缺少统一 token budget、裁剪策略和可观测指标。

### 2.4 flow config 每轮查库

无 `aiAgentId` 的自动意图路由场景中，`RootNode.get()` 每轮调用 `repository.queryAllFlowConfigForIntentRouting()`。显式 agent 场景也会调用 `queryAiAgentClientFlowConfig(aiAgentId)`。

这类配置属于低频变更、高频读取数据，应增加应用级或短 TTL 缓存，并提供版本失效机制。否则高并发时会把每轮请求的固定成本放大。

### 2.5 persona 已缓存，但注入和消费链路不够统一

`injectPersonaContext()` 会把 persona 写入 `DynamicContext`。PE 链路已经有消费点，但通用对话、意图路由、split routing 并没有统一把 persona 纳入 prompt 预算和上下文快照。

这会产生两个问题：

- 有些请求加载了 persona，但实际没有用上。
- 不同节点是否使用 persona 取决于各自实现，体验不一致。

### 2.6 few-shot 检索每轮按 query 触发

`IntentRoutingService.retrieveFewshotSamples(userMessage)` 对非 trivial 输入每轮走 PGvector TopK 检索。方向正确，但缺少 query 级短缓存和路由阶段的 memoization。

在真实在线评测、重复 query、前端重试、用户连续修正输入时，这部分会产生重复向量检索成本。

### 2.7 skills 渐进披露方向正确，但开发期 autoReload 可能放大开销

trading skills 当前配置：

- `ClasspathSkillRegistry.autoLoad(true)`
- `SpringAiSkillAdvisor.lazyLoad(true)`
- `SkillsAgentHook.autoReload(true)`
- `read_skill` 工具按需读取完整 skill

其中 `lazyLoad(true)` 和轻量工具描述是正确方向。需要注意的是 `autoReload(true)` 更适合开发期。如果底层实现每轮检查文件变化或刷新 skill registry，生产环境会出现“每轮都像重新加载 skill”的观感和额外 I/O。

### 2.8 同一 session 并发语义不明确

当前 controller 把请求丢进全局线程池执行。不同 session 并发是合理的，但同一个 session 如果同时收到两条消息，会出现：

- 两个 turn 同时读取同一份旧 history；
- 两个 LLM 都基于旧上下文回答；
- 持久化 messageIndex 可能竞争；
- Redis 最近窗口可能被后完成的请求覆盖；
- session summary、persona refresh、active skill working set 更新可能乱序。

聊天语义天然要求同一 session 内按用户消息顺序执行。当前缺少 session 级执行门禁。

---

## 3. 设计目标与非目标

### 3.1 目标

1. 保持 `ChatClient` 全局复用，不引入 session 私有 ChatClient。
2. 建立清晰的上下文生命周期边界：应用级、用户级、会话级、单轮级。
3. 新增 session runtime 层，一轮内统一加载并复用 history、persona、flow config、few-shot 等上下文。
4. 不同 session 可以并发，同一 session 默认串行执行。
5. 降低重复 DB/Redis/Mem0/PGvector 访问。
6. 为后续 token-aware history 裁剪、session summary、skill working set 复用留接口。
7. 尽量兼容现有 `DynamicContext`，避免一次性重写所有节点。

### 3.2 非目标

1. 不改变底层 LLM 的无状态调用模型。
2. 不实现“一个 session 一个 LLM 实例”。
3. 不把 session history、persona、tool state 塞入 `ChatClient`。
4. 不重构 Spring AI `ChatClient`、`Advisor`、`ToolCallback` 的装配方式。
5. 不改变 PE、通用对话、巡检、交易分析的业务语义。
6. 不在第一期引入分布式锁；单机应用内先用本地 session guard。
7. 不在第一期实现完整 session summary 压缩，只预留接口。

---

## 4. 核心原则

### 4.1 ChatClient 全局复用

`ChatClient` 应按配置维度复用：

```text
clientId + taskType + modelVersion + promptVersion + toolVersion -> ChatClient
```

它只包含：

- ChatModel；
- default system prompt；
- default advisors；
- default tool callbacks；
- MCP tools；
- skill read tool；
- observability advisor。

它不包含：

- sessionId；
- userId；
- traceId；
- 会话历史；
- 用户画像；
- 当前 active skill 状态；
- SSE emitter。

每次调用通过 request spec 传入 turn/session 参数：

```java
chatClient.prompt()
    .user(message)
    .advisors(a -> a
        .param("chat_memory_conversation_id", sessionId)
        .param("user_id", userId)
        .param("trace_id", traceId));
```

### 4.2 上下文按生命周期隔离

```text
Application Scope
  ChatClient / ChatModel / ToolCallback / Advisor / SkillRegistry / flow config cache

User Scope
  persona / long-term memory profile / user preference

Session Scope
  recent history snapshot / session summary / active skill working set / last message index

Turn Scope
  current query / traceId / emitter / routing result / metrics / temporary node data
```

### 4.3 同 session 串行，不同 session 并行

```text
session-A turn-1 -> session-A turn-2 -> session-A turn-3

session-A turn-1 并行 session-B turn-1 并行 session-C turn-1
```

这是最符合聊天语义的默认策略。后续如需要支持同 session 并行，可引入 message version 和 conflict merge，但第一期不做。

---

## 5. 目标架构

### 5.1 分层结构

```text
HTTP Controller
  -> AutoAgentExecuteStrategy
     -> SessionExecutionGuard.acquire(sessionId)
        -> TurnContext 创建
    -> RuntimeContextAssembler.prepare(...)
       -> UserRuntimeContext
       -> SessionRuntimeContext
       -> 写入 DynamicContext 兼容 key
    -> RootNode / downstream nodes
       -> 按节点级 PromptContextPolicy 构建节点专属 prompt context
    -> ConversationPersistence
    -> RuntimeContextAssembler.afterTurn(...)
     -> SessionExecutionGuard.release(sessionId)
```

### 5.2 新增组件

#### SessionExecutionGuard

职责：

- 为每个 sessionId 提供串行执行门禁。
- 不同 sessionId 不互相阻塞。
- 支持超时等待、错误释放、指标记录。

建议第一期实现：

```java
public interface SessionExecutionGuard {
    <T> T execute(String sessionId, Callable<T> task);
}
```

实现可使用：

- `ConcurrentHashMap<String, ReentrantLock>`
- 或 `Striped<Lock>` 风格的固定分片锁
- 或 session actor mailbox

第一期推荐 `ConcurrentHashMap + ReentrantLock + finally release`，简单透明。

#### RuntimeContextAssembler

职责：

- 在每轮请求开始时统一准备上下文。
- 将 user/session/turn 数据放入强类型对象。
- 兼容写入现有 `DynamicContext` key，降低迁移成本。

建议接口：

```java
public interface RuntimeContextAssembler {
    TurnRuntimeContext prepare(ExecuteCommandEntity request,
                               DefaultAutoAgentExecuteStrategyFactory.DynamicContext dynamicContext);

    void afterTurn(ExecuteCommandEntity request,
                   DefaultAutoAgentExecuteStrategyFactory.DynamicContext dynamicContext,
                   TurnRuntimeContext turnContext);
}
```

#### UserRuntimeContextManager

职责：

- 按 userId 缓存 persona。
- 后续扩展长期记忆摘要、用户偏好。

第一期可复用现有 `IUserPersonaCacheService`，只在 assembler 内统一调用，不让业务节点直接调用。

#### SessionRuntimeContextManager

职责：

- 按 sessionId 维护会话级快照。
- 一轮内 history 只加载一次。
- 后续扩展 session summary、active skill working set。

建议模型：

```java
public class SessionRuntimeContext {
    private String sessionId;
    private String userId;
    private List<ChatMessageEntity> recentMessages;
    private List<String> recentHistoryMessages;
    private String sessionSummary;
    private Set<String> activeSkillNames;
    private Integer lastMessageIndex;
    private long loadedAt;
    private long version;
}
```

第一期 `sessionSummary` 和 `activeSkillNames` 可先保留字段，不接入业务。

#### AgentRuntimeConfigCache

职责：

- 缓存 `queryAllFlowConfigForIntentRouting()`。
- 缓存 `queryAiAgentClientFlowConfig(aiAgentId)`。
- 支持 TTL 和手动失效。

建议第一期：

```text
cache key:
  intent-routing-all
  agent-flow-config:{aiAgentId}

TTL:
  1-5 分钟，配置化

失效:
  暂时通过管理接口或应用重启
  后续可接入配置更新时间/version
```

#### RoutingFewshotCache

职责：

- 缓存 `IntentFewshotService.retrieveTopK(query, k)` 结果。
- 减少重复 query 和评测场景下 PGvector 开销。

建议第一期：

```text
cache key = normalize(query) + ":" + k
TTL = 5-30 分钟
maxSize = 1000
```

#### PromptContextBuilder 与 NodePromptContextPolicy

职责：

- 从 `TurnRuntimeContext` 中为具体节点裁剪该节点需要的上下文。
- 保持各节点 prompt 模板独立，不把 session runtime context 当作全局通用 prompt。
- 按节点职责声明是否需要 history、persona、session summary、few-shot、active skill working set。
- 在统一 token budget 下，对每个节点分别计算和裁剪 prompt context。

建议模型：

```java
public class NodePromptContextPolicy {
    private String nodeName;
    private HistoryMode historyMode;
    private boolean includePersona;
    private boolean includeSessionSummary;
    private boolean includeFewshot;
    private boolean includeActiveSkillNames;
    private int maxHistoryMessages;
    private int maxPromptTokens;
}
```

```java
public interface PromptContextBuilder {
    RuntimePromptContext build(TurnRuntimeContext turnContext,
                               NodePromptContextPolicy policy);
}
```

第一期可以先不完整实现 `PromptContextBuilder`，但设计上必须明确边界：`RuntimeContextAssembler` 只负责加载、缓存、快照和兼容写入；节点仍然通过自己的 prompt template 与节点级 policy 决定如何消费这些上下文。

---

## 6. DynamicContext 兼容策略

第一期不移除 `DynamicContext.dataObjects`，而是把它作为 turn 级兼容容器。

新增常量 key，避免继续散落字符串：

```java
public final class RuntimeContextKeys {
    public static final String TURN_CONTEXT = "turnRuntimeContext";
    public static final String SESSION_CONTEXT = "sessionRuntimeContext";
    public static final String USER_CONTEXT = "userRuntimeContext";
    public static final String RECENT_HISTORY_MESSAGES = "recentHistoryMessages";
    public static final String PERSONA = "persona";
    public static final String FLOW_CONFIG_MAP = "aiAgentClientFlowConfigVOMap";
}
```

`RuntimeContextAssembler.prepare()` 写入：

- `dynamicContext.setValue("sessionId", request.getSessionId())`
- `dynamicContext.setValue("userId", request.getUserId())`
- `dynamicContext.setValue("persona", userContext.getPersona())`
- `dynamicContext.setValue("recentHistoryMessages", sessionContext.getRecentHistoryMessages())`
- `dynamicContext.setAiAgentClientFlowConfigVOMap(flowConfigMap)`
- `dynamicContext.setValue("turnRuntimeContext", turnContext)`

然后逐步迁移节点：

- `IntentRoutingNode` 从 `dynamicContext.getValue("recentHistoryMessages")` 取历史。
- `QueryDecompositionNode` 从相同 key 取历史。
- `TaskRoutingSlotNode` 从相同 key 取历史。
- `RootNode` 不再直接查询 flow config。
- persona 注入逻辑从 `RootNode` 下沉到 assembler。

---

## 7. 请求流程

### 7.1 自动意图路由

```text
AutoAgentExecuteStrategy
  -> SessionExecutionGuard.execute(sessionId)
    -> create DynamicContext
    -> RuntimeContextAssembler.prepare
      -> load flow config from AgentRuntimeConfigCache
      -> load persona from UserRuntimeContextManager
      -> load recent history from SessionRuntimeContextManager
      -> write compatibility keys
    -> RootNode
      -> choose IntentRoutingNode or QueryDecompositionNode
    -> routing nodes
      -> read recentHistoryMessages from DynamicContext
      -> IntentRoutingService
        -> retrieve few-shot through RoutingFewshotCache
    -> downstream node
    -> persist conversation
    -> RuntimeContextAssembler.afterTurn
      -> refresh session history snapshot
```

### 7.2 显式 aiAgentId 链路

```text
AutoAgentExecuteStrategy
  -> guard
  -> assembler.prepare
    -> load agent flow config by aiAgentId from AgentRuntimeConfigCache
    -> load persona
    -> load recent history
  -> RootNode
    -> Step1AnalyzerNode / IntelligentInspection
```

### 7.3 通用对话

通用对话仍使用全局 `ChatClient`。调用时传入：

- `chat_memory_conversation_id`
- `chat_memory_response_size`
- `user_id`
- `trace_id`

如果需要 persona，应由 `GeneralChatNode` 从 `TurnRuntimeContext` 或 `DynamicContext` 读取统一构造 system supplement，而不是自行调用 memory service。

---

## 8. 并发设计

### 8.1 默认策略

| 场景 | 策略 |
| --- | --- |
| 不同 userId，不同 sessionId | 并行 |
| 同 userId，不同 sessionId | 并行，共享 user-level persona cache |
| 同 sessionId | 串行 |
| 同 sessionId 第二个请求到达 | 等待、排队或快速返回 busy，第一期建议等待并配置超时 |

### 8.2 Guard 行为

第一期建议：

```text
tryLock(waitTimeout)
  成功 -> 执行完整 turn，包括流式输出和持久化
  失败 -> 返回 session busy 错误，提示稍后重试
finally
  release lock
```

配置建议：

```yaml
agent.runtime.session-guard.enabled: true
agent.runtime.session-guard.wait-timeout-ms: 3000
agent.runtime.session-guard.max-lock-idle-minutes: 30
```

如果希望前端第二条消息等待同一 SSE 连接返回结果，可以把 wait timeout 调大。若希望交互更明确，可以快速返回 busy。

### 8.3 锁清理

`ConcurrentHashMap<String, ReentrantLock>` 会有 session key 增长风险。第一期可在 guard 中记录 lastAccessTime，定时清理空闲且未锁定的 lock。

后续如果部署多实例，需要升级为 Redis lock 或基于消息队列的 session actor。

---

## 9. 缓存与失效

### 9.1 Flow Config Cache

缓存对象：

- all intent routing flow config
- agentId specific flow config
- active step prompt snapshot

失效策略：

- TTL 默认 1-5 分钟。
- 提供 `clearRuntimeConfigCache()` 管理方法。
- 后续数据库配置表增加 `updated_at/version` 后，按 version 判断失效。

### 9.2 Session Context Cache

缓存对象：

- recent messages
- recent history text
- session summary placeholder
- active skill names placeholder
- last message index / version / loadedAt

失效策略：

- turn 开始时读取缓存。
- turn 完成持久化后刷新当前 session snapshot。
- Redis/MySQL 作为事实源，本地 session cache 只是运行期加速。

第一期单实例场景建议使用 Caffeine 作为本机 session runtime cache：

```yaml
agent.runtime.session-context-cache.enabled: true
agent.runtime.session-context-cache.expire-after-access-minutes: 30
agent.runtime.session-context-cache.expire-after-write-minutes: 120
agent.runtime.session-context-cache.maximum-size: 10000
```

Caffeine 过期只表示本机快照失效，不表示会话历史丢失。下一轮请求应通过 `SessionRuntimeContextManager` 从 Redis/MySQL 或现有 `ChatMemoryPersistenceService` 重新加载最近窗口并回填 Caffeine。

### 9.3 单实例缓存一致性协议

即使第一期只考虑单实例，也必须显式约束 MySQL、Redis、Caffeine、`TurnRuntimeContext` 之间的一致性。推荐模型是：

```text
MySQL / ChatMemoryPersistenceService: 事实源
Redis recent window: 共享近端缓存
Caffeine SessionRuntimeContext: 单机运行期快照
TurnRuntimeContext: 单轮请求内只读视图
```

一致性原则：

1. 同一 `sessionId` 的 turn 必须先经过 `SessionExecutionGuard` 串行化。
2. 一轮请求开始后，节点只读取本轮 `TurnRuntimeContext`，不再各自访问 Redis/MySQL。
3. Caffeine 只能在持久化成功后刷新，不能在 LLM 生成中或持久化前提前推进。
4. 任一持久化或 Redis recent window 更新失败时，应 invalidate 当前 session 的 Caffeine key，让下一轮回源重建。
5. `SessionRuntimeContext` 必须携带 `lastMessageIndex` 或 `version`，刷新 Caffeine 时只允许新版本覆盖旧版本。

推荐读路径：

```text
SessionRuntimeContextManager.getOrLoad(sessionId)
  -> hit Caffeine
     -> 使用本机 SessionRuntimeContext 构造 TurnRuntimeContext
  -> miss Caffeine
     -> 从 Redis recent window / ChatMemoryPersistenceService 加载
     -> 必要时回源 MySQL
     -> 构造 SessionRuntimeContext
     -> 回填 Caffeine
```

推荐成功写路径：

```text
LLM 输出完成
  -> 持久化 user/assistant message 到 MySQL
  -> 更新 Redis recent window
  -> 基于持久化后的最新窗口构造 SessionRuntimeContext
  -> 使用 lastMessageIndex/version 防旧写刷新 Caffeine
  -> release SessionExecutionGuard
```

推荐失败路径：

```text
持久化失败或 Redis 刷新失败
  -> 不刷新 Caffeine
  -> invalidate session context cache key
  -> 记录错误与 traceId
  -> release SessionExecutionGuard
```

版本防护建议：

```java
boolean canReplace(SessionRuntimeContext oldValue, SessionRuntimeContext newValue) {
    return oldValue == null
        || newValue.getLastMessageIndex() >= oldValue.getLastMessageIndex()
        || newValue.getVersion() >= oldValue.getVersion();
}
```

在单实例 + 同 session 串行锁成立时，版本防护通常不会触发冲突；保留它是为了防止异常重试、异步刷新、测试桩或未来演进引入旧 snapshot 覆盖新 snapshot。

### 9.4 User Context Cache

缓存对象：

- persona
- long-term preference placeholder

失效策略：

- 沿用现有 Redis TTL。
- assembler 不直接访问 Mem0，只调用 `IUserPersonaCacheService`。
- 后续在用户触发 memory sync 后主动清理 persona cache。

### 9.5 Few-Shot Cache

缓存对象：

- route query -> topK few-shot samples

失效策略：

- TTL 默认 10 分钟。
- 样本新增、删除、更新后清理 cache。

---

## 10. Token Budget 与 Prompt 组装

第一期目标不是实现完整压缩，而是把裁剪入口统一起来。

新增 `RuntimePromptContext`：

```java
public class RuntimePromptContext {
    private String persona;
    private String sessionSummary;
    private List<String> recentHistoryMessages;
    private List<IntentFewshotSample> fewshotSamples;
    private int estimatedTokens;
}
```

由专门的 `PromptContextBuilder` 负责。第一期如果暂未抽象 builder，节点内部也必须按相同策略从 `TurnRuntimeContext` 或 `DynamicContext` 中选择上下文，不能由 assembler 直接产出节点最终 prompt：

1. persona 是否纳入；
2. session summary 是否纳入；
3. recent history 取几轮；
4. few-shot 取几个；
5. 超预算时按优先级裁剪。

建议裁剪优先级：

```text
当前用户输入 > 必要 system/task prompt > session summary > 最近历史 > persona > few-shot
```

注意：persona 和 few-shot 都不是每个节点都必须注入。应按节点类型声明需求，避免所有 prompt 都携带所有上下文。

### 10.1 Runtime Context 与 Prompt 的边界

本方案统一的是一轮请求中的上下文素材，不是统一 prompt。

`RuntimeContextAssembler` 的输出应被视为上下文原材料池：

- current query；
- recent history snapshot；
- persona；
- flow config；
- few-shot retrieval result；
- session summary；
- active skill working set；
- traceId/sessionId/userId。

这些数据可以在同一 turn 内被多个节点复用，但不能直接拼成一个所有节点共享的大 prompt。每个节点仍然保留自己的 prompt 模板、任务目标和输出约束。

正确的数据流应是：

```text
RuntimeContextAssembler.prepare()
  -> TurnRuntimeContext / UserRuntimeContext / SessionRuntimeContext
  -> Node.execute()
     -> PromptContextBuilder.build(turnContext, nodePolicy)
     -> node prompt template + node prompt context
     -> ChatClient.prompt()
```

也就是说：

- assembler 负责“加载什么、缓存什么、快照什么”；
- `PromptContextBuilder` 负责“当前节点可以拿哪些上下文、拿多少、超预算时如何裁剪”；
- 具体节点负责“如何组织自己的 system/task/user prompt”。

### 10.2 节点级上下文策略示例

| 节点 | 建议使用的上下文 | 不应默认携带的上下文 |
| --- | --- | --- |
| `RootNode` | flow config、`aiAgentId`、`agentType`、routing mode | 完整 history、persona、few-shot |
| `IntentRoutingNode` | 当前 query、少量 recent history、routing few-shot | 长 persona、完整 session summary |
| `QueryDecompositionNode` | 当前 query、recent history、routing result | 与分解无关的 persona、skill working set |
| `TaskRoutingSlotNode` | decomposition result、slot schema、必要 recent history | routing few-shot、完整 persona |
| `GeneralChatNode` | 当前 query、recent history/chat memory advisor 参数、persona 可选 | routing few-shot、flow config 细节 |
| PE / trading 节点 | domain prompt、任务上下文、必要 history、工具约束 | 与任务无关的通用上下文 |

节点级策略可以先以常量或枚举方式落地，后续再演进为配置化。例如：

```java
public enum HistoryMode {
    NONE,
    RECENT_SHORT,
    RECENT_FULL,
    SUMMARY_PLUS_RECENT
}
```

第一期迁移时，路由相关节点只需要从 `DynamicContext` 复用统一加载的 recent history；第四期再将这些策略集中到 `PromptContextBuilder` 中做 token-aware 裁剪。

---

## 11. Skills 处理策略

### 11.1 保持当前渐进披露

继续保留：

- `SpringAiSkillAdvisor.lazyLoad(true)`
- lightweight tool description
- `read_skill`

### 11.2 生产环境关闭 autoReload

建议新增配置：

```yaml
spring.ai.trading.skills.auto-reload: false
```

开发环境开启，生产环境关闭。`TradingSkillsConfig` 读取该配置后决定 `SkillsAgentHook.autoReload(...)`。

### 11.3 Session Active Skill Working Set

后续可在 `SessionRuntimeContext.activeSkillNames` 中记录本 session 已读取过的 skill：

- LLM 调用 `read_skill(get-stock-info)` 成功后记录 skill name。
- 后续同 session 可在 prompt 中只提示已激活 skill 名称，而不是重复解释全部工具。

第一期只预留字段，不拦截 tool call。

---

## 12. 分阶段实施方案

### Phase 1：上下文读取收敛，不改变业务行为

目标：

1. 新增 `RuntimeContextAssembler`。
2. 新增 `RuntimeContextKeys`。
3. 新增 `SessionRuntimeContext`、`UserRuntimeContext`、`TurnRuntimeContext`。
4. `AutoAgentExecuteStrategy` 在进入 `RootNode` 前调用 assembler。
5. `IntentRoutingNode`、`QueryDecompositionNode`、`TaskRoutingSlotNode` 从 `DynamicContext` 读取 recent history。
6. 保持原有 fallback：如果 `recentHistoryMessages` 不存在，再调用现有 history 方法。

验收：

- 同一轮 split routing 只读取一次 conversation history。
- 现有 routing 单元测试通过。
- 现有通用对话、PE 链路行为不变。

### Phase 2：flow config 与 few-shot 缓存

目标：

1. 新增 `AgentRuntimeConfigCache`。
2. `RootNode` 不直接调用 repository 查询 flow config，改由 assembler 准备。
3. 新增 `RoutingFewshotCache`。
4. `IntentRoutingService` 通过缓存获取 few-shot。

验收：

- 高频请求下 flow config DB 查询明显下降。
- 重复 query 的 PGvector 查询命中缓存。
- 支持手动清理缓存。

### Phase 3：同 session 串行执行

目标：

1. 新增 `SessionExecutionGuard`。
2. `AutoAgentExecuteStrategy.execute()` 包裹完整 turn。
3. 增加 busy/timeout 降级策略。
4. 增加并发测试。

验收：

- 同一 session 并发请求不会乱序写入 messageIndex。
- 不同 session 请求仍可并发执行。
- turn 异常时锁必定释放。

### Phase 4：token-aware prompt context

目标：

1. 新增 `PromptContextBuilder`。
2. 新增 `NodePromptContextPolicy`，让每个节点声明自己的上下文需求。
3. recent history 按节点级 token budget 裁剪。
4. persona、summary、few-shot 按节点策略进入预算控制。
5. 为压缩节点、routing prompt、通用对话 prompt 共享上下文裁剪逻辑，但不共享最终 prompt 模板。

验收：

- 长会话 prompt token 数可控。
- routing prompt 不再无条件拼接完整 recent cache。
- 压缩策略和正常策略共用上下文入口。
- 不同节点的 prompt context 可按 policy 区分，不能退化为所有节点共用同一份大 prompt。

### Phase 5：session summary 与 active skill working set

目标：

1. 增加 session summary 存储和刷新策略。
2. 记录 session active skill names。
3. 让 prompt 可以感知已激活 skill，但不重复加载全部 skill 内容。

验收：

- 长会话不依赖完整历史也能保留主要上下文。
- skill 使用在同 session 内更稳定。

---

## 13. 代码改动建议

### 13.1 新增包

```text
ai-agent-study-domain/src/main/java/denny/ai/agent/domain/service/runtime/
  RuntimeContextAssembler.java
  DefaultRuntimeContextAssembler.java
  RuntimeContextKeys.java
  SessionExecutionGuard.java
  LocalSessionExecutionGuard.java
  SessionRuntimeContextManager.java
  UserRuntimeContextManager.java
  AgentRuntimeConfigCache.java
  RoutingFewshotCache.java
  PromptContextBuilder.java
  DefaultPromptContextBuilder.java

ai-agent-study-domain/src/main/java/denny/ai/agent/domain/model/valobj/runtime/
  TurnRuntimeContext.java
  SessionRuntimeContext.java
  UserRuntimeContext.java
  RuntimePromptContext.java
  NodePromptContextPolicy.java
```

### 13.2 修改点

| 文件 | 改动 |
| --- | --- |
| `AutoAgentExecuteStrategy.java` | 创建 DynamicContext 后调用 assembler；后续接入 SessionExecutionGuard |
| `RootNode.java` | 不再直接加载 flow config；保留 fallback |
| `AbstractExecuteSupport.java` | persona 注入逻辑迁移到 assembler；保留旧方法兼容一段时间 |
| `IntentRoutingNode.java` | 读取 `recentHistoryMessages` |
| `QueryDecompositionNode.java` | 读取 `recentHistoryMessages` |
| `TaskRoutingSlotNode.java` | 读取 `recentHistoryMessages` |
| `IntentRoutingService.java` | few-shot 获取通过缓存适配层 |
| `TradingSkillsConfig.java` | `autoReload` 配置化 |

---

## 14. 测试方案

### 14.1 单元测试

| 测试类 | 覆盖点 |
| --- | --- |
| `DefaultRuntimeContextAssemblerTest` | user/session/turn context 构建、兼容 key 写入、异常降级 |
| `SessionRuntimeContextManagerTest` | history cache 命中、miss 后读取、afterTurn 成功后刷新、失败时 invalidate、lastMessageIndex/version 防旧写 |
| `AgentRuntimeConfigCacheTest` | intent routing config 缓存、agent config 缓存、手动失效 |
| `RoutingFewshotCacheTest` | query 归一化、命中、TTL、样本变更失效 |
| `LocalSessionExecutionGuardTest` | 同 session 串行、不同 session 并行、异常释放锁 |
| `PromptContextBuilderTest` | 按节点 policy 选择上下文、token 超预算裁剪、不同节点不共享最终 prompt context |

### 14.2 节点回归测试

| 测试类 | 覆盖点 |
| --- | --- |
| `IntentRoutingNodeTest` | 优先使用 DynamicContext 中的 recent history |
| `QueryDecompositionNodeTest` | 不重复调用 ChatMemoryPersistenceService |
| `TaskRoutingSlotNodeTest` | split 第二阶段复用同一份 history |
| `RootNodeTest` | flow config 已由 assembler 准备时不重复查询 |
| `GeneralChatNodeTest` | session/user/trace advisor params 保持正确 |

### 14.3 并发测试

1. 同 session 两个请求并发进入，验证第二个等待或 busy。
2. 同 session 第一个请求异常，验证第二个请求可以继续执行。
3. 不同 session 多请求并发，验证没有全局阻塞。
4. 同 userId 不同 session 并发，验证 persona cache 可共享但 session history 不共享。

### 14.4 集成测试

1. 连续三轮通用对话，验证 history 顺序正确。
2. split routing 场景，验证 conversation history 只读取一次。
3. repeated query 触发 few-shot cache 命中。
4. 修改 flow config 后手动清 cache，验证新配置生效。
5. 模拟持久化成功但 Redis/Caffeine 刷新路径，验证下一轮读取到最新 history。
6. 模拟持久化或 Redis 刷新失败，验证 Caffeine 被 invalidate，下一轮从事实源重建。

---

## 15. 风险与取舍

### 15.1 持锁执行完整流式请求会增加同 session 等待

这是为了保证聊天顺序。第一期可通过 wait timeout 控制体验。如果用户希望允许同 session 同时发多条消息，需要引入 message version 和 merge 机制，复杂度明显上升。

### 15.2 本地 session guard 不适用于多实例

第一期默认单实例或粘性会话。多实例部署时需要升级 Redis lock、数据库乐观锁或消息队列 actor。

### 15.3 缓存会带来配置变更延迟

flow config 和 few-shot cache 需要 TTL 与手动失效机制。生产环境应提供管理接口或配置发布后主动清理。

### 15.4 DynamicContext 兼容期会继续存在字符串 key

第一期不强行消灭字符串 key，以降低改造风险。后续可逐步让节点依赖 `TurnRuntimeContext` 强类型对象。

---

## 16. 成功标准

1. `ChatClient` 继续全局复用，且没有 session 状态进入 `ChatClient`。
2. 一轮请求内 history、persona、flow config 不再被多个节点重复读取。
3. 同 session 并发请求不会导致 messageIndex、Redis history 或上下文乱序。
4. flow config 与 few-shot 高频重复读取有缓存。
5. session context cache 遵守持久化成功后刷新、失败时失效、version 防旧写的一致性协议。
6. 节点逻辑仍可通过 `DynamicContext` 兼容运行。
7. 节点级 prompt context 可以按节点职责区分，runtime context 不会退化为全局通用 prompt。
8. 后续可以自然接入 token-aware history、session summary、active skill working set。

---

## 17. 建议优先级

推荐先做 Phase 1 和 Phase 3：

1. Phase 1 先收敛上下文读取，收益直接，风险低。
2. Phase 3 解决同 session 并发乱序，这是正确性的底线。
3. Phase 2 缓存优化可随后补上，主要改善性能。
4. Phase 4/5 属于长会话质量优化，等基础边界稳定后再做。

这条路径能在不推翻现有 armory、routing、memory 设计的前提下，把 runtime 从“节点各自按需加载”推进到“统一上下文装配 + 明确生命周期隔离”。
