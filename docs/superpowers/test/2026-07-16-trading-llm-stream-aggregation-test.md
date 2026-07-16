# Test: Trading Agent LLM 流式读取与服务端聚合

## 1. 测试背景

### 1.1 对应 Story

- Story 文档：`docs/trading-agent/2026-07-16-trading-llm-stream-aggregation-story.md`

### 1.2 测试目标

- 验证模型 chunk 在服务端按序聚合，结束后才交给业务节点。
- 验证连接、首个有效文本、文本 chunk 空闲、模型总耗时和节点总耗时边界。
- 验证重试、压缩、取消和异常传播仍符合现有契约。
- 验证 12 个 Trading Node 的报告解析与前端 SSE 协议没有回归。

### 1.3 测试范围

- `StreamingChatResponseCollector`
- `AiStreamingProperties` 与 WebClient streaming 配置
- `RetryableExceptionTypes`、`RetryChatModel.stream()`
- `AbstractExecuteSupport` 流式聚合入口
- `SseEventSink` cancellation signal 与 `TradingSseSession` 断连传播
- 12 个 Trading Agent 文本生成节点
- `TradingNodeInvoker`、`AnalystCollectionStage` 和 legacy `TradingDispatcher` 时限

### 1.4 不在本次测试范围

- `GeneralChatNode` 的前端逐 chunk 展示细节，仅做回归确认。
- Trading Agent 之外的同步 LLM 节点改造。
- Redis/数据库临时结果持久化和服务重启恢复。
- 真实模型回答内容质量和投资建议准确率。

---

## 2. 测试策略

### 2.1 测试分层

| 测试层级 | 是否覆盖 | 说明 |
|----------|----------|------|
| 单元测试 | 是 | 使用 Reactor `StepVerifier`、虚拟时间和 mock Flux 验证聚合与超时 |
| 模块集成测试 | 是 | 验证公共入口、重试模型和 Trading pipeline 协作 |
| 接口测试 | 是 | 验证 SSE 只返回完整业务事件，不暴露模型 chunk |
| 回归测试 | 是 | 验证报告解析、partial success、GeneralChat 真流式不受影响 |
| 手工云端验证 | 是 | 使用真实慢模型验证超过 80 秒仍可完成 |

### 2.2 测试原则

- 单元测试统一 mock 模型和网络依赖，不调用真实 LLM。
- 时间相关测试使用虚拟时间，不执行真实的 30/45/150 秒等待。
- 每个用例必须以明确 assert、StepVerifier expectation 或 mock invocation verification 结束。
- 只有云端手工验收允许访问真实模型服务。
- 初始 `status` 统一为 `append`，实现并验证通过后改为 `pass`。

### 2.3 Mock 策略

| 依赖项 | 是否 Mock | Mock 方式 | 说明 |
|--------|-----------|-----------|------|
| LLM chunk Flux | 是 | `Flux.just` / `Flux.never` / `Flux.concat` | 控制顺序、延迟和异常 |
| `ChatClient` fluent API | 是 | Mockito deep stub 或测试适配入口 | 只验证当前层调用契约 |
| `RetryChatModel` delegate | 是 | Mockito + `Flux<ChatResponse>` | 验证 attempt 三状态和首内容前后重试差异 |
| SSE sink | 是 | Reactor Sink + Stub/Mockito | 验证事件次数、请求级取消信号和部分结果丢弃 |
| 外部股票数据服务 | 是 | Stub | 节点测试只关注模型调用和报告解析 |
| Reactor 时间 | 是 | `StepVerifier.withVirtualTime` | 避免真实等待 |

---

## 3. 测试场景设计

### 3.1 正常场景

| 编号 | 场景名称 | 前置条件 | 输入 | 预期结果 | status |
|------|----------|----------|------|----------|--------|
| TC-001 | 多 chunk 顺序聚合 | 聚合器配置有效 | `Flux.just("a", "b", "c")` | 返回 `abc`，chunkCount=3 | append |
| TC-002 | Unicode 与 JSON 跨 chunk | JSON 字段跨 chunk 分割 | 多个中文/JSON chunk | 完整字符串可被现有解析器解析 | append |
| TC-003 | 超过 80 秒但持续输出 | 虚拟时间，总耗时 90 秒，每 10 秒一个 chunk | 延迟 Flux | 正常完成，不受同步 80 秒读取超时影响 | append |
| TC-004 | 节点完整报告返回 | mock 模型返回合法报告 JSON | 任一分析节点 Prompt | ReportVO 正确，完整后只发送一次报告事件 | append |
| TC-005 | 12 个节点统一入口 | 目标节点已改造 | 编译/静态检查 | 均调用公共 streaming 聚合入口，无目标 `.call().content()` | append |
| TC-006 | 首内容超时后重试成功 | RetryConfig 允许重试 | 第一次 attempt 45 秒无任何 `ChatResponse`，第二次返回有效文本 | delegate 调用两次，最终结果完整 | append |

### 3.2 异常场景

| 编号 | 场景名称 | 前置条件 | 输入 | 预期结果 | status |
|------|----------|----------|------|----------|--------|
| TC-101 | 首内容超时耗尽 | 每 attempt 首内容时限 45 秒，重试预算耗尽 | `Flux.never()` | 在 150 秒逻辑调用总预算内重试并最终抛出明确超时，不生成报告 | append |
| TC-102 | 有效文本后流异常 | 状态已经进入 `CONTENT_OBSERVED` | `chunk1` 后抛异常 | 不重试，不返回 `chunk1` 残缺报告 | append |
| TC-103 | 文本 chunk 空闲超时 | idle=30 秒 | 首个有效文本后 31 秒无非空文本 | 取消订阅并抛 idle timeout，不重试 | append |
| TC-104 | 模型逻辑调用总超时 | total=150 秒 | 重试、退避或持续输出使总时长超过 150 秒 | 取消整个逻辑调用，不再创建新 attempt | append |
| TC-105 | 独立节点总超时 | node=180 秒 | 一个并行节点不结束，其他节点完成 | 只取消超时节点，其他结果保留，无 late context/SSE | append |
| TC-106 | SSE 生成中断开 | 模型 Flux 正在静默等待 | 触发 sink cancellation signal | 立即取消模型订阅，抛出取消异常，不发送业务报告 | append |
| TC-107 | 线程被中断 | 聚合正在等待 | interrupt 当前任务 | 恢复中断语义，取消上游，异常向外传播 | append |
| TC-108 | 上下文溢出 | RetryChatModel 收到 1261 | 压缩后第二次 streaming 成功 | 使用压缩 Prompt，聚合结果正确 | append |
| TC-109 | 空 delta 后首内容超时 | 第一个 `ChatResponse` 无文本 | 空 delta 后 `Flux.never()` | 状态为 `RESPONSE_OBSERVED`，45 秒后失败且不重试 | append |
| TC-110 | 工具信息后首内容超时 | 第一个 `ChatResponse` 只有工具信息 | 工具信息后无文本 | 锁定重试，超时后不重复工具副作用 | append |
| TC-111 | 取消后禁止部分结果 | 已聚合至少一个文本 chunk | 触发请求级取消信号 | 丢弃 StringBuilder，不返回或解析部分报告 | append |
| TC-112 | SSE writer 写失败传播取消 | writer 遇到 IOException | 模拟 heartbeat/业务事件写失败 | session 标记断连并触发一次 cancellation signal | append |

### 3.3 边界场景

| 编号 | 场景名称 | 前置条件 | 输入 | 预期结果 | status |
|------|----------|----------|------|----------|--------|
| TC-201 | 空 Flux | 无 chunk，正常 complete | `Flux.empty()` | 返回空字符串，由节点现有兜底处理 | append |
| TC-202 | 单 chunk | 正常 Flux | 一个完整 JSON chunk | 原样返回，chunkCount=1 | append |
| TC-203 | 空字符串 chunk | Flux 含空 chunk | `"a", "", "b"` | 返回 `ab`，不改变顺序 | append |
| TC-204 | 超时临界值内完成 | 首内容 44.999 秒、idle 29.999 秒 | 延迟 Flux | 正常完成 | append |
| TC-205 | 配置为零或负数 | 启动加载配置 | 非法 Duration | 启动校验失败，错误指出具体属性 | append |
| TC-206 | 节点时限小于模型总时限 | node<=total | 非法组合 | 启动校验失败，不允许静默运行 | append |
| TC-207 | 并发请求隔离 | 同时执行多个聚合 | 不同 chunk 序列 | 每个结果独立，无串流和共享 StringBuilder | append |
| TC-208 | 较长响应 | 模拟接近模型 token 上限 | 大量 chunk | 完整聚合，无顺序错误或二次拼接 | append |
| TC-209 | attempt 状态隔离 | 同一逻辑调用发生重试 | attempt 1 超时、attempt 2 成功 | attempt 2 从 `AWAITING_RESPONSE` 开始，重试预算和总 deadline 继续共享 | append |
| TC-210 | 并发 LLM 调用隔离 | 多个节点同时订阅同一 `RetryChatModel` Bean | 各自不同的延迟 Flux | 每次调用拥有独立 `StreamState` 和 attempt `StreamPhase`，互不重置计时器 | append |
| TC-211 | 模型级配置覆盖 | 全局 45/30/150，模型 extParam 提供覆盖值 | 装配指定模型 | 仅该模型使用覆盖值，其他模型继续使用全局默认 | append |
| TC-212 | 同请求并发流统一取消 | 四个节点共享同一 SseEventSink | 四个 Flux 同时在途后触发取消 | 四个订阅全部取消且各自丢弃部分结果 | append |
| TC-213 | 跨请求取消隔离 | 两个请求各自持有 cancellation signal | 取消请求 A | 仅 A 的模型流取消，请求 B 继续执行 | append |
| TC-214 | 无 SSE 调用不误取消 | 子任务/后台调用没有 SseEventSink | 正常模型 Flux | 使用永不触发信号，模型调用正常完成 | append |

### 3.4 回归场景

| 编号 | 场景名称 | 前置条件 | 输入 | 预期结果 | status |
|------|----------|----------|------|----------|--------|
| TC-301 | 报告 JSON 解析回归 | 使用改造前固定响应样本 | 12 类节点合法响应 | 评分、摘要、风险等字段与改造前一致 | append |
| TC-302 | SSE 协议回归 | HTTP Trading 请求 | 完整交易分析 | 不出现模型 chunk 事件，现有业务事件结构不变 | append |
| TC-303 | partial success 回归 | 某分析师流失败，其他成功 | 并行分析任务 | 按现有规则继续并标记 partial success | append |
| TC-304 | GeneralChat 真流式回归 | 普通聊天请求 | 多 chunk 响应 | 前端仍逐 chunk 收到内容 | append |
| TC-305 | 同步 RestClient 回归 | 非本次迁移的同步节点 | 正常响应 | 原同步调用仍使用现有超时配置并可工作 | append |
| TC-306 | legacy 与 pipeline 时限一致 | 分别走两个入口 | 相同慢节点 | 使用相同配置值和超时语义 | append |
| TC-307 | 重试预算回归 | DB RetryConfig 不变 | 429/5xx/timeout | 最大尝试次数和退避策略不被聚合器重复执行 | append |
| TC-308 | 并行节点 deadline 回归 | 四个分析师同时提交 | 三个完成、一个超过 180 秒 | 阶段不乘节点数量，只取消超时分析师并形成 partial success | append |

---

## 4. 用例与代码映射

| 测试编号 | 对应用例方法 | 目标类/方法 | 覆盖类型 |
|----------|--------------|-------------|----------|
| TC-001 | `should_collect_chunks_in_order_when_stream_completes()` | `StreamingChatResponseCollector#collect` | 正常 |
| TC-003 | `should_complete_when_stream_exceeds_sync_timeout_but_keeps_emitting()` | `RetryChatModel#stream` + collector | 正常 |
| TC-006 | `should_retry_when_first_content_times_out_before_any_response()` | `RetryChatModel#stream` | 正常/重试 |
| TC-102 | `should_not_retry_when_error_occurs_after_effective_content()` | `RetryChatModel#stream` | 异常 |
| TC-103 | `should_cancel_when_effective_content_is_idle_for_thirty_seconds()` | `RetryChatModel#stream` | 异常 |
| TC-104 | `should_cancel_all_attempts_when_logical_call_deadline_is_reached()` | `RetryChatModel#stream` | 异常 |
| TC-107 | `should_cancel_subscription_when_waiting_thread_is_interrupted()` | `StreamingChatResponseCollector#collect` | 异常 |
| TC-201 | `should_return_empty_string_when_stream_is_empty()` | `StreamingChatResponseCollector#collect` | 边界 |
| TC-206 | `should_reject_node_timeout_not_greater_than_model_timeout()` | 配置校验 | 边界 |
| TC-207 | `should_isolate_buffers_between_concurrent_requests()` | `StreamingChatResponseCollector#collect` | 并发 |
| TC-209 | `should_reset_attempt_phase_but_preserve_call_budget_when_retrying()` | `RetryChatModel#streamAttempt` | 边界/重试 |
| TC-210 | `should_isolate_stream_state_between_concurrent_model_calls()` | `RetryChatModel#stream` | 并发 |
| TC-211 | `should_override_global_streaming_timeout_for_configured_model()` | `AiClientModelNode` | 配置 |
| TC-212 | `should_cancel_all_inflight_streams_for_disconnected_request()` | collector + `SseEventSink` | 取消/并发 |
| TC-213 | `should_not_cancel_other_request_when_one_request_disconnects()` | collector + `SseEventSink` | 隔离 |
| TC-214 | `should_complete_without_sse_cancellation_signal_for_background_call()` | collector | 边界 |
| TC-303 | `should_continue_with_partial_success_when_one_analyst_stream_fails()` | `AnalystCollectionStage` | 回归 |
| TC-308 | `should_not_multiply_parallel_stage_timeout_by_analyst_count()` | `AnalystCollectionStage` | 回归 |

12 个节点不为相同调用替换重复创建 12 套低价值 mock 测试。通过公共聚合器测试、代表节点解析测试、静态调用检查和完整 pipeline 回归共同覆盖。

---

## 5. 关键校验点

### 5.1 数据正确性

- chunk 顺序、字符和 JSON 边界保持不变。
- 失败尝试和成功尝试的内容不能混合。
- 只有正常 complete 的完整字符串可以进入报告解析。

### 5.2 状态与生命周期

- 超时和断开会取消上游订阅。
- SSE 断连使用主动 cancellation signal，不依赖下一个模型 chunk 到达后轮询。
- 请求取消以异常结束并丢弃部分聚合内容，不能转换为正常 complete。
- 节点取消后不能产生 late context write、late SSE 或 phase transition。
- 并行分析师的聚合缓冲区彼此隔离。
- 每次逻辑 LLM 调用拥有独立 `StreamState`；每次 attempt 拥有独立 `StreamPhase`。
- 并行分析师各自使用独立节点 deadline，阶段等待时间不乘分析师数量。

### 5.3 异常与重试

- `AWAITING_RESPONSE` 下首内容超时沿用 RetryConfig 和剩余总预算。
- `RESPONSE_OBSERVED` 或 `CONTENT_OBSERVED` 后异常不重试。
- 空 delta、role、usage 和工具信息不结束首内容计时，但会锁定后续重试。
- 聚合器不创建第二套 retryWhen。
- timeout 日志能够区分 connect、first_content、idle、model_total 和 node。

### 5.4 日志与敏感信息

- 校验日志包含 operation、firstContentLatencyMs、totalLatencyMs、chunkCount 和 completionState。
- 日志不包含 API Key、完整 Prompt 或完整模型响应。

---

## 6. 执行计划

### 6.1 自动化测试

| 步骤 | 内容 | 预期结果 | status |
|------|------|----------|--------|
| 1 | 编写 RetryChatModel 状态机、聚合器和配置单元测试 | 正常、异常和边界用例可重复执行 | append |
| 2 | 执行 RetryChatModel streaming 专项测试 | attempt 三状态、45/30/150 和重试契约通过 | append |
| 3 | 执行 trading-domain 模块测试 | 节点和 pipeline 回归通过 | append |
| 4 | 执行 domain 模块测试 | 公共能力与 GeneralChat 无回归 | append |
| 5 | 全项目编译 | `BUILD SUCCESS` | append |
| 6 | 静态搜索 12 个目标节点 | 无 `.call().content()` 遗留 | append |

建议命令在实现计划中根据新增测试类最终包名确定，至少包含：

```text
mvn -pl ai-agent-study-domain -am -Dtest=StreamingChatResponseCollectorTest,RetryChatModelStreamTest -Dsurefire.failIfNoSpecifiedTests=false test
mvn -pl ai-agent-study-trading/ai-agent-study-trading-domain -am test -Dsurefire.failIfNoSpecifiedTests=false
mvn clean compile -DskipTests
```

### 6.2 云端手工验证

| 步骤 | 操作 | 预期结果 | status |
|------|------|----------|--------|
| 1 | 部署应用并确认 streaming/节点超时配置已加载 | 启动日志显示默认 10s/45s/30s/150s/180s 及模型覆盖来源 | append |
| 2 | 发起一次完整 Trading Agent 分析 | 12 个节点按业务选择正常执行 | append |
| 3 | 选择耗时超过 80 秒但持续输出的模型调用 | 调用成功，不再出现同步 `Read timed out` | append |
| 4 | 检查浏览器 SSE | 只出现进度和完整报告事件，不出现模型原始 chunk | append |
| 5 | 检查服务日志 | 可看到首个有效文本和总耗时，无敏感内容 | append |
| 6 | 模拟完全无响应和空 delta 后无文本 | 前者按 RetryConfig 重试，后者锁定重试并明确失败 | append |
| 7 | 中途关闭浏览器连接 | 上游任务被取消，不产生 late SSE/context write | append |

---

## 7. 验收标准

| 编号 | 验收项 | 标准 | status |
|------|--------|------|--------|
| AC-001 | 公共聚合与调用隔离正确 | TC-001~TC-003、TC-201~TC-214 通过 | append |
| AC-002 | 异常和取消正确 | TC-101~TC-112 通过 | append |
| AC-003 | 既有重试契约保持 | TC-006、TC-102、TC-109、TC-110、TC-307 通过 | append |
| AC-004 | 12 个节点迁移完成 | 静态检查和代表节点测试通过 | append |
| AC-005 | 前端协议无变化 | TC-302、云端步骤 4 通过 | append |
| AC-006 | 慢响应问题解决 | TC-003、云端步骤 3 通过 | append |
| AC-007 | pipeline 生命周期无回归 | TC-303、TC-306、TC-308 通过 | append |
| AC-008 | GeneralChat 无回归 | TC-304 通过 | append |
| AC-009 | 构建质量门槛 | 专项测试、模块测试和全项目编译通过 | append |
| AC-010 | SSE 主动取消可靠 | TC-106、TC-111、TC-112、TC-212~TC-214 通过 | append |

---

## 8. 风险说明

| 风险点 | 影响 | 应对措施 |
|--------|------|----------|
| 真实供应商发送心跳但不发送内容 | 网络连接正常，但业务首文本仍可能超时 | 首内容计时只由非空文本结束，并区分 attempt 状态 |
| 云端模型耗时波动 | 手工验收结果不稳定 | 自动化使用虚拟时间，云端验证作为补充 |
| 节点超时取消不彻底 | 产生额外计费和 late write | 强制验证 subscription cancel 和中断传播 |
| 测试只验证公共入口 | 个别节点可能漏迁移 | 使用静态搜索和目标文件清单补足覆盖 |

---

## 9. 执行结果记录

| 项目 | 结果 |
|------|------|
| 单元测试 | append |
| 模块集成测试 | append |
| 接口/SSE 回归 | append |
| 云端手工验证 | append |
| 全项目编译 | append |

### 问题记录

| 编号 | 问题描述 | 影响范围 | 状态 |
|------|----------|----------|------|
| - | 当前无，实施和验证阶段补充 | - | append |

### 结论

- 是否达到实现完成条件：否，当前仅完成 Story 与测试设计。
- 是否达到提测/合并条件：否，待代码实现及全部 `append` 项验证为 `pass`。
