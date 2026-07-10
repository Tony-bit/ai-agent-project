# 统一会话记忆与上下文方案

日期：2026-07-07
状态：草案

## 背景

当前会话记忆有两套并行机制。

第一套是 `MessageChatMemoryAdvisor`。

- 通过数据库 `ai_client_advisor.advisor_type = ChatMemory` 配置，例如 advisor `4001`。
- 当前由 `AiClientAdvisorTypeEnumVO.CHAT_MEMORY` 创建。
- 使用 `MessageWindowChatMemory.builder().build()`。
- 因为没有显式传入 `ChatMemoryRepository`，Spring AI 默认使用 `InMemoryChatMemoryRepository`。
- 底层是 JVM 内存，类似 `ConcurrentHashMap`。
- 作用是给 ChatClient 自动注入运行时上下文窗口。

第二套是 `ChatMemoryPersistenceService`。

- 当前被部分领域节点和 HTTP 接口直接使用。
- 通过 `getConversationHistory(sessionId)` 读取历史。
- 读取路径是先 Redis，Redis miss 或过期后查 MySQL，然后回填 Redis。
- 通过 `persistConversation(...)` 持久化完整会话记录。
- 作用是保存原始会话，并提供跨请求、跨重启的恢复能力。

问题是这两套记忆没有打通。旧会话历史可以从 MySQL 展示，也可以被业务节点读取，但 `MessageChatMemoryAdvisor` 在 JVM 内存丢失后，无法从 Redis/MySQL 恢复历史。

同时，业务节点直接调用 `ChatMemoryPersistenceService.getConversationHistory(...)` 并拼上下文，会让上下文构建逻辑散落在多个节点里，不利于 SOLID，也不利于后续统一窗口、压缩、缓存和 token 策略。

## 问题

当用户聊了一会儿后断开，过一段时间重新打开旧会话，如果运行时内存和 Redis 都已经没有历史：

1. 页面历史仍然可以从 MySQL 加载。
2. `ChatMemoryPersistenceService` 可以从 MySQL 恢复历史，并回填 Redis。
3. `MessageChatMemoryAdvisor` 只能看自己的 JVM 内存，无法从 Redis/MySQL 恢复。

结果是：配置了 `4001` 的 client 会从新消息开始重新累加运行时记忆，而不是自然接上旧会话上下文。

另一个问题是：路由、拆分、补槽等节点如果手动读取历史，而对应 ChatClient 又配置了 `4001`，同一份历史可能被重复注入 prompt。

## 目标

建立一个统一的会话记忆与上下文层，让所有历史读取、窗口控制、缓存回填、压缩摘要和上下文组装都收敛到统一组件中。

目标架构：

```text
ConversationMemoryService
  -> L1 Caffeine 运行时缓存
  -> L2 Redis 热缓存
  -> L3 MySQL 原始会话存储
  -> 压缩摘要运行时缓存/读取

ConversationContextProvider
  -> 面向不同场景输出上下文
  -> routing context
  -> chat context
  -> compression context

ConversationContextAdvisor
  -> 作为 4001 / ChatMemory 的新实现
  -> 读取 advisor params 中的 conversationId 和 scene
  -> 调用 ConversationContextProvider 输出场景化上下文
  -> 负责把上下文注入 ChatClient prompt

SpringAiChatMemoryRepository
  -> 适配 Spring AI ChatMemoryRepository
  -> 只负责 Message 存取与转换
  -> 底层调用 ConversationMemoryService
```

改造后：

- `4001` 仍然是 ChatClient 自动上下文注入开关。
- `4001` 的底层不再是 Spring AI 默认内存，而是统一记忆链路和场景化上下文注入能力。
- 业务节点不再直接依赖 `ChatMemoryPersistenceService`。
- 业务节点如果需要历史上下文，通过 `ConversationContextProvider` 获取。
- 持久化写入仍然收敛在统一记忆服务内，不让 advisor 回调直接重复写 MySQL。

## 非目标

- 不移除 advisor `4001`。
- 不改变 `ai_client_advisor` 的数据库含义。
- 不要求第一阶段删除所有旧类名，可以先适配、再重构命名。
- 不让 Spring AI advisor 回调直接重复写 MySQL。
- 不把 Mem0 放进短期上下文读取链路。

## 核心组件

### 1. `ConversationMemoryService`

这是统一记忆服务，负责底层读写与缓存策略。

职责：

- 从 L1 Caffeine 读取最近原始消息。
- L1 miss 后读取 Redis。
- Redis miss 后读取 MySQL。
- MySQL 命中后回填 Redis 和 L1。
- 统一限制最近原始消息窗口为 20 条。
- 负责最终持久化写入 MySQL。
- 写入 MySQL 成功后，以 MySQL 为准重建最近窗口，再刷新 Redis 和 L1。
- MySQL durable write 成功后，登记或发布长期记忆同步任务，例如 Mem0；不在 `saveTurn(...)` 内同步调用 Mem0 API。
- 提供压缩摘要读取/写入能力，摘要只放 L1/Redis，不写 MySQL。

建议接口：

```java
public interface ConversationMemoryService {

    ConversationMemorySnapshot loadSnapshot(String sessionId, ConversationMemoryOptions options);

    void saveTurn(ConversationTurn turn);

    void refreshRuntimeCache(String sessionId, List<ChatMessageEntity> recentMessages);

    void clearRuntimeMemory(String sessionId);
}
```

`ChatMemoryPersistenceService` 不建议继续作为业务节点直接依赖。可以有两种处理方式：

1. 第一阶段保留类名，但内部委托给 `ConversationMemoryService`，兼容现有接口。
2. 后续逐步把调用方迁移到 `ConversationMemoryService` / `ConversationContextProvider`，再考虑删除或重命名。

### 2. `ConversationContextProvider`

这是面向业务场景的上下文提供器，负责把统一记忆转换成不同节点需要的上下文形态。

职责：

- 不暴露 Redis/MySQL/Caffeine 细节。
- 不让业务节点自己拼历史。
- 根据场景选择是否带摘要、带多少最近消息、过滤哪些 role。
- 控制 token 和窗口策略。

建议接口：

```java
public interface ConversationContextProvider {

    RoutingConversationContext getRoutingContext(String sessionId);

    ChatConversationContext getChatContext(String sessionId);

    CompressionConversationContext getCompressionContext(String sessionId);
}
```

示例：

```java
RoutingConversationContext context =
        conversationContextProvider.getRoutingContext(sessionId);
```

业务节点只表达“我要路由上下文”，不再关心历史从哪里来、取多少条、是否需要摘要。

### 3. `SpringAiChatMemoryRepository`

这是 Spring AI 仓储适配器，实现：

```java
org.springframework.ai.chat.memory.ChatMemoryRepository
```

建议类名：

```java
SpringAiConversationMemoryRepository
```

职责：

- 给普通 chat memory 窗口读写使用。
- 将 Spring AI 的 `Message` 与项目内 `ChatMessageEntity` 互转。
- `findByConversationId(...)` 底层调用 `ConversationMemoryService`。
- `saveAll(...)` 只刷新运行时状态和 Redis，不直接写 MySQL；写入内容必须标记为 runtime/non-durable。
- `deleteByConversationId(...)` 只清 L1/Redis，不删除 MySQL 历史。
- 不读取 advisor params。
- 不判断 `conversation_context_scene`。
- 不决定路由、拆分、补槽、普通聊天等场景的 prompt 形态。

读取链路：

```text
MessageWindowChatMemory
  -> SpringAiConversationMemoryRepository.findByConversationId(sessionId)
    -> ConversationMemoryService.loadSnapshot(...)
      -> L1
      -> Redis
      -> MySQL
```

写入链路：

```text
SpringAiConversationMemoryRepository.saveAll(sessionId, messages)
  -> 更新 L1
  -> 刷新 Redis runtime window，并标记 source=advisor_runtime / durable=false
  -> 不写 MySQL
```

MySQL 仍由完整回合结束后的 durable write 负责：

```text
RootNode -> ConversationMemoryService.saveTurn(...)
```

### 4. 运行时缓存一致性状态机

为了避免 “Redis 有、MySQL 没有” 被误认为事实源，运行时缓存需要显式区分消息状态。

状态定义：

```text
inflight：
  本轮请求已经进入模型调用流程，可能只有用户输入或临时上下文。
  可以存在于 L1，不建议写入 Redis。

completed：
  模型已经成功返回，Advisor 已经把本轮 user/assistant 写入 L1/Redis runtime window。
  这部分内容可以用于短时间内的连续追问，但还不是事实源。

durable：
  ConversationMemoryService.saveTurn(...) 已经成功写入 MySQL。
  这是事实源状态。
```

Redis/L1 中的 runtime window 需要带元数据：

```text
sessionId
runtimeVersion
durableVersion
source = advisor_runtime | durable_rebuild
durable = true | false
recentMessages
summary
updatedAt
ttl
```

读取规则：

```text
如果 L1/Redis 命中 durable=true 且 durableVersion 不落后：
  可以直接作为运行时上下文。

如果 L1/Redis 命中 durable=false：
  只能作为短期连续对话的 runtime context。
  不能把它当作 MySQL 事实源。
  如果需要展示历史、同步 Mem0、重新生成摘要事实范围，必须以 MySQL 为准。

如果发现 Redis/L1 比 MySQL 新：
  允许本次继续用 runtime context，避免用户刚收到回复后立刻追问丢上下文。
  同时记录 warning 或指标，等待 durable write 补齐。
```

写入规则：

```text
Advisor saveAll 成功：
  写 L1/Redis runtime window。
  标记 durable=false，source=advisor_runtime。
  不写 MySQL。

ConversationMemoryService.saveTurn 成功：
  写 MySQL。
  以 MySQL 最新原始消息为准重建最近 20 条窗口。
  重新生成或复用摘要。
  覆盖 L1/Redis runtime window。
  标记 durable=true，source=durable_rebuild。

ConversationMemoryService.saveTurn 失败：
  不把 runtime window 升级为 durable。
  L1/Redis 可以保留短 TTL 的 completed runtime context。
  Redis 过期后，这轮未 durable 的消息会自然消失。
  需要记录 error，并由业务侧决定是否重试 durable write。
```

这条规则的核心是：

```text
Redis/Caffeine 可以比 MySQL 新，但只能代表短期运行时视图。
MySQL 永远是事实源。
saveTurn 成功后必须用 MySQL 重建 Redis/Caffeine，而不是反过来用 Redis 覆盖 MySQL。
```

### 5. `ConversationContextAdvisor`

这是 `4001 / ChatMemory` 更合适的新实现位置，负责承接当前 `AiClientAdvisorTypeEnumVO.CHAT_MEMORY` 创建 advisor 的职责。

原因：

```text
当前 ChatMemory 是在 AiClientAdvisorTypeEnumVO.CHAT_MEMORY 中创建的。
如果只替换 ChatMemoryRepository，repository 通常只能看到 conversationId 和 Message。
repository 不适合读取 advisor params，也不适合决定路由/拆分/补槽/普通聊天的 prompt 形态。
场景化上下文注入需要放在 Advisor 或 ChatMemory 适配层，其中 Advisor 更自然。
```

建议优先实现自定义 Advisor，例如：

```java
public class ConversationContextAdvisor implements Advisor {

    private final ConversationContextProvider conversationContextProvider;
    private final ChatMemory chatMemory;

    // 根据 advisor params 中的 conversationId 和 conversation_context_scene 注入上下文
}
```

职责：

- 读取 `CHAT_MEMORY_CONVERSATION_ID_KEY`。
- 读取 `conversation_context_scene`，默认值为 `chat`。
- 根据 scene 调用 `ConversationContextProvider`：
  - `routing -> getRoutingContext(...)`
  - `decomposition -> getDecompositionContext(...)`
  - `slot -> getSlotContext(...)`
  - `chat -> getChatContext(...)`
- 将 provider 输出的上下文转换为 ChatClient prompt 可消费的消息或文本。
- 普通聊天场景可以复用 `MessageWindowChatMemory + SpringAiConversationMemoryRepository` 的读写窗口能力。
- 不直接写 MySQL，最终持久化仍由 `ConversationMemoryService.saveTurn(...)` 负责。

`AiClientAdvisorTypeEnumVO.CHAT_MEMORY` 的目标变化：

```text
当前：
  MessageChatMemoryAdvisor
    -> MessageWindowChatMemory
      -> 默认 InMemoryChatMemoryRepository

目标：
  ConversationContextAdvisor
    -> ConversationContextProvider
    -> MessageWindowChatMemory
      -> SpringAiConversationMemoryRepository
```

如果第一阶段不想完全替换 `MessageChatMemoryAdvisor`，也可以先实现一个组合式 Advisor：

```text
ConversationContextAdvisor
  -> chat 场景委托 MessageChatMemoryAdvisor
  -> routing/decomposition/slot 场景走 ConversationContextProvider
```

但长期边界仍应保持：场景选择在 Advisor 层，仓储层只做消息存取。

## 当前显式上下文注入如何改

当前部分节点会直接调用：

```java
chatMemoryPersistenceService.getConversationHistory(sessionId)
```

然后自己拼接历史。这类逻辑要迁移为：

```java
conversationContextProvider.getXxxContext(sessionId)
```

也就是说，“显式调用注入上下文”不是消失，而是从“节点自己查存储、自己拼 prompt”变成“节点向上下文提供器声明自己需要什么场景的上下文”。

### 路由场景

当前：

```text
IntentRoutingNode
  -> ChatMemoryPersistenceService.getConversationHistory(sessionId)
  -> map role/content
  -> routeUnified(message, historyMessages, config)
```

目标：

```text
IntentRoutingNode
  -> ConversationContextProvider.getRoutingContext(sessionId)
  -> routeUnified(message, routingContext, config)
```

`IntentRoutingNode` 不再关心：

- Redis 是否命中。
- MySQL 是否回源。
- 是否需要摘要。
- 保留多少条最近消息。
- role 怎么过滤。

### 普通对话/最终回答场景

普通 ChatClient 调用不建议手动拼历史，而是继续使用 `4001`：

```text
ChatClient
  -> advisor_id=4001
  -> ConversationContextAdvisor
  -> ConversationContextProvider
  -> ConversationMemoryService
```

节点只需要继续传：

```java
.advisors(a -> a.param(CHAT_MEMORY_CONVERSATION_ID_KEY, request.getSessionId()))
```

上下文由 advisor 自动注入。

为了兼容当前工程里大量 client/LLM，`4001` 不应按节点类型大面积移除。更稳的策略是保留 `4001` 作为 ChatClient 记忆注入的统一开关，但把 `4001` 的实现从默认 `MessageChatMemoryAdvisor` 替换为自定义 `ConversationContextAdvisor`。

`ConversationContextAdvisor` 读取 advisor 参数声明当前上下文场景：

```java
.advisors(a -> a
        .param(CHAT_MEMORY_CONVERSATION_ID_KEY, request.getSessionId())
        .param("conversation_context_scene", "routing"))
```

`ConversationContextAdvisor` 根据 `conversation_context_scene` 调用 `ConversationContextProvider`，输出对应场景的上下文。这样路由、拆分、补槽、普通对话都可以继续依赖同一个 advisor 配置，不需要为了避免重复注入而大面积修改数据库配置。

重要边界：

```text
Advisor 层：
  看 advisor params
  判断 scene
  决定注入哪种上下文形态

ConversationContextProvider：
  组装 routing/chat/decomposition/slot/compression 等业务上下文

SpringAiConversationMemoryRepository：
  只按 conversationId 读写 Message
  不看 scene
  不拼 prompt
```

### 压缩场景

压缩节点不应依赖 advisor 自动上下文。它需要的是压缩专用上下文：

```java
CompressionConversationContext context =
        conversationContextProvider.getCompressionContext(sessionId);
```

压缩上下文可以包含：

- 全量原始消息引用，来自 MySQL。
- 最近 20 条原始消息。
- 已有摘要。
- 待压缩的旧消息范围。

## 窗口与缓存策略

统一最近原始消息窗口为 20 条。

```yaml
chat:
  memory:
    runtime-window-size: 20
    redis-cache-size: 20
    compression-trigger-size: 20
    local-cache-ttl-minutes: 60
    local-cache-max-sessions: 10000
```

语义：

```text
L1 Caffeine：每个 session 最近 20 条原始消息，60 分钟未访问过期，最多 10000 个 session。
Redis：每个 session 最近 20 条原始消息。
MySQL：保存全量原始消息。
压缩摘要：承接最新 20 条之前的旧历史，只缓存于 Caffeine/Redis。
```

L1 推荐使用 Caffeine：

```text
Caffeine.newBuilder()
  .maximumSize(localCacheMaxSessions)
  .expireAfterAccess(localCacheTtlMinutes, TimeUnit.MINUTES)
```

`advisor 4001.ext_param.maxMessages` 应从 `200` 调整为 `20`，或改为映射统一配置，避免数据库值和应用策略漂移。

## 压缩记忆策略

目标上下文形态：

```text
旧历史压缩摘要
+ 最近 20 条原始消息
```

推荐行为：

```text
如果总历史消息 <= 20:
  只注入最近原始消息。

如果总历史消息 > 20:
  对最新 20 条之前的旧历史使用压缩摘要。
  再注入最新 20 条原始消息。
```

如果：

```text
压缩摘要 + 最近 20 条原始消息
```

仍然超过 LLM token 阈值，则继续降级：

```text
最近 20 条
  -> 更少的最近原始消息
  -> 只保留压缩摘要
```

当前 `CompressionContextNode` 默认保留最近 2 轮：

```text
DEFAULT_KEEP_ROUNDS = 2
```

需要调整为和统一策略一致：

```text
keepRecentMessages = 20
```

注意单位：

```text
20 messages 大约等于 10 轮 user/assistant 对话。
```

摘要存储决策：

```text
压缩摘要只放 Caffeine 和 Redis。
不新增 MySQL 摘要表。
不写 ai_chat_session 摘要字段。
MySQL 只保存全量原始消息。
```

原因：

```text
摘要是运行时上下文优化结果，不作为长期事实源。
摘要丢失后，可以从 MySQL 全量原始消息重新生成。
避免摘要表版本、覆盖范围、失效策略带来的额外复杂度。
```

恢复链路：

```text
优先读取 Caffeine 中的 summary + recent messages。
Caffeine miss 后读取 Redis 中的 summary + recent messages。
Redis miss 后从 MySQL 读取全量原始消息。
如果历史超过 20 条，则重新生成旧历史摘要。
生成后回填 Caffeine 和 Redis。
```

## 删除语义

Spring AI 的 `ChatMemoryRepository.deleteByConversationId(...)` 定义为：

```text
清模型运行时上下文
清 L1 + Redis
不删除 MySQL
不等于前端删除会话
```

前端界面的“删除会话/清空会话”如果要删除用户历史，应走单独业务接口：

```text
删除 MySQL chat_session/chat_message
清 Redis
清 L1
必要时处理 Mem0 关联记忆
```

## Mem0 同步策略

Mem0 同步保持为显式/异步后续动作，不由 `ConversationMemoryService.saveTurn(...)` 直接内联调用 Mem0 API。

这里的 Mem0 同步特指：

```text
MySQL 原始会话记录
  -> ChatSessionMemorySyncService.syncSessionToMemory(...)
  -> Mem0RestClient.addMemory(...)
  -> Mem0 长期记忆
```

它不包括：

```text
Advisor runtime cache -> Mem0
Redis/Caffeine summary -> Mem0
Redis/Caffeine recent messages -> Mem0
```

当前已有触发入口需要保留：

```text
自动触发：
  SessionEndJudgementNode
    -> 关键词判断会话结束，或 LLM 判断 ended=true
    -> ISessionEndDetectionService.syncSessionToMemory(...)
    -> ChatSessionMemorySyncService.syncSessionToMemory(...)

手动触发：
  前端“同步记忆”按钮
    -> POST /api/v1/session/{sessionId}/sync-memory?userId=...
    -> ChatSessionController.syncSessionMemory(...)
    -> ChatSessionMemorySyncService.syncSessionToMemory(...)
```

也就是说，当前不是只有一种触发模式；自动结束判断和手动同步按钮都会进入同一个 Mem0 同步服务。后续实现统一会话记忆时，应保留这两个入口的兼容语义。

推荐边界：

```text
ConversationMemoryService.saveTurn(...)
  -> 写 MySQL 原始消息
  -> 以 MySQL 为准重建 Redis/L1 runtime window
  -> MySQL 事务成功后登记 Mem0 待同步任务或发布 domain event
  -> 返回主聊天链路

ChatSessionMemorySyncService / 异步消费者 / endSession
  -> 查询 MySQL 中 durable 且未同步 Mem0 的会话
  -> 构建 Mem0 messages
  -> 调用 mem0RestClient.addMemory(...)
  -> 成功后更新 add_memory 或等价同步标记
```

不选择在 `saveTurn(...)` 内联调用 Mem0 API 的原因：

```text
Mem0 是长期记忆，不属于短期模型上下文读取链路。
Mem0 调用可能慢、失败或限流，不应阻塞用户本轮聊天响应。
Mem0 同步必须基于 MySQL durable 数据，不能基于 Advisor runtime cache。
saveTurn 失败时不能触发 Mem0；saveTurn 成功但 Mem0 失败时，应允许后续重试。
```

因此 `saveTurn(...)` 的责任是建立可靠同步边界，而不是执行长期记忆同步本身。

第一阶段可以保留现有 `endSession` / `ChatSessionMemorySyncService` 触发方式；后续如果要更自动化，可以在 `saveTurn(...)` 成功后写 outbox/event，由异步 worker 统一消费。无论采用哪种触发方式，Mem0 同步都必须从 MySQL 读取 durable 原始消息。

## 重复注入治理

统一仓储接入后，`4001` 能自己恢复历史。

因此需要审计所有上下文注入点，分成三类：

```text
A 类：通过 ConversationContextProvider 获取业务上下文
B 类：通过 4001 Advisor 自动注入 ChatClient 上下文
C 类：A + B 同时存在，存在重复注入风险
```

治理规则：

```text
路由/拆分/补槽：
  继续允许配置 4001
  通过 advisor 参数声明 routing/decomposition/slot 场景
  由 ConversationContextAdvisor 注入场景化上下文
  不再由节点手动读取 ChatMemoryPersistenceService 并拼接同一份历史

普通对话/最终回答/执行类 ChatClient：
  使用 4001 / ConversationContextAdvisor
  不手动拼同一份历史

压缩：
  使用 ConversationContextProvider.getCompressionContext
  不把压缩摘要生成逻辑塞进普通对话 prompt
```

## 异常处理

### L1 miss，Redis hit

```text
如果 Redis runtime window 标记 durable=true：
  返回 Redis 历史
  回填 L1

如果 Redis runtime window 标记 durable=false：
  可以返回给短期连续对话使用
  回填 L1 时继续保留 durable=false
  不用于历史展示、Mem0 同步、摘要事实范围判断
```

### L1/Redis 命中但比 MySQL 新

```text
说明 Advisor runtime 已写入，但 durable write 可能尚未完成或失败
本次模型上下文可以继续使用 runtime window
记录 warning/metric
后续 saveTurn 成功后，以 MySQL 为准重建 L1/Redis
如果 saveTurn 最终失败，runtime window 按 TTL 自然过期
```

### L1 miss，Redis miss，MySQL hit

```text
返回 MySQL 历史
以 MySQL 为准重建 runtime window
标记 durable=true，source=durable_rebuild
回填 Redis
回填 L1
```

### Redis 异常

```text
降级查 MySQL
如果 L1 有可用历史，保留 L1
记录 warning
不阻断聊天请求
```

### MySQL 异常

```text
如果 L1 有历史，返回 L1
否则返回空历史
记录 error
除非调用方强依赖历史，否则不阻断聊天请求
```

### Advisor 保存异常

```text
模型响应已经成功时，不因为运行时记忆保存失败而让请求失败
记录 warning
最终 durable write 仍依赖 ConversationMemoryService.saveTurn
```

### durable write 失败

```text
ConversationMemoryService.saveTurn(...) 写 MySQL 失败时：
  不升级 L1/Redis 的 durable 标记
  不用 Redis/L1 反向覆盖 MySQL
  保留 completed runtime context 供短期连续追问使用
  记录 error，并交给业务侧或任务队列重试

重试成功后：
  以 MySQL 最新记录为准重建最近 20 条窗口
  覆盖 L1/Redis
  标记 durable=true，source=durable_rebuild
```

## 迁移计划

### 阶段 1：抽出统一记忆服务

- 新增 `ConversationMemoryService`。
- 让现有 `ChatMemoryPersistenceService` 先委托给 `ConversationMemoryService`。
- 保持现有接口兼容，降低一次性改动风险。

### 阶段 2：新增上下文提供器

- 新增 `ConversationContextProvider`。
- 提供 `getRoutingContext(...)`、`getChatContext(...)`、`getCompressionContext(...)`。
- 将路由、拆分、补槽、压缩中的直接历史读取迁移到 provider。

### 阶段 3：接入 Spring AI 仓储

- 新增 `SpringAiConversationMemoryRepository`。
- 实现 Spring AI `ChatMemoryRepository`。
- `MessageWindowChatMemory` 创建时传入该 repository。
- 验证 `4001` 不再使用默认 `InMemoryChatMemoryRepository`。

### 阶段 4：替换 4001 Advisor 实现

- 新增 `ConversationContextAdvisor`。
- 在 `AiClientAdvisorTypeEnumVO.CHAT_MEMORY` 中创建 `ConversationContextAdvisor`，不再直接返回默认 `MessageChatMemoryAdvisor`。
- `ConversationContextAdvisor` 读取 `CHAT_MEMORY_CONVERSATION_ID_KEY` 和 `conversation_context_scene`。
- `conversation_context_scene` 缺省时按 `chat` 处理，保证未改造 client 兼容。
- `chat` 场景可以复用 `MessageWindowChatMemory + SpringAiConversationMemoryRepository`。
- `routing/decomposition/slot` 场景通过 `ConversationContextProvider` 输出更合适的上下文形态。

### 阶段 5：对齐窗口与压缩

- 将 `4001.maxMessages` 从 `200` 调整为 `20`。
- Redis 最近原始消息缓存对齐为 `20`。
- L1 Caffeine 对齐为 `20`。
- 压缩边界对齐为最新 `20` 条原始消息。

### 阶段 6：重复注入审计与场景化改造

- 扫描所有 `getConversationHistory(...)` 调用。
- 扫描所有 `CHAT_MEMORY_CONVERSATION_ID_KEY` advisor 参数。
- 保留 `4001` 作为 ChatClient 统一记忆入口。
- 为路由、拆分、补槽、普通对话等场景补充 `conversation_context_scene` 参数。
- 将节点中手动拼接历史的逻辑迁移到 `ConversationContextProvider` 和 `ConversationContextAdvisor`。
- 避免同一个 prompt 注入两份相同历史。

## 测试计划

### 单元测试

1. `ConversationMemoryService` 在 L1 命中时直接返回。
2. L1 miss、Redis hit 时返回 Redis 并回填 L1。
3. Redis miss、MySQL hit 时返回 MySQL 并回填 Redis/L1。
4. `SpringAiConversationMemoryRepository.findByConversationId(...)` 能从统一记忆链路恢复历史。
5. `saveAll(...)` 更新 L1/Redis，但不写 MySQL。
6. `deleteByConversationId(...)` 清理 L1/Redis，但不删除 MySQL。
7. 超过 20 条原始消息时，只保留最近 20 条进入运行时窗口。
8. `ConversationContextProvider.getRoutingContext(...)` 不暴露存储细节。
9. `SpringAiConversationMemoryRepository` 不依赖 advisor params，不判断 `conversation_context_scene`。
10. `ConversationContextAdvisor` 能根据 `conversation_context_scene` 选择 routing/decomposition/slot/chat 上下文。
11. `conversation_context_scene` 缺省时按 `chat` 兼容处理。
12. `saveAll(...)` 写入的 runtime window 标记为 `durable=false`，不会被当作事实源。
13. `saveTurn(...)` 成功后以 MySQL 最新记录为准重建 Redis/L1，标记为 `durable=true`。
14. 当 Redis/L1 比 MySQL 新时，读取逻辑允许短期上下文使用，但不会用于历史展示、Mem0 同步或摘要事实范围。

### 集成测试

1. 准备一个只有 MySQL 有历史的旧 session。
2. 模拟 advisor 使用 `conversationId = sessionId` 读取历史。
3. 验证 advisor 能拿到旧历史。
4. 验证 Redis 被回填。
5. 验证下一次读取命中 L1。
6. 验证路由节点通过 `ConversationContextProvider` 获取历史，而不是直接访问 `ChatMemoryPersistenceService`。
7. 验证配置 `4001` 的 ChatClient 在不改数据库 advisor 配置的情况下，能通过 `conversation_context_scene` 注入不同场景上下文。
8. 模拟 Advisor 写入 Redis 成功、`saveTurn(...)` 写 MySQL 失败，验证 Redis/L1 保持 `durable=false`，并在 TTL 后不再恢复为事实历史。
9. 模拟 `saveTurn(...)` 重试成功，验证 Redis/L1 被 MySQL 记录重建并切换为 `durable=true`。

### 回归测试

1. 现有 `ChatMemoryPersistenceServiceIntegrationTest` 保持通过或迁移到新服务后等价通过。
2. 现有 routing 相关测试保持通过。
3. 配置 `advisor_id=4001` 的 ChatClient 仍然遵守 `maxMessages=20`。
4. 压缩触发后仍能返回摘要 + 最近消息，且不超过 token 阈值。
5. durable write 失败不会导致 Redis/L1 反向覆盖 MySQL。

## 验收标准

- 旧会话重新打开后，即使 JVM 内存和 Redis 都为空，只要 MySQL 有历史，`4001` 也能恢复模型上下文。
- `MessageChatMemoryAdvisor` 不再使用 Spring AI 默认的 `InMemoryChatMemoryRepository`。
- `4001` 的实现由自定义 `ConversationContextAdvisor` 承接，或者由等价的自定义 ChatMemory 适配层承接；不能把场景判断放进 `SpringAiConversationMemoryRepository`。
- `SpringAiConversationMemoryRepository` 只负责按 conversationId 存取和转换 Message。
- 业务节点不再直接调用 `ChatMemoryPersistenceService.getConversationHistory(...)` 拼上下文。
- 业务节点通过 `ConversationContextProvider` 获取场景化上下文。
- Advisor runtime 保存不会重复写 MySQL。
- Advisor runtime 写入的 Redis/L1 必须携带 `durable=false` 或等价状态，不能被当作事实源。
- `ConversationMemoryService.saveTurn(...)` 成功后，必须以 MySQL 为准重建 Redis/L1 最近窗口，并标记为 `durable=true` 或等价状态。
- 当 Redis/L1 比 MySQL 新时，系统允许短期连续对话使用 runtime context，但历史展示、Mem0 同步、摘要事实范围必须以 MySQL 为准。
- MySQL 恢复后会回填 Redis。
- Redis 或 MySQL 恢复后会回填 L1。
- Advisor 最多注入最近 20 条原始消息。
- 旧历史通过压缩摘要承接。
- 现有会话历史查询接口继续可用。

## 已对齐决策

- Mem0 同步保持显式/异步后续动作。
- `ConversationMemoryService.saveTurn(...)` 不内联调用 Mem0 API。
- 当前自动触发入口保留：`SessionEndJudgementNode` 判断会话结束后调用 `syncSessionToMemory(...)`。
- 当前手动触发入口保留：前端“同步记忆”按钮调用 `/api/v1/session/{sessionId}/sync-memory`。
- 两个入口最终都进入 `ChatSessionMemorySyncService.syncSessionToMemory(...)`。
- 第一阶段不要求新增 `saveTurn(...) -> Mem0` 触发；`saveTurn(...)` 成功后可以暂时只保证 MySQL durable 数据可被现有入口同步。
- 后续如果要自动化，可以在 `saveTurn(...)` 成功后登记 outbox/event，由异步 worker 消费，但仍不能内联调用 Mem0 API。
- Mem0 同步只读取 MySQL durable 原始消息，不读取 Advisor runtime cache。
