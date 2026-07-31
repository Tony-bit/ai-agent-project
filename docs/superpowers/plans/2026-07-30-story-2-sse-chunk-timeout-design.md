# Story 2：LLM SSE Chunk 分层超时设计

## 1. 文档状态

- 状态：架构评审意见已逐条对齐，等待书面规格复核与实现计划。
- 日期：2026-07-30。
- 前置 Story：`2026-07-30-story-1-stream-http-attempt-retry.md`。
- 后续 Story：Story 3 将本 Story 的结构化 timeout 接入 Story 1 的 stream query retry。

## 2. 背景

当前 `RetryChatModel.stream()` 在整个外层 `Flux<ChatResponse>` 上观察有效文本：首段有效文本前使用 `firstContentTimeout`，收到有效文本后使用 30 秒 `idleTimeout`，最外层还应用 `totalTimeout`。该观察位置跨越 Spring AI 内部的多轮 HTTP/SSE 请求和本地工具执行。

典型失败链路如下：

```text
第 1 轮 LLM 收到文本
→ 第 1 轮返回 tool-call
→ 本地工具执行 40 秒
→ Spring AI 发起第 2 轮 LLM 请求
→ 外层 30 秒有效文本 idle 已耗尽
→ 整次 query 被误杀
```

这里并不是某一条 SSE 连接 30 秒没有网络数据，而是外层文本计时器错误覆盖了工具执行和下一轮请求。Story 1 又会在 `RetryChatModel` 内原子暂存一个 query attempt 的 `ChatResponse`，成功前不向下游提交，因此 Chunk 活性观察不能放在 Story 1 的原子聚合之后。

## 3. Story 定义

### 3.1 目标

- 将 `RetryChatModel` 的流式 timeout 收敛为每个 query attempt 的绝对时间上限。
- 在每次 OpenAI-compatible HTTP/SSE 请求的 WebClient response body 上独立观察原始 `DataBuffer`。
- 当前 HTTP 请求在 45 秒内没有返回 headers，或按时返回 2xx 流式 headers 但没有首个 body Chunk 时，主动取消并抛出结构化异常。
- 收到首个 Chunk 后，连续 30 秒没有新 Chunk 只记录 stall，不终止请求。
- 收到首个 Chunk 后，连续 90 秒没有新 Chunk 时主动取消并抛出结构化异常。
- 第一轮 HTTP body 结束后立即清理 watchdog；本地工具执行不消耗 SSE Chunk idle 窗口。
- 工具完成后的下一轮 HTTP/SSE 请求重新获得完整的 45 秒首 Chunk 窗口。
- timeout 异常能够无损穿过 Spring AI 和 Story 1 的原子 query attempt 边界。
- 同步 `call()` 的 timeout 和 retry 语义保持不变。

### 3.2 非目标

- Story 2 不重试任何 timeout。
- 不消费 Story 1 的 retry credit，不计算 retry backoff，不创建下一 query attempt。
- 不重放 `ClientRequest`，不实现 SSE 断点续传或 `Last-Event-ID`。
- 不解析 `[DONE]`，不判断文本、reasoning 或 tool-call 是否完整。
- 不处理工具幂等、补偿或 exactly-once。
- 不修改同步 `RetryStrategy`、`RetryChatModel.call()` 或 RestClient timeout。
- 不在本 Story 向模型层下传 Trading node/run deadline，也不实现 Trading run scope；node 继续在外层独立取消，父级剩余预算裁剪由后续 deadline Story 负责。

## 4. 已确认决策

| 编号 | 决策 | 说明 |
|---|---|---|
| D-001 | 每个 query attempt 独立获得 150 秒绝对 timeout | Story 1 retry 创建新 attempt 时重新计算窗口 |
| D-002 | attempt deadline 优先于 Chunk deadline | 本 Story 内 45/90 秒受当前 150 秒 attempt 剩余预算裁剪；同刻到期时抛 attempt timeout |
| D-003 | Trading node 默认 timeout 从 180 秒提高到 240 秒 | node 在外层独立治理，不向模型层下传剩余 deadline |
| D-004 | 首 Chunk timeout 为 45 秒 | 从当前 HTTP 请求订阅/发出开始，覆盖等待 headers；收到 2xx 流式 headers 后继续覆盖首个 body Chunk |
| D-005 | 30 秒是 stall 观测阈值 | 只记录，不中断 |
| D-006 | 90 秒是 Chunk hard idle timeout | timeout 时取消当前 response body 并抛错 |
| D-007 | Chunk 定义为 WebFlux 原始 `DataBuffer` | 判断传输活性，不等待用户可见文本或完整 SSE event |
| D-008 | 每个 HTTP/SSE request 独立维护 watchdog | 不跨工具执行，不跨 Spring AI 内部模型轮次 |
| D-009 | Story 2 timeout 暂不重试 | Story 3 再接入 Story 1 的同一 query retry 基础设施 |
| D-010 | layered 与 legacy 短期并存 | layered 稳定后删除旧文本 timeout |
| D-011 | 非 2xx 不进入 SSE body watchdog | 保留真实 HTTP status，不能改写为 Story 2 SSE timeout |
| D-012 | 三类 timeout 继承公共基类 | Story 2 统一 hard exclusion；Story 3 只精确调整两类 SSE timeout |
| D-013 | layered 模式缺少 policy 时 fail closed | 抛出显式配置契约异常，不发送无 watchdog 的流式请求 |
| D-014 | `ClientResponse` body 只做单次函数变换 | 必须使用 `body(original -> watchdog(original))`，禁止替换式双订阅 |
| D-015 | completion integrity 不进入 Story 2 | empty complete、缺失 `[DONE]` 和 tool-call 完整性由后续解码后校验 Story 负责 |
| D-016 | 终止型 timeout 使用 Reactor operator | 45/90/150 秒使用 `.timeout()`；30 秒 stall 使用 subscription-local 可取消观察任务 |
| D-017 | JDK connect timeout 不重试 | `HttpConnectTimeoutException` 保持 hard exclusion，不叠加 JDK read/request timeout |
| D-018 | layered 默认启用，legacy 仅用于显式回滚 | `timeout-mode` 只支持全局配置；正常部署无需配置该字段 |

## 5. 分层 timeout 模型

| 层级 | 默认值 | 起点 | 结束或刷新 | 行为 |
|---|---:|---|---|---|
| TCP connect | 10 秒 | 单次 HTTP 建连 | 建连成功 | `HttpConnectTimeoutException` hard exclusion；connection refused/reset 按现有普通错误传播 |
| First body Chunk | 45 秒 | 当前 HTTP 请求订阅/发出 | 2xx 流式响应收到首个 `DataBuffer` | headers 前到期取消 exchange；2xx headers 后到期取消 body，抛出 `FirstStreamChunkTimeoutException` |
| Chunk stall | 30 秒 | 每个已收到的 Chunk | 收到下一 Chunk | 记录 `llm_stream_stall`，不终止 |
| Chunk hard idle | 90 秒 | 收到首个及后续 Chunk | 收到下一 Chunk | 取消当前 body，抛出 `StreamChunkIdleTimeoutException` |
| Tool | 60 秒 | 单次工具调用开始 | 工具结束 | 由工具治理 Story 负责 |
| Query attempt | 150 秒 | Story 1 创建一次 delegate subscription | attempt complete/error/cancel | 取消 attempt，抛出 `LlmQueryAttemptTimeoutException` |
| Trading node | 240 秒 | 节点开始 | 节点完成/失败 | 外层独立取消节点并拒绝晚提交；本 Story 不下传 deadline |
| Trading run | 15 分钟 | Pipeline 开始 | Pipeline 终止 | 未来 run scope 负责 |

### 5.1 本 Story 内父子预算

每个 Story 1 retry attempt 都重新获得独立的 150 秒绝对窗口：

```text
attemptDeadline = attemptStartedAt + 150s

effectiveFirstChunkTimeout = min(45s, attemptRemaining)
effectiveChunkIdleTimeout  = min(90s, attemptRemaining)
```

backoff 不占用下一 attempt 的 150 秒名义窗口。`RetryChatModel` 必须把当前 attempt 的绝对 deadline 与 timeout 配置一并放入 Reactor Context，使 WebClient watchdog 能按 attempt 剩余预算裁剪 45/90 秒。若子 timeout 与 attempt deadline 处于同一绝对时刻，统一由 `LlmQueryAttemptTimeoutException` 表达；子 timer 发错前必须重新检查 attempt deadline，不能依赖 Scheduler 的执行顺序。

Trading node 的 240 秒 timeout 当前仅在外层通过取消任务和拒绝晚提交治理，Story 2 不建设 `NodeExecutionScope -> RetryChatModel -> WebClient` 的 deadline 传递链。因此本 Story 不承诺 45/90/150 秒按 node/run 剩余时间裁剪，也不承诺底层异常能表达 node deadline owner。node timeout 后必须将 cancel 传播到实际 Reactor subscription，阻止新的 retry、HTTP 请求和工具轮次；不响应取消的工具或 Provider 已接收的工作仍是明确接受的残余风险。

未来 deadline Story 接入 node/run 后，再扩展为：

```text
effectiveAttemptDeadline = min(attemptDeadline, nodeDeadline, runDeadline)
```

## 6. `DataBuffer` 语义

`DataBuffer` 是 WebFlux 对 HTTP response body 原始字节块的抽象，位于 Spring AI 的 SSE、JSON、tool-call 和 `ChatResponse` 解码之前。

一个 `DataBuffer` 可能包含：

- metadata、reasoning、文本 token、tool-call、finish reason 或 usage 对应的 SSE 字节；
- SSE comment/heartbeat；
- 半个 SSE event、半个 JSON 或半个 UTF-8 字符；
- 多个 SSE event。

因此本 Story 判断的是“当前 response body 是否仍有原始字节流入”，而不是“模型是否产生有效文本”或“SSE event 是否完整”。任意 `DataBuffer` 都结束首 Chunk 等待并刷新 stall/idle 计时器。SSE framing、JSON 和业务语义继续由 Spring AI 负责；decode error 继续作为 Story 1 hard exclusion 传播。

## 7. 状态机

每个纳入 watchdog 的 2xx streaming response body subscription 创建独立状态：

```text
AWAITING_FIRST_CHUNK
  ├─ 45s 到期
  │    → cancel body
  │    → FirstStreamChunkTimeoutException
  ├─ 收到首个 DataBuffer
  │    → chunkCount = 1
  │    → STREAMING
  └─ complete/error/cancel
       → TERMINATED

STREAMING
  ├─ 收到 DataBuffer
  │    → chunkCount++
  │    → 重置 30s stall timer
  │    → 重置 90s hard idle timer
  ├─ 30s 到期
  │    → 记录一次 stall
  │    → 保持 STREAMING
  ├─ 90s 到期
  │    → cancel body
  │    → StreamChunkIdleTimeoutException
  └─ complete/error/cancel
       → 清理 timer
       → TERMINATED
```

状态、计数器和 timer 必须是 subscription-local，不得保存到共享的 `WebClient.Builder`、filter Bean、`OpenAiApi` 或 `RetryChatModel` 实例字段。

## 8. 工具调用场景

目标链路：

```text
第 1 轮 HTTP/SSE
→ 接收文本和 tool-call DataBuffer
→ response body complete
→ 清理第 1 轮 watchdog

工具执行 40 秒
→ activeChunkWatchdogCount = 0
→ 不产生 stall 或 Chunk idle timeout

第 2 轮 HTTP/SSE
→ 创建新的 AWAITING_FIRST_CHUNK 状态
→ 重新获得完整 45 秒首 Chunk 窗口
→ 正常完成
```

如果 Provider 在一条仍然活跃的 HTTP/SSE body 中发送部分 tool-call 后停止超过 90 秒，watchdog 应当终止该 body。这属于真实的当前连接 idle，不属于本地工具执行误杀。

## 9. 架构与组件

### 9.1 `StreamChunkTimeoutPolicy`

不可变、每次 query subscription 独享，建议包含：

```text
firstChunkTimeout
stallThreshold
chunkIdleTimeout
queryAttemptTimeout
attemptDeadline
logicalCallId
modelId
```

`RetryChatModel.stream()` 在 layered 模式下为每个 Story 1 delegate subscription 计算新的 `attemptDeadline`，并通过 Reactor Context 写入 policy。该 Context 只传 timeout 配置、当前 attempt 的绝对 deadline 和观测标识，不传 Story 1 retry credit 或 backoff 状态，也不执行 retry。

### 9.2 `SseChunkTimeoutFilter`

安装在 `AiClientHttpTimeoutConfig` 提供的专用 `WebClient.Builder` 上。`legacy` 模式不启用该 filter；`layered` 模式的 OpenAI completions streaming request 必须能从 Reactor Context 取得 `StreamChunkTimeoutPolicy`，缺失时 fail closed。

职责：

- 从当前 subscription 的 Reactor Context 读取 policy；
- 在订阅 `next.exchange(request)` 前记录单调时钟起点，并计算唯一的首 Chunk 绝对 deadline；
- response headers 返回前，以同一 deadline 约束 `next.exchange(request)`；
- response headers 返回后先检查 HTTP status；只有 2xx 流式响应才把该 deadline 的剩余时间交给 body 首 Chunk watchdog，不重新获得 45 秒；
- 非 2xx response 不安装 SSE Chunk watchdog，保留真实 HTTP status 和 WebClient 原有 error decoding；错误 body 即使需要独立上限，也不得改写为两类 SSE timeout；
- 通过 `response.mutate().body(original -> watchdog(original)).build()` 包装 `ClientResponse` body 的 `Flux<DataBuffer>`，保证原 body 只消费一次；
- 每个 Chunk 刷新 30/90 秒计时状态；
- timeout 时取消上游 body 并传播结构化异常；
- complete/error/cancel 时清理全部 timer；
- 不缓存、修改、拼接或手动解析 body 内容；
- 不调用 `retryWhen`，不重新执行 `next.exchange(request)`。

首 Chunk timeout 不是两个串联的 45 秒 timeout。其逻辑必须等价于：

```text
firstChunkDeadline = requestSubscribedAt + 45s

等待 response headers 时：remaining = firstChunkDeadline - now
订阅 response body 时：   remaining = firstChunkDeadline - now
收到首个 DataBuffer：     结束 firstChunkDeadline
```

若 deadline 在 response headers 前到期，则取消当前 HTTP exchange，并抛出 `FirstStreamChunkTimeoutException`。若按时收到 2xx headers，但在首个 `DataBuffer` 前到期，则取消 response body，并抛出同一异常。实现不得先给 headers 45 秒、再给 body 45 秒，也不得等拿到 `ClientResponse` 后才启动计时。按时收到非 2xx headers 后退出 SSE watchdog 分支，后续错误无论如何终止都必须保留该 HTTP status，不能改写为 `FirstStreamChunkTimeoutException` 或 `StreamChunkIdleTimeoutException`。

### 9.3 Query attempt timeout

layered 模式下，`RetryChatModel` 删除基于有效文本的 first-content/idle phase 计时，只为 Story 1 的每个 delegate subscription 设置明确的 150 秒绝对 attempt timeout。该 timeout 不能由中间 `ChatResponse` 或 Chunk 刷新。

当前 `.timeout(Duration)` 表达的是相邻信号 timeout，不能继续作为绝对 attempt timeout 的隐含实现。实现必须以固定 attempt deadline 或包围整个 attempt 完成信号的单次计时器表达。

attempt timer 与 first/idle timer 使用统一终止仲裁。子 timer 准备发错时先读取单调时钟并检查 `attemptDeadline`：只要 attempt deadline 已到，即使子 timer 同刻被调度，也必须由 `LlmQueryAttemptTimeoutException` 终止；只有子 deadline 严格早于 attempt deadline 时才允许抛出对应的 SSE timeout。

### 9.4 Reactor timer 与 `StallObserver`

会终止流的 timeout 优先复用 Reactor 官方 operator，不复制或重写 `.timeout()` 的内部实现：

```text
response headers       -> Mono<ClientResponse>.timeout(...)
first Chunk / hard idle -> Flux<DataBuffer>.timeout(firstTimeout, nextTimeoutFactory)
query attempt          -> attempt 完成 Mono.timeout(...)
```

150 秒 attempt timeout 必须施加在整个 attempt 的完成信号上，不能直接对持续发出 `ChatResponse` 的 Flux 调用简单的 `.timeout(Duration)`，否则每个中间响应都会刷新计时。Story 1 的原子聚合可以通过只在 attempt complete 时发出结果的 Mono 表达该完成信号。

30 秒 stall 到期只记录、不终止，不能使用 `.timeout()`。每个 2xx streaming body subscription 在 `Flux.defer` 内创建独立的 `StallObserver`，其状态只属于当前 body：

```text
generation
observedChunkCount
lastChunkAtNanos
loggedGeneration
scheduledTask: Disposable
terminated
```

收到首个及后续 Chunk 时，observer 取消上一条 stall task、递增 `generation`，并通过共享 Scheduler 安排新的 30 秒 task。task 到期时仅在 subscription 未终止、`generation` 未变化且当前 gap 尚未记录的条件下记录一次 `llm_stream_stall`；它不发出 Reactor 信号，也不重新安排自己。新 Chunk 创建下一代 task，允许新的 gap 再记录一次。complete/error/cancel/timeout 统一 dispose 当前 task 并使 generation 失效。

`StallObserver` 不得接收、捕获或保存 `DataBuffer`，只保存 timeout 配置、计数和观测元数据。网络 `onNext`、Scheduler task 和下游 cancel 可能来自不同线程，因此 observer 必须使用 subscription-local 锁或等价原子状态机协调；该锁不在并发请求之间共享，日志和指标写入在锁外执行。

生产环境使用共享、可注入的 Reactor Scheduler，所有 deadline 和 elapsed 通过 `scheduler.now(NANOSECONDS)` 的单调时间计算；禁止每个请求创建 Scheduler 或线程池，也禁止单个 watchdog dispose 共享 Scheduler。测试注入 `VirtualTimeScheduler`，不真实等待 30/45/90/150 秒。

### 9.5 Completion integrity 边界

Story 2 只观察传输活性，不把协议或业务完整性伪装成 timeout：

- 2xx empty complete 在本 Story 只清理 watchdog，不抛 timeout；
- 缺失 `[DONE]` 但上游正常 EOF 时遵循 Spring AI 的 complete 信号；
- SSE/JSON decode error 原样传播并取消 body，不转换为 Chunk timeout；
- 不解析 finish reason，不判断 tool-call ID、名称或参数是否完整。

“正常 EOF 不等于业务响应完整”作为明确风险记录。若产品需要拒绝空响应、缺失 finish reason 或不完整 tool-call，应创建独立 completion-integrity Story，在 Spring AI 解码后定义结构化 hard failure 和后续 retry 决策，不能把该职责放入 raw `DataBuffer` filter。

## 10. 异常契约

### 10.1 公共基类 `LlmTimeoutException`

三类结构化 timeout 统一继承稳定的运行时基类：

```text
LlmTimeoutException extends RuntimeException
├── FirstStreamChunkTimeoutException
├── StreamChunkIdleTimeoutException
└── LlmQueryAttemptTimeoutException
```

普通错误分类不得通过类名、message 或 error code 猜测 timeout。Story 1 classifier 在 cause chain 中识别 `LlmTimeoutException` 基类，并在 Story 2 阶段将三种 subtype 全部作为 hard exclusion。Spring AI、WebClient 和 Story 1 聚合边界可以增加外层包装，但必须保留可遍历到原始 timeout subtype 的 cause chain。

### 10.2 `FirstStreamChunkTimeoutException`

表示当前 HTTP/SSE request 从订阅/发出到 headers 超时，或按时收到 2xx 流式 headers 后等待首个 body Chunk 超过同一配置窗口。

### 10.3 `StreamChunkIdleTimeoutException`

表示当前 HTTP/SSE request 已收到至少一个 Chunk，随后超过 hard idle 窗口没有新 Chunk。

### 10.4 `LlmQueryAttemptTimeoutException`

表示 Story 1 的单次完整 query attempt 超过绝对上限。

异常建议提供结构化 getter：

```text
configuredTimeout
effectiveTimeout
deadlineOwner
elapsed
observedChunkCount
logicalCallId
modelId
```

Story 2 的异常只携带事实，不实现 retry marker，不声明自身可重试。Story 1 当前通过公共基类把三类 timeout 作为 hard exclusion，不创建下一 query attempt。Story 3 后续只允许在通用 timeout hard exclusion 之前精确处理 `FirstStreamChunkTimeoutException` 和 `StreamChunkIdleTimeoutException`，并复用 Story 1 的同一预算、backoff、attempt 丢弃和 query 重启能力；`LlmQueryAttemptTimeoutException` 始终不可重试。

## 11. 配置与兼容

建议配置：

```yaml
ai:
  client:
    streaming:
      connect-timeout: 10s
      first-chunk-timeout: 45s
      stall-threshold: 30s
      chunk-idle-timeout: 90s
      query-attempt-timeout: 150s

spring:
  ai:
    trading:
      tool-timeout: 60s
      node-timeout: 240s
      run-timeout: 15m
```

兼容规则：

- `timeout-mode` 是全局配置，不支持模型级 override；默认值为 `layered`，正常部署无需显式配置。
- 仅在新分层 watchdog 出现兼容性问题时显式配置 `timeout-mode: legacy`，临时回滚到当前 first-content、有效文本 idle 和外层 total timeout 行为。`application.yml` 以注释形式保留该回滚开关及用途说明。
- `layered` 模式禁用旧 30 秒有效文本 idle 硬中断。
- `layered` 模式不再以外层有效文本作为首响应信号；45 秒由首个原始 body Chunk结束。
- 模型级和全局新旧字段按“模型级新字段 > 模型级旧字段 fallback > 全局新字段 > 全局旧字段 fallback > 代码默认值”解析。
- 旧 `first-content-timeout` 是 `first-chunk-timeout` 的短期 fallback；旧 `idle-timeout` 是 `stall-threshold` 的短期 fallback，但不再执行硬中断；旧 `total-timeout` 是 `query-attempt-timeout` 的短期 fallback，并改用绝对 attempt 语义。
- 同一层级同时配置新旧字段时新字段优先，并对被替代或作为 fallback 使用的旧字段记录一次弃用告警。
- 原始绑定对象中的新旧可迁移字段保持 nullable，由 resolver 统一应用优先级并在最后补充代码默认值；不得用字段初始化值掩盖“用户是否显式配置”。
- layered 配置必须在启动时满足：所有 Duration 为正，且 `connectTimeout < firstChunkTimeout < queryAttemptTimeout`、`stallThreshold < chunkIdleTimeout < queryAttemptTimeout`。非法配置直接启动失败，不静默交换、裁剪或修正。
- layered 稳定一个版本后删除 legacy operator 和弃用字段。

紧急回滚配置：

```yaml
ai:
  client:
    streaming:
      timeout-mode: legacy
```

## 12. 与 Story 1/3 的边界

### 12.1 Story 1

- Story 1 是唯一应用层 stream query retry owner。
- Story 1 原子聚合一个 query attempt 的 `ChatResponse`，但 Story 2 watchdog 位于该聚合之前。
- Story 1 的 ordinary error classifier 只依赖稳定的 `LlmTimeoutException` 公共基类执行 hard exclusion，不通过 message 或具体 SSE subtype 扩展普通网络错误范围。
- Story 2 不修改 Story 1 的 attempt 预算、退避或结果隔离。

### 12.2 Story 3

- Story 3 在 Story 1 classifier 中精确识别 `FirstStreamChunkTimeoutException` 和 `StreamChunkIdleTimeoutException`。
- Story 3 决定哪些 SSE timeout 可以消费现有 retry credit。
- Story 3 timeout retry 仍然重启完整 query attempt，接受 Story 1 已确认的 at-least-once 工具风险。
- Story 3 不修改 WebClient watchdog，不创建第二套 retry state。
- `LlmQueryAttemptTimeoutException` 表示 attempt 预算耗尽，始终不可重试。

## 13. 同步 `call()` 兼容

`RetryChatModel.call()` 继续使用现有 `CallRetryStrategy`。同步 `OpenAiChatModel.call()` 继续经 `OpenAiApi` 的 RestClient 和既有 connect/read timeout 执行。

Story 2 不得：

- 修改 `RetryChatModel.call()`；
- 修改同步 `RetryStrategy` 的 timeout 分类；
- 将 streaming timeout 配置绑定到 RestClient；
- 改变同步 retry 次数和 backoff。

必须用回归测试锁定同步 429/5xx 重试、context overflow 压缩和 RestClient timeout 行为不变。

### 13.1 Streaming JDK timeout 叠加约束

WebClient/JDK HttpClient 只保留 10 秒 connect timeout，不设置 `JdkClientHttpConnector#setReadTimeout` 或 `HttpRequest.timeout()`，避免新增覆盖整个 request 的 JDK timeout 与 45/90/150 秒发生竞争。`HttpConnectTimeoutException` 继续按现有 timeout hard exclusion 传播，不触发 Story 1 ordinary retry；`ConnectException`、connection refused 和 connection reset 仍按 Story 1 普通传输错误规则处理。Spring AI streaming 路径、WebClient filter 和本 Story 均不得增加隐藏 retry。

## 14. 可观测性

### 14.1 `llm_stream_stall`

```text
logicalCallId
modelId
streamRequestId（如可得）
configuredThresholdMs
elapsedSinceLastChunkMs
observedChunkCount
roundSequence（如可得）
```

同一 Chunk 间隔只记录一次 stall。新 Chunk 到达后允许下一间隔再次记录。

### 14.2 `llm_stream_timeout`

```text
timeoutType=FIRST_CHUNK|CHUNK_IDLE|QUERY_ATTEMPT
configuredTimeoutMs
effectiveTimeoutMs
deadlineOwner=FIRST_CHUNK|CHUNK_IDLE|QUERY_ATTEMPT
elapsedMs
observedChunkCount
logicalCallId
modelId
```

ID 进入日志或 trace，不作为高基数 Micrometer tag。`timeoutType` 和 `deadlineOwner` 可以作为低基数 tag。日志不得包含完整 Prompt、响应正文、tool 参数、API Key 或用户敏感信息。每个 timeout 最多记录一次；cancel、decode error 和正常 EOF 不得误记 timeout。`activeChunkWatchdogCount` 在所有终止路径后归零，timer 创建/取消计数主要用于测试，不按请求 ID 建立指标。

## 15. 资源与并发约束

- filter 只包装并转发现有 `DataBuffer`，不得缓存或复制 body。
- `ClientResponse` 必须使用 `mutate().body(original -> watchdog(original))` 做函数式 body 变换；禁止先取得原 body 再调用 `body(Flux)` 替换，也禁止额外订阅原 body 进行探测或释放。
- 正常转发给下游的 `DataBuffer` 由下游继续消费，filter 不得 release；因 timeout/cancel 竞争已产生但未转发的 pooled buffer 必须恰好 release 一次。
- response headers 已返回但订阅 body 时 deadline 已过，watchdog 仍须对原 body 建立唯一一次订阅并立即取消，然后传播 timeout；不得只返回 `Flux.error` 而遗留未消费的 response body。
- timeout/cancel 必须取消当前 HTTP exchange 或 body subscription；具体取决于首 Chunk deadline 到期时是否已经收到 response headers。
- complete/error/cancel/timeout 均清理 stall 和 idle timer。
- cancel、attempt timeout 与 Chunk timeout 使用 subscription-local 终止仲裁；只允许获胜者传播终止信号和记录 timeout，落败 timer 必须立即清理。
- 同一个 WebClient 的并发请求拥有完全隔离的状态、计数和 timer。
- 同一个 Flux 多次订阅时，每次订阅创建新 policy 和 watchdog。
- filter Bean、`RetryChatModel`、WebClient 和 Scheduler 可以共享，但不得保存当前 policy、generation、Chunk count、Disposable 或终止状态；这些可变数据只在 `Flux.defer` 创建的 subscription-local 对象中存在。
- `legacy` 模式不安装 layered watchdog；`layered` 模式的目标 streaming request 缺少 policy 时抛出 `MissingStreamTimeoutPolicyException`，不得调用 `next.exchange(request)`，并记录不含敏感信息的配置契约告警。
- 工具执行期间不应存在上一轮遗留 watchdog。

## 16. 测试策略

### 16.1 WebClient body watchdog

- 45 秒内没有 response headers，或按时收到 2xx 流式 headers 但没有首个 `DataBuffer`，抛出 `FirstStreamChunkTimeoutException` 并取消当前 exchange/body。
- response headers 在第 46 秒才到达时，必须在第 45 秒取消 exchange，不能等 headers 返回后才报错。
- response headers 在第 20 秒到达、首个 body Chunk 在第 50 秒到达时，必须在第 45 秒取消 body；headers 与 body 不得各自获得 45 秒。
- 首 Chunk 在 45 秒内到达，之后进入 STREAMING。
- 相邻 Chunk 间隔 35 秒，只记录 stall，请求继续。
- 使用 `VirtualTimeScheduler` 验证首 Chunk 后推进 29 秒无 stall、推进到 30 秒恰好一条、继续到 89 秒仍只有一条，90 秒由 hard idle timeout 终止并清理 stall task。
- 新 Chunk 到达时取消上一代 stall task 并创建新 generation；旧 task 已开始执行时也因 generation 不匹配而退出，同一个 gap 不重复记录。
- 收到首 Chunk 后 90 秒没有新 Chunk，抛出 `StreamChunkIdleTimeoutException`。
- 每个 Chunk 都重置 30/90 秒 timer。
- heartbeat/comment、metadata、reasoning、文本和 tool-call 字节均刷新 timer。
- 半个 SSE event 的 DataBuffer 也刷新 timer，不要求协议完整。
- response headers 到达但 body 未到，仍受首 Chunk 45 秒约束。
- `400/429/500/503` headers 按时返回但错误 body 缓慢、为空或只返回部分内容时，不得抛出两类 SSE timeout，最终错误必须保留真实 HTTP status。
- response 使用 `body(original -> watchdog(original))` 后原 body 只订阅一次；禁止 `body(Flux)` 提前释放原 body。
- headers 到达后延迟订阅 body，或订阅时首 Chunk deadline 已过，均准确取消唯一的原 body subscription，且 pooled buffer 无泄漏或 double release。
- empty complete、upstream error、cancel 路径均无遗留 timer。
- 2xx empty complete、缺失 `[DONE]` 的正常 EOF、半帧 decode error 和部分 tool-call 输入均不被改写为 timeout；业务完整性结论留给后续 completion-integrity Story。
- timeout 与 cancel 竞争时只传播一个终止信号。

### 16.2 Spring AI 工具循环契约

- 第 1 轮返回 tool-call 并正常结束。
- 工具执行 40 秒，超过 30 秒 stall threshold。
- 工具期间 `activeChunkWatchdogCount == 0`，不记录 stall，不抛 Chunk idle timeout。
- 第 2 轮重新获得完整 45 秒首 Chunk 窗口并正常完成。
- 多次连续 tool-call 的每轮 HTTP/SSE request 均拥有独立 watchdog。
- Reactor Context 在 Spring AI 1.1.2 的 `internalStream()` 递归后仍可见。
- Story 1 ordinary retry、context overflow 压缩重订阅和多轮工具递归均保留 policy；主动移除 Context 时 layered 模式在 HTTP exchange 前以 `MissingStreamTimeoutPolicyException` fail closed。
- 同一 TradingAgent 的四个并发 role 共享模型、filter、WebClient 和 Scheduler，但 policy、attempt deadline、watchdog、stall generation、Chunk count 与 cancel 完全隔离；一个 role 的 stall/timeout/cancel 不影响其余 role。

### 16.3 Query attempt

- 每次 Story 1 attempt 获得新的 150 秒名义窗口。
- attempt 1 在 80 秒失败并退避 2 秒后，attempt 2 重新获得完整 150 秒；本 Story 不按 node/run 剩余时间裁剪。
- attempt 内持续收到 Chunk 也不能刷新 150 秒绝对 deadline。
- first/idle deadline 严格早于 attempt deadline 时抛对应 SSE timeout；两者完全同刻时必须抛 `LlmQueryAttemptTimeoutException`，并以虚拟时间覆盖前 1ns、同刻和后 1ns。
- `LlmQueryAttemptTimeoutException` 不触发 Story 1 retry。

### 16.4 跨 Story 与回归

- Story 2 的三类 timeout 在 Story 1 classifier 中均为 hard exclusion。
- timeout 后 Story 2 阶段不创建下一 query subscription。
- Story 1 ordinary connect/reset/429/目标 5xx 重试继续有效。
- `HttpConnectTimeoutException` 不触发 Story 1 retry；connection refused/reset 仍可重试，且请求链不存在 JDK read/request timeout 或 Spring AI/WebClient 隐藏 retry。
- Story 1 原子 attempt 成功前下游零 `ChatResponse`。
- 同步 `call()`、压缩、取消、并发和配置解析回归通过。
- 未配置 `timeout-mode` 时默认 layered；显式 `legacy` 回滚行为锁定。配置测试覆盖全局/模型级新字段、旧字段 fallback、新旧冲突优先级、弃用告警和所有非法时长关系。
- Trading node timeout 后 cancel 必须到达实际 Reactor/HTTP subscription，且不得再启动 Story 1 retry、HTTP request 或工具轮次；不强行断言不响应取消的外部工作已经停止。

## 17. 验收标准

| 编号 | 验收项 | 标准 |
|---|---|---|
| AC-001 | Query attempt timeout | 每个 attempt 使用固定 150 秒绝对窗口，不被 Chunk 刷新 |
| AC-002 | First Chunk | 每轮 HTTP/SSE 请求 45 秒无首 Chunk 时准确取消和分类 |
| AC-003 | Stall | 首 Chunk 后 30 秒无新 Chunk 只记录，不中断 |
| AC-004 | Chunk idle | 首 Chunk 后 90 秒无新 Chunk 准确取消和分类 |
| AC-005 | 工具隔离 | 工具执行 40 秒不占用任一 SSE watchdog |
| AC-006 | 新轮重置 | 工具后的下一轮请求重新获得完整首 Chunk 窗口 |
| AC-007 | Story 隔离 | Story 2 不执行 retry，Story 1 timeout 不重试，Story 3 扩展点稳定 |
| AC-008 | 本地预算 | 45/90 秒不突破当前 150 秒 attempt deadline；同刻到期时 attempt timeout 优先 |
| AC-009 | 资源释放 | complete/error/cancel/timeout 后 timer 和 body subscription 均清理 |
| AC-010 | 并发隔离 | 并发请求和多次订阅不共享可变 watchdog 状态 |
| AC-011 | 同步兼容 | `call()`、RestClient timeout 和同步 retry 语义不变 |
| AC-012 | 配置兼容 | legacy 可回滚，旧字段可迁移，新字段优先且语义明确 |
| AC-013 | HTTP status 优先 | 非 2xx 不进入 SSE body watchdog，慢/空错误 body 不丢失真实 status |
| AC-014 | Context 契约 | layered 模式缺少 policy 时 fail closed；retry、压缩和工具递归保持 policy |
| AC-015 | Body 所有权 | 原 body 单次消费，转发和丢弃 buffer 所有权明确，无提前释放、泄漏或 double release |
| AC-016 | Stall 生命周期 | 每个 gap 最多记录一次，Chunk 刷新 generation，所有终止路径清理 task，四 role 并发隔离 |
| AC-017 | Scheduler | 终止型 timeout 复用 Reactor operator，生产共享 Scheduler，虚拟时间测试不真实等待 |
| AC-018 | Completion 边界 | empty complete、正常 EOF、decode/tool-call 异常不误报 timeout，内容完整性留给后续 Story |
| AC-019 | JDK timeout | connect timeout 不重试，不叠加 read/request timeout 或隐藏 stream retry |
| AC-020 | 配置解析 | layered 默认启用，legacy 可显式回滚，新旧字段优先级与时长关系校验稳定 |

## 18. 风险记录

- `DataBuffer` 不是完整 SSE event；慢速无效字节可能持续刷新 idle。该层只保证传输活性，协议正确性由 Spring AI 和 150 秒 attempt deadline 兜底。
- empty complete、缺失 `[DONE]` 或语法可解析但业务不完整的 tool-call 可能被 Spring AI 视为正常完成；Story 2 明确不在 raw body 层判断内容完整性，后续 completion-integrity Story 必须在解码后治理该风险。
- WebClient filter 必须让 `next.exchange(request)` 和首个 body Chunk 共享同一个 45 秒绝对 deadline；只包装 body 会遗漏 headers 前时间，给 exchange/body 分别设置 45 秒又会把总窗口错误放大到最多 90 秒。
- Spring AI 升级可能改变 Reactor Context 或工具递归实现；真实工具循环契约测试是升级门禁。
- Story 1 正在开发，Story 2 合并时必须确认 watchdog 位于原子 attempt 聚合之前。
- 240 秒 node timeout 不向模型层下传；node 临近到期时底层仍按本地 45/90/150 秒判断，只能依赖外层 cancel 终止实际 subscription。取消传播失败、工具不响应中断或 Provider 已接收工作的残余风险必须通过测试、晚提交拒绝和业务幂等约束控制。
- 不经过 Trading node 的调用没有 240 秒整体 query 上限；多个 150 秒 retry attempt 加 backoff 可能显著超过单次 attempt timeout，该整体 SLA 不属于 Story 2。

## 19. 推荐结论

Story 2 采用“每 query attempt 150 秒绝对上限 + 每个成功 HTTP/SSE response 的两阶段 Chunk watchdog”：45 秒从 request subscription 起覆盖 headers 与首个原始 `DataBuffer`，首 Chunk 后 30 秒 stall 由 subscription-local `StallObserver` 只观测一次，90 秒 hard idle 主动取消；终止型 timeout 复用 Reactor `.timeout()`，45/90 秒不得突破当前 attempt deadline，同刻到期时 attempt timeout 优先。非 2xx 保留 HTTP status，不进入 SSE body watchdog。layered 默认启用，legacy 只作为显式临时回滚；layered 模式缺少 policy 时 fail closed，`ClientResponse` body 只做单次函数变换。JDK HttpClient 只保留 connect timeout 且不重试，不叠加 read/request timeout。Trading node/run deadline 不在本 Story 下传，内容完整性在解码后的独立 Story 治理；Story 2 只产生结构化 timeout 并取消当前工作，Story 3 再将选定 SSE timeout 接入 Story 1 的同一 query retry 基础设施。
