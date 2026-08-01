# Story 3：SSE Timeout 接入完整 Query 重试设计

## 1. 文档状态

- 状态：实现与自动化回归已完成，等待人工 DML 与运行时验收。
- 日期：2026-07-31。
- 前置 Story：`docs/superpowers/plans/2026-07-30-story-1-stream-http-attempt-retry.md`。
- 前置 Story：`docs/superpowers/plans/2026-07-30-story-2-sse-chunk-timeout-design.md`。
- 配套测试：`docs/superpowers/test/2026-07-31-story-3-sse-timeout-query-retry-test.md`。

## 2. 背景

Story 1 已将 `RetryChatModel` 建设为唯一应用层 stream retry owner。一次 query attempt 对应一次完整的 `delegate.stream(entryPrompt)` 订阅，其中可以包含 Spring AI 工具递归产生的多轮 HTTP/SSE 请求。attempt 成功前，全部 `ChatResponse` 只在后端瞬时缓存；attempt 失败时整轮丢弃，从入口 `Prompt` 重新执行。

Story 2 将 timeout 检测下沉到真实传输活动位置，并定义三类结构化异常：

- `FirstStreamChunkTimeoutException`：当前 HTTP/SSE 请求在 45 秒内未返回 headers，或按时返回 2xx streaming headers 但未在同一窗口内收到首个 `DataBuffer`。
- `StreamChunkIdleTimeoutException`：当前 HTTP/SSE 请求已收到至少一个 Chunk，随后 90 秒没有新 Chunk。
- `LlmQueryAttemptTimeoutException`：一次完整 query attempt 达到 150 秒绝对上限。

Story 2 只检测、取消并传播异常，不执行 retry。Story 3 负责把前两类 SSE timeout 精确接入 Story 1 已有的完整 query retry 基础设施。

## 3. 目标与非目标

### 3.1 目标

- 在配置显式开启时，让 45 秒首 Chunk timeout 和 90 秒 Chunk idle timeout 触发完整 query retry。
- 复用 Story 1 的 `ordinaryRetriesRemaining`、`maxAttempts`、backoff、attempt 原子隔离和取消能力。
- 失败 attempt 的文本、metadata、reasoning 和 tool-call delta 全部丢弃。
- retry 从当前入口 `Prompt` 重新调用 `delegate.stream()`，不拼接失败 attempt 的任何输出。
- 保留最后一次原始 timeout subtype 和 cause chain。
- 每个失败 attempt 通过结构化日志与指标留痕，但不保留历史响应或异常集合。
- 保持同步 `call()`、普通 429/5xx/传输错误重试和上下文压缩语义不变。

### 3.2 非目标

- 不重放 Spring AI 内部当前第 N 轮 HTTP request。
- 不实现 SSE 断点续传、`Last-Event-ID`、Chunk 偏移恢复或部分响应拼接。
- 不修改、继承或 fork `OpenAiApi`、`OpenAiChatModel`。
- 不在 WebClient filter 中调用 `retryWhen` 或重新执行 `next.exchange(request)`。
- 不新增 timeout 专用 attempt 预算、backoff 或 retry owner。
- 不保证工具 exactly-once，不建设工具幂等、补偿或结果持久化框架。
- 不向 `RetryChatModel` 下传 `NodeExecutionScope`、node deadline 或 run deadline。
- 不把 decode error、empty complete、缺失 `[DONE]`、工具错误或业务错误扩展为可重试。

## 4. 已确认决策

| 编号 | 决策 | 说明 |
|---|---|---|
| D-001 | retry 单位是完整 LLM query attempt | timeout 后从入口 `Prompt` 重启，不恢复内部 HTTP 轮次 |
| D-002 | 只放行两类 SSE timeout | `FirstStreamChunkTimeoutException` 与 `StreamChunkIdleTimeoutException` |
| D-003 | 150 秒 attempt timeout 不重试 | `LlmQueryAttemptTimeoutException` 表示本 attempt 绝对预算耗尽 |
| D-004 | 复用 Story 1 ordinary attempt 预算 | 不创建 timeout 专用次数 |
| D-005 | 复用 Story 1 backoff | 不立即重试，不重置混合错误序列的退避状态 |
| D-006 | 新增模型级功能开关 | `RetryConfig.retryOnStreamTimeout`，默认 `false` |
| D-007 | 工具采用 at-least-once | 工具后的第二轮 SSE timeout 仍重启完整 query，工具可能重复执行 |
| D-008 | 传播最后一次原始异常 | 不包装通用 retry exhausted 异常，不挂载历史响应 |
| D-009 | node/run 继续由外层取消 | Story 3 不主动读取父级剩余时间 |
| D-010 | 先终止旧 attempt，再启动新 attempt | 不允许 subscription、HTTP body 或 backoff 状态跨 attempt 重叠 |
| D-011 | timeout 留痕但不留存内容 | 每次失败记录元数据，不记录 Prompt、正文或 tool 参数 |
| D-012 | 同步调用不变 | `RetryChatModel.call()` 和 `RetryStrategy` 不接入该开关 |
| D-013 | Stream backoff 增加固定窗口 jitter | Story 1 ordinary stream retry 与 Story 3 timeout retry 统一在基础退避上增加 `0~1000ms` 随机值 |
| D-014 | 不增加 attempt 缓存容量上限 | 接受单次输出最多数万 token 的内存风险，继续由 150 秒 attempt timeout 约束存活时间 |
| D-015 | 配置使用现有单一 `RetryConfig` | 新配置统一采用嵌套格式；不支持平铺 retry 字段与子配置对象混写 |
| D-016 | 同一错误只执行一个恢复动作 | hard exclusion 先传播；可靠 `1261` 只压缩并 retry；其后才判断 SSE timeout 和 ordinary retry |
| D-017 | Trading 并行任务必须真实可中断 | `AnalystCollectionStage` 不再依赖 `CompletableFuture.supplyAsync()` 的弱取消语义，改为持有 `ExecutorService.submit()` 返回的 `Future` 并执行 `cancel(true)` |
| D-018 | 客户端断开覆盖范围保持现状 | 公共 collector cancellation contract、并行 analyst 真实取消集成和 12 节点显式清单共同作为门禁；`IntentRoutingNode`、`GeneralChatNode` 不在本 Story 扩展 |

## 5. 总体架构

```text
配置装配链路
DB ai_client_model.ext_param
  -> AgentRepository 解析单一 RetryConfig / CompressionConfig / StreamingTimeoutConfig
  -> AiClientModelNode 构造 RetryChatModel
  -> 每次 stream subscription 创建独立 StreamState

运行时链路
delegate.stream(state.currentPrompt)
  -> Spring AI 工具递归可产生多轮 HTTP/SSE
  -> Story 2 WebClient watchdog 观察每轮真实传输活动
  -> FirstStreamChunkTimeoutException / StreamChunkIdleTimeoutException
  -> RetryChatModel 恢复编排
       1. cancellation / local fatal / attempt timeout -> PROPAGATE
       2. nonRetryableErrorCodes veto               -> PROPAGATE
       3. reliable 1261                             -> COMPRESS_AND_RETRY
       4. other definite non-retryable 4xx (excluding 1261) -> PROPAGATE
       5. allowed SSE timeout + feature flag        -> ORDINARY_RETRY
       6. Story 1 ordinary retryable error          -> ORDINARY_RETRY
       7. otherwise                                 -> PROPAGATE
  -> 每个错误只选择一个动作
       PROPAGATE
         -> 传播当前原始异常
       COMPRESS_AND_RETRY
         -> 消耗独立 compression budget
         -> 丢弃失败 attempt 缓存
         -> streamAttempt(compressed currentPrompt)
       ORDINARY_RETRY
         -> 消耗 ordinary retry credit
         -> base backoff + random(0~1000ms)
         -> 丢弃失败 attempt 缓存
         -> streamAttempt(state.currentPrompt)

外层取消链路
node/run/client cancel
  -> AnalystCollectionStage 持有的 Future.cancel(true)
  -> StreamingChatResponseCollector cancellation signal
  -> Reactor subscription cancel
  -> Story 2 exchange/body/watchdog 清理
  -> 禁止创建后续 query attempt
```

Story 3 不增加第二个 retry owner：完整 query 的恢复动作仍统一由 `RetryChatModel` 编排，`StreamQueryRetryClassifier` 只负责识别允许进入 ordinary retry 的错误事实。实现范围跨越 domain 的重试编排与配置契约、trading-domain 的真实取消通道，以及 infrastructure 的配置解析回归和人工 DML；collector、WebClient filter 与 Spring AI 内部工具递归都不拥有 retry 决策权。

## 6. 配置契约

### 6.1 `RetryConfig` 新字段

```java
@Builder.Default
private boolean retryOnStreamTimeout = false;
```

字段语义：

- `false`：Story 2 继续检测并中断 45/90 秒 timeout，但 timeout 直接传播，不创建下一 query attempt。
- `true`：满足全部 retry 条件时，两类 SSE timeout 进入 Story 1 完整 query retry。
- 该字段只控制 stream timeout retry，不影响同步 `call()`。
- 该字段不改变 timeout 时长、attempt 次数或 backoff 参数。
- 字段缺失时按 `false` 解析，兼容现有模型配置文本。

### 6.2 生效条件

只有下列条件同时成立，Story 3 才允许 retry：

```text
retryConfig.enabled == true
&& retryConfig.retryOnStreamTimeout == true
&& ordinaryRetriesRemaining > 0
&& error 是允许的 SSE timeout subtype
```

`maxAttempts` 包含首次调用。例如 `maxAttempts=3` 表示首次调用加最多两次 ordinary retry；SSE timeout 与普通错误共享这两次 credit。压缩后重新调用使用独立 compression budget，因此总 query subscription 可能超过 3，但不得超过 `maxModelCalls`。

downstream cancel 不是 Throwable，不进入 `StreamQueryRetryClassifier`。取消资格由 Reactor subscription 生命周期治理：已观察到 cancel 后不得订阅下一 attempt；若 cancel 与零退避 retry 同刻竞争且下一 attempt 已开始订阅，该 subscription 必须立即收到 cancel，且不得继续创建后续 attempt。

### 6.3 开关关系与配置格式

运行时只有一个 `AiClientModelVO.RetryConfig`。`enabled` 是总重试开关，`retryOnStreamTimeout` 是仅对两类结构化 SSE timeout 生效的子开关：

| `enabled` | `retryOnStreamTimeout` | 普通 stream 错误重试 | SSE timeout 重试 |
|---:|---:|---:|---:|
| `false` | `false` | 否 | 否 |
| `false` | `true` | 否 | 否 |
| `true` | `false` | 是 | 否 |
| `true` | `true` | 是 | 是 |

`retryOnStreamTimeout` 不控制 Story 2 是否检测或抛出 timeout。只有上游已经产生允许的结构化 SSE timeout 时，Story 3 才读取该字段决定是否创建下一完整 query attempt；上游未产生该异常时自然不触发，不增加模式冲突告警。

新增或同时包含其他子配置的模型配置统一使用嵌套格式：

```json
{
  "retryConfig": {
    "enabled": true,
    "maxAttempts": 3,
    "retryOnStreamTimeout": true
  },
  "compressionConfig": {},
  "streamingTimeout": {}
}
```

现有纯平铺 retry JSON 在不包含 `retryConfig`、`compressionConfig`、`streamingTimeout` 时继续按历史规则解析。本 Story 不修改 `AgentRepository` 以支持“平铺 retry 字段 + 嵌套子配置对象”的混合格式；一旦使用子配置对象，全部 retry 字段必须位于 `retryConfig` 内。

## 7. 错误判定矩阵

| 场景 | 结构化依据 | 是否重试 |
|---|---|---|
| 45 秒内未收到 headers | `FirstStreamChunkTimeoutException` | 开关开启且有 credit 时重试 |
| 2xx headers 已到，但同一 45 秒窗口无首 Chunk | `FirstStreamChunkTimeoutException` | 开关开启且有 credit 时重试 |
| 已收到 Chunk，随后 90 秒无新 Chunk | `StreamChunkIdleTimeoutException` | 开关开启且有 credit 时重试 |
| heartbeat/metadata/reasoning/部分文本后 90 秒 idle | cause chain 中存在 idle subtype | 开关开启且有 credit 时重试 |
| 部分 tool-call Chunk 后 90 秒 idle | cause chain 中存在 idle subtype | 开关开启且有 credit 时重试 |
| 工具已执行，第二轮 SSE timeout | 两类允许的 SSE subtype | 完整 query 重试，接受工具重复 |
| 150 秒 query attempt 上限 | `LlmQueryAttemptTimeoutException` | 不重试 |
| 30 秒 stall | 只有观测事件，无异常 | 不触发 retry |
| JDK connect timeout | `HttpConnectTimeoutException` | 不由 Story 3 重试 |
| 用户取消/客户端断开 | cancel 或 `ClientDisconnectedException` | 不重试 |
| node/run timeout | 外层 cancel/interrupt | 不重试 |
| 工具 timeout/工具异常 | `ToolExecutionException` 等 | 不重试 |
| SSE/JSON decode error | decode/codec 异常 | 不重试 |
| 可靠 context overflow | error code `1261` | 使用独立压缩预算，压缩成功后 retry |
| 同一 cause chain 同时含 `1261` 与 SSE timeout | 可靠 `1261` + 允许的 SSE subtype | 只压缩后 retry，不再扣 ordinary credit |
| 压缩失败或压缩预算耗尽 | `CompressionExhaustedException` 等 | 直接传播，不回退到 SSE timeout retry |
| 2xx empty complete/正常 EOF | Reactor complete | 不重试 |
| 非 2xx HTTP error | 真实 HTTP status | 保持 Story 1 ordinary classifier |
| connection reset/body I/O error | 普通传输异常 | 保持 Story 1 ordinary classifier |

不得通过异常 message 中出现 `timeout`、`idle` 或数字状态码推断 Story 3 retry。必须在有限 cause chain 中找到稳定的结构化 subtype。

## 8. 错误恢复顺序

`RetryChatModel` 必须按简单、互斥的顺序选择唯一恢复动作：

```text
if (isCancellationOrSafetyExclusion(error)) {
    propagate(error)
} else if (matchesNonRetryableErrorCodes(error)) {
    propagate(error)
} else if (isReliableContextOverflow1261(error)) {
    compressAndRetryOrPropagate(error)
} else if (isOtherDefiniteNonRetryable4xx(error)) {
    propagate(error)
} else if (isAllowedSseTimeout(error)) {
    retryWithOrdinaryCreditOrPropagate(error)
} else if (isOrdinaryRetryable(error)) {
    retryWithOrdinaryCreditOrPropagate(error)
} else {
    propagate(error)
}
```

`isCancellationOrSafetyExclusion` 包含 downstream/client/node cancellation、`LlmQueryAttemptTimeoutException`、工具错误、decode/codec/JSON 错误、业务校验错误、`HttpConnectTimeoutException`、其他非目标 timeout，以及明确的认证/权限错误 401/403。`nonRetryableErrorCodes` 是独立的一票否决；任一可靠 code 命中后直接传播，包括显式把 `1261` 加入 veto 的情况。

可靠 `1261` 是可恢复的特殊 4xx，必须先于“其他明确不可重试 4xx”判断。典型 `HTTP 400 + errorCode=1261` 进入压缩；`1261 + SSE timeout` 也只执行压缩动作，不扣 ordinary retry credit。压缩成功后使用压缩后的 `state.currentPrompt` 发起下一 model call；压缩失败或 compression budget 耗尽时直接传播压缩异常，不回退到 SSE retry。命中该分支后不得继续落入后续 4xx 或 SSE 分支。

只有前四类均未命中时，`StreamQueryRetryClassifier` 才判断两类允许的 SSE timeout；其余错误继续执行 Story 1 的真实 HTTP status、provider code 和普通传输错误分类。downstream cancel 和 node task cancellation 主要以无错误的 Reactive Streams cancel 形式出现，由 subscription 生命周期治理；若以结构化异常传播，则按 safety exclusion 处理。attempt timeout 与 SSE timeout 同时存在时，attempt timeout 优先。

## 9. Retry 预算与 backoff

Story 3 不引入第二套状态：

```text
ordinaryAttempts = clamp(RetryConfig.maxAttempts, 1, 10)
ordinaryRetriesRemaining = ordinaryAttempts - 1
maxCompressionAttempts = compressionEnabled
    ? clamp(CompressionConfig.maxCompressionAttempts, 1, 3)
    : 0
maxModelCalls = ordinaryAttempts + maxCompressionAttempts
```

45/90 秒 timeout 与 429、目标 5xx、connection reset 和 body I/O error 共用该预算。只有确定要调度下一 query attempt 时才扣减一个 credit。

`querySubscriptionNumber` 在每次实际调用 `delegate.stream()` 时增加，包括首次、ordinary/SSE retry 和压缩后 retry。compression budget 保持独立：`1261 + SSE timeout` 只增加 `compressionAttempts`，压缩成功后下一 subscription 增加 `querySubscriptionNumber`，但不增加 `ordinaryRetriesUsed`；同一个错误不得同时消耗两类预算。无压缩时 subscription 不超过 `ordinaryAttempts`；包含压缩时总 subscription 不超过 `maxModelCalls`。

`RetryChatModel.stream()` 中的 ordinary retry 共用同一基础 backoff 序列：

```text
baseDelay(1) = initialIntervalMs
baseDelay(n+1) = min(baseDelay(n) * multiplier, maxIntervalMs)
jitterMs(n) = random integer in [0, 1000]
actualDelay(n) = baseDelay(n) + jitterMs(n)
```

`maxIntervalMs` 继续限制基础退避，实际等待最多为 `maxIntervalMs + 1000ms`。jitter 每次 retry 重新生成，以毫秒为单位；测试通过可注入随机源固定取值，禁止依赖真实随机结果。该 jitter 同时作用于 Story 1 ordinary stream retry 与 Story 3 timeout retry，但不修改同步 `call()`/`RetryStrategy` 的等待语义。

混合错误不重置退避。例如 attempt 1 因 503 失败、attempt 2 因 90 秒 idle 失败时，后者使用第二级基础 backoff 并重新生成 jitter。Story 3 不解析 `Retry-After`，也不对已经等待 45/90 秒的 timeout 提供立即重试特例。Reactor backoff 保持可取消，不得使用 `Thread.sleep()`。

## 10. Query 重启与工具语义

timeout retry 始终执行：

```text
delegate.stream(state.currentPrompt)
```

外层 `RetryChatModel` 无法取得 `OpenAiChatModel.internalStream()` 在工具执行后通过 `conversationHistory()` 创建的内部第二轮 `Prompt`，因此不能只重放第二轮 HTTP request。

`state.currentPrompt` 表示当前 query 的入口状态：正常情况下是调用入口 `Prompt`；若之前因 context overflow 完成压缩，则是压缩后的入口 `Prompt`。SSE timeout retry 必须复用当前压缩状态，不回退到压缩前 Prompt，也不保留失败 attempt 内部工具递归产生的 conversation history。

工具已成功执行、第二轮 SSE timeout 时：

1. Story 2 取消第二轮 HTTP/SSE。
2. Story 1 丢弃整个失败 query attempt 的临时响应。
3. Story 3 消耗一个 ordinary retry credit 并执行 backoff。
4. 从当前 `state.currentPrompt` 重启完整 query。
5. Provider 可以再次生成 tool-call，工具 callback 可以再次执行。

该行为明确采用 at-least-once，不承诺 exactly-once。具有外部副作用的工具应由业务侧提供幂等或可重复执行语义；Story 3 只通过 feature flag、`maxAttempts` 和日志控制风险。

## 11. 取消、终止与资源时序

正确时序：

```text
Story 2 timeout
  -> 取消当前 exchange/body
  -> 清理 watchdog/stall timer
  -> 传播结构化异常
Story 1
  -> 清空失败 attempt 的 ChatResponse 缓存
  -> classifier 作出 retry decision
  -> 执行可取消 backoff
  -> 创建下一 query attempt
```

约束：

- 前一个 attempt 的 error 已向 retry 分支传播、临时内容已清空且 active subscription 已归零后，才能订阅下一个 attempt。
- 即使 `initialIntervalMs=0`，attempt N 的 `doFinally(ERROR)` 与 active=0 也必须早于 attempt N+1 的 subscribe；若 downstream cancel 与 attempt N+1 subscribe 同 tick 竞争，cancel 必须立即到达新 subscription。
- backoff 期间先观察到 downstream cancel、客户端断开或 node timeout 时，不得创建下一 attempt。
- timeout error 与 cancel 竞争时，允许的结果只有两种：cancel 先发生且没有下一订阅，或 retry subscription 已开始但随即被 cancel；两种结果都不得产生后续 attempt。
- Story 3 不直接管理 `DataBuffer`；exchange/body/timer 清理由 Story 2 负责。
- Story 3 不读取 `NodeExecutionScope.deadline()`。node 到期后依靠现有外层 cancellation 终止 backoff、active attempt 和底层 HTTP。
- 当前实现不能在 retry 前主动判断父级剩余时间是否足够；该优化留给独立 deadline scope Story。

Trading 并行 analyst 的取消链路是 Story 3 验收前置条件。`CompletableFuture.supplyAsync()` 返回对象的 `cancel(true)` 不能作为底层任务已收到 interrupt 的保证；`AnalystCollectionStage` 必须通过受管 `ExecutorService.submit()` 获取真实 `Future`，并在 node timeout、run timeout、客户端断开或父任务取消时调用 `Future.cancel(true)`。测试必须从 Trading 调度入口制造取消，最终断言 collector、Reactor subscription 和 HTTP exchange 全部终止，而不是只断言 future 状态变为 cancelled。

客户端断开范围保持现状，并采用分层门禁：

1. `StreamingChatResponseCollectorTest.should_cancel_upstream_and_discard_partial_content_when_request_is_cancelled()` 锁定公共 collector 的上游取消和部分结果丢弃契约。
2. `AnalystCollectionStageCancellationIntegrationTest` 从真实 Trading 调度入口锁定四个并行 analyst 的 `Future.cancel(true) -> collector -> Reactor -> HTTP` 取消链路。
3. 下列 12 个 Trading 节点作为显式审计清单，必须继续通过公共 `collectStreamingResponse()` 消费 LLM stream：

```text
FundamentalAnalystNode
TechnicalAnalystNode
SentimentAnalystNode
NewsAnalystNode
BullResearcherNode
BearResearcherNode
ResearchManagerNode
ConservativeRiskAnalystNode
NeutralRiskAnalystNode
AggressiveRiskAnalystNode
PortfolioManagerNode
RecommendationNode
```

不为 12 个节点分别复制完整 HTTP 集成测试。工作量较小的 `IntentRoutingNode`、`GeneralChatNode` 不追加 collector 改造，客户端中途断开后可能短暂继续执行的残余风险明确接受。

## 12. 结果与异常契约

- attempt `onComplete` 前，下游看不到任何当前 attempt 的 `ChatResponse`。
- timeout attempt 的文本、metadata、reasoning 和 tool-call delta 全部丢弃。
- 后续 attempt 成功时，下游只收到该成功 attempt。
- 全部 attempt 耗尽时，只传播最后一次原始终止异常。
- 最后一次为 idle timeout 时，保持 `StreamChunkIdleTimeoutException`；最后一次为首 Chunk timeout 时，保持 `FirstStreamChunkTimeoutException`。
- 不新增 `RetryExhaustedException`，不构建 composite error，不把历史异常全部挂为 suppressed。
- 前序失败只进入结构化日志、指标和 trace event。

## 13. 可观测性

每次 attempt 失败建议记录：

```text
logicalCallId
modelId
querySubscriptionNumber
maxModelCalls
timeoutType
configuredTimeoutMs
effectiveTimeoutMs
elapsedMs
observedChunkCount
partialContentLength
errorType
errorCode
retryDecision=RETRY|PROPAGATE
ordinaryRetriesUsed
ordinaryRetriesRemaining
compressionAttempts
baseBackoffMs
jitterMs
actualBackoffMs
```

约束：

- 不记录完整 `Prompt`、响应正文、reasoning、tool 参数、API Key 或用户敏感信息。
- `logicalCallId` 等高基数 ID 只进入日志/trace，不作为 Micrometer tag。
- 前序 attempt 每次记录一条失败决策；耗尽时增加一条最终汇总。
- Story 2 的 timeout detection 日志与 Story 3 的 retry decision 日志职责分离，通过同一 `logicalCallId` 关联。
- 不保留历史 attempt 响应或异常集合来实现日志。

Story 3 增加一个低基数决策计数器：

```text
llm_stream_timeout_retry_decisions_total
  timeoutType=FIRST_CHUNK|CHUNK_IDLE
  decision=SCHEDULED|DISABLED|EXHAUSTED|HARD_EXCLUDED
```

每个 timeout decision 恰好计数一次。日志负责关联具体 query，指标只观察总体开启、调度、耗尽和排除比例，不包含 `logicalCallId`、modelId 或其他高基数 tag。

## 14. 文件职责

| 文件 | Story 3 职责 |
|---|---|
| `AiClientModelVO.java` | 在 `RetryConfig` 新增 `retryOnStreamTimeout=false` 并说明字段语义 |
| `StreamQueryRetryClassifier.java` | 按优先级精确放行两类 SSE timeout；保持其他分类不变 |
| `RetryChatModel.java` | 复用现有 ordinary credit、attempt 丢弃与重启；stream 共享 backoff 增加 `0~1000ms` 可测试 jitter，不新增 timeout state |
| `AiClientModelNode.java` | 继续原样传递同一份 `RetryConfig`，兼容字段缺失 |
| `AnalystCollectionStage.java` | 并行 analyst 改为持有真实 `Future`，确保 timeout/cancel 通过 `cancel(true)` 到达工作线程 |
| Trading 取消链路集成测试 | 从真实调度入口验证 task interrupt、collector、Reactor 和 HTTP 的端到端取消 |
| Story 2 timeout 异常类 | 保持事实型异常，不实现 retry marker |

不得修改：

- `OpenAiApi` request replay；
- `OpenAiChatModel.internalStream()`；
- WebClient retry filter；
- `RetryChatModel.call()` 与同步 `RetryStrategy`；
- Story 2 的 45/30/90/150 秒 watchdog。

## 15. 兼容与发布

- `retryOnStreamTimeout` 使用 `@Builder.Default` 且默认 `false`；旧 JSON 字段缺失、现有 builder 未显式赋值时均保持关闭。
- `AgentRepository` 继续输出一个 `AiClientModelVO.RetryConfig`；新嵌套 JSON 必须正确绑定新字段，旧纯平铺 JSON 继续保持历史行为。
- `AiClientModelNode` 原样传递同一对象；retry disabled 时生成的有效配置必须保持 `retryOnStreamTimeout=false` 和 `maxAttempts=1`。
- `RetryStrategy` 与同步 `call()` 不读取新字段；普通同步错误分类、压缩和等待语义不变。
- `RetryChatModel.stream()` 的既有 ordinary error 只增加共享 jitter，不因新开关关闭而停用；`StreamQueryRetryClassifier` 仅在两类结构化 SSE timeout 分支读取新字段。
- 代码默认值保持 `false` 只用于旧配置、未显式配置的新模型和 builder 兼容；本次发布不采用灰度，代码与自动化测试通过后由用户执行 DML，一次性为四个目标模型开启。
- 关闭开关只关闭 Story 3；Story 2 timeout 检测和 Story 1 原有 ordinary retry 继续有效。
- 回滚不要求切换 Story 2 到 legacy timeout 模式。
- 上游未产生 Story 2 结构化 SSE timeout 时自然不进入 Story 3，不做配置冲突判断或专门告警。
- Spring AI 升级必须重跑工具后第二轮 timeout 集成测试，确认异常 subtype 仍能穿过内部递归。

### 15.1 数据库配置同步

人工执行 DML：`docs/dev-ops/mysql/sql/dml/005-sse-timeout-query-retry-config.sql`。

- 该文件不是 Flyway 自动迁移，不由应用启动或 Codex 执行；数据库写入由用户审核后手工执行。
- 同步范围为当前已有非空 retry 配置的 `model_id=2001/2003/2007/2009`，四条全部转换为统一嵌套结构并设置 `retryOnStreamTimeout=true`。
- `2003/2007` 原有 `compressionConfig` 必须完整保留；`2001/2009` 使用空 `compressionConfig` 和空 `streamingTimeout`，继续采用代码默认值。
- `2002/2004/2005/2006/2008` 的 `ext_param` 当前为空，本次不推断或创建 retry 参数。
- 执行顺序固定为：完成 Story 3 代码与自动化测试，执行 DML，重启应用或重新完成模型装配，再验证新逻辑。数据库更新不会自动改变已经注册的 `RetryChatModel` 实例。
- 执行前必须确认预检 `source_model_count=4`；执行后必须确认 `updated_model_count=4`、`retry_enabled_model_count=4`、`enabled_model_count=4`、`invalid_structure_count=0`，并人工核对最终 JSON。

## 16. 验收标准

| 编号 | 验收项 | 标准 |
|---|---|---|
| AC-001 | 功能开关 | 默认关闭；开启后只有两类 SSE timeout 获得 retry |
| AC-002 | Query 粒度 | 每次 timeout retry 都从当前 `state.currentPrompt` 重启完整 query，保留已完成的压缩状态 |
| AC-003 | 统一预算 | SSE timeout 与普通错误的 ordinary attempt 不超过 `maxAttempts`；包含压缩 retry 的总 subscription 不超过 `maxModelCalls` |
| AC-004 | 统一 backoff | stream 普通错误与 timeout 复用基础退避并分别增加 `0~1000ms` jitter，混合错误不重置 |
| AC-005 | 分类优先级 | cancel、attempt timeout 和其他 hard exclusion 不被 Story 3 翻转 |
| AC-006 | 结果隔离 | 失败 timeout attempt 的任何内容都不进入最终结果 |
| AC-007 | 工具语义 | 工具后第二轮 timeout 允许完整 query 重启，测试明示 at-least-once |
| AC-008 | 最终异常 | 耗尽后传播最后一次原始 timeout subtype |
| AC-009 | 取消传播 | active attempt 或 backoff 被取消后无下一订阅和迟到 HTTP |
| AC-010 | 资源顺序 | 前一 attempt 终止清理后才启动下一 attempt |
| AC-011 | 日志留痕 | 每次失败有结构化元数据，无 Prompt/正文/tool 参数 |
| AC-012 | 同步兼容 | `call()`、同步 retry 与压缩语义不变 |
| AC-013 | Story 隔离 | 无 HTTP request replay、第二套 retry owner 或 deadline 下传 |
| AC-014 | 配置兼容 | 旧字段缺失和旧 builder 默认关闭；标准嵌套 JSON 正确绑定；同步 `call()` 不受新字段与 stream jitter 影响 |
| AC-015 | 指标 | 每个 timeout decision 恰好增加一次低基数计数 |
| AC-016 | 唯一恢复动作 | safety exclusion、显式 veto、1261 压缩、其他 4xx、SSE retry、ordinary retry 按顺序互斥；一个错误只执行一个动作 |
| AC-017 | Trading 真实取消 | 并行 analyst 使用可中断 `Future`；node/run/client cancel 后 task、Reactor 与 HTTP 均终止 |
| AC-018 | Collector 覆盖门禁 | 公共 cancellation contract 通过；并行 analyst 集成通过；12 个 Trading 节点清单继续使用公共 collector |
| AC-019 | DML 人工验收 | 四个目标模型全部转换并开启，预检/更新/启用/结构计数符合约定，压缩配置保留，五个空配置模型未修改，重启/重新装配后生效 |

## 17. 风险记录

- 完整 query retry 可能重复执行已经成功的工具，属于明确接受的 at-least-once 风险。
- Provider 可能已经接收失败 attempt 的请求，因此即使下游未见结果，也可能重复计费或生成。
- node 临近 240 秒时仍可能启动一个无法完成的 attempt；Story 3 依赖外层 cancel，不主动读取剩余 deadline。
- 若 cancellation 没有到达实际 Reactor/HTTP subscription，可能产生迟到请求；必须通过集成测试锁定。
- `IntentRoutingNode`、`GeneralChatNode` 未扩展 collector；客户端断开后可能短暂继续执行，作为本 Story 明确接受的残余风险。
- `initialIntervalMs=0` 时，Reactor cancel 与 fallback 订阅可能同 tick 竞争；必须断言 cancel 后没有持续活动或后续 attempt。
- stream jitter 使实际等待最多比 `maxIntervalMs` 多 1000ms；必须记录基础值、随机值和实际值，并以可注入随机源保证测试确定性。
- 单 attempt 的 `ChatResponse` 原子缓存不增加 Chunk/字符硬上限；接受数万 token 输出带来的有限堆内存占用，继续依赖 150 秒 attempt timeout 和外层取消限制存活时间。
- 配置文本不支持平铺 retry 字段与嵌套子配置混写；运维新增 `compressionConfig` 或 `streamingTimeout` 时必须同步采用标准嵌套 `retryConfig`。
- Spring AI 或 WebClient 对异常的包装变化可能使 subtype 不可见；cause-chain 契约测试是升级门禁。
- 四个目标模型会由 DML 一次性开启，可能同步放大工具副作用和 Provider 请求数；执行后必须观察 retry success rate、请求数和工具重复执行日志。

## 18. 推荐结论

Story 3 采用最小增量方案：在唯一的 `RetryConfig` 增加默认关闭的 `retryOnStreamTimeout`，新配置统一使用嵌套格式。`RetryChatModel` 按 hard exclusion、可靠 `1261` 压缩、SSE timeout、ordinary error 的顺序选择唯一恢复动作；`StreamQueryRetryClassifier` 只负责精确识别 `FirstStreamChunkTimeoutException` 与 `StreamChunkIdleTimeoutException`。retry 继续以完整 query attempt 为单位，复用 Story 1 的 ordinary credit、压缩后的入口 Prompt、结果隔离和最后异常传播；stream 共享基础 backoff 增加 `0~1000ms` jitter，不影响同步 `call()`。Trading 并行 analyst 必须持有真实可中断 `Future`，以端到端集成测试锁定 node/run/client cancel 到 Reactor/HTTP 的传播。本 Story 不重放内部 HTTP request、不下传 node deadline、不增加 attempt 缓存容量限制，并明确接受工具 at-least-once 以及非 collector 节点的残余执行风险。

## 19. 开发执行计划

本节是本 design 的可执行实现计划，不再创建独立 implementation 文档。执行者应使用 `executing-plans` 按任务顺序实施；每完成一个任务，将对应 `status` 从 `append` 改为 `pass`，并执行该任务的定向测试后再进入下一项。

### 19.1 实施约束与文件清单

- 工作目录固定为仓库根目录 `D:\Code\ai-agent-study`。
- 先写失败测试，再做最小实现；不得先执行 DML 验证尚未完成的代码。
- 每次只暂存当前任务列出的文件，不得覆盖或提交工作区中的其他改动。
- `RetryChatModel` 仍是完整 query retry 的唯一 owner；不得在 `SseChunkTimeoutFilter`、`OpenAiApi`、`OpenAiChatModel` 或 collector 中增加 retry。
- 自动化测试使用短时长、虚拟时间、固定 jitter 或本地 HTTP server，不得真实等待 45/90/150 秒。

| 文件 | 操作 | 职责 |
|---|---|---|
| `AiClientModelVO.java` | 修改 | 增加默认关闭的 `retryOnStreamTimeout` |
| `StreamTimeoutType.java` | 创建 | 定义两类可恢复 SSE timeout 的稳定低基数类型 |
| `StreamQueryRetryClassifier.java` | 修改 | 暴露互斥判定所需的结构化事实 |
| `RetryChatModel.java` | 修改 | 编排唯一恢复动作、预算、终止屏障、jitter、日志和指标 |
| `StreamTimeoutRetryMetrics.java` | 创建 | 可选 Micrometer 决策计数器；无 registry 时为空操作 |
| `AiClientModelNode.java` | 修改 | 传入可选指标组件，保持同一 `RetryConfig` |
| `AnalystCollectionStage.java` | 修改 | 使用真实 `Future` 并在等待期感知 timeout/cancel |
| `OpenAiStreamTimeoutQueryRetryIntegrationTest.java` | 创建 | Story 2/3 真实 HTTP 与工具第二轮集成测试 |
| `AnalystCollectionStageCancellationIntegrationTest.java` | 创建 | Trading task 到 Reactor/HTTP 的真实取消测试 |
| `TradingCollectorCoverageTest.java` | 创建 | 12 个节点的公共 collector 静态门禁 |

### 任务 1：增加配置字段并锁定解析兼容

| 任务 | status |
|---|---|
| 任务 1：增加配置字段并锁定解析兼容 | pass |

**文件：**

- 修改：`ai-agent-study-domain/src/main/java/denny/ai/agent/domain/model/valobj/AiClientModelVO.java`
- 修改：`ai-agent-study-domain/src/test/java/denny/ai/agent/domain/service/armory/AiClientModelNodeRetryTest.java`
- 创建：`ai-agent-study-domain/src/test/java/denny/ai/agent/domain/model/valobj/AiClientModelVORetryConfigTest.java`
- 修改：`ai-agent-study-infrastructure/src/test/java/denny/ai/agent/infrastructure/adapter/repository/AgentRepositoryCompressionConfigTest.java`

- [ ] **步骤 1：先写默认值、装配和 JSON 兼容测试**

`AiClientModelVORetryConfigTest` 至少断言 builder 与无参构造均默认关闭：

```java
@Test
void should_default_stream_timeout_retry_to_false() {
    assertFalse(RetryConfig.builder().build().isRetryOnStreamTimeout());
    assertFalse(new RetryConfig().isRetryOnStreamTimeout());
}
```

在 `AgentRepositoryCompressionConfigTest` 增加三个断言：旧平铺 JSON 缺字段时为 `false`；标准嵌套 JSON 可绑定 `true`；模型 A 开启不影响模型 B 的默认值。在 `AiClientModelNodeRetryTest` 断言 disabled fallback 的 `retryOnStreamTimeout=false`、`maxAttempts=1`，并断言 enabled 配置对象以同一引用进入 `RetryChatModel`。

- [ ] **步骤 2：运行测试确认失败**

```powershell
mvn -pl ai-agent-study-domain,ai-agent-study-infrastructure -am "-Dtest=AiClientModelVORetryConfigTest,AiClientModelNodeRetryTest,AgentRepositoryCompressionConfigTest" "-Dsurefire.failIfNoSpecifiedTests=false" test
```

预期：FAIL，编译报告 `isRetryOnStreamTimeout()` 或 builder 的 `retryOnStreamTimeout(...)` 不存在。

- [ ] **步骤 3：增加唯一配置字段**

在 `AiClientModelVO.RetryConfig` 中加入：

```java
/**
 * 是否允许两类结构化 SSE timeout 消耗 ordinary credit，
 * 并从当前入口 Prompt 重启完整 stream query。
 * 不控制 timeout 检测，也不影响同步 call()。
 */
@Builder.Default
private boolean retryOnStreamTimeout = false;
```

`AgentRepository.parseRuntimeConfig()` 不增加第二个配置对象，也不支持平铺与嵌套混写；现有 Fastjson 绑定应直接读取新字段。只有测试发现绑定失败时，才修正现有 `retryConfig` 反序列化，不改变 composite 判定规则。

- [ ] **步骤 4：运行定向测试确认通过**

```powershell
mvn -pl ai-agent-study-domain,ai-agent-study-infrastructure -am "-Dtest=AiClientModelVORetryConfigTest,AiClientModelNodeRetryTest,AgentRepositoryCompressionConfigTest" "-Dsurefire.failIfNoSpecifiedTests=false" test
```

预期：PASS；旧 JSON 和现有 builder 均默认关闭，嵌套字段可开启，`call()` 相关测试无变化。

- [ ] **步骤 5：提交本任务**

```powershell
git add ai-agent-study-domain/src/main/java/denny/ai/agent/domain/model/valobj/AiClientModelVO.java
git add ai-agent-study-domain/src/test/java/denny/ai/agent/domain/model/valobj/AiClientModelVORetryConfigTest.java
git add ai-agent-study-domain/src/test/java/denny/ai/agent/domain/service/armory/AiClientModelNodeRetryTest.java
git add ai-agent-study-infrastructure/src/test/java/denny/ai/agent/infrastructure/adapter/repository/AgentRepositoryCompressionConfigTest.java
git commit -m "feat: add stream timeout retry switch"
```

### 任务 2：把错误分类拆成可组合的结构化事实

| 任务 | status |
|---|---|
| 任务 2：把错误分类拆成可组合的结构化事实 | pass |

**文件：**

- 创建：`ai-agent-study-domain/src/main/java/denny/ai/agent/domain/service/armory/stream/StreamTimeoutType.java`
- 修改：`ai-agent-study-domain/src/main/java/denny/ai/agent/domain/service/armory/factory/element/StreamQueryRetryClassifier.java`
- 修改：`ai-agent-study-domain/src/test/java/denny/ai/agent/domain/service/armory/factory/element/StreamQueryRetryClassifierTest.java`

- [ ] **步骤 1：补齐优先级与 cause-chain 失败测试**

测试必须覆盖：

```java
assertEquals(Optional.of(StreamTimeoutType.FIRST_CHUNK),
        classifier.streamTimeoutType(firstChunkTimeout()));
assertEquals(Optional.of(StreamTimeoutType.CHUNK_IDLE),
        classifier.streamTimeoutType(new RuntimeException("wrapped", chunkIdleTimeout())));
assertTrue(classifier.isSafetyExcluded(
        new RuntimeException("wrapped", queryAttemptTimeout())));
assertTrue(classifier.matchesNonRetryableCode(
        new RuntimeException("wrapped", http(400,
                "{\"error\":{\"code\":\"1261\"}}"))));
assertTrue(classifier.isDefiniteNonRetryable4xx(http(400)));
assertFalse(classifier.isDefiniteNonRetryable4xx(http(429)));
```

另用循环断言 cancel/client disconnect、tool、decode/codec/JSON、validation、JDK timeout、401 和 403 均属于 safety exclusion；异常 message 仅含 `timeout`、`idle` 或数字不得产生 `StreamTimeoutType`。

- [ ] **步骤 2：运行 classifier 测试确认失败**

```powershell
mvn -pl ai-agent-study-domain -am "-Dtest=StreamQueryRetryClassifierTest" "-Dsurefire.failIfNoSpecifiedTests=false" test
```

预期：FAIL，新判定方法和 `StreamTimeoutType` 尚不存在。

- [ ] **步骤 3：实现有限 cause-chain 与五个判定入口**

保留 `MAX_CAUSE_DEPTH=8` 和循环引用保护。先在 `armory.stream` 包创建公共稳定类型：

```java
public enum StreamTimeoutType { FIRST_CHUNK, CHUNK_IDLE }
```

classifier 导入该类型并增加以下 package-private API：

```java

Optional<StreamTimeoutType> streamTimeoutType(Throwable error);
boolean isSafetyExcluded(Throwable error);
boolean matchesNonRetryableCode(Throwable error);
boolean isDefiniteNonRetryable4xx(Throwable error);
boolean isOrdinaryRetryable(Throwable error);
```

`streamTimeoutType` 只按类型识别两个 Story 2 subtype。`isSafetyExcluded` 识别非目标 `LlmTimeoutException`、cancel/client disconnect、tool、decode、validation、JDK timeout 和 401/403；目标 timeout 与 query-attempt timeout 同链时仍返回 `true`。`isDefiniteNonRetryable4xx` 识别除 429 外的结构化 4xx；1261 是否压缩由上层在该判断前决定。`isOrdinaryRetryable` 保持 Story 1 的 429/目标 5xx/transport 行为，并继续排除所有 `LlmTimeoutException`。

现有 `isRetryable(Throwable)` 暂时保留并委托给 `isOrdinaryRetryable(Throwable)`，避免 Story 1 调用点在同一任务中断裂。

- [ ] **步骤 4：运行 classifier 与相邻分类回归**

```powershell
mvn -pl ai-agent-study-domain -am "-Dtest=StreamQueryRetryClassifierTest,RetryableExceptionTypesTest,AiErrorCodeExtractorTest" "-Dsurefire.failIfNoSpecifiedTests=false" test
```

预期：PASS；ordinary 分类不变，两类 SSE subtype 只能通过新入口识别。

- [ ] **步骤 5：提交本任务**

```powershell
git add ai-agent-study-domain/src/main/java/denny/ai/agent/domain/service/armory/stream/StreamTimeoutType.java
git add ai-agent-study-domain/src/main/java/denny/ai/agent/domain/service/armory/factory/element/StreamQueryRetryClassifier.java
git add ai-agent-study-domain/src/test/java/denny/ai/agent/domain/service/armory/factory/element/StreamQueryRetryClassifierTest.java
git commit -m "refactor: expose structured stream retry facts"
```

### 任务 3：在 RetryChatModel 编排唯一恢复动作和共享预算

| 任务 | status |
|---|---|
| 任务 3：在 RetryChatModel 编排唯一恢复动作和共享预算 | pass |

**文件：**

- 修改：`ai-agent-study-domain/src/main/java/denny/ai/agent/domain/service/armory/factory/element/RetryChatModel.java`
- 创建：`ai-agent-study-domain/src/test/java/denny/ai/agent/domain/service/armory/factory/element/RetryChatModelStreamTimeoutRetryTest.java`

- [ ] **步骤 1：先写开关、预算、压缩优先级和最终异常测试**

测试方法固定为：

```text
should_retry_first_chunk_timeout_when_both_switches_are_enabled
should_retry_chunk_idle_timeout_and_discard_failed_attempt
should_not_retry_stream_timeout_when_child_switch_is_disabled
should_not_retry_stream_timeout_when_global_switch_is_disabled
should_share_ordinary_credit_between_503_and_stream_timeout
should_propagate_last_original_stream_timeout_when_exhausted
should_choose_exactly_one_recovery_action_by_priority
should_count_model_calls_and_budgets_after_compression_then_timeout
```

精确计数用例使用 `maxAttempts=2`、`maxCompressionAttempts=1`，最终断言 `delegate.stream subscriptions=3`、`compressionService calls=1`、`ordinaryRetriesUsed=1`、`ordinaryRetriesRemaining=0`、`compressionAttempts=1`、`maxModelCalls=3`。优先级测试分别组合 safety + timeout、veto + timeout、`1261 + timeout`、400 + timeout；`1261 + timeout` 只能压缩一次且不扣 ordinary credit，`1261 + veto/safety` 不得压缩。

- [ ] **步骤 2：运行新测试确认失败**

```powershell
mvn -pl ai-agent-study-domain -am "-Dtest=RetryChatModelStreamTimeoutRetryTest" "-Dsurefire.failIfNoSpecifiedTests=false" test
```

预期：FAIL；当前 `RetryChatModel` 尚未放行 Story 3 timeout。

- [ ] **步骤 3：按固定顺序重写 `resumeAfterStreamError()`**

```java
StreamTimeoutType timeoutType = state.retryClassifier
        .streamTimeoutType(error).orElse(null);

if (state.retryClassifier.isSafetyExcluded(error)) {
    return propagate(state, error, timeoutType, "HARD_EXCLUDED");
}
if (state.retryClassifier.matchesNonRetryableCode(error)) {
    return propagate(state, error, timeoutType, "HARD_EXCLUDED");
}
if (AiErrorCodes.isContextOverflow(errorCode)) {
    return compressAndRetryOrPropagate(state, error, errorCode);
}
if (state.retryClassifier.isDefiniteNonRetryable4xx(error)) {
    return propagate(state, error, timeoutType, "HARD_EXCLUDED");
}
if (timeoutType != null) {
    return retryStreamTimeoutOrPropagate(state, error, timeoutType);
}
if (state.retryClassifier.isOrdinaryRetryable(error)) {
    return retryOrdinaryOrPropagate(state, error);
}
return Flux.error(error);
```

`retryStreamTimeoutOrPropagate()` 必须同时检查 `retryConfig.isEnabled()`、`retryConfig.isRetryOnStreamTimeout()` 和 `ordinaryRetriesRemaining > 0`。只有确定调度下一 attempt 时才扣减 ordinary credit。压缩成功后继续使用 `state.currentPrompt`；SSE retry 不允许恢复为压缩前 Prompt。下一 attempt 必须使用 `Flux.defer(() -> streamAttempt(state))`，避免 backoff 被取消前提前增加 `modelCalls`。

- [ ] **步骤 4：运行核心测试和 Story 1 stream 回归**

```powershell
mvn -pl ai-agent-study-domain -am "-Dtest=RetryChatModelStreamTimeoutRetryTest,RetryChatModelStreamTest,RetryChatModelCornerTest,RetryChatModelCompressionTest" "-Dsurefire.failIfNoSpecifiedTests=false" test
```

预期：PASS；两类 timeout 共享 ordinary credit，其他 timeout 和 safety error 均不重试，耗尽后最后一次原始异常不被包装。

- [ ] **步骤 5：提交本任务**

```powershell
git add ai-agent-study-domain/src/main/java/denny/ai/agent/domain/service/armory/factory/element/RetryChatModel.java
git add ai-agent-study-domain/src/test/java/denny/ai/agent/domain/service/armory/factory/element/RetryChatModelStreamTimeoutRetryTest.java
git commit -m "feat: retry complete query on stream timeout"
```

### 任务 4：建立 attempt 终止屏障并锁定取消语义

| 任务 | status |
|---|---|
| 任务 4：建立 attempt 终止屏障并锁定取消语义 | pass |

**文件：**

- 修改：`ai-agent-study-domain/src/main/java/denny/ai/agent/domain/service/armory/factory/element/RetryChatModel.java`
- 创建：`ai-agent-study-domain/src/test/java/denny/ai/agent/domain/service/armory/factory/element/RetryChatModelAtomicAttemptTest.java`

- [ ] **步骤 1：先写资源顺序和取消竞态测试**

使用 `AtomicInteger activeSubscriptions`、`AtomicInteger maxActiveSubscriptions`、`doOnSubscribe`、`doFinally` 和 `StepVerifier.withVirtualTime()` 覆盖：

```text
should_wait_for_failed_attempt_termination_before_zero_backoff_retry
should_cancel_active_attempt_without_late_retry
should_cancel_backoff_without_subscribing_next_attempt
should_cancel_new_attempt_immediately_when_cancel_races_with_retry_tick
should_isolate_retry_state_between_concurrent_subscriptions
should_not_keep_failed_attempt_responses_or_errors
```

首个用例必须断言 `maxActiveSubscriptions=1`，且 attempt 1 的 `doFinally(ERROR)` 先于 attempt 2 的 `doOnSubscribe`。backoff 取消用例推进完整虚拟时间后，`delegate.stream()` 仍只被订阅一次。

- [ ] **步骤 2：运行原子 attempt 测试确认失败**

```powershell
mvn -pl ai-agent-study-domain -am "-Dtest=RetryChatModelAtomicAttemptTest" "-Dsurefire.failIfNoSpecifiedTests=false" test
```

预期：FAIL；当前 fallback 构造会提前调用 `streamAttempt(state)`，也没有显式的上一个 attempt 终止屏障。

- [ ] **步骤 3：为每个 attempt 增加一次性终止信号**

在 `streamAttempt()` 内创建独立 `Sinks.One<Void>`，让恢复判定等待上游 `doFinally`：

```java
Sinks.One<Void> terminated = Sinks.one();
Flux<ChatResponse> attempt = createStreamAttempt(state, querySubscriptionNumber);
return attempt
        .doFinally(signal -> terminated.tryEmitEmpty())
        .onErrorResume(error -> terminated.asMono()
                .thenMany(Flux.defer(() ->
                        resumeAfterStreamError(state, error))));
```

普通 retry 与压缩后 retry 的下一订阅均通过统一方法延迟创建：

```java
private Flux<ChatResponse> nextAttempt(StreamState state, Duration delay) {
    return Mono.delay(delay)
            .thenMany(Flux.defer(() -> streamAttempt(state)));
}
```

不使用 `Thread.sleep()`。`querySubscriptionNumber` 只在 `delegate.stream()` 所在的 `Flux.defer` 真正订阅时增加；cancel 掉 backoff 不得消耗 model call 或 ordinary credit 之外的隐藏状态。attempt 的响应 buffer 继续在 error/cancel/complete 后清空，只保留固定大小的失败统计快照，不保存响应或异常历史集合。

- [ ] **步骤 4：运行核心、collector 和 timeout 生命周期测试**

```powershell
mvn -pl ai-agent-study-domain -am "-Dtest=RetryChatModelAtomicAttemptTest,RetryChatModelStreamTimeoutRetryTest,StreamingChatResponseCollectorTest,SseChunkTimeoutFilterTest" "-Dsurefire.failIfNoSpecifiedTests=false" test
```

预期：PASS；无 attempt 重叠、迟到订阅、失败内容泄漏或取消后残留活动。

- [ ] **步骤 5：提交本任务**

```powershell
git add ai-agent-study-domain/src/main/java/denny/ai/agent/domain/service/armory/factory/element/RetryChatModel.java
git add ai-agent-study-domain/src/test/java/denny/ai/agent/domain/service/armory/factory/element/RetryChatModelAtomicAttemptTest.java
git commit -m "fix: serialize stream retry attempt lifecycle"
```

### 任务 5：增加 stream jitter、结构化日志和低基数指标

| 任务 | status |
|---|---|
| 任务 5：增加 stream jitter、结构化日志和低基数指标 | pass |

**文件：**

- 修改：`ai-agent-study-domain/pom.xml`
- 创建：`ai-agent-study-domain/src/main/java/denny/ai/agent/domain/service/armory/stream/StreamTimeoutRetryMetrics.java`
- 修改：`ai-agent-study-domain/src/main/java/denny/ai/agent/domain/service/armory/factory/element/RetryChatModel.java`
- 修改：`ai-agent-study-domain/src/main/java/denny/ai/agent/domain/service/armory/AiClientModelNode.java`
- 创建：`ai-agent-study-domain/src/test/java/denny/ai/agent/domain/service/armory/stream/StreamTimeoutRetryMetricsTest.java`
- 修改：`ai-agent-study-domain/src/test/java/denny/ai/agent/domain/service/armory/factory/element/RetryChatModelStreamTimeoutRetryTest.java`
- 修改：`ai-agent-study-domain/src/test/java/denny/ai/agent/domain/service/armory/factory/element/RetryChatModelStreamTest.java`
- 修改：`ai-agent-study-domain/src/test/java/denny/ai/agent/domain/service/armory/AiClientModelNodeRetryTest.java`

- [ ] **步骤 1：先写固定随机序列、日志和指标测试**

测试注入 `LongSupplier` 序列 `250, 750`，制造 503 后 idle timeout，虚拟时间精确断言实际等待 `1250ms`、`2750ms`。另断言固定值 0 和 1000 均合法，基础值不超过 `maxIntervalMs`，实际值不超过 `maxIntervalMs + 1000ms`；同步 `call()` 的现有 backoff 测试保持原等待值。

`RetryChatModelStreamTest` 中依赖精确虚拟时间的 Story 1 测试必须统一注入 `jitter=0`，尤其是 `should_ignore_retry_after_and_use_retry_config_backoff()` 与 `layered_retry_should_receive_a_fresh_attempt_window()`；不得让现有测试调用生产随机源。

使用 Logback `ListAppender` 断言每次失败 decision 日志包含第 13 节字段且不包含 Prompt、部分正文、reasoning 和 tool 参数。使用 `SimpleMeterRegistry` 分别触发 `SCHEDULED`、`DISABLED`、`EXHAUSTED`、`HARD_EXCLUDED`，每种 decision 恰好加一，并断言 meter 只有 `timeoutType`、`decision` 两个 tag。

- [ ] **步骤 2：运行测试确认失败**

```powershell
mvn -pl ai-agent-study-domain -am "-Dtest=StreamTimeoutRetryMetricsTest,RetryChatModelStreamTimeoutRetryTest,AiClientModelNodeRetryTest,BackoffTest" "-Dsurefire.failIfNoSpecifiedTests=false" test
```

预期：FAIL；指标类型、可注入随机源和三类 backoff 字段尚不存在。

- [ ] **步骤 3：实现可测试 jitter，且只作用于 stream**

在 `StreamState` 中将 `nextDelay()` 改为返回不可变值对象：

```java
private record BackoffDelay(long baseMs, long jitterMs, long actualMs) {}

private BackoffDelay nextDelay() {
    long base = interval;
    long jitter = Math.max(0L, Math.min(1000L, jitterMsSupplier.getAsLong()));
    long actual = base > Long.MAX_VALUE - jitter ? Long.MAX_VALUE : base + jitter;
    interval = (long) Math.min(maxInterval, Math.max(0, interval * multiplier));
    return new BackoffDelay(base, jitter, actual);
}
```

生产构造器使用 `ThreadLocalRandom.current().nextLong(0, 1001)`；package-private 测试构造器接收 `LongSupplier`。不要把该 supplier 或 jitter 下传给 `CallRetryStrategy`。

- [ ] **步骤 4：实现可选指标组件和一次性 decision 记录**

`ai-agent-study-domain/pom.xml` 显式增加 `io.micrometer:micrometer-core`。`StreamTimeoutRetryMetrics` 接收可空 `MeterRegistry`，并提供：

```java
public enum Decision { SCHEDULED, DISABLED, EXHAUSTED, HARD_EXCLUDED }

public void record(StreamTimeoutType timeoutType, Decision decision) {
    if (meterRegistry == null) {
        return;
    }
    Counter.builder("llm_stream_timeout_retry_decisions_total")
            .tag("timeoutType", timeoutType.name())
            .tag("decision", decision.name())
            .register(meterRegistry)
            .increment();
}
```

`AiClientModelNode` 使用 `@Autowired(required=false)` 接收可选 `MeterRegistry`，构造 `StreamTimeoutRetryMetrics` 后传给 `RetryChatModel`；原有公开构造器全部保留并默认使用 no-op metrics。命中目标 timeout 后，只有最终选定的一个 decision 计数一次；`1261 + timeout` 被压缩动作接管，不进入 timeout decision counter。

- [ ] **步骤 5：补齐 retry decision 日志**

每个失败 attempt 只记录一条 `llm_stream_query_retry_decision`，字段包括 `querySubscriptionNumber/maxModelCalls`、ordinary 已用/剩余、compression 次数、timeout 元数据、错误类型/code、decision 和 `baseBackoffMs/jitterMs/actualBackoffMs`。传播最终异常时再记录一条固定大小 summary；不得保存历史异常来生成 summary。

- [ ] **步骤 6：运行测试确认通过**

```powershell
mvn -pl ai-agent-study-domain -am "-Dtest=StreamTimeoutRetryMetricsTest,RetryChatModelStreamTimeoutRetryTest,RetryChatModelAtomicAttemptTest,AiClientModelNodeRetryTest,BackoffTest,RetryStrategyTest" "-Dsurefire.failIfNoSpecifiedTests=false" test
```

预期：PASS；stream ordinary retry 与 timeout retry 使用同一 jitter 序列，`call()` 等待语义未改变，指标不存在时模型仍可正常装配。

- [ ] **步骤 7：提交本任务**

```powershell
git add ai-agent-study-domain/pom.xml
git add ai-agent-study-domain/src/main/java/denny/ai/agent/domain/service/armory/stream/StreamTimeoutRetryMetrics.java
git add ai-agent-study-domain/src/main/java/denny/ai/agent/domain/service/armory/factory/element/RetryChatModel.java
git add ai-agent-study-domain/src/main/java/denny/ai/agent/domain/service/armory/AiClientModelNode.java
git add ai-agent-study-domain/src/test/java/denny/ai/agent/domain/service/armory/stream/StreamTimeoutRetryMetricsTest.java
git add ai-agent-study-domain/src/test/java/denny/ai/agent/domain/service/armory/factory/element/RetryChatModelStreamTimeoutRetryTest.java
git add ai-agent-study-domain/src/test/java/denny/ai/agent/domain/service/armory/factory/element/RetryChatModelStreamTest.java
git add ai-agent-study-domain/src/test/java/denny/ai/agent/domain/service/armory/AiClientModelNodeRetryTest.java
git commit -m "feat: observe stream timeout retry decisions"
```

### 任务 6：验证真实 HTTP watchdog 和工具后第二轮 timeout

| 任务 | status |
|---|---|
| 任务 6：验证真实 HTTP watchdog 和工具后第二轮 timeout | pass |

**文件：**

- 创建：`ai-agent-study-domain/src/test/java/denny/ai/agent/domain/service/armory/factory/element/OpenAiStreamTimeoutQueryRetryIntegrationTest.java`
- 参考但不修改实现：`ai-agent-study-domain/src/test/java/denny/ai/agent/domain/service/armory/factory/element/OpenAiQueryRetryIntegrationTest.java`

- [ ] **步骤 1：复用现有本地 OpenAI SSE 测试结构编写四条正向用例和一条负向控制**

```text
should_detect_timeout_without_retry_when_switch_is_disabled
should_retry_from_fresh_query_when_headers_timeout
should_retry_from_fresh_query_when_first_body_chunk_timeout
should_retry_from_fresh_query_when_body_becomes_idle
should_restart_entry_query_and_allow_tool_reexecution_after_second_round_timeout
```

测试继续使用 JDK `HttpServer`、真实 `OpenAiApi`、`AiClientHttpTimeoutConfig` 和 `OpenAiChatModel`。server 使用受管 `ExecutorService`，在 `finally` 中同时 `server.stop(0)` 和 `executor.shutdownNow()`。每个测试使用 100~300ms 的模型级 timeout override 和 3~5 秒测试上限。

工具用例脚本固定为：request 1 返回 tool-call；request 2 返回一个 partial Chunk 后停顿并触发 idle timeout；request 3 再次返回同一 tool-call；request 4 成功。断言 `query subscriptions=2`、`HTTP requests=4`、`tool callback=2`、最终内容只来自 request 4，并断言 request 1 与 request 3 的入口 messages 等价。

- [ ] **步骤 2：先验证测试装置的负向控制**

```powershell
mvn -pl ai-agent-study-domain -am "-Dtest=OpenAiStreamTimeoutQueryRetryIntegrationTest" "-Dsurefire.failIfNoSpecifiedTests=false" test
```

负向控制显式使用 `retryOnStreamTimeout=false`，预期只发送一次 query 并传播原始 timeout subtype；这同时证明 Story 2 在 Story 3 开关关闭时仍继续检测。正向用例若因任务 3~5 已完成而首次运行即 PASS，属于预期，不得通过临时破坏生产配置人为制造失败。

- [ ] **步骤 3：在正向用例中显式开启配置并验证 subtype 穿透**

测试模型配置必须显式包含：

```java
RetryConfig.builder()
        .enabled(true)
        .retryOnStreamTimeout(true)
        .maxAttempts(2)
        .initialIntervalMs(0)
        .maxIntervalMs(0)
        .build();
```

不得在测试或生产代码中把 timeout 转成普通 `IOException`，也不得给 `OpenAiApi` 增加 request replay。

- [ ] **步骤 4：运行真实 HTTP 与 Story 2 filter 回归**

```powershell
mvn -pl ai-agent-study-domain -am "-Dtest=OpenAiStreamTimeoutQueryRetryIntegrationTest,OpenAiQueryRetryIntegrationTest,SseChunkTimeoutFilterTest,AiClientHttpTimeoutConfigTest" "-Dsurefire.failIfNoSpecifiedTests=false" test
```

预期：PASS；headers 前、headers 后首 Chunk 前、部分 body 后和工具第二轮四条链路都从入口 Prompt 创建新 query。

- [ ] **步骤 5：提交本任务**

```powershell
git add ai-agent-study-domain/src/test/java/denny/ai/agent/domain/service/armory/factory/element/OpenAiStreamTimeoutQueryRetryIntegrationTest.java
git commit -m "test: cover stream timeout query retry integration"
```

### 任务 7：修复 Trading 并行 analyst 的真实取消链路

| 任务 | status |
|---|---|
| 任务 7：修复 Trading 并行 analyst 的真实取消链路 | pass |

**文件：**

- 修改：`ai-agent-study-trading/ai-agent-study-trading-domain/src/main/java/denny/ai/agent/trading/domain/pipeline/AnalystCollectionStage.java`
- 创建：`ai-agent-study-trading/ai-agent-study-trading-domain/src/test/java/denny/ai/agent/trading/domain/pipeline/AnalystCollectionStageCancellationIntegrationTest.java`

- [ ] **步骤 1：先写 timeout、interrupt 和 client cancel 测试**

测试使用受管单线程或四线程 `ExecutorService`，在 analyst 的 `prepare()` 中订阅一个带 `doOnCancel`/`doFinally` 的阻塞 stream。分别制造 node deadline、执行 `AnalystCollectionStage.execute()` 的父线程 interrupt、以及 `SseEventSink.shouldContinue=false`，断言：

```text
worker thread 收到 interrupt
Future.isCancelled() == true
collector 不返回部分内容
Reactor doFinally == CANCEL
active subscription 最终归零
取消后没有新的 HTTP/query subscription
```

客户端取消用例通过真实 `WebClient` 或可观测的 HTTP body publisher 建立 stream；不能只断言 `Future` 状态。

- [ ] **步骤 2：运行测试确认失败**

```powershell
mvn -pl ai-agent-study-trading/ai-agent-study-trading-domain -am "-Dtest=AnalystCollectionStageCancellationIntegrationTest" "-Dsurefire.failIfNoSpecifiedTests=false" test
```

预期：FAIL；当前 `CompletableFuture.supplyAsync(...).cancel(true)` 不能保证中断 `tradingTaskExecutor` 中正在执行的任务，等待期也不能及时观察 client cancel。

- [ ] **步骤 3：使用 `ExecutorCompletionService` 保存真实 Future**

在一次 `execute()` 内创建 completion service，并把 `AnalystTask.future` 改为 `Future<NodeExecutionResult<?>>`：

```java
ExecutorCompletionService<NodeExecutionResult<?>> completions =
        new ExecutorCompletionService<>(tradingTaskExecutor);
Future<NodeExecutionResult<?>> future = completions.submit(
        () -> prepareAnalyst(analyst, context, scope));
```

删除 `CompletableFuture.allOf()`。用同一个绝对 deadline 等待全部任务，每次 `poll()` 最多阻塞 50ms，以便检查 `TradingPipelineSseGuard.shouldContinue(context)`：

```java
while (completed < tasks.size()) {
    if (!TradingPipelineSseGuard.shouldContinue(context)) {
        cancelOutstanding(tasks, CancellationReason.CLIENT);
        return;
    }
    long remainingNanos = deadlineNanos - System.nanoTime();
    if (remainingNanos <= 0) {
        cancelOutstanding(tasks, CancellationReason.TIMEOUT);
        break;
    }
    Future<NodeExecutionResult<?>> done = completions.poll(
            Math.min(remainingNanos, TimeUnit.MILLISECONDS.toNanos(50)),
            TimeUnit.NANOSECONDS);
    if (done != null) {
        completed++;
    }
}
```

`InterruptedException` 分支必须先 `Thread.currentThread().interrupt()`，再将未完成 scope 标记为 cancelled 并执行真实 `future.cancel(true)`。deadline 分支标记 timed out；client 分支标记 cancelled。`commitAnalyst()` 只对 `isDone() && !isCancelled()` 的 Future 调用 `get()`，并处理 `ExecutionException`，不得使用 `CompletableFuture.getNow()`。

- [ ] **步骤 4：运行 Trading 定向回归**

```powershell
mvn -pl ai-agent-study-trading/ai-agent-study-trading-domain -am "-Dtest=AnalystCollectionStageCancellationIntegrationTest,TradingPipelineTest,TradingStarterPipelineTest" "-Dsurefire.failIfNoSpecifiedTests=false" test
```

预期：PASS；正常 analyst 仍可全部提交，timeout/run interrupt/client cancel 均到达工作线程和 stream。

- [ ] **步骤 5：提交本任务**

```powershell
git add ai-agent-study-trading/ai-agent-study-trading-domain/src/main/java/denny/ai/agent/trading/domain/pipeline/AnalystCollectionStage.java
git add ai-agent-study-trading/ai-agent-study-trading-domain/src/test/java/denny/ai/agent/trading/domain/pipeline/AnalystCollectionStageCancellationIntegrationTest.java
git commit -m "fix: propagate analyst cancellation to worker stream"
```

### 任务 8：建立 collector 覆盖门禁并执行全量回归

| 任务 | status |
|---|---|
| 任务 8：建立 collector 覆盖门禁并执行全量回归 | pass |

**文件：**

- 验证：`ai-agent-study-domain/src/test/java/denny/ai/agent/domain/service/auto/step/StreamingChatResponseCollectorTest.java`
- 创建：`ai-agent-study-trading/ai-agent-study-trading-domain/src/test/java/denny/ai/agent/trading/domain/node/TradingCollectorCoverageTest.java`
- 验证：任务 1~7 的全部生产与测试文件

- [ ] **步骤 1：保留公共 collector 的既有取消契约**

直接回归现有 `should_cancel_upstream_and_discard_partial_content_when_request_is_cancelled()`；不要复制第二份同义单元测试，也不要把 retry 逻辑放入 collector。

- [ ] **步骤 2：增加 12 节点静态门禁**

`TradingCollectorCoverageTest` 从模块 `src/main/java` 读取 12 个已声明节点源码，逐一断言包含 `collectStreamingResponse(`，且不包含绕过公共入口的 `.collectList().block(`。节点集合必须精确为：

```java
List.of(
        "FundamentalAnalystNode", "TechnicalAnalystNode",
        "SentimentAnalystNode", "NewsAnalystNode",
        "BullResearcherNode", "BearResearcherNode", "ResearchManagerNode",
        "ConservativeRiskAnalystNode", "NeutralRiskAnalystNode",
        "AggressiveRiskAnalystNode", "PortfolioManagerNode",
        "RecommendationNode");
```

`IntentRoutingNode` 和 `GeneralChatNode` 不加入该集合。

- [ ] **步骤 3：运行 Story 3 定向套件**

```powershell
mvn -pl ai-agent-study-domain -am "-Dtest=StreamQueryRetryClassifierTest,RetryChatModelStreamTimeoutRetryTest,RetryChatModelAtomicAttemptTest,OpenAiStreamTimeoutQueryRetryIntegrationTest,AiClientModelNodeRetryTest,AiClientModelVORetryConfigTest,StreamTimeoutRetryMetricsTest,StreamingChatResponseCollectorTest" "-Dsurefire.failIfNoSpecifiedTests=false" test

mvn -pl ai-agent-study-infrastructure -am "-Dtest=AgentRepositoryCompressionConfigTest" "-Dsurefire.failIfNoSpecifiedTests=false" test

mvn -pl ai-agent-study-trading/ai-agent-study-trading-domain -am "-Dtest=AnalystCollectionStageCancellationIntegrationTest,TradingCollectorCoverageTest" "-Dsurefire.failIfNoSpecifiedTests=false" test
```

预期：三组命令全部 PASS。

- [ ] **步骤 4：运行相邻能力和全模块回归**

```powershell
mvn -pl ai-agent-study-domain -am test "-Dsurefire.failIfNoSpecifiedTests=false"
mvn -pl ai-agent-study-infrastructure -am test "-Dsurefire.failIfNoSpecifiedTests=false"
mvn -pl ai-agent-study-trading/ai-agent-study-trading-domain -am test "-Dsurefire.failIfNoSpecifiedTests=false"
mvn compile -DskipTests
```

预期：domain、infrastructure、trading-domain 全部测试通过，全项目编译成功。重点确认 `RetryChatModelTest`、`RetryStrategyTest`、`RetryChatModelCompressionTest`、`SseChunkTimeoutFilterTest`、`AiStreamingPropertiesTest` 无回归。

- [ ] **步骤 5：执行设计边界与文档自审**

```powershell
git diff --check
$patterns = @( ("T" + "BD"), ("T" + "ODO"), ("稍后" + "实现") )
Select-String -Path docs/superpowers/plans/2026-07-31-story-3-sse-timeout-query-retry-design.md -Pattern $patterns
git diff --name-only
```

预期：`git diff --check` 无空白错误（Windows 下仅出现 LF/CRLF 提示可接受）；`Select-String` 无输出；生产改动不包含 `OpenAiApi`、`OpenAiChatModel.internalStream()`、`SseChunkTimeoutFilter`、同步 `RetryStrategy` 或 45/30/90/150 秒 watchdog。若仓库原先已有无关脏文件，只记录并忽略，不得清理或提交。

- [ ] **步骤 6：提交测试门禁**

```powershell
git add ai-agent-study-trading/ai-agent-study-trading-domain/src/test/java/denny/ai/agent/trading/domain/node/TradingCollectorCoverageTest.java
git commit -m "test: guard cancellable trading collector coverage"
```

### 任务 9：人工执行 DML 并完成运行时验收

| 任务 | status |
|---|---|
| 任务 9：人工执行 DML 并完成运行时验收 | append |

**文件：**

- 人工执行：`docs/dev-ops/mysql/sql/dml/005-sse-timeout-query-retry-config.sql`
- 对照：`docs/superpowers/test/2026-07-31-story-3-sse-timeout-query-retry-test.md`

- [ ] **步骤 1：提交 rollout 文档，但停止在数据库写入之前**

代码、测试和编译全部通过后，先把 Story 3 design、测试方案和 DML 文件纳入变更集：

```powershell
git add docs/superpowers/plans/2026-07-31-story-3-sse-timeout-query-retry-design.md
git add docs/superpowers/test/2026-07-31-story-3-sse-timeout-query-retry-test.md
git add docs/dev-ops/mysql/sql/dml/005-sse-timeout-query-retry-config.sql
git commit -m "docs: finalize stream timeout retry rollout"
```

随后把自动化结果、当前 commit 和 DML 路径交给用户。提交 SQL 文件不等于执行 SQL；Codex 或开发 Agent 不得代替用户执行该 DML。

- [ ] **步骤 2：用户执行预检和事务更新**

仅当预检输出满足：

```text
source_model_count=4
```

才继续执行事务。目标模型只能是 `2001/2003/2007/2009`。

- [ ] **步骤 3：用户核对更新结果**

必须同时满足：

```text
updated_model_count=4
retry_enabled_model_count=4
enabled_model_count=4
invalid_structure_count=0
```

并人工确认 `2003/2007` 的 `compressionConfig` 保留，`2002/2004/2005/2006/2008` 未被修改。

- [ ] **步骤 4：重启或重新装配模型后执行手工验收**

分别制造首 Chunk timeout、Chunk idle timeout、工具后第二轮 timeout 和 backoff 中客户端断开。确认四个模型读取 `enabled=true` 与 `retryOnStreamTimeout=true`；失败 attempt 内容不提交；工具场景日志可见完整 query 重启；取消后无迟到 HTTP。

- [ ] **步骤 5：更新状态**

自动化、DML 计数和运行时验收全部符合后，将本任务、设计文档第 16 节及测试文档第 7 节对应状态改为 `pass`。任一计数或行为不符时保持 `append`，保留原始输出并停止扩大启用范围。

状态全部更新为 `pass` 后提交验收记录：

```powershell
git add docs/superpowers/plans/2026-07-31-story-3-sse-timeout-query-retry-design.md
git add docs/superpowers/test/2026-07-31-story-3-sse-timeout-query-retry-test.md
git commit -m "docs: record story 3 acceptance"
```

### 19.2 最终完成定义

以下条件必须全部成立，Story 3 才可视为完成：

1. 两类 Story 2 timeout 只在两个开关均开启且 ordinary credit 充足时重启完整 query。
2. safety、veto、1261、其他 4xx、SSE timeout、ordinary error 始终只选择一个恢复动作。
3. 普通错误与 SSE timeout 共享 attempt、backoff 和 jitter；压缩预算独立，总 model call 不超过公式上限。
4. 前一 attempt 已终止并清空临时结果后才创建下一订阅；active/backoff cancel 均不会产生迟到请求。
5. 工具后第二轮 timeout 的真实 HTTP 测试证明完整 query 重启与 at-least-once 工具语义。
6. Trading 并行 analyst 的 `Future.cancel(true)` 能实际中断 worker，并沿 collector/Reactor 到达 HTTP。
7. 同步 `call()`、Story 1 ordinary retry、Story 2 watchdog、配置解析和 12 节点 collector 门禁全部回归通过。
8. 用户人工执行 DML，完成 `4/4/4/4/0` 计数验收并重启或重新装配模型。
