# 测试方案：Story 1 流式 LLM Query 重试

## 1. 测试背景

### 1.1 对应开发执行文档

- `docs/superpowers/plans/2026-07-30-story-1-stream-http-attempt-retry.md`

### 1.2 测试目标

- 验证普通网络错误按整次 LLM query 重试，而不是重放当前 SSE request。
- 验证失败 query attempt 的部分结果不与成功 attempt 拼接。
- 验证当前 attempt `onComplete` 前没有任何 `ChatResponse` 到达下游；成功后才按原顺序释放。
- 验证全部 query attempt 耗尽时不返回部分结果，只传播最后一次终止异常。
- 验证 stream classifier 采用 hard exclusion、veto、HTTP status、provider fallback、transport cause 的安全优先顺序。
- 验证 Spring AI delegate 的 `complete/error` 是唯一终止依据，不解析 raw `[DONE]`。
- 验证 attempt 终止后不留存响应、history、HTTP/SSE 对象或历史 attempt 档案。
- 验证 `Retry-After` 被忽略，所有 retry 延迟只由 `RetryConfig` 退避参数决定。
- 验证资源释放以订阅生命周期和虚拟时间为依据，不依赖 GC 或 WebClient 内部实现。
- 验证 `RetryConfig.maxAttempts` 只统计应用层 query subscription，不统计工具递归 HTTP 轮次或 Provider 内部行为。
- 验证工具后的错误会从原始 `Prompt` 重启，并明确工具可能重复执行。
- 验证 Story 1 不重试 timeout，并锁定 Story 2 在原子聚合前观察 chunk、Story 3 接入 retry 的契约边界。
- 验证同步 `call()`、压缩、取消、并发和重试预算没有回归。

### 1.3 测试范围

- `RetryChatModel.stream()` 的 query attempt、错误分类、预算、退避和取消。
- `RetryChatModel.stream()` 的当前 attempt `ChatResponse` 瞬时聚合与原子释放。
- `StreamingChatResponseCollector` 只做既有最终文本聚合，不执行 retry 或 attempt reset。
- `AiClientModelNode` 的 `RetryConfig` 与 timeout 装配。
- Spring AI 1.1.2 `OpenAiChatModel` 工具递归下的整次 query 重启语义。
- `AiClientApiNode`、`AiClientHttpTimeoutConfig` 无 WebClient retry filter 的静态回归。

### 1.4 不在测试范围

- 当前第 N 轮 HTTP request 重放。
- SSE 单事件、chunk、字节偏移或 `Last-Event-ID` 续传。
- first-event、raw-idle、stall/chunk timeout 新设计。
- timeout 异常接入重试。
- WebClient `ClientRequest` replay、原子 `DataBuffer`、`[DONE]` 与 EOF 判定。
- 工具 exactly-once、幂等框架、补偿事务或断点持久化。
- 真实 Provider 的输出质量与计费准确性。

---

## 2. 测试策略

### 2.1 测试分层

| 测试层级 | 是否覆盖 | 说明 |
|----------|----------|------|
| 单元测试 | 是 | query attempt、错误分类、预算、退避、取消和最终异常 |
| 模块集成测试 | 是 | 本地断流 server 与真实 WebClient/JDK connector |
| Spring AI 契约测试 | 是 | 真实 `OpenAiChatModel` 工具递归与 query 重启 |
| 回归测试 | 是 | timeout、同步 call、压缩、runtime context 和装配 |
| 真实 Provider 手工测试 | 可选 | 仅观察请求次数与日志，不作为自动化门禁 |

### 2.2 测试原则

- 普通单元测试只 mock 当前层直接依赖。
- HTTP I/O 分类使用本地 HTTP/raw socket server，不依赖真实 Provider。
- Spring AI 工具行为使用真实 `OpenAiChatModel`，不 mock `internalStream()`。
- 退避使用 Reactor 虚拟时间，避免真实等待。
- 每个用例必须以 `assert`、`StepVerifier`、订阅计数或 HTTP 请求计数结束。
- query attempt 必须串行；前一 attempt 终止后才能观察到下一订阅。
- 初始 `status` 统一为 `append`，实现并验证通过后改为 `pass`。

### 2.3 Mock 与测试替身

| 依赖 | 是否 Mock | 方式 | 说明 |
|------|-----------|------|------|
| `ChatModel delegate` | 是 | Mockito/可编程 Flux | 单元测试控制 chunk 与终止错误 |
| Reactor 时间 | 是 | `StepVerifier.withVirtualTime` | 验证退避和取消 |
| HTTP Provider | 是 | 本地 `HttpServer`/raw socket server | 制造 header 与 body I/O 中断 |
| `OpenAiChatModel` | 否 | Spring AI 1.1.2 真实实现 | 锁定工具递归契约 |
| Tool callback | 测试实现 | `AtomicInteger` | 观察 at-least-once 重复执行 |
| 外部数据库、Redis、真实 LLM | 是 | 不接入 | Story 1 不依赖这些系统 |

---

## 3. 正常场景

| 编号 | 场景 | 前置条件 | 输入 | 预期结果 | status |
|------|------|----------|------|----------|--------|
| TC-001 | 首次 connect error 后成功 | `maxAttempts=2` | attempt 1 connect error；attempt 2 完整结果 | query 订阅两次，最终只返回第二次结果 | pass |
| TC-002 | `429` 后成功 | `maxAttempts=2` | 首次 `429`，第二次成功 | 消耗一个 credit，按退避重跑完整 query | pass |
| TC-003 | 目标 5xx 后成功 | 参数化 `500/502/503/504` | 首次错误，第二次成功 | 每种状态均重跑完整 query 一次 | pass |
| TC-004 | body 中途断开后成功 | attempt 1 内部已产生部分内容但未提交 | attempt 2 返回完整内容 | 下游从未看到 attempt 1，只收到 attempt 2 | pass |
| TC-005 | 工具后断流再成功 | attempt 1 已执行 tool callback | attempt 2 从原始 Prompt 重启 | 工具允许再次执行，最终只提交成功 attempt | pass |
| TC-006 | 多次错误共享预算 | `maxAttempts=3` | 前两次普通错误，第三次成功 | 总 query attempt 为 3，退避序号不重置 | pass |
| TC-007 | 成功前下游不可见 | delegate 已发出多个 chunk 但尚未 complete | `StepVerifier` 在 complete 前检查 | 下游零 `onNext`；complete 后按原顺序收到全部 chunk | pass |

## 4. 异常场景

| 编号 | 场景 | 前置条件 | 输入 | 预期结果 | status |
|------|------|----------|------|----------|--------|
| TC-101 | 响应头前断开 | 有 retry credit | server 接收连接后关闭 | 重跑完整 query | pass |
| TC-102 | connection reset | 有 retry credit | cause chain 含 reset | 重跑完整 query | pass |
| TC-103 | `429` 持续失败并耗尽 | `maxAttempts=3` | 三次均 `429` | 三次后传播最终异常，无第四次订阅 | pass |
| TC-104 | 5xx 持续失败并耗尽 | `maxAttempts=2` | 两次相同 5xx | 传播最终异常，无第三次订阅 | pass |
| TC-105 | 非目标 4xx | retry enabled | `400/401/403/404/408/409/422/529` | 直接传播，不重试 | pass |
| TC-106 | timeout 异常 | retry enabled | 现有 first/idle/total timeout | 立即传播，不消耗 credit，不创建下一 attempt | pass |
| TC-107 | SSE/JSON 解码错误 | body 正常传输 | 非法协议内容 | 直接传播，不重试 | pass |
| TC-108 | 工具执行错误 | callback 抛异常 | tool-call 已到达 | 直接传播，不重试 | pass |
| TC-109 | retryable code 被 veto | `nonRetryableErrorCodes` 命中 | 对应 provider code | 直接传播，不重试 | pass |
| TC-110 | 用户取消 | attempt 进行中或退避中 | cancellation signal | 取消当前订阅，无下一 attempt | pass |
| TC-111 | 多次部分输出后全部失败 | 每次 attempt 均先产生 chunk | 最后一次抛出可辨识异常 | 无部分结果，只传播最后一次异常及其 cause chain | pass |
| TC-112 | 错误分类冲突矩阵 | 同一异常同时暴露 status/code/type 信号 | 参数化 hard exclusion、veto、HTTP `400/429`、provider fallback、reset | 严格按 Story 优先级得到唯一 retry 结论 | pass |
| TC-113 | 无 raw `[DONE]` 可见但正常完成 | delegate 先发部分内容后 `complete` | query 边界只观察 Reactor 信号 | 按成功提交，不创建 retry | pass |
| TC-114 | 忽略 `Retry-After` | 异常 message/metadata 含 `Retry-After: 30` | `initialIntervalMs=1000` | 使用虚拟时间验证 1000ms 后 retry，不等待 30 秒 | pass |

## 5. 边界与并发场景

| 编号 | 场景 | 前置条件 | 输入 | 预期结果 | status |
|------|------|----------|------|----------|--------|
| TC-201 | retry disabled | `enabled=false` | 普通可重试错误 | 只订阅一次 query | pass |
| TC-202 | `maxAttempts=1` | retry enabled | 普通可重试错误 | 只订阅一次 query | pass |
| TC-203 | `maxAttempts>10` | retry enabled | 持续普通错误 | 最多 10 次 query attempt | pass |
| TC-204 | 退避中取消 | 已安排 retry | 下游 cancel 后推进全部虚拟时间 | timer 取消、active=0、无下一订阅 | pass |
| TC-205 | attempt 内部产生内容后取消 | 下游尚未收到内容 | 下游 cancel | 上游 cancel 恰好一次、active=0、下游零 `onNext`、无重试 | pass |
| TC-206 | 并发订阅隔离 | 同一模型 Bean 两个订阅 | 不同错误序列 | budget、退避和结果互不影响 | pass |
| TC-207 | 空成功结果 | delegate 正常 complete | 无 chunk | 正常完成，不重试 | pass |
| TC-208 | query 重启等价 | attempt 2 | 捕获两次入口 Prompt | 两次均从同一入口 Prompt/压缩状态开始 | pass |
| TC-209 | 应用层 attempt 与 HTTP 轮次分离 | 对比无工具与 tool-call query | 统计 query subscription、tool callback 和 HTTP request | attempt 只按 query subscription 增长；工具 HTTP 轮次不消耗 credit | pass |
| TC-210 | attempt 不留存 | attempt 1 部分输出后失败，attempt 2 成功 | 检查 query 控制状态、日志和最终结果 | 无历史 attempt/响应引用；日志仅含元数据；最终只含 attempt 2 | pass |
| TC-211 | 三种终止路径资源归零 | 参数化 complete/error/cancel | 记录 subscribe/cancel/`doFinally`/active | 每条路径结束后 active=0，终止次数符合预期 | pass |

## 6. 回归场景

| 编号 | 场景 | 前置条件 | 输入 | 预期结果 | status |
|------|------|----------|------|----------|--------|
| TC-301 | first-content timeout | 现有 timeout 配置 | 首个有效响应超时 | 截止与异常类型保持现状 | pass |
| TC-302 | idle timeout | 已观察有效内容 | 后续内容空闲超时 | 截止与异常类型保持现状 | pass |
| TC-303 | total timeout | query 持续未结束 | 超过现有 totalTimeout | 仍取消并传播现有异常 | pass |
| TC-304 | 同步 `call()` 普通重试 | 现有 `RetryStrategy` | 429/5xx 后成功 | 次数、退避和最终响应不变 | pass |
| TC-305 | 同步上下文压缩 | 现有压缩配置 | `1261` 后成功 | 压缩恢复语义不变 | pass |
| TC-306 | stream 主动压缩 | 超过阈值 | 压缩后成功 | 压缩调用和入口 Prompt 语义不变 | pass |
| TC-307 | stream `1261` 恢复 | 压缩预算可用 | 首次 `1261` | 压缩恢复保持有效 | pass |
| TC-308 | 延迟订阅 runtime context | 作用域外订阅 | 压缩触发 | 使用入口捕获 context | pass |
| TC-309 | WebClient 无 replay filter | Spring 容器装配 | 检查 builder/filter | 仅保留 connect timeout 配置 | pass |
| TC-310 | `GeneralChatNode` 原子可见性 | 模型 attempt 尚未 complete | delegate 发出部分 chunk | emitter 不收到模型内容；成功后才收到该 attempt 内容 | pass |

---

## 7. 用例与测试代码映射

| 测试编号 | 建议测试方法 | 目标类/边界 |
|----------|--------------|-------------|
| TC-001~TC-004 | `should_retry_whole_query_when_ordinary_stream_error_occurs()` | `RetryChatModel` |
| TC-005 | `should_restart_original_query_and_allow_tool_reexecution_when_tool_round_fails()` | Spring AI 工具契约集成测试 |
| TC-006 | `should_share_retry_budget_across_query_attempts()` | query retry state |
| TC-007 | `should_not_emit_attempt_responses_before_completion()` | `RetryChatModel` |
| TC-101~TC-102 | `should_classify_transport_failures_as_retryable()` | ordinary error classifier |
| TC-103~TC-104 | `should_propagate_last_error_when_query_attempts_are_exhausted()` | query retry boundary |
| TC-105~TC-110 | `should_not_retry_out_of_scope_errors_or_cancellation()` | classifier + query retry boundary |
| TC-111 | `should_discard_all_partial_results_and_propagate_last_error_when_exhausted()` | query retry boundary + collector |
| TC-112 | `should_apply_safe_stream_error_classification_precedence()` | stream ordinary error classifier |
| TC-113 | `should_accept_delegate_completion_without_inspecting_raw_done_marker()` | query retry boundary |
| TC-114 | `should_ignore_retry_after_and_use_retry_config_backoff()` | query retry state |
| TC-201~TC-203 | `should_apply_retry_config_attempt_limits()` | retry policy/state |
| TC-204~TC-206 | `should_cancel_and_isolate_query_attempt_state()` | query retry boundary |
| TC-208 | `should_restart_from_equivalent_entry_prompt()` | query retry integration test |
| TC-209 | `should_count_query_subscriptions_instead_of_tool_http_rounds()` | Spring AI/model/API integration test |
| TC-210 | `should_not_retain_failed_attempt_state_or_content()` | query retry boundary + collector |
| TC-211 | `should_release_subscription_state_on_every_terminal_path()` | instrumented test publisher |
| TC-301~TC-303 | 现有及新增 timeout 回归测试 | `RetryChatModelStreamTest` |
| TC-304~TC-308 | 现有 retry/compression/runtime context 测试 | `RetryChatModel`、`RetryStrategy` |
| TC-309 | `should_not_install_webclient_retry_filter()` | HTTP/API 装配测试 |
| TC-310 | `should_not_send_model_chunks_before_attempt_completion()` | `GeneralChatNode` + `RetryChatModel` |

## 8. 关键校验点

### 8.1 结果与错误

- 最终成功结果只来自一个完整成功的 query attempt。
- 当前 attempt `onComplete` 前，下游 subscriber 与 emitter 均不得收到该 attempt 的 `ChatResponse`/内容。
- 失败 attempt 的文本、tool-call delta 和 metadata 不与成功 attempt 拼接。
- 全部耗尽后只传播最后一次 attempt 的原始终止异常，不返回伪成功、空成功、部分结果或 composite error。
- 最后一次异常的类型、message 和 cause chain 除 Reactor 既有 unwrap 外保持不变。
- timeout/cancel/decode/tool/business hard exclusion 不能被 message、provider code 或 `retryableErrorCodes` 翻转。
- `nonRetryableErrorCodes` 对 cause chain 中任一已识别 raw/normalized/provider/HTTP code 一票否决。
- 可靠实际 HTTP status 优先于 provider body code；provider code 只在无可靠 status 时回退。
- `retryableErrorCodes` 不能扩展 Story 1 固定错误范围。
- delegate `complete` 一律按成功，只有 delegate `error` 才进入错误分类；测试不得在 query 层解析 raw `[DONE]`。
- 异常包含 `Retry-After` 时仍严格使用 `RetryConfig.initialIntervalMs/multiplier/maxIntervalMs`，不得建立 header 解析或透传依赖。

### 8.2 Timeout 跨 Story 契约

- Story 1 不新增 timeout 类型或 operator，timeout 不进入 ordinary retry classifier。
- Story 2 必须让 first-content、idle、total timeout 在原子聚合前观察真实 chunk，并保留配置与取消能力。
- Story 3 必须复用 Story 1 的同一 budget/backoff/attempt 丢弃机制接入 timeout，不建立第二套 retry。

### 8.3 状态、资源与风暴保护

- retry credit 只在确定调度下一 query attempt 时扣减。
- 并发订阅不共享可变 budget、退避序号或临时结果。
- cancel 后无后台订阅、退避 timer 或额外 HTTP request。
- 一个 query attempt 完全终止后才启动下一 attempt。
- query subscription 数不超过 `RetryConfig.maxAttempts`；HTTP 请求数允许因工具轮次大于 attempt 数，但不得出现同一轮隐藏重复请求。
- Provider 服务端内部 retry 不可观察，不作为本地预算或测试断言对象。
- query 控制状态不包含历史 attempt 集合、失败响应快照、raw SSE/body 或失败 conversation history。
- 失败日志只记录 attempt 序号、耗时、chunk 数、部分长度和 error type/code，不记录完整响应正文。
- attempt N 的 `doFinally(ERROR)` 和 active=0 必须早于 attempt N+1 的 subscribe。
- complete/error/cancel 后 active subscription 均为零；backoff cancel 后推进虚拟时间也无新订阅。
- 资源测试不调用 `System.gc()`，不依赖弱引用、堆快照、真实 sleep 或 WebClient `DataBuffer` 内部状态。

### 8.4 范围隔离

- 无 WebClient request replay、原子 SSE `DataBuffer` 或 `[DONE]`/EOF 判定。
- 无 first-event、raw-idle、stall/chunk timeout 新逻辑。
- `RetryChatModel.call()` 与 `RetryStrategy` 无生产代码语义修改。

---

## 9. 自动化执行计划

| 步骤 | 内容 | 预期结果 | status |
|------|------|----------|--------|
| 1 | 执行 ordinary error classifier 与预算单元测试 | 错误范围、attempt 和退避通过 | pass |
| 2 | 执行 query attempt 原子可见性、隔离与取消测试 | complete 前零输出、无结果拼接、无取消后重试 | pass |
| 3 | 执行 Spring AI 工具递归集成测试 | 从原始 query 重启并观察 at-least-once | pass |
| 4 | 执行 timeout 边界回归 | Story 1 不重试 timeout，Story 2/3 接入契约未被混入 | pass |
| 5 | 执行同步 call、压缩、runtime context 回归 | 历史语义不变 | pass |
| 6 | 执行 domain 模块测试 | `BUILD SUCCESS` | pass |
| 7 | 编译全项目并静态检查范围 | 无 Story 2/3 和 WebClient replay 混入 | pass |

执行命令：

```powershell
mvn -pl ai-agent-study-domain -am -Dtest=StreamQueryRetryClassifierTest,RetryChatModelAtomicAttemptTest,OpenAiQueryRetryIntegrationTest,RetryChatModelStreamTest,RetryChatModelCornerTest,RetryChatModelCompressionTest,CompressionRetryIntegrationTest,RetryStrategyTest,AiClientModelNodeRetryTest,StreamingChatResponseCollectorTest -Dsurefire.failIfNoSpecifiedTests=false test

mvn -pl ai-agent-study-domain -am test -Dsurefire.failIfNoSpecifiedTests=false

mvn compile -DskipTests
```

## 10. 验收标准

| 编号 | 验收项 | 标准 | status |
|------|--------|------|--------|
| AC-001 | query 重试正确 | TC-001~TC-007、TC-101~TC-104 通过 | pass |
| AC-002 | attempt 结果隔离 | TC-004、TC-005 最终结果无失败 attempt 内容；TC-111 无部分结果且传播最后异常 | pass |
| AC-003 | 错误范围严格 | TC-105~TC-112 通过，冲突信号按安全优先规则得到唯一结论 | pass |
| AC-004 | timeout 分层边界 | TC-301~TC-303 确认 Story 1 不重试；文档明确 Story 2 观察位置与 Story 3 接入责任 | pass |
| AC-005 | at-least-once 契约明确 | TC-005 证明工具允许重复执行，不声明 exactly-once | pass |
| AC-006 | 预算、风暴、留存与释放保护 | TC-201~TC-211 通过，失败 attempt 无跨 attempt 内容引用且所有终止路径 active=0 | pass |
| AC-007 | 同步与压缩无回归 | TC-304~TC-308 通过 | pass |
| AC-008 | 无 WebClient replay | TC-309 与静态范围检查通过 | pass |
| AC-009 | Spring AI 终止依据 | TC-113 正常 complete 不重试，测试和生产设计均不解析 `[DONE]` | pass |
| AC-010 | 统一退避来源 | TC-114 通过，`RetryConfig` 退避与 stream timeout 配置职责分离 | pass |
| AC-011 | 后端原子可见性 | TC-007、TC-310 通过，成功前 subscriber/emitter 均看不到模型 chunk | pass |
| AC-012 | 应用层计数 | TC-209 证明工具 HTTP 轮次不消耗 query retry credit，且无隐藏 Spring stream retry | pass |
| AC-013 | 构建质量门禁 | domain 测试和全项目编译成功 | pass |

## 11. 风险记录

| 风险 | 测试关注点 | 通过条件 |
|------|------------|----------|
| 工具或 Provider 请求重复执行 | callback 与 HTTP 请求计数 | 行为符合明示的 at-least-once 契约且受预算限制 |
| 失败 attempt 内容污染 | 部分 chunk 后断流再成功 | 最终结果只含成功 attempt |
| timeout 看不到被暂存的 chunk | Story 2 timeout 位置契约 | timer 在原子聚合前观察真实 chunk，配置与中断能力保留 |
| Spring AI 内部实现升级 | 真实工具递归契约测试 | 升级后仍从入口 query 重启或明确重新评审 |
| retry owner 叠加 | query subscription、工具轮次与 HTTP 请求分项计数 | 无工具轮无隐藏重复；工具 HTTP 轮次不误扣 query credit |
| 无异常 EOF 可能静默截断 | delegate `complete/error` 契约 | Story 1 接受 Spring AI 的完成判断，不在上层猜测 raw SSE 状态 |
| 历史 attempt 引用造成内存增长 | 多次失败后的状态与日志检查 | 不保存 attempt 档案或响应正文，只保留固定大小控制状态 |

## 12. 执行结果记录

| 项目 | 结果 |
|------|------|
| 单元测试 | pass（Story 1 定向套件共 92 项） |
| query retry 集成测试 | pass |
| Spring AI 工具契约测试 | pass |
| timeout 回归测试 | pass |
| 同步与压缩回归 | pass |
| domain 模块测试 | pass（共 522 项） |
| 全项目编译 | pass |

### 问题记录

| 编号 | 问题描述 | 影响范围 | 状态 |
|------|----------|----------|------|
| BUG-001 | `xfg-wrench-starter-design-framework` 内嵌旧版 Spring/Logback class 并抢占 classpath | WebClient 结构化异常与日志初始化 | pass |
| BUG-002 | 并发隔离测试最初使用全局调用顺序，存在非确定性 | 测试稳定性 | pass |

### 结论

- 当前是否达到开发完成条件：是，全部任务、测试场景和验收项均为 `pass`。
- 当前是否达到提测/合并条件：是，定向测试、domain 全量测试、全项目编译和静态范围检查均通过。
