# TradingAgent SSE 队列化输出与 Heartbeat 设计文档

## 背景

当前 TradingAgent 通过 `POST /api/v1/trading/analysis` 返回 SSE 流，前端使用 `fetch + ReadableStream` 消费 `text/event-stream`。

现有交易分析链路中，多个节点可能并发执行，例如四个数据分析师并发分析。节点完成后会通过 `ResponseBodyEmitter.send(...)` 推送阶段事件。同时，长耗时 LLM 调用当前主要是阻塞式调用：

```java
chatClient.prompt().user(prompt).call().content()
```

这意味着在等待 LLM 完整返回期间，SSE 连接可能长时间没有任何输出。如果这段静默时间过长，浏览器、系统网络栈、代理或中间层可能将连接视为空闲连接并关闭。后端下一次写 SSE 时会看到类似：

```text
ClientAbortException
ServletOutputStream failed to flush
ResponseBodyEmitter has already completed
```

此外，目前多个业务线程可能直接写同一个 `ResponseBodyEmitter`。从模型上看，同一个 HTTP SSE response 应该只有一个输出口负责写入，否则业务事件、heartbeat、complete 之间存在并发 flush 和竞态风险。

## 设计结论

采用“有界队列 + 单 writer + heartbeat 探针”的请求级传输层。

```text
业务线程 / 分析师线程 / 风控线程 / heartbeat 调度
        -> SseEventSink
        -> 有界队列
        -> 单 writer loop
        -> ResponseBodyEmitter
```

核心原则：

- 所有业务线程只负责把结构化事件入队，不直接写 `ResponseBodyEmitter`。
- 每个 SSE session 只有一个 writer loop 可以调用 `emitter.send(...)` 和 `emitter.complete()`。
- heartbeat 只做非阻塞入队，队列满时跳过本轮 heartbeat。
- 断连不仅停止写 SSE，还要通过 `shouldContinue()` 向业务链路传播，阻止后续昂贵工作继续启动。
- `complete()` 具备强终止语义，不能完全依赖队列可写。
- 当前锁方案只作为短期止血，长期收口到统一 sink，避免“新队列 + 旧直写锁”并存。

## 目标

- 每个交易分析请求拥有独立的 `TradingSseSession`。
- 所有 SSE 输出统一走 `SseEventSink`，业务节点不再直接持有或写入 `ResponseBodyEmitter`。
- 每个 session 内部使用有界队列承接业务事件、heartbeat、complete。
- 每个 session 只有一个 writer loop 串行写出，保证同一连接上 `send()` 与 `complete()` 不并发。
- 支持 heartbeat comment frame，避免长时间无业务事件导致 idle timeout。
- 使用结构化事件模型，由 writer 统一序列化 SSE frame。
- 维护清晰状态机，区分正常完成、客户端断开、发送失败、背压关闭。
- 暴露 `shouldContinue()`，在长耗时节点和 LLM 调用前检查，避免断连后继续烧 LLM。
- 增加可观测字段与指标，便于区分 LLM 静默、writer 卡住、客户端断开和队列背压。

## 非目标

第一阶段不处理：

- 四个分析师 token 级并发流式展示。
- WebSocket 改造。
- 断线重连与事件重放。
- 分析结果持久化重构。
- 前端 UI 大改。

后续如果要做四个分析师子进度或 true streaming，可以继续复用“结构化事件 + 单 writer”的传输层，只需要扩展事件类型和前端分桶展示。

## 总体架构

```text
TradingAnalysisController
  -> 创建 ResponseBodyEmitter
  -> 创建 TradingSseSession
  -> 启动 writerFuture
  -> 启动 heartbeatFuture
  -> DynamicContext 写入 SseEventSink
  -> TradingStarter.start
      -> TradingPipeline 各节点执行
          -> AbstractExecuteSupport.sendSseResult
          -> sink.sendBusiness(eventName, payload)
      -> pipeline finally sink.complete()

TradingSseSession writer loop
  -> 从 queue 取 SseOutboundEvent
  -> 统一序列化 SSE frame
  -> emitter.send(frame)
  -> 更新 lastWriteAt / 状态 / 指标
```

## 核心组件

### SseEventSink

`SseEventSink` 定义在 domain 模块，避免 domain 反向依赖 trigger。

```java
package denny.ai.agent.domain.service.sse;

public interface SseEventSink {

    boolean sendBusiness(String eventName, Object payload);

    boolean trySendHeartbeat();

    void complete();

    void markDisconnected(Throwable cause);

    boolean isDisconnected();

    boolean shouldContinue();

    SseSessionState state();
}
```

语义说明：

- `sendBusiness(...)`：业务事件入队。成功返回 `true`；session 已关闭或背压失败返回 `false`。
- `trySendHeartbeat()`：heartbeat 非阻塞入队，失败直接跳过，不影响业务链路。
- `complete()`：发起终止流程。需要幂等，允许业务 finally、emitter callback、writer 异常同时触发。
- `markDisconnected(...)`：writer 发现客户端断开或发送失败时调用。
- `shouldContinue()`：业务链路在昂贵操作前检查。返回 `false` 时应尽快跳过后续节点或中止当前流程。
- `state()`：用于日志、测试和诊断。

### SseOutboundEvent

不要传 raw string 作为业务事件。业务层传结构化事件，由 writer 统一序列化 SSE frame。

```java
public record SseOutboundEvent(
        SseOutboundType type,
        String eventName,
        Object payload,
        String requestId,
        String sessionId,
        String analystType,
        long eventId,
        Instant timestamp,
        String comment
) {
    public static SseOutboundEvent business(String eventName, Object payload) { ... }

    public static SseOutboundEvent heartbeat() { ... }

    public static SseOutboundEvent complete() { ... }
}
```

事件类型：

```java
enum SseOutboundType {
    BUSINESS,
    HEARTBEAT,
    COMPLETE
}
```

writer 序列化规则：

```text
event: progress
data: {"requestId":"...","sessionId":"...","eventId":1,"timestamp":"...","analystType":"technical","payload":{...}}

```

heartbeat 使用 SSE comment frame：

```text
: heartbeat

```

raw string 只保留给 heartbeat/comment 这类传输层 frame，不开放给业务事件。

### SseSessionState

只用 `AtomicBoolean disconnected` 不够表达竞态。建议使用明确状态机：

```java
enum SseSessionState {
    OPEN,
    CLOSING,
    CLOSED,
    DISCONNECTED,
    FAILED
}
```

状态语义：

- `OPEN`：正常接收业务事件和 heartbeat。
- `CLOSING`：已经进入完成流程，不再接收新业务事件，只允许 writer 排空已有事件并完成。
- `CLOSED`：正常完成，资源已清理。
- `DISCONNECTED`：客户端连接已断开，后续业务应尽快停止。
- `FAILED`：服务端发送异常、背压保护触发或其他非正常失败。

推荐状态流转：

```text
OPEN -> CLOSING -> CLOSED
OPEN -> DISCONNECTED
OPEN -> FAILED
CLOSING -> FAILED
CLOSING -> CLOSED
DISCONNECTED -> CLOSED
FAILED -> CLOSED
```

`complete()`、`onCompletion`、`onTimeout`、writer 捕获异常、业务 finally 可能互相 racing，所有状态流转都要通过 CAS 或等价机制保证幂等。

### TradingSseSession

`TradingSseSession` 位于 trigger 模块，实现 `SseEventSink`。

主要字段：

```java
class TradingSseSession implements SseEventSink {
    private final ResponseBodyEmitter emitter;
    private final BlockingQueue<SseOutboundEvent> queue;
    private final AtomicReference<SseSessionState> state;
    private final AtomicLong eventIdGenerator;
    private final AtomicLong lastWriteAt;
    private final AtomicLong lastBusinessAt;
    private final AtomicLong lastHeartbeatAt;
    private Future<?> writerFuture;
    private ScheduledFuture<?> heartbeatFuture;
}
```

除 `ResponseBodyEmitter` 外，建议记录：

- `requestId`
- `sessionId`
- `ticker`
- `analystType`
- `queueSize`
- `heartbeatSkipCount`
- `businessQueueFullCount`
- `sendFailureCount`
- `clientDisconnectedCount`
- `sessionClosedDueToBackpressureCount`

## 数据流

### 正常请求

```text
TradingAnalysisController.analyze
  -> 创建 ResponseBodyEmitter
  -> 创建 TradingSseSession
  -> session.startWriter()
  -> session.startHeartbeat()
  -> DynamicContext 写入 SseEventSink
  -> TradingStarter.start
  -> TradingPipeline 执行各节点
  -> 节点 sendSseResult
  -> sink.sendBusiness(eventName, payload)
  -> writer emitter.send
  -> pipeline 完成
  -> session.complete
  -> writer emitter.complete
```

### 长耗时 LLM 调用期间

```text
业务线程阻塞等待 LLM 完整返回
heartbeat scheduler 每 10s 尝试入队
writer 持续写出 heartbeat comment
连接保持活跃
LLM 返回后业务事件继续入队
```

注意：heartbeat 只能避免 idle timeout，不等价于 liveness 检测。如果 `emitter.send(...)` 本身卡住，heartbeat 也无法救回来。因此需要记录 `lastWriteAt`、`lastBusinessAt`、`lastHeartbeatAt`，用于判断是 LLM 静默、writer 卡住，还是客户端断开。

### 客户端断开

```text
writer emitter.send 抛异常
  -> state 标记 DISCONNECTED 或 FAILED
  -> 停止 heartbeat
  -> 清空队列
  -> cancel writerFuture
  -> cleanup

后续业务线程调用 sink.shouldContinue()
  -> 返回 false
  -> 不再启动后续 LLM / 外部数据 / 长耗时节点

后续业务线程调用 sink.sendBusiness(...)
  -> 返回 false
  -> 不再写 emitter
```

## 断连向业务链路传播

断连不能只理解成“不要再写 SSE”。如果前端连接已经死亡，后续昂贵 LLM 调用、外部数据抓取和多节点推理也应该尽快停止。

第一阶段建议做轻量传播：

- `DynamicContext` 中写入 `SseEventSink`。
- 每个长耗时节点开始前检查 `sink.shouldContinue()`。
- 每次 LLM 调用前检查 `sink.shouldContinue()`。
- 多步骤节点内部，在进入下一步前再次检查。
- 如果 `shouldContinue()` 为 `false`，节点返回空结果、跳过后续步骤，或抛出受控的 `AnalysisAbortedException` 让 pipeline 统一收口。

需要检查的典型节点：

- 四个数据分析师节点。
- Bull / Bear researcher。
- Research manager。
- 风险分析师。
- Portfolio manager。
- Recommendation / final report 节点。

当前阻塞式 LLM 调用一旦已经发出，不一定能立即取消；但 `shouldContinue()` 至少能阻止断连后继续发起后续昂贵调用。后续如果切换到 true stream 或底层 SDK 支持取消，可将该语义升级成类似 `AbortSignal` 的向下传递。

## 队列与背压

建议初始容量：

```java
QUEUE_CAPACITY = 512
BUSINESS_OFFER_TIMEOUT_MS = 200
```

处理规则：

- `BUSINESS`：短暂等待入队，失败时触发背压保护，状态进入 `FAILED`，停止继续写。
- `HEARTBEAT`：只用 `offer(...)`，失败直接跳过，不阻塞 heartbeat scheduler。
- `COMPLETE`：不完全依赖队列可写，见“终止语义”。

队列满不一定等于客户端断开，也可能是业务事件突发、writer 正在慢速 flush、网络层写出变慢。因此日志与指标要区分：

- `queue_full_business_drop`
- `session_closed_due_to_backpressure`
- `send_failure`
- `client_disconnected`
- `heartbeat_skip`

如果因为业务事件入队失败而关闭 session，日志中应明确写成 backpressure close，不要误报成浏览器主动断连。

## COMPLETE 终止语义

`COMPLETE` 必须比普通业务事件有更强的终止语义，避免 writer 永远阻塞在 `queue.take()` 或 complete 事件因队列满而丢失。

推荐策略：

- `complete()` 首先 CAS `OPEN -> CLOSING`。
- 停止 heartbeat future，避免关闭过程中继续入队。
- 尝试 `queue.offer(COMPLETE, shortTimeout)`。
- 如果 COMPLETE 入队成功，由 writer 排空队列中已有业务事件后执行 `emitter.complete()`。
- 如果 COMPLETE 入队失败，执行强制收口：清空队列、直接受控调用 `emitter.complete()`、cancel writerFuture、cleanup。
- writer loop 不使用无限 `queue.take()`，改用 `queue.poll(timeout)`，并在超时后检查终止状态。

这样 `complete()` 的成功不完全依赖队列仍然可写，也不会让 writer 在队列空但 session 已关闭时永久阻塞。

## Writer Executor 与 Heartbeat Executor

每个 SSE session 会有一个 writer loop，writer 可能长期阻塞在队列等待或网络 flush 上，因此 writer executor 需要单独规划。

建议：

- writer executor 独立于业务线程池。
- 不使用 `CallerRunsPolicy`，避免业务线程被迫执行长期 writer loop。
- 线程池容量按最大并发 SSE session 估算，例如限制最大 session 数，或设置足够大的 writer pool。
- Java 21 以后可优先考虑 virtual thread 承载这类阻塞 writer。
- 拒绝策略要清晰，创建 writer 失败时直接拒绝本次 SSE 请求，而不是半开 session。

heartbeat executor 原则：

- 只做轻量非阻塞入队。
- 不调用 `put()`。
- 不使用长时间 `offer(timeout)`。
- 单次 heartbeat 失败只记录 `heartbeat_skip`，不关闭 session。
- session 结束后必须 cancel 对应 heartbeat future。

## HTTP 头与代理缓冲

建议 SSE response 设置：

```text
Content-Type: text/event-stream;charset=UTF-8
Cache-Control: no-cache, no-transform
Connection: keep-alive
X-Accel-Buffering: no
```

`no-transform` 可以降低代理、压缩层或网关改写/攒包的概率。上线前需要用：

```bash
curl -N http://localhost:<port>/api/v1/trading/analysis
```

验证 heartbeat 是否真的按约 10 秒到达客户端，而不是被容器、网关或压缩层缓冲后一起吐出。

## 前端兼容性

前端当前是 `fetch + ReadableStream` 自己解析 SSE，而不是浏览器原生 `EventSource`。因此不能只假设 comment frame 会天然忽略，需要补一个前端解析测试：

- 输入 `: heartbeat\n\n`，parser 不产生业务消息。
- 输入 `: heartbeat\n\nevent: progress\ndata: {...}\n\n`，parser 只产生一个 `progress` 业务事件。
- comment frame 不触发 JSON parse error。
- 空 block 不触发空消息展示。

第一阶段前端展示策略不变：不展示 token 级内容，只展示已有阶段事件和最终结果。四个分析师的细粒度子进度后续通过 `analystType` 分桶演进。

## 兼容性硬约束

第一阶段必须兼容现有前端和现有业务事件协议。内部可以使用结构化 `SseOutboundEvent`，但写给前端的业务 `data:` JSON 结构默认保持不变。

如果当前前端期望：

```text
data: {"type":"debate","subType":"bear_researcher","content":"..."}

```

第一阶段 writer 仍输出同样的 `data:` payload：

```text
data: {"type":"debate","subType":"bear_researcher","content":"..."}

```

不要在第一阶段直接改成 envelope：

```text
data: {"requestId":"...","sessionId":"...","payload":{"type":"debate","subType":"bear_researcher","content":"..."}}

```

原因是前端可能直接读取旧字段，例如 `type`、`subType`、`content`。如果后端把旧字段包进 `payload`，前端会出现空消息、类型丢失或展示分支不命中。

推荐兼容策略：

- 后端内部事件使用 `SseOutboundEvent`，包含 `requestId`、`sessionId`、`eventId`、`timestamp` 等元数据。
- 第一阶段对外 SSE `data:` 仍写旧业务 JSON。
- 元数据先用于日志、指标和诊断，不强行进入前端消费协议。
- 如果需要发送 `event:` 行，必须先确认前端 parser 会忽略或兼容；否则第一阶段只发送原有 `data:` 行。
- heartbeat comment frame 是唯一新增的前端可见传输层 frame，前端必须忽略它。

兼容性验收标准：

- URL、HTTP method、请求参数不变。
- `Content-Type: text/event-stream` 不变。
- 现有鉴权、跨域、请求超时配置不变。
- 现有业务消息字段、类型名、阶段名、最终结果结构不变。
- 只新增保活相关 response header，不删除旧 header。
- 前端在不改 UI 展示逻辑的情况下，正常请求、长耗时请求和最终结果展示都保持原行为。

## 旧直写路径收口

迁移时要避免新 sink 和旧 lock 并存导致绕过队列。

收口原则：

- TradingAgent 链路内，`DynamicContext` 不再暴露 `ResponseBodyEmitter` 给节点直接写。
- `AbstractExecuteSupport.sendSseResult(...)` 优先且唯一使用 `SseEventSink`。
- `TradingStarter` 的最终 complete 调用 sink，不直接调用 `emitter.complete()`。
- 当前 `sseSendLock` 只作为短期止血代码，队列方案落地后删除或只保留在非 TradingAgent 兼容路径。
- 代码审查时搜索 `emitter.send(`、`ResponseBodyEmitter`、`sseSendLock`，确保 TradingAgent 主链路没有绕过单 writer。

## 模块改动

### ai-agent-study-domain

新增：

```text
ai-agent-study-domain/src/main/java/denny/ai/agent/domain/service/sse/SseEventSink.java
ai-agent-study-domain/src/main/java/denny/ai/agent/domain/service/sse/SseSessionState.java
```

修改：

```text
ai-agent-study-domain/src/main/java/denny/ai/agent/domain/service/auto/step/AbstractExecuteSupport.java
```

职责变化：

- `sendSseResult(...)` 统一通过 `SseEventSink` 入队。
- 如果 sink 不存在，保留非 trading 链路的旧兼容逻辑。
- 客户端断连、背压关闭、发送失败均降级为可控日志，不继续抛成致命业务异常。

### ai-agent-study-trigger

新增：

```text
ai-agent-study-trigger/src/main/java/denny/ai/agent/trading/trigger/http/TradingSseSession.java
ai-agent-study-trigger/src/main/java/denny/ai/agent/trading/trigger/http/SseOutboundEvent.java
ai-agent-study-trigger/src/main/java/denny/ai/agent/trading/trigger/http/SseOutboundType.java
```

修改：

```text
ai-agent-study-trigger/src/main/java/denny/ai/agent/trading/trigger/http/TradingAnalysisController.java
```

职责变化：

- 创建 `TradingSseSession`。
- 注入 writer executor 和 heartbeat executor。
- 设置 SSE response header。
- 将 `SseEventSink` 写入 `DynamicContext`。
- 注册 `emitter.onCompletion`、`emitter.onTimeout`、`emitter.onError` 到 session 状态机。

### ai-agent-study-trading-domain

修改：

```text
ai-agent-study-trading/ai-agent-study-trading-domain/src/main/java/denny/ai/agent/trading/domain/config/TradingExecutorConfig.java
```

新增 executor：

```java
@Bean("tradingSseWriterExecutor")
public Executor tradingSseWriterExecutor() { ... }

@Bean("tradingSseHeartbeatExecutor")
public TaskScheduler tradingSseHeartbeatExecutor() { ... }
```

如果当前项目仍使用 Java 17，先使用普通线程池；后续升级 Java 21 后可将 writer executor 改成 virtual thread。

## 事件字段约定

后端内部每个业务事件建议统一携带：

- `requestId`
- `sessionId`
- `eventId`
- `timestamp`
- `ticker`
- `analystType`
- `stage`
- `payload`

即便第一阶段不做断线重放，这些字段也能显著改善多分析师分桶、问题定位、日志串联和未来恢复能力。

注意：这些字段第一阶段不要求进入前端 `data:` payload。对外协议是否升级成 envelope，应作为后续前后端协议变更单独设计。

## 测试方案

### 兼容性回归矩阵

| 覆盖面 | 回归用例 | 预期结果 |
| --- | --- | --- |
| 接口入口 | 使用现有前端参数触发 `/api/v1/trading/analysis` | 请求能正常建立 SSE 连接，HTTP method、URL、参数不需要变化 |
| 响应类型 | 检查 response header | `Content-Type` 仍为 `text/event-stream`，新增 header 不破坏旧逻辑 |
| 旧业务 payload | 对比改造前后的 `data:` JSON 字段 | `type`、`subType`、`content`、阶段字段、最终结果字段保持不变 |
| heartbeat | SSE 流中插入 `: heartbeat\n\n` | 前端不展示空消息，不触发 JSON parse error |
| 业务顺序 | heartbeat 穿插在业务事件之间 | 前端业务消息顺序与原语义一致 |
| 正常完成 | 一次完整 TradingAgent 分析 | 前端仍能收到最终结果，连接正常 complete |
| 并发分析师 | 四个分析师并发完成 | 后端无并发写 emitter，前端阶段消息不丢失 |
| 长耗时 LLM | 模拟 60 秒无业务事件 | heartbeat 持续到达，连接不因 idle timeout 中断 |
| 客户端断开 | 前端中途关闭连接 | 后端不刷 fatal 日志，后续昂贵节点通过 `shouldContinue()` 尽快停止 |
| 队列背压 | 模拟慢客户端或 writer flush 变慢 | session 受控关闭，日志标记 backpressure 而不是误报客户端断开 |
| 旧路径收口 | 搜索 TradingAgent 链路直写 emitter | 主链路没有绕过 `SseEventSink` 的 `emitter.send(...)` |

### 单元测试

- `TradingSseSession` 保证多线程并发 `sendBusiness(...)` 后只有单 writer 写 emitter。
- heartbeat 队列满时跳过，不阻塞 scheduler。
- business 队列满时进入 `FAILED` 或 backpressure close，并记录指标。
- `complete()` 多次调用幂等。
- COMPLETE 入队失败时仍能强制 cleanup，不让 writer 永久阻塞。
- writer `send()` 抛异常时进入 `DISCONNECTED` 或 `FAILED`，停止 heartbeat 并清空队列。
- `shouldContinue()` 在 `DISCONNECTED`、`FAILED`、`CLOSING`、`CLOSED` 返回 `false`。
- 结构化业务事件被正确序列化为 SSE frame，且第一阶段对外 `data:` payload 保持旧结构。

### 集成测试

- 模拟 LLM 阻塞 60 秒，验证 heartbeat 每 10 秒写出。
- 模拟前端中途断开，验证后续节点不再启动新的 LLM 调用。
- 模拟四个分析师并发完成，验证事件不乱写、不并发 flush、不丢 final complete。
- 模拟慢客户端导致队列背压，验证 session 受控关闭且日志原因明确。
- 验证响应头包含 `Cache-Control: no-cache, no-transform` 和 `X-Accel-Buffering: no`。
- 使用现有前端页面跑完整流程，验证阶段展示、最终结果展示、错误提示逻辑保持不变。

### 前端解析测试

- `: heartbeat\n\n` 被忽略。
- heartbeat 后跟业务事件时，只产生业务事件。
- comment frame 不触发 JSON parse error。
- 空消息不会渲染到 UI。
- 第一阶段业务 `data:` JSON 不包 envelope，旧字段读取逻辑保持通过。
- 如果后端增加 `event:` 行，前端 parser 必须证明不会误解析；如果不能证明，则第一阶段不增加 `event:` 行。

### 手工验证

```bash
curl -N -H "Accept: text/event-stream" http://localhost:<port>/api/v1/trading/analysis
```

观察 heartbeat 是否按预期即时到达。

同时保存一份改造前后的 SSE 片段，人工对比业务 `data:` payload 是否保持兼容，只允许新增 heartbeat comment 和保活 header。

## 与 true streaming 的关系

队列 + 单 writer 不会阻碍后续转成 stream。真正变化的是业务事件粒度：

第一阶段：

```text
analysis_started
technical_done
fundamental_done
sentiment_done
news_done
final_report
```

后续 true streaming：

```text
analyst_started
analyst_delta
analyst_done
manager_delta
manager_done
final_delta
final_done
```

只要事件仍然先入队，再由单 writer 写出，多 agent 并发和 token stream 都可以被统一串行化。前端是否实时展示每个 analyst 的内容，是展示层协议问题，不是传输层必须改变的问题。

## 落地顺序

1. 新增 `SseEventSink`、`SseSessionState`、`SseOutboundEvent`。
2. 实现 `TradingSseSession` 的队列、writer、heartbeat、状态机和强 cleanup。
3. `TradingAnalysisController` 改为创建 session，并将 sink 写入 `DynamicContext`。
4. `AbstractExecuteSupport.sendSseResult(...)` 改为结构化入队。
5. `TradingStarter` 和 pipeline finally 改为调用 `sink.complete()`。
6. 在长耗时节点和 LLM 调用前增加 `sink.shouldContinue()` 检查。
7. 收口 TradingAgent 旧直写路径，移除或隔离 `sseSendLock`。
8. 补齐后端单元测试、集成测试和前端 parser comment 测试。
9. 用 `curl -N` 验证 heartbeat 实际到达间隔。

## 风险与取舍

- heartbeat 能解决 idle timeout，但不能解决 `emitter.send(...)` 卡死，需要靠 writer 线程池隔离、超时观测和状态指标定位。
- 队列满后关闭 session 是服务端自我保护策略，不应被简单归类为客户端断开。
- 第一阶段 `shouldContinue()` 只能阻止新昂贵工作启动，已经发出的阻塞 LLM 调用未必能立即取消。
- 单 writer 会让同一连接的输出严格串行，这是 SSE 的合理模型；吞吐瓶颈主要来自客户端消费速度和网络 flush，而不是本地加锁。
- 结构化事件会比 raw string 多一点改造成本，但能避免 `data:` 重复、换行污染、event name 丢失和前端解析不一致。
