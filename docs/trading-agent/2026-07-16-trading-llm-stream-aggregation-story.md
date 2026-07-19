# Story: Trading Agent LLM 流式读取与服务端聚合

| 字段 | 内容 |
|------|------|
| 创建日期 | 2026-07-16 |
| 状态 | pending |
| 优先级 | P0 |
| 关联问题 | Trading Agent 分析节点调用 LLM 时发生 `SocketTimeoutException: Read timed out` |
| 关联 Story | `docs/trading-agent/2026-04-30-ai-client-http-timeout-story.md`、`docs/trading-agent/2026-06-01-llm-true-streaming-story.md` |
| 配套测试设计 | `docs/superpowers/test/2026-07-16-trading-llm-stream-aggregation-test.md` |

---

## 1. 背景与问题

Trading Agent 的分析、辩论、风控和决策节点目前通过：

```java
chatClient.prompt().user(prompt).call().content();
```

同步等待模型生成完整响应。`AiClientHttpTimeoutConfig` 为同步 `RestClient` 设置了 80 秒读取超时。模型在长 Prompt、服务拥塞或复杂推理场景下，首个有效文本时间或完整生成时间可能超过该值，从而在模型仍正常生成时被客户端提前中断。

本次需要解决两个不同问题：

1. 完整生成耗时超过同步读取超时。
2. 首个有效文本时间、连续文本 chunk 间隔和单次任务总耗时没有独立边界。

本次不是把每个模型 chunk 转发给前端。目标是上游使用 streaming 持续读取，后端在当前请求的 JVM 内存中聚合完整文本，模型结束后继续按现有协议一次性发送分析报告。

---

## 2. 用户故事

作为 Trading Agent 用户，我希望复杂分析在模型持续输出期间不会因为固定的同步读取超时而失败，并且仍然只在报告生成完整、解析完成后收到一次结构化结果。

作为系统维护者，我希望首个有效文本、流空闲、模型总耗时和交易节点总耗时具有明确且一致的边界，发生超时时能够通过日志判断失败阶段。

---

## 3. 目标与非目标

### 3.1 目标

- 将 12 个 Trading Agent 文本生成节点从 `.call().content()` 改为 `.stream().content()`。
- 在后端内存中按顺序聚合所有 chunk，完成后返回一个完整 `String`。
- 保持现有报告 JSON 和 SSE 事件协议不变；将 `TradingContextVO` 写入收口到统一提交门禁。
- 统一封装流式聚合逻辑，避免 12 个节点重复实现。
- 12 个节点先生成强类型业务结果，只有未取消、未超时的成功结果才允许提交并推进 Stage。
- 区分连接超时、首个有效文本超时、文本 chunk 空闲超时、模型调用总超时和节点总超时。
- 保持现有 `RetryChatModel` 的压缩和重试语义。
- 增加可执行的单元测试、回归测试和云端手工验收步骤。

### 3.2 非目标

- 不把分析节点的 token/chunk 实时发送给前端。
- 不修改前端 SSE 协议、页面渲染逻辑或报告 JSON 格式。
- 不修改 `GeneralChatNode` 已有的真流式前端输出行为。
- 不将聚合中的临时文本写入本地文件、Redis 或数据库。
- 不增加聚合响应大小上限、并发内存配额或 OOM 专项保护；本次接受该残余风险，仅依赖模型输出 token 限制和 150 秒调用总时限。
- 不改造 Trading Agent 之外的压缩、路由、巡检和 PE 节点同步调用。
- 不在本次引入断点续传、跨进程恢复或任务结果持久化。

---

## 4. 改造范围

本次改造以下 12 个节点：

| 阶段 | 节点 | clientId | status |
|------|------|----------|--------|
| 分析 | `FundamentalAnalystNode` | 6002 | append |
| 分析 | `TechnicalAnalystNode` | 6003 | append |
| 分析 | `SentimentAnalystNode` | 6004 | append |
| 分析 | `NewsAnalystNode` | 6005 | append |
| 辩论 | `BullResearcherNode` | 6006 | append |
| 辩论 | `BearResearcherNode` | 6007 | append |
| 辩论 | `ResearchManagerNode` | 6008 | append |
| 决策 | `PortfolioManagerNode` | 6009 | append |
| 风控 | `NeutralRiskAnalystNode` | 6010 | append |
| 风控 | `ConservativeRiskAnalystNode` | 6011 | append |
| 风控 | `AggressiveRiskAnalystNode` | 6012 | append |
| 推荐 | `RecommendationNode` | 6013 | append |

`IntentRoutingNode` 不在本次范围内，其输出和解析契约不是上述文本报告调用模式。

---

## 5. 方案选择

### 5.1 方案 A：每个节点自行聚合

每个节点直接调用 `.stream().content().collect(...)`。实现简单，但会复制超时、取消、日志和异常处理逻辑，后续容易出现配置不一致。

### 5.2 方案 B：在 `AbstractExecuteSupport` 统一封装（采用）

在公共执行基类提供受保护的流式聚合入口，由独立、可测试的聚合组件处理 Flux。12 个节点复用原有报告解析规则，但调整为返回强类型结果；真实 context 写入和最终 SSE 统一移到提交门禁。

采用原因：

- 与当前所有节点继承 `AbstractExecuteSupport` 的结构一致。
- 统一配置和日志，节点改动最小。
- 聚合逻辑可独立使用 Reactor 虚拟时间测试。
- 后续其他文本节点需要相同行为时可复用，但本次不扩大迁移范围。

### 5.3 方案 C：服务端落 Redis/数据库后再返回

可以支持进程重启后的恢复，但需要任务状态、幂等键、过期清理和断点语义。本次响应体较小，且用户只要求生成期间暂存，因此暂不采用。

---

## 6. 目标数据流

```text
Trading Role / Node (Prepare)
  -> ChatClient.stream().content()
  -> RetryChatModel.stream()
  -> WebClient 持续读取模型 SSE/chunk
  -> StreamingChatResponseCollector 按序追加到 StringBuilder
  -> 模型流正常完成
  -> 返回完整 String
  -> 节点解析 JSON / 校验并构建强类型业务结果
  -> 返回 NodeExecutionResult.success(value)
Stage / NodeResultCommitter (Commit)
  -> 校验 result=SUCCESS、scope=RUNNING、deadline 未过期且 phase 正确
  -> 原子取得提交权
  -> 写入 TradingContextVO
  -> 标记 scope=COMMITTED
Stage (Post-Commit)
  -> 将现有 analyst_report / debate / risk / recommendation SSE 事件异步入队
  -> 根据已提交结果和阶段成功策略推进 phase
```

聚合内容只存在当前方法调用的局部 `StringBuilder` 中：

- 每次 LLM 调用独立，不共享可变状态。
- 正常完成后由现有业务对象持有解析结果。
- 超时、取消或异常时丢弃不完整字符串。
- 节点在 Prepare 阶段不写真实 `DynamicContext`；迟到的完整结果同样会被提交门禁丢弃。
- JVM 退出时不保证恢复，这是本次明确接受的行为。

---

## 7. 核心设计

### 7.1 公共流式聚合入口

`AbstractExecuteSupport` 增加统一入口，节点不直接操作 Flux：

```java
protected String collectStreamingResponse(
        ChatClient.ChatClientRequestSpec requestSpec,
        String operationName,
        SseEventSink sseEventSink)
```

职责：

- 调用 `requestSpec.stream().content()`。
- 将 Flux 交给聚合组件。
- 记录操作名、首个有效文本耗时、总耗时、chunk 数和响应长度。
- 通过只读的 `SseEventSink` cancellation signal 检查请求是否仍允许继续；关闭后取消订阅。
- 正常完成后返回完整字符串，异常时不返回部分结果。

该公共入口不接收 Trading 模块的 `NodeExecutionScope` 或可写 `DynamicContext`，避免 domain 公共能力反向依赖 trading。Stage 在调用 Role 前从 context 提取只读业务输入和 sink；节点 deadline/提交资格继续由 trading 层 scope 管理。

### 7.2 聚合规则

- 使用请求局部 `StringBuilder`，按 Flux 发射顺序追加非空 chunk。
- 不使用 `String::concat`，避免长文本反复复制。
- 空 Flux 正常聚合为空字符串，由节点现有兜底解析处理。
- 任何异常、超时或取消都清理局部状态并向上抛出，不发送残缺报告。
- 聚合器不实现首内容、空闲或模型总超时，也不增加 `retryWhen()`；它消费的 `RetryChatModel` Flux 已经具有完整的超时和重试边界。
- 同步阻塞等待有界 Flux 完成是可接受的，因为现有节点本身就是同步 `doApply()` 契约，且由交易任务线程池承载。

### 7.3 超时分层

建议默认值如下，最终通过配置属性提供：

| 层级 | 默认值 | 语义 | 处理 |
|------|--------|------|------|
| TCP 连接超时 | 10 秒 | 无法建立到模型服务的连接 | 传输层抛出连接异常，进入现有重试判定 |
| 首个有效文本超时 | 45 秒/attempt | 模型订阅后长期没有非空文本 delta | `RetryChatModel` 根据 attempt 状态决定重试或失败 |
| 文本 chunk 空闲超时 | 30 秒 | 已收到有效文本，但后续非空文本长期不再到达 | `RetryChatModel` 取消本次流并失败，不重放已输出内容 |
| 模型调用总超时 | 150 秒 | 一次逻辑 LLM 调用的硬截止时间，包含全部 attempt、退避和压缩 | `RetryChatModel.stream()` 外层取消整个调用 |
| Trading 节点总超时 | 180 秒 | 包含取数、Prompt 构建、模型全部重试和解析 | 外层只取消超时节点并阻止 late write/SSE |

约束：

- `node-timeout` 必须大于 `model-total-timeout`，默认保留 30 秒业务处理余量。
- 分析师并行阶段为每个节点建立独立 deadline，不再按分析师数量乘算；一个节点超时只取消该节点。
- 并行阶段在所有节点成功、失败或超时后立即结束；至少一个成功时沿用 partial success，全部失败时结束流程。
- 配置值必须大于零；启动时校验关系，不允许以不一致配置静默运行。

建议配置结构：

```yaml
ai:
  client:
    streaming:
      connect-timeout: 10s
      first-content-timeout: 45s
      idle-timeout: 30s
      total-timeout: 150s

spring:
  ai:
    trading:
      node-timeout: 180s
```

上述配置是全局默认策略。所有通过 `AiClientModelNode` 装配的模型当前都会包装为 `RetryChatModel`；即使数据库关闭 retry，也会使用 `enabled=false, maxAttempts=1` 的有效配置，因此不需要新增第二条模型装配链路。

个别模型确有差异时，可在现有模型 `extParam` JSON 中覆盖，不在业务节点中硬编码：

```json
{
  "streamingTimeout": {
    "firstContentTimeoutMs": 60000,
    "idleTimeoutMs": 30000,
    "totalTimeoutMs": 150000
  }
}
```

未配置 `streamingTimeout` 时继承全局 `45s/30s/150s`。覆盖配置在 `AiClientModelNode` 装配模型时与全局默认合并并校验，必须满足正数约束且有效 `totalTimeout` 小于节点总时限。第一版所有 Trading 节点使用全局默认值，待积累 `firstContentLatencyMs` 的 P95/P99 后再按模型覆盖。

### 7.4 WebClient、RestClient 与超时职责

- 同步 `.call()` 继续使用现有 `RestClient` 配置，本次不删除兼容能力。
- `.stream()` 使用 WebClient；TCP 连接超时由当前 classpath 可用的连接器配置，不新增 Reactor Netty 依赖。
- 不复用同步 80 秒 `READ_TIMEOUT_MS` 作为 streaming 总时限。
- 传输层只负责连接保护，不把网络首字节、SSE heartbeat 和业务首文本混为同一语义。
- 首个有效文本、文本空闲和模型调用总时限统一在 `RetryChatModel` 的 Reactor 链中实现，聚合器不做二次超时。

### 7.5 重试与部分结果

沿用现有 `RetryChatModel.stream()` 契约，并将现有 attempt 局部的 `AtomicBoolean emitted` 替换为 attempt 局部的 `AtomicReference<StreamPhase>`：

```text
AWAITING_RESPONSE  -> 尚未收到任何 ChatResponse
RESPONSE_OBSERVED  -> 收到 role、usage、空 delta 或工具信息，但没有非空文本
CONTENT_OBSERVED   -> 已收到至少一个非空文本 delta
```

- `StreamPhase` 在每次 `streamAttempt()` 中创建；同一逻辑调用的重试预算和 150 秒总 deadline 由 `StreamState` 共享，不同节点和不同 LLM 调用完全隔离。
- `AWAITING_RESPONSE` 下首内容超时：按数据库中的 RetryConfig 和剩余总预算退避重试。
- `RESPONSE_OBSERVED` 下首内容超时：直接失败，不重试，避免空 delta 后的工具或其他副作用被重放。
- 第一个非空文本将状态切换为 `CONTENT_OBSERVED`，结束 45 秒首内容计时并启动 30 秒文本空闲计时；之后每个非空文本重置空闲计时。
- `CONTENT_OBSERVED` 后发生空闲超时或其他异常：直接传播，不自动重放。
- 聚合器不会在 `RetryChatModel` 外再增加第二套重试，避免重试预算重复计算。
- 每次订阅的局部聚合状态独立，失败尝试的内容不会拼入后续尝试。
- 上下文溢出压缩仍由 `RetryChatModel` 负责，聚合器不识别 1261 或修改 Prompt。

### 7.6 前端与 SSE 兼容

- 前端不会收到 LLM 原始 chunk。
- `analyst_progress` 等现有进度事件保持不变。
- 只有完整内容成功解析后才发送现有报告事件。
- 失败时走现有 pipeline 错误或 partial success 规则。
- `GeneralChatNode` 仍保持 chunk 到达即发送 SSE 的真流式体验，不使用本聚合模式替换。

### 7.7 取消与中断

- `SseEventSink` 增加请求级 `cancellationSignal()`；没有 SSE 的子任务/后台调用使用永不触发的信号。
- `TradingSseSession` 使用一次性 Reactor Sink 承载取消信号，在浏览器断连、heartbeat/业务事件写出失败、队列背压关闭或 session 进入失败状态时只触发一次。
- Controller 现有 `onCompletion/onTimeout/onError` 和 writer 写失败路径继续负责发现断连，不要求浏览器额外发送取消请求；10 秒 heartbeat 提供静默期间的断连探测。
- 聚合器把请求级取消信号绑定到当前模型 Flux。信号触发后取消订阅，取消继续传播到 `RetryChatModel`、原始 `delegate.stream()` 和 WebClient 响应连接。
- 取消必须以明确的 `ClientDisconnectedException` 或等价取消异常结束，不能把当前 `StringBuilder` 的部分内容当作正常完成结果。
- 同一 Trading HTTP 请求的并发 LLM 调用共享取消信号；任一浏览器连接断开会取消该请求下全部在途模型流。不同请求之间的信号完全隔离。
- 当前任务已终止、SSE 已关闭或线程被中断时，取消上游订阅。
- `TradingNodeInvoker` 超时调用 `future.cancel(true)` 后，聚合等待必须响应中断，不能继续后台消费模型输出。
- 取消后不得写入真实 `TradingContextVO`、发送 late SSE 或推进 phase。

取消通过关闭现有模型 streaming 连接实现。大多数 OpenAI 兼容供应商会在连接关闭后停止生成，但是否立即停止服务端计算和计费取决于供应商；本次不假设存在额外的任务 ID 取消 API。

### 7.8 节点结果、提交门禁与 Stage 推进

本次采用轻量的两阶段式提交，但不引入数据库事务或分布式 2PC：

1. **Prepare**：Role 完成取数、LLM 流式聚合、JSON 解析和业务校验，只生成强类型结果，不修改真实 `DynamicContext`，不发送最终报告 SSE。
2. **Commit**：Stage 调用统一 `NodeResultCommitter` 校验结果状态和执行作用域，取得提交权后只把强类型结果写入 `TradingContextVO` 并标记 `COMMITTED`。
3. **Discard**：失败、超时、取消或超过 deadline 后才返回的完整结果均被丢弃，不能推进 phase。
4. **Post-Commit**：Stage 只对 `COMMITTED` 结果发送最终报告 SSE 并计算 phase；SSE 异步入队，不属于 CAS 临界区。

核心对象：

```java
NodeExecutionResult<T> {
    NodeExecutionStatus status; // SUCCESS / FAILED / TIMED_OUT / CANCELLED
    T value;
    Throwable error;
    NodeExecutionScope scope;
}

NodeExecutionScope {
    Instant deadline;
    AtomicReference<NodeExecutionState> state;
    CancellationSignal cancellationSignal;
}
```

`NodeExecutionResult` 判断是否存在通过解析和校验的成功结果；`NodeExecutionScope` 判断该结果当前是否仍有提交资格。仅检查 `result.isSuccess()` 不足以阻止超时后返回的完整迟到结果。

提交状态遵循以下单向转换：

```text
RUNNING -> COMMITTING -> COMMITTED
RUNNING -> FAILED
RUNNING -> TIMED_OUT
RUNNING -> CANCELLED
```

- `NodeResultCommitter` 只接受 `SUCCESS`，并通过 CAS 从 `RUNNING` 取得 `COMMITTING`。
- 取得提交权前必须再次检查 deadline、请求取消信号和 Stage 当前 phase。
- 超时处理必须先把 scope 从 `RUNNING` 切到 `TIMED_OUT`，再执行 `future.cancel(true)`；因此后台任务即使不响应中断，也无法提交迟到结果。
- 如果提交与超时同时竞争，只有一个状态转换能够成功：超时先成功则丢弃结果；截止时间内提交先成功则完成短小的原子提交，外层按成功处理。
- Commit 区域只包含一次 context 结果赋值和 `COMMITTED` 状态记录，不包含 SSE、phase 推进、LLM、网络、解析或其他业务计算。
- Role 不再拥有真实上下文的写权限。执行所需数据在调用前提取为只读输入；进度 SSE 通过带 scope 检查的通道发送，最终报告 SSE 仅在 Commit 成功后由 Stage 异步入队。
- SSE 入队成功不代表浏览器已经收到，Committer 不等待 writer 确认，因此不会把客户端网络时延引入业务主链路。
- 最终 SSE 入队失败或稍后 writer 写出失败时，触发请求级 cancellation signal 并停止后续 Stage；已经提交的 context 不回滚。

Stage 是后续业务是否继续的唯一决策点：

- 四个分析师并行阶段：至少一个 `COMMITTED` 时允许 partial success 并进入 `INVESTMENT_DEBATE`；全部失败、超时或取消时进入 `ERROR`，不创建辩论上下文。
- 其他串行 Stage：当前必需节点提交成功后才能推进；失败、超时或取消时进入 `ERROR`，后续 Stage 不执行。
- `TradingPipeline` 继续通过 `expectedPhase()` / `nextPhase()` 校验阶段顺序，但各 Stage 不得再无条件调用 `transitionTo(nextPhase)`。

该方案借鉴 Codex 的任务级 cancellation token、明确 interrupted 终态和 abort 收尾，以及 OpenCode 的显式 aborted/error 状态与统一 finalization。两者的 snapshot/patch 用于记录副作用，并不等价于复制整份上下文；结合本工程的内存业务上下文，采用强类型结果加受控提交比深拷贝 `DynamicContext` 更直接。

---

## 8. 文件变更设计

### 8.1 新增文件

| 文件 | 作用 | status |
|------|------|--------|
| `ai-agent-study-domain/.../AiStreamingProperties.java` | streaming 全局默认超时配置与关系校验 | append |
| `ai-agent-study-domain/.../StreamingChatResponseCollector.java` | chunk 聚合、请求取消和指标日志 | append |
| `ai-agent-study-domain/.../ClientDisconnectedException.java` | 区分请求主动取消与模型/业务失败，禁止部分结果落地 | append |
| `ai-agent-study-trading-domain/.../NodeExecutionResult.java` | 承载节点强类型结果、失败原因和执行作用域 | append |
| `ai-agent-study-trading-domain/.../NodeExecutionScope.java` | 节点级 deadline、取消信号和原子提交状态机 | append |
| `ai-agent-study-trading-domain/.../NodeResultCommitter.java` | 统一校验提交资格，只同步强类型结果到真实 context 并标记终态 | append |
| `ai-agent-study-domain/src/test/.../StreamingChatResponseCollectorTest.java` | 聚合组件单元测试 | append |
| `docs/superpowers/test/2026-07-16-trading-llm-stream-aggregation-test.md` | 配套验证设计 | append |

### 8.2 修改文件

| 文件/范围 | 修改内容 | status |
|-----------|----------|--------|
| `AiClientModelVO.java` | 增加可选 `StreamingTimeoutConfig`，从模型 `extParam` 读取覆盖值 | append |
| `AiClientModelNode.java` | 合并全局默认与模型覆盖策略并传给 `RetryChatModel` | append |
| `AiClientHttpTimeoutConfig.java` | 为现有 WebClient 连接器增加 10 秒连接保护，保留 RestClient | append |
| `RetryableExceptionTypes.java` | 识别当前 WebClient 连接器的连接超时异常链 | append |
| `RetryChatModel.java` | 实现 attempt 三状态、45 秒首内容、30 秒空闲和 150 秒逻辑调用总时限 | append |
| `SseEventSink.java` | 增加请求级 cancellation signal 契约 | append |
| `AbstractExecuteSupport.java` | 注入聚合组件并提供统一受保护入口 | append |
| `TradingAgentProperties.java` | 增加可配置的节点总时限并校验 | append |
| `TradingNodeInvoker.java` | 创建节点 scope，使用配置化 180 秒 deadline；超时先关闭提交门禁再取消 Future | append |
| 所有 Trading Stage | 根据 `NodeExecutionResult` 执行统一提交；成功后异步发送最终 SSE，并按已提交结果推进 phase | append |
| `AnalystCollectionStage.java` | 收集每个分析师的独立结果/deadline，移除超时乘算并实现 partial success 提交策略 | append |
| `TradingDispatcher.java` | legacy 路径与配置化时限对齐 | append |
| `TradingSseSession.java` | 使用 Reactor Sink 在断连/失败时触发一次性请求取消信号 | append |
| 上述 12 个 Trading Node | 使用统一流式聚合入口并返回强类型结果，移除对真实 `DynamicContext` 的直接写入和最终 SSE 发送 | append |
| `application.yml` | 增加默认配置和环境变量覆盖入口 | append |
| `RetryChatModelStreamTest.java` | 增加 attempt 三状态、首内容/空闲/总超时和状态隔离用例 | append |

说明：实施前需根据现有 package 布局确定新增类的最终路径，但职责和依赖方向不得改变。domain 公共能力不能反向依赖 trading 模块。

---

## 9. 可观测性

每次聚合调用记录以下字段，不记录完整 Prompt 或完整响应：

| 字段 | 说明 |
|------|------|
| `operation` | 节点/操作名称 |
| `promptLength` | Prompt 字符数 |
| `firstContentLatencyMs` | 首个非空文本延迟 |
| `totalLatencyMs` | 完整聚合耗时 |
| `chunkCount` | 收到的 chunk 数 |
| `responseLength` | 完整响应字符数 |
| `completionState` | completed / timeout / cancelled / error |
| `timeoutStage` | connect / first_content / idle / model_total / node |

异常日志保留根因类名和 traceId/sessionId，不输出 API Key、完整 Prompt、完整模型响应。

---

## 10. 实施任务

| Task | 内容 | status |
|------|------|--------|
| Task 1 | 新增 streaming 配置属性、默认值和启动校验 | append |
| Task 2 | 配置 WebClient 连接保护，补齐连接超时异常识别 | append |
| Task 3 | 在 `RetryChatModel` 实现并测试统一的 streaming 超时状态机 | append |
| Task 4 | 在 `AbstractExecuteSupport` 暴露统一入口 | append |
| Task 5 | 新增 `NodeExecutionResult`、`NodeExecutionScope` 和统一 `NodeResultCommitter` | append |
| Task 6 | 改造 12 个 Trading Node 为 Prepare-only 强类型结果生产者，保持报告 JSON 和 SSE 协议不变 | append |
| Task 7 | 改造 Invoker 与所有 Stage，按提交结果和阶段成功策略推进 phase | append |
| Task 8 | 配置化 Trading 节点总时限并统一新旧 pipeline | append |
| Task 9 | 补齐重试、取消、提交竞争、partial success 和 phase 回归测试 | append |
| Task 10 | 执行模块测试、全量编译和云端慢响应验收 | append |

---

## 11. 风险与缓解

| 风险 | 影响 | 缓解措施 |
|------|------|----------|
| 外层节点时限小于模型总时限 | 模型尚未完成就被外层取消 | 启动校验并保留 30 秒余量 |
| 空 delta 或工具信息后重试 | 重复计费或重复副作用 | `RESPONSE_OBSERVED` 立即锁定重试，但继续等待首个有效文本 |
| 聚合器未响应线程中断 | 节点超时后继续消耗模型资源 | 测试取消订阅和中断传播 |
| SSE 静默期间断连未被模型流感知 | 浏览器离开后继续生成和计费 | heartbeat 发现断连后触发请求级 cancellation signal，主动取消全部在途模型流 |
| 取消被当成正常 complete | 残缺文本进入报告解析 | 使用明确取消异常结束，不允许返回部分 `StringBuilder` |
| 迟到完整结果被误判为成功 | 超时后污染 context 或推进后续 Stage | 结果状态与 scope 提交资格双重校验；超时先关闭门禁再取消 Future |
| 提交与超时发生竞争 | 同一节点同时被记录为成功和超时 | CAS 单向状态机；只有 `RUNNING` 能转换到提交或终止状态 |
| Role 仍直接写真实上下文 | 提交门禁被绕过 | 12 个 Role 只返回强类型结果，真实 context 仅由 Committer 修改 |
| SSE 网络写出进入 CAS 临界区 | 慢客户端扩大竞争窗口并拖慢 Pipeline | Commit 只写 context；最终 SSE 在成功后异步入队，不等待浏览器确认 |
| context 提交后 SSE 失败 | 前端未收到已生成报告 | 保留请求级 context，不做回滚；触发取消并停止后续 Stage，记录失败指标 |
| 并发任务累积响应文本 | JVM 内存上涨 | 本次接受残余风险；文本只保留请求局部变量，并依赖模型输出 token 和 150 秒总时限 |
| streaming 超时策略影响 GeneralChat | 改变聊天首字体验或超时 | 统一策略进入 `RetryChatModel`，增加 GeneralChat 回归并支持模型级覆盖 |
| 空流或残缺 JSON | 报告解析失败 | 空流沿用节点兜底；异常流不返回部分报告 |
| legacy Dispatcher 与新 pipeline 时限不一致 | 同一任务在不同入口表现不同 | 两条路径统一读取 `TradingAgentProperties` |

---

## 12. 验收标准

| 编号 | 验收项 | 标准 | status |
|------|--------|------|--------|
| AC-001 | 12 个节点改为上游流式读取 | 生产代码中目标节点不再出现 `.call().content()` | append |
| AC-002 | 服务端完整聚合 | 多 chunk 按顺序合并，完成后返回完整字符串 | append |
| AC-003 | 前端协议不变 | 不发送原始 chunk，完整报告事件结构与改造前一致 | append |
| AC-004 | 慢生成不再受 80 秒同步读取限制 | 持续有 chunk 且总耗时超过 80 秒的请求能够完成 | append |
| AC-005 | 首内容超时可控 | 每个 attempt 超过 45 秒无有效文本时，根据三状态和既有预算重试或明确失败 | append |
| AC-006 | 空闲与总时限生效 | 文本空闲 30 秒或逻辑调用总耗时 150 秒时取消上游并失败 | append |
| AC-007 | 外层节点时限协调 | 默认单节点时限为 180 秒；并行节点 deadline 独立且不乘节点数量 | append |
| AC-008 | 重试无内容污染 | `RESPONSE_OBSERVED` 或 `CONTENT_OBSERVED` 后失败不重放，不返回或发送部分报告 | append |
| AC-009 | 取消无 late side effect | 超时/断连后不再写 context、发 late SSE 或推进 phase | append |
| AC-010 | 回归通过 | domain/trading 相关测试通过且全项目编译成功 | append |
| AC-011 | SSE 断连主动取消 | 断连信号触发后，该请求全部在途模型订阅被取消且不返回部分结果 | append |
| AC-012 | 节点结果受控提交 | 12 个节点 Prepare 阶段不直接写真实 context；仅 `SUCCESS + RUNNING + deadline/phase 合法` 能同步结果并标记 `COMMITTED` | append |
| AC-013 | 迟到结果无副作用 | 超时/取消先取得终止状态后，迟到的完整结果不能写 context、发送最终 SSE 或推进 phase | append |
| AC-014 | Stage 成功策略正确 | 分析师至少一个提交时 partial success；全部失败或串行必需节点失败时进入 ERROR 且后续 Stage 不执行 | append |
| AC-015 | Post-Commit SSE 隔离 | Commit 不等待 SSE 网络写出；入队/写出失败不回滚 context，但会取消请求并停止后续 Stage | append |

---

## 13. 发布与回滚

发布前先在测试环境使用可控慢 Flux 和真实模型各验证一次，再只重启应用微服务。上线后重点观察首个有效文本延迟、总耗时、首内容/空闲超时数量、节点超时数量和 partial success 比例。

本次无数据库 schema 和前端变更。回滚以代码提交为单位恢复 12 个节点的同步调用、旧 WebClient 配置和 180 秒节点常量；配置项保留不会影响旧代码，也可同步移除。
