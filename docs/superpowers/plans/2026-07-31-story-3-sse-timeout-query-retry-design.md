# Story 3：SSE Timeout 接入完整 Query 重试设计

## 1. 文档状态

- 状态：设计讨论已确认，等待用户书面复核。
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

## 5. 总体架构

```text
Story 2 WebClient watchdog
  -> FirstStreamChunkTimeoutException
  -> StreamChunkIdleTimeoutException
             |
             v
StreamQueryRetryClassifier
  -> 检查取消/attempt timeout 优先级
  -> 检查 RetryConfig.retryOnStreamTimeout
  -> 返回唯一 retry decision
             |
             v
RetryChatModel StreamState
  -> 消耗 ordinary retry credit
  -> 复用 nextDelay() 计算 backoff
  -> 丢弃失败 attempt 缓存
  -> streamAttempt(entryPrompt)
```

Story 3 不新增独立执行组件。它只扩展现有 classifier 与 `RetryConfig` 契约，让 Story 2 的两类 SSE timeout 进入 Story 1 已有的 retry 分支。

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

`maxAttempts` 包含首次调用。例如 `maxAttempts=3` 表示最多执行三个完整 query attempt，而不是首次调用加三次 retry。

downstream cancel 不是 Throwable，不进入 `StreamQueryRetryClassifier`。取消资格由 Reactor subscription 生命周期治理：已观察到 cancel 后不得订阅下一 attempt；若 cancel 与零退避 retry 同刻竞争且下一 attempt 已开始订阅，该 subscription 必须立即收到 cancel，且不得继续创建后续 attempt。

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
| 2xx empty complete/正常 EOF | Reactor complete | 不重试 |
| 非 2xx HTTP error | 真实 HTTP status | 保持 Story 1 ordinary classifier |
| connection reset/body I/O error | 普通传输异常 | 保持 Story 1 ordinary classifier |

不得通过异常 message 中出现 `timeout`、`idle` 或数字状态码推断 Story 3 retry。必须在有限 cause chain 中找到稳定的结构化 subtype。

## 8. 分类优先级

classifier 必须按以下顺序得出唯一错误结论：

1. cause chain 中存在 `ClientDisconnectedException`、`CancellationException`、`LlmQueryAttemptTimeoutException`、工具错误、decode/codec/JSON 错误、业务校验错误、`HttpConnectTimeoutException` 或其他非 SSE 安全 hard exclusion 时，不重试。
2. cause chain 中存在两类允许的 SSE timeout，且不存在上述冲突 hard exclusion 时，检查 `retryOnStreamTimeout`。
3. 其他 `LlmTimeoutException`、`TimeoutException`、`SocketTimeoutException` 或 `HttpTimeoutException` 继续 hard exclusion。
4. 其余错误继续执行 Story 1 的 veto、真实 HTTP status、provider code 和普通传输错误分类。

downstream cancel 和 node task cancellation 主要以无错误的 Reactive Streams cancel 形式出现，由 `RetryChatModel` subscription 生命周期处理，而不是由 classifier 猜测。若 cancellation 以结构化异常传播，则仍按第一步 hard exclusion。attempt timeout 与 SSE timeout 同时存在于 cause chain 时，attempt timeout 优先。

## 9. Retry 预算与 backoff

Story 3 不引入第二套状态：

```text
ordinaryAttempts = clamp(RetryConfig.maxAttempts, 1, 10)
ordinaryRetriesRemaining = ordinaryAttempts - 1
```

45/90 秒 timeout 与 429、目标 5xx、connection reset 和 body I/O error 共用该预算。只有确定要调度下一 query attempt 时才扣减一个 credit。

所有 ordinary retry 共用同一 backoff 序列：

```text
delay(1) = initialIntervalMs
delay(n+1) = min(delay(n) * multiplier, maxIntervalMs)
```

混合错误不重置退避。例如 attempt 1 因 503 失败、attempt 2 因 90 秒 idle 失败时，后者使用第二级 backoff。Story 3 不解析 `Retry-After`，也不对已经等待 45/90 秒的 timeout 提供立即重试特例。

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
attemptNumber
maxAttempts
timeoutType
configuredTimeoutMs
effectiveTimeoutMs
elapsedMs
observedChunkCount
partialContentLength
errorType
errorCode
retryDecision=RETRY|PROPAGATE
retriesRemaining
backoffMs
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
| `RetryChatModel.java` | 复用现有 ordinary credit、backoff、attempt 丢弃与重启，不新增 timeout state |
| `AiClientModelNode.java` | 继续原样传递同一份 `RetryConfig`，兼容字段缺失 |
| Story 2 timeout 异常类 | 保持事实型异常，不实现 retry marker |

不得修改：

- `OpenAiApi` request replay；
- `OpenAiChatModel.internalStream()`；
- WebClient retry filter；
- `RetryChatModel.call()` 与同步 `RetryStrategy`；
- Story 2 的 45/30/90/150 秒 watchdog。

## 15. 兼容与发布

- `retryOnStreamTimeout` 默认 `false`，旧配置文本无需迁移。
- 可以按模型/client 显式开启，进行灰度验证。
- 关闭开关只关闭 Story 3；Story 2 timeout 检测和 Story 1 原有 ordinary retry 继续有效。
- 回滚不要求切换 Story 2 到 legacy timeout 模式。
- 全局 `timeout-mode=legacy` 时不会产生 Story 2 结构化 SSE timeout，因此模型级 `retryOnStreamTimeout=true` 明确按 no-op 处理，并记录一次配置告警；不阻止应用启动。
- Spring AI 升级必须重跑工具后第二轮 timeout 集成测试，确认异常 subtype 仍能穿过内部递归。

## 16. 验收标准

| 编号 | 验收项 | 标准 |
|---|---|---|
| AC-001 | 功能开关 | 默认关闭；开启后只有两类 SSE timeout 获得 retry |
| AC-002 | Query 粒度 | 每次 timeout retry 都从当前 `state.currentPrompt` 重启完整 query，保留已完成的压缩状态 |
| AC-003 | 统一预算 | SSE timeout 与普通错误共享 `maxAttempts` 和 ordinary credit |
| AC-004 | 统一 backoff | timeout 复用 `initialIntervalMs/multiplier/maxIntervalMs`，混合错误不重置 |
| AC-005 | 分类优先级 | cancel、attempt timeout 和其他 hard exclusion 不被 Story 3 翻转 |
| AC-006 | 结果隔离 | 失败 timeout attempt 的任何内容都不进入最终结果 |
| AC-007 | 工具语义 | 工具后第二轮 timeout 允许完整 query 重启，测试明示 at-least-once |
| AC-008 | 最终异常 | 耗尽后传播最后一次原始 timeout subtype |
| AC-009 | 取消传播 | active attempt 或 backoff 被取消后无下一订阅和迟到 HTTP |
| AC-010 | 资源顺序 | 前一 attempt 终止清理后才启动下一 attempt |
| AC-011 | 日志留痕 | 每次失败有结构化元数据，无 Prompt/正文/tool 参数 |
| AC-012 | 同步兼容 | `call()`、同步 retry 与压缩语义不变 |
| AC-013 | Story 隔离 | 无 HTTP request replay、第二套 retry owner 或 deadline 下传 |
| AC-014 | 模式兼容 | legacy 下 timeout retry 开关 no-op 并告警；layered 下按模型开关生效 |
| AC-015 | 指标 | 每个 timeout decision 恰好增加一次低基数计数 |

## 17. 风险记录

- 完整 query retry 可能重复执行已经成功的工具，属于明确接受的 at-least-once 风险。
- Provider 可能已经接收失败 attempt 的请求，因此即使下游未见结果，也可能重复计费或生成。
- node 临近 240 秒时仍可能启动一个无法完成的 attempt；Story 3 依赖外层 cancel，不主动读取剩余 deadline。
- 若 cancellation 没有到达实际 Reactor/HTTP subscription，可能产生迟到请求；必须通过集成测试锁定。
- `initialIntervalMs=0` 时，Reactor cancel 与 fallback 订阅可能同 tick 竞争；必须断言 cancel 后没有持续活动或后续 attempt。
- Spring AI 或 WebClient 对异常的包装变化可能使 subtype 不可见；cause-chain 契约测试是升级门禁。
- feature flag 开启范围过大可能放大工具副作用和 Provider 请求数，应先灰度并观察 retry success rate。

## 18. 推荐结论

Story 3 采用最小增量方案：在 `RetryConfig` 增加默认关闭的 `retryOnStreamTimeout`，由 `StreamQueryRetryClassifier` 在排除 attempt timeout、连接超时、工具、decode、业务校验等冲突 hard exclusion 后，精确放行 `FirstStreamChunkTimeoutException` 与 `StreamChunkIdleTimeoutException`。downstream cancel 由 Reactor subscription 生命周期治理，不伪装成 classifier 条件。retry 继续由 `RetryChatModel` 以完整 query attempt 为单位执行，复用 Story 1 的 ordinary credit、backoff、当前压缩后的入口 Prompt、结果隔离和最后异常传播；不重放内部 HTTP request，不下传 node deadline，并明确接受工具 at-least-once。
