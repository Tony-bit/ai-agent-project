# Story 2：LLM SSE Chunk 分层超时设计

## 1. 文档状态

- 状态：设计讨论已确认，等待实现计划。
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
- 当前 HTTP 请求在 45 秒内没有首个 body Chunk 时主动取消并抛出结构化异常。
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
- 不在本 Story 实现 Trading run scope；只定义与既有节点和未来 run deadline 的预算关系。

## 4. 已确认决策

| 编号 | 决策 | 说明 |
|---|---|---|
| D-001 | 每个 query attempt 独立获得 150 秒绝对 timeout | Story 1 retry 创建新 attempt 时重新计算窗口 |
| D-002 | 父级 deadline 优先 | 实际 attempt deadline 受 node/run 剩余预算裁剪 |
| D-003 | Trading node 默认 timeout 从 180 秒提高到 240 秒 | 给一次失败较早的 retry attempt 留出可用窗口 |
| D-004 | 首 Chunk timeout 为 45 秒 | 从当前 HTTP 请求订阅/发出开始，覆盖等待 headers 和首个 body Chunk |
| D-005 | 30 秒是 stall 观测阈值 | 只记录，不中断 |
| D-006 | 90 秒是 Chunk hard idle timeout | timeout 时取消当前 response body 并抛错 |
| D-007 | Chunk 定义为 WebFlux 原始 `DataBuffer` | 判断传输活性，不等待用户可见文本或完整 SSE event |
| D-008 | 每个 HTTP/SSE request 独立维护 watchdog | 不跨工具执行，不跨 Spring AI 内部模型轮次 |
| D-009 | Story 2 timeout 暂不重试 | Story 3 再接入 Story 1 的同一 query retry 基础设施 |
| D-010 | layered 与 legacy 短期并存 | layered 稳定后删除旧文本 timeout |

## 5. 分层 timeout 模型

| 层级 | 默认值 | 起点 | 结束或刷新 | 行为 |
|---|---:|---|---|---|
| TCP connect | 10 秒 | 单次 HTTP 建连 | 建连成功 | 连接失败按现有普通错误传播 |
| First body Chunk | 45 秒 | 当前 HTTP 请求订阅/发出 | 收到首个 `DataBuffer` | 取消当前 HTTP exchange/body，抛出 `FirstStreamChunkTimeoutException` |
| Chunk stall | 30 秒 | 每个已收到的 Chunk | 收到下一 Chunk | 记录 `llm_stream_stall`，不终止 |
| Chunk hard idle | 90 秒 | 收到首个及后续 Chunk | 收到下一 Chunk | 取消当前 body，抛出 `StreamChunkIdleTimeoutException` |
| Tool | 60 秒 | 单次工具调用开始 | 工具结束 | 由工具治理 Story 负责 |
| Query attempt | 150 秒 | Story 1 创建一次 delegate subscription | attempt complete/error/cancel | 取消 attempt，抛出 `LlmQueryAttemptTimeoutException` |
| Trading node | 240 秒 | 节点开始 | 节点完成/失败 | 取消节点并拒绝晚提交 |
| Trading run | 15 分钟 | Pipeline 开始 | Pipeline 终止 | 未来 run scope 负责 |

### 5.1 父子预算

每个 Story 1 retry attempt 都重新获得名义上的 150 秒窗口，但不能突破父级 deadline：

```text
attemptDeadline = min(
    attemptStartedAt + 150s,
    nodeDeadline,
    runDeadline
)
```

backoff 不占用下一 attempt 的 150 秒名义窗口，但会消耗固定的 node/run 剩余时间。子层 timeout 同样不得超过当前 attempt 和父级剩余预算：

```text
effectiveFirstChunkTimeout = min(45s, attemptRemaining, nodeRemaining, runRemaining)
effectiveChunkIdleTimeout  = min(90s, attemptRemaining, nodeRemaining, runRemaining)
effectiveToolTimeout       = min(60s, attemptRemaining, nodeRemaining, runRemaining)
```

如果当前工程尚未向模型层传递 node/run deadline，则 Story 2 先保证本地 45/90/150 秒上限；父级裁剪作为明确扩展契约，由后续 deadline Story 接入。

## 6. `DataBuffer` 语义

`DataBuffer` 是 WebFlux 对 HTTP response body 原始字节块的抽象，位于 Spring AI 的 SSE、JSON、tool-call 和 `ChatResponse` 解码之前。

一个 `DataBuffer` 可能包含：

- metadata、reasoning、文本 token、tool-call、finish reason 或 usage 对应的 SSE 字节；
- SSE comment/heartbeat；
- 半个 SSE event、半个 JSON 或半个 UTF-8 字符；
- 多个 SSE event。

因此本 Story 判断的是“当前 response body 是否仍有原始字节流入”，而不是“模型是否产生有效文本”或“SSE event 是否完整”。任意 `DataBuffer` 都结束首 Chunk 等待并刷新 stall/idle 计时器。SSE framing、JSON 和业务语义继续由 Spring AI 负责；decode error 继续作为 Story 1 hard exclusion 传播。

## 7. 状态机

每个 WebClient response body subscription 创建独立状态：

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
logicalCallId
modelId
```

`RetryChatModel.stream()` 在 layered 模式下通过 Reactor Context 写入 policy。该 Context 只传 timeout 配置和观测标识，不传 Story 1 retry budget，也不执行 retry。

### 9.2 `SseChunkTimeoutFilter`

安装在 `AiClientHttpTimeoutConfig` 提供的专用 `WebClient.Builder` 上，只在 Reactor Context 中存在 `StreamChunkTimeoutPolicy` 时生效。

职责：

- 从当前 subscription 的 Reactor Context 读取 policy；
- 在订阅 `next.exchange(request)` 前记录单调时钟起点，并计算唯一的首 Chunk 绝对 deadline；
- response headers 返回前，以同一 deadline 约束 `next.exchange(request)`；
- response headers 返回后，只把该 deadline 的剩余时间交给 body 首 Chunk watchdog，不重新获得 45 秒；
- 包装 `ClientResponse` body 的 `Flux<DataBuffer>`；
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

若 deadline 在 response headers 前到期，则取消当前 HTTP exchange；若在 headers 后、首个 `DataBuffer` 前到期，则取消 response body。两种路径统一抛出 `FirstStreamChunkTimeoutException`。实现不得先给 headers 45 秒、再给 body 45 秒，也不得等拿到 `ClientResponse` 后才启动计时。

### 9.3 Query attempt timeout

layered 模式下，`RetryChatModel` 删除基于有效文本的 first-content/idle phase 计时，只为 Story 1 的每个 delegate subscription 设置明确的 150 秒绝对 attempt timeout。该 timeout 不能由中间 `ChatResponse` 或 Chunk 刷新。

当前 `.timeout(Duration)` 表达的是相邻信号 timeout，不能继续作为绝对 attempt timeout 的隐含实现。实现必须以固定 attempt deadline 或包围整个 attempt 完成信号的单次计时器表达。

## 10. 异常契约

### 10.1 `FirstStreamChunkTimeoutException`

表示当前 HTTP/SSE request 从订阅/发出到首个 body Chunk 超过配置窗口。

### 10.2 `StreamChunkIdleTimeoutException`

表示当前 HTTP/SSE request 已收到至少一个 Chunk，随后超过 hard idle 窗口没有新 Chunk。

### 10.3 `LlmQueryAttemptTimeoutException`

表示 Story 1 的单次完整 query attempt 超过绝对上限。

异常建议提供结构化 getter：

```text
configuredTimeout
elapsed
observedChunkCount
logicalCallId
modelId
```

Story 2 的异常只携带事实，不实现 retry marker，不声明自身可重试。Story 1 当前必须把三类 timeout 作为 hard exclusion，不创建下一 query attempt。Story 3 后续只对选定的 SSE timeout 类型改变 retry decision，并复用 Story 1 的同一预算、backoff、attempt 丢弃和 query 重启能力；`LlmQueryAttemptTimeoutException` 始终不可重试。

## 11. 配置与兼容

建议配置：

```yaml
ai:
  client:
    streaming:
      timeout-mode: layered
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

- `legacy` 模式维持当前 first-content、有效文本 idle 和外层 timeout 行为。
- `layered` 模式禁用旧 30 秒有效文本 idle 硬中断。
- `layered` 模式不再以外层有效文本作为首响应信号；45 秒由首个原始 body Chunk结束。
- 旧 `first-content-timeout` 可作为 `first-chunk-timeout` 的短期 fallback，并记录弃用日志。
- 旧 `idle-timeout` 可作为 `stall-threshold` 的短期 fallback，不再执行硬中断。
- 旧 `total-timeout` 可作为 `query-attempt-timeout` 的短期 fallback，但 layered 模式使用绝对 attempt 语义。
- 模型级 override 新增对应字段，新字段优先于旧字段。
- layered 稳定一个版本后删除 legacy operator 和弃用字段。

## 12. 与 Story 1/3 的边界

### 12.1 Story 1

- Story 1 是唯一应用层 stream query retry owner。
- Story 1 原子聚合一个 query attempt 的 `ChatResponse`，但 Story 2 watchdog 位于该聚合之前。
- Story 1 的 ordinary error classifier 不得把 Story 2 timeout 当作普通网络错误。
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
elapsedMs
observedChunkCount
logicalCallId
modelId
```

ID 进入日志或 trace，不作为高基数 Micrometer tag。日志不得包含完整 Prompt、响应正文、tool 参数、API Key 或用户敏感信息。

## 15. 资源与并发约束

- filter 只包装并转发现有 `DataBuffer`，不得缓存或复制 body。
- timeout/cancel 必须取消当前 HTTP exchange 或 body subscription；具体取决于首 Chunk deadline 到期时是否已经收到 response headers。
- complete/error/cancel/timeout 均清理 stall 和 idle timer。
- cancel 与 timeout 竞争时只允许一个终止信号。
- 同一个 WebClient 的并发请求拥有完全隔离的状态、计数和 timer。
- 同一个 Flux 多次订阅时，每次订阅创建新 policy 和 watchdog。
- Reactor Context 缺少 policy 时 filter 原样透传。
- 工具执行期间不应存在上一轮遗留 watchdog。

## 16. 测试策略

### 16.1 WebClient body watchdog

- 45 秒内没有首个 `DataBuffer`，抛出 `FirstStreamChunkTimeoutException` 并取消当前 exchange/body。
- response headers 在第 46 秒才到达时，必须在第 45 秒取消 exchange，不能等 headers 返回后才报错。
- response headers 在第 20 秒到达、首个 body Chunk 在第 50 秒到达时，必须在第 45 秒取消 body；headers 与 body 不得各自获得 45 秒。
- 首 Chunk 在 45 秒内到达，之后进入 STREAMING。
- 相邻 Chunk 间隔 35 秒，只记录 stall，请求继续。
- 收到首 Chunk 后 90 秒没有新 Chunk，抛出 `StreamChunkIdleTimeoutException`。
- 每个 Chunk 都重置 30/90 秒 timer。
- heartbeat/comment、metadata、reasoning、文本和 tool-call 字节均刷新 timer。
- 半个 SSE event 的 DataBuffer 也刷新 timer，不要求协议完整。
- response headers 到达但 body 未到，仍受首 Chunk 45 秒约束。
- empty complete、upstream error、cancel 路径均无遗留 timer。
- timeout 与 cancel 竞争时只传播一个终止信号。

### 16.2 Spring AI 工具循环契约

- 第 1 轮返回 tool-call 并正常结束。
- 工具执行 40 秒，超过 30 秒 stall threshold。
- 工具期间 `activeChunkWatchdogCount == 0`，不记录 stall，不抛 Chunk idle timeout。
- 第 2 轮重新获得完整 45 秒首 Chunk 窗口并正常完成。
- 多次连续 tool-call 的每轮 HTTP/SSE request 均拥有独立 watchdog。
- Reactor Context 在 Spring AI 1.1.2 的 `internalStream()` 递归后仍可见。

### 16.3 Query attempt

- 每次 Story 1 attempt 获得新的 150 秒名义窗口。
- attempt 1 在 80 秒失败并退避 2 秒后，node 仍有 158 秒时，attempt 2 获得完整 150 秒。
- attempt 1 消耗 150 秒后，attempt 2 被固定 node deadline 裁剪。
- attempt 内持续收到 Chunk 也不能刷新 150 秒绝对 deadline。
- `LlmQueryAttemptTimeoutException` 不触发 Story 1 retry。

### 16.4 跨 Story 与回归

- Story 2 的三类 timeout 在 Story 1 classifier 中均为 hard exclusion。
- timeout 后 Story 2 阶段不创建下一 query subscription。
- Story 1 ordinary connect/reset/429/目标 5xx 重试继续有效。
- Story 1 原子 attempt 成功前下游零 `ChatResponse`。
- 同步 `call()`、压缩、取消、并发和配置解析回归通过。
- legacy 与 layered 模式行为分别锁定。

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
| AC-008 | 父级预算 | attempt 与子层 timeout 不突破 node/run 剩余 deadline |
| AC-009 | 资源释放 | complete/error/cancel/timeout 后 timer 和 body subscription 均清理 |
| AC-010 | 并发隔离 | 并发请求和多次订阅不共享可变 watchdog 状态 |
| AC-011 | 同步兼容 | `call()`、RestClient timeout 和同步 retry 语义不变 |
| AC-012 | 配置兼容 | legacy 可回滚，旧字段可迁移，新字段优先且语义明确 |

## 18. 风险记录

- `DataBuffer` 不是完整 SSE event；慢速无效字节可能持续刷新 idle。该层只保证传输活性，协议正确性由 Spring AI 和 150 秒 attempt deadline 兜底。
- WebClient filter 必须让 `next.exchange(request)` 和首个 body Chunk 共享同一个 45 秒绝对 deadline；只包装 body 会遗漏 headers 前时间，给 exchange/body 分别设置 45 秒又会把总窗口错误放大到最多 90 秒。
- Spring AI 升级可能改变 Reactor Context 或工具递归实现；真实工具循环契约测试是升级门禁。
- Story 1 正在开发，Story 2 合并时必须确认 watchdog 位于原子 attempt 聚合之前。
- 240 秒 node timeout 仍无法保证一个耗尽 150 秒的 attempt 后再获得完整 150 秒；这是已接受的父级裁剪语义。

## 19. 推荐结论

Story 2 采用“每 query attempt 150 秒绝对上限 + 每 HTTP/SSE request 两阶段 Chunk watchdog”：45 秒等待首个原始 `DataBuffer`，首 Chunk后 30 秒 stall 只观测，90 秒 hard idle 主动取消。layered 模式禁用旧的有效文本 first/idle timeout，工具执行期间不存在 SSE watchdog；Story 2 只产生结构化 timeout 并取消当前工作，Story 3 再将选定 timeout 接入 Story 1 的同一 query retry 基础设施。
