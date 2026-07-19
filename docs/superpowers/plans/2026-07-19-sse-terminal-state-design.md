# SSE 终态判定与异常区分设计

## 状态

- 日期：2026-07-19
- 状态：实现计划已生成，待执行
- 对应实现计划：`docs/superpowers/plans/2026-07-19-sse-terminal-state.md`

## 1. 背景

前端通过 `fetch + ReadableStream` 消费 SSE。对浏览器而言，服务端正常调用
`complete()`、代理提前关闭连接和部分异常断流都可能表现为 `reader.read()` 返回
`done=true`。因此，HTTP 流结束不能等价为业务任务完成，前端必须依赖明确的业务终态。

当前协议已经定义两类成功终态：

- 通用 Agent：`type=complete`
- 交易分析：`type=trading, subType=trading_complete, completed=true`

错误事件 `type=error` 或 `subType=error` 是明确失败终态。HTTP 错误、读取异常和协议解析
异常也属于明确失败。唯一无法直接判断的是：SSE 以 EOF 结束，但前端没有收到任何终态。

现有 UI 将这个未知状态渲染为红色“操作失败”。同时，`auto_agent` 经意图路由进入交易
分析时使用旧的 `ResponseBodyEmitter` 直写路径。交易子流程发送终态后会直接关闭外层
emitter，而终态发送方法没有返回真实发送结果，导致日志中的“emitter 关闭完成”不能证明
`trading_complete` 已经送达前端。

## 2. 目标与非目标

### 2.1 目标

- 明确区分任务完成、任务失败和终态未知三种状态。
- 只有收到成功终态时才显示“任务完成”。
- 明确错误继续显示“操作失败”。
- 缺少终态的 EOF 显示中性警告，不误报为成功或明确失败。
- `auto_agent` 内嵌交易流程可靠发送 `trading_complete`，并由外层请求统一关闭 SSE。
- 终态发送、拒绝、失败和连接关闭具备可检索的 INFO/WARN 日志。

### 2.2 非目标

- 不将“结果面板已有内容”视为任务完成证据。
- 不改变已有 SSE 业务事件 JSON 字段。
- 不引入 WebSocket、断线重连或事件重放。
- 不重写独立 `/api/v1/trading/analysis` 已有的 `TradingSseSession` 队列实现。
- 不调整交易分析业务流程和最终决策内容。

## 3. 当前触发矩阵

| 场景 | 前端证据 | 当前状态 |
| --- | --- | --- |
| 通用任务完成 | `type=complete` | completed |
| 交易任务完成 | `trading/trading_complete` | completed |
| 后端明确失败 | `type=error` 或 `subType=error` | failed |
| HTTP 或流读取异常 | `fetch` 或 `reader.read()` 抛异常 | failed |
| SSE 协议数据非法 | `protocolErrors > 0` | failed |
| 用户取消 | `AbortError` | cancelled |
| EOF 前没有终态 | `done=true && !terminalSeen` | failed/stream_interrupted |

最后一行可能由完成事件遗漏、发送失败、代理提前关闭、后端无错误事件退出或未知终态类型
导致。前端无法仅凭 EOF 和结果 DOM 判断具体原因。

## 4. 设计方案

采用“可靠终态协议 + 前端三态展示”。

### 4.1 状态语义

| 状态 | 判定条件 | 用户展示 |
| --- | --- | --- |
| completed | 收到 `complete` 或 `trading_complete` | 成功样式，“任务完成” |
| failed | 收到错误终态，或发生 HTTP、读取、解析异常 | 错误样式，“操作失败”及具体原因 |
| indeterminate | 流以 EOF 结束，但没有成功或失败终态 | 警告样式，“连接已结束，未确认任务状态” |
| cancelled | 用户主动取消请求 | 中性提示，“任务已取消” |

`hasResult` 只用于在未知状态下补充“已收到的结果仍保留”，不得改变请求状态。

### 4.2 前端数据流

```text
SSE event
  -> normalizeAgentEvent
  -> classifyAgentEvent
      -> success terminal: terminalSeen=true, outcome=completed
      -> error terminal: terminalSeen=true, outcome=failed
      -> non-terminal result: render only

stream EOF
  -> terminalSeen=true: preserve terminal outcome
  -> terminalSeen=false and no protocol error: outcome=indeterminate
      -> hasResult: warning + results preserved
      -> no result: warning + retry guidance

fetch/read/parse failure
  -> outcome=failed
  -> error presentation
```

前端不能用 `final_decision`、`final_completed`、`content completed=true` 或结果面板 DOM 数量
推导请求完成，因为这些事件只证明某条消息或某个阶段完成。

### 4.3 后端所有权

独立交易接口继续由 `TradingSseSession` 的单 writer 负责业务事件写出和连接关闭。

`auto_agent` 内嵌交易路径遵循以下所有权：

```text
AiAgentController / AutoAgentExecuteStrategy owns emitter lifecycle
  -> IntentRoutingNode starts TradingStarter
  -> TradingStarter emits final_decision
  -> TradingStarter emits trading_complete
  -> TradingStarter returns without completing outer emitter
  -> AutoAgentExecuteStrategy performs remaining cleanup
  -> AutoAgentExecuteStrategy closes emitter once
```

交易子流程只负责发送交易业务终态，不关闭外层 `auto_agent` emitter。这样可以避免子流程关闭
传输通道后，外层仍在持久化和清理的生命周期倒置。

### 4.4 终态发送结果

`TradingStateContext.sendTerminalCompleteOnce()` 和 `sendTerminalErrorOnce()` 必须返回真实发送
结果。终态方法应调用能够返回 boolean 的发送实现，不能在 sender 为空、sink 拒绝、连接已断开
或 `emitter.send()` 抛异常后仍返回 `true`。

对于 raw emitter 兼容路径，事件发送和最终关闭应由同一个所有者按顺序执行。对于 sink 路径，
`sendBusiness()` 返回入队结果，`complete()` 由 writer 在排空已入队业务事件后执行。

终态状态的原子标记不能早于发送结果而永久锁死重试机会。推荐语义：

- 首次调用获得终态发送权。
- 发送成功后固定终态。
- 发送失败时记录失败原因并返回 `false`，由调用方进入受控关闭或错误收口。
- 重复调用不重复发送，并记录 DEBUG 日志。

### 4.5 可观测性

每个终态至少包含 `sessionId`、终态类型、发送路径和发送结果。建议日志：

```text
INFO  SSE terminal accepted: sessionId=..., type=trading_complete, path=emitter
INFO  SSE terminal accepted: sessionId=..., type=trading_complete, path=sink
WARN  SSE terminal rejected: sessionId=..., type=trading_complete, state=...
WARN  SSE terminal send failed: sessionId=..., type=trading_complete, error=...
INFO  SSE emitter closed: sessionId=..., owner=auto_agent, terminalSent=true
```

“任务完成”“最终决策生成”和“emitter 关闭完成”是三个不同事实，日志不得互相替代。

## 5. 异常处理

- 明确业务错误必须先尝试发送错误终态，再由传输所有者关闭连接。
- 终态发送失败不伪装成业务成功，应记录 WARN，并让前端最终进入 indeterminate 或读取异常。
- 客户端取消不渲染错误卡片，也不转换为任务完成。
- 未知事件类型保持非终态；升级协议时必须同步更新前端分类测试。
- 已收到部分或最终结果但缺少终态时，结果保留，状态仍为 indeterminate。

## 6. 测试设计

### 6.1 前端单元测试

| 用例 | 输入 | 预期 |
| --- | --- | --- |
| 通用完成 | result + `complete` + EOF | completed，显示任务完成 |
| 交易完成 | `final_decision` + `trading_complete` + EOF | completed，显示任务完成 |
| 明确业务失败 | result + `error` + EOF | failed，显示操作失败 |
| 读取异常 | partial result + reader reject | failed，显示网络或读取错误 |
| 协议异常 | malformed event + EOF | failed，显示协议错误 |
| 有结果无终态 | final result + EOF | indeterminate，显示中性警告并保留结果 |
| 无结果无终态 | progress + EOF | indeterminate，显示未确认状态和重试提示 |
| 用户取消 | AbortError | cancelled，不显示操作失败 |

### 6.2 后端单元测试

- `sendTerminalCompleteOnce()` 在 sender 成功时返回 `true`。
- sender 抛异常、sink 拒绝或 emitter 已关闭时返回 `false`。
- 成功终态最多发送一次。
- `auto_agent` 内嵌交易完成后，`TradingStarter` 不调用外层 emitter 的 `complete()`。
- 外层策略在交易节点返回并完成清理后只关闭 emitter 一次。
- 终态发送发生在 emitter 关闭之前。

### 6.3 集成测试

- `/api/v1/trading/analysis` 正常响应最后一个业务事件为 `trading_complete`，随后 EOF。
- `/api/v1/agent/auto_agent` 识别股票分析后也输出 `trading_complete`，随后由外层关闭。
- 模拟终态 sender 失败，验证没有成功日志，前端进入 indeterminate 或明确读取失败。
- 模拟后端错误事件，验证前端进入 failed，不被已有结果覆盖。
- 使用 `curl -N` 保存完整响应，确认终态帧在连接关闭前可见。

## 7. 代码影响范围

### 7.1 前端

- `docs/dev-ops/nginx/html/index.html`：将无终态 EOF 从 `failed` 调整为
  `indeterminate`，并使用警告样式展示。
- `docs/dev-ops/nginx/html/js/agent-ui-core.js`：保留现有成功和失败终态分类，必要时抽取
  流结束状态判定函数，避免通用与交易入口重复实现。
- `docs/dev-ops/nginx/html/test/agent-ui-core.test.js`：补充 completed、failed、
  indeterminate 和 cancelled 状态矩阵测试。
- `docs/dev-ops/nginx/html/test/agent-ui-security-smoke.html`：补充浏览器级终态展示回归。

### 7.2 后端

- `TradingStateContext`：终态方法返回真实发送结果，并保证成功终态最多发送一次。
- `TradingStarter`：区分独立交易 sink 与 `auto_agent` 外层 emitter 的生命周期所有权；内嵌路径
  不关闭外层 emitter。
- `IntentRoutingNode`：将交易终态发送结果正确转交到 `auto_agent` SSE 输出路径。
- `AbstractExecuteSupport`：为兼容 emitter 路径提供可观测的发送结果，保留 sink 优先策略。
- `AutoAgentExecuteStrategy`：作为 `auto_agent` emitter 的唯一关闭所有者，在清理结束后收口连接。

### 7.3 测试与文档

- 补充交易状态上下文、交易启动器和意图路由的后端单元测试。
- 补充 `/api/v1/trading/analysis` 与 `/api/v1/agent/auto_agent` 的终态集成测试。
- 更新前端产品回归文档中 `disconnect-before-terminal` 的预期展示，但仍禁止将未知状态包装为成功。

## 8. 兼容性与风险

- 保留现有 `type`、`subType`、`content`、`completed` 字段，正常消费者无需迁移。
- 新增前端 indeterminate 状态只影响缺少终态的异常流，不改变正常成功和明确失败。
- 移除交易子流程对外层 emitter 的关闭后，必须保证外层策略所有返回路径都能关闭 emitter。
- 如果部署版本仍有其他节点依赖子流程提前关闭连接，需要通过集成测试确认并收口到外层所有权。
- INFO 终态日志应控制为每个请求最多一条成功记录，避免长流程日志噪声。

## 9. 验收标准

- 正常交易请求稳定显示“任务完成”，不再落入 stream_interrupted。
- 明确错误稳定显示“操作失败”，不会因结果面板已有内容被改判为成功。
- 缺少终态的 EOF 显示中性未知状态，不显示红色“操作失败”或绿色“任务完成”。
- 两个交易入口均能在原始 SSE 响应中观察到 `trading_complete`。
- 日志可以区分业务完成、终态发送成功/失败和连接关闭。
- 所有新增状态分支均有自动化测试覆盖。

## 10. 关键决策

1. HTTP/SSE 的 EOF 不是业务完成证据，成功必须由明确终态事件确认。
2. 结果面板已有内容不改变请求终态，只影响未知状态下的补充提示。
3. 明确错误与终态未知使用不同状态和视觉语义，不能统一渲染为“操作失败”。
4. 交易子流程不关闭 `auto_agent` 的外层 emitter，传输生命周期由创建者统一管理。
5. 终态发送 API 必须返回真实结果，关闭日志不能替代终态送达日志。
6. 独立交易接口继续复用现有 `TradingSseSession` 单 writer 和队列排空机制。
