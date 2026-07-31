# 测试方案：Story 3 SSE Timeout 接入完整 Query 重试

## 1. 测试背景

### 1.1 对应设计

- Story 3：`docs/superpowers/plans/2026-07-31-story-3-sse-timeout-query-retry-design.md`。
- 前置 Story 1：`docs/superpowers/plans/2026-07-30-story-1-stream-http-attempt-retry.md`。
- 前置 Story 2：`docs/superpowers/plans/2026-07-30-story-2-sse-chunk-timeout-design.md`。

### 1.2 测试目标

- 验证 `FirstStreamChunkTimeoutException` 与 `StreamChunkIdleTimeoutException` 在开关开启时进入 Story 1 完整 query retry。
- 验证 `LlmQueryAttemptTimeoutException`、取消、连接超时、decode 和工具错误继续 hard exclusion。
- 验证 SSE timeout 与普通错误复用同一 attempt budget 和 backoff。
- 验证失败 attempt 的所有结果被丢弃，成功 attempt 才对下游可见。
- 验证工具后的第二轮 SSE timeout 会重启完整 query，并符合 at-least-once。
- 验证 node timeout/cancel 可以终止 active attempt 或 backoff，不产生迟到 retry。
- 验证每次失败有结构化日志留痕，且不泄露 Prompt、正文和 tool 参数。
- 验证同步 `call()`、Story 1 ordinary retry 和 Story 2 watchdog 无回归。

### 1.3 测试范围

- `AiClientModelVO.RetryConfig` 新字段默认值与配置绑定。
- `StreamQueryRetryClassifier` timeout subtype、cause chain 和优先级。
- `RetryChatModel.stream()` ordinary credit、backoff、结果隔离、取消和最终异常。
- Story 2 结构化 timeout 到 Story 1 query retry 的集成链路。
- Spring AI 1.1.2 工具递归后的第二轮 timeout 行为。
- 结构化日志与低基数指标。

### 1.4 不在本次测试范围

- WebClient 当前 request replay 或 SSE 断点续传。
- `OpenAiApi`、`OpenAiChatModel` 的自定义实现。
- 工具 exactly-once、幂等键、补偿或结果持久化。
- node/run deadline 下传与 retry 前剩余预算判断。
- Story 2 的 watchdog 内部实现正确性；本测试只消费其稳定异常契约，跨 Story 集成用例除外。
- completion integrity，包括 empty complete、缺失 `[DONE]` 和不完整 tool-call 的业务判定。

---

## 2. 测试策略

### 2.1 测试分层

| 测试层级 | 是否覆盖 | 说明 |
|---|---|---|
| 单元测试 | 是 | 配置默认值、classifier、预算、退避与异常传播 |
| 组件测试 | 是 | `RetryChatModel` 原子 attempt、取消与日志 |
| HTTP 集成测试 | 是 | Story 2 异常穿透 WebClient/Spring AI 后触发 query retry |
| Spring AI 契约测试 | 是 | 真实工具递归、第二轮 timeout 与工具重复执行 |
| 回归测试 | 是 | ordinary retry、同步 `call()`、压缩、Story 2 watchdog |
| 真实 Provider 手工测试 | 可选 | 灰度观察 timeout retry 成功率、计费和工具副作用 |

### 2.2 测试原则

- 单元测试只 mock 当前层直接依赖，中间件和真实 Provider 不接入。
- 时间相关用例使用 `StepVerifier.withVirtualTime` 或可注入 Scheduler，不真实等待 45/90/150 秒。
- HTTP/SSE 故障使用本地 HTTP server 或可编程 publisher。
- 工具递归使用真实 Spring AI 1.1.2 `OpenAiChatModel`，不 mock `internalStream()`。
- 每个用例以明确的 assert、订阅计数、HTTP 请求计数、callback 次数或日志字段断言结束。
- 所有初始 `status` 为 `append`；实现并验证通过后才能改为 `pass`。

### 2.3 Mock 策略

| 依赖项 | 是否 Mock | Mock 方式 | 说明 |
|---|---|---|---|
| `ChatModel delegate` | 是 | Mockito/可编程 `Flux` | 控制每个 query attempt 的输出和异常 |
| Story 2 timeout publisher | 是 | 结构化异常 stub | classifier 与 query retry 单元测试 |
| Reactor 时间 | 是 | `VirtualTimeScheduler` | backoff、取消与 timeout 竞态 |
| HTTP Provider | 是 | 本地 HTTP server/raw socket | 跨 Story 集成，不依赖外部网络 |
| `OpenAiChatModel` | 否 | Spring AI 1.1.2 真实实例 | 锁定工具递归行为 |
| Tool callback | 测试实现 | `AtomicInteger` | 断言 at-least-once |
| 日志输出 | 是 | test appender | 断言字段与敏感信息缺失 |
| Redis、数据库、真实 LLM | 是 | 不接入 | 与 Story 3 无关 |

---

## 3. 测试场景设计

### 3.1 正常场景

| 编号 | 场景名称 | 前置条件 | 输入 | 预期结果 | status |
|---|---|---|---|---|---|
| TC-001 | 首 Chunk timeout 后重试成功 | `enabled=true`、`retryOnStreamTimeout=true`、`maxAttempts=2` | attempt 1 抛 `FirstStreamChunkTimeoutException`，attempt 2 成功 | 完整 query 订阅两次，下游只收到 attempt 2 | append |
| TC-002 | Chunk idle timeout 后重试成功 | 同上 | attempt 1 先产生部分响应再抛 `StreamChunkIdleTimeoutException`，attempt 2 成功 | attempt 1 内容全部丢弃，最终只提交 attempt 2 | append |
| TC-003 | 包装后的 timeout 可识别 | 开关开启 | timeout subtype 位于两层 cause chain 内 | classifier 返回可重试，创建下一 query attempt | append |
| TC-004 | 普通错误与 timeout 共享预算 | `maxAttempts=3` | attempt 1 为 503，attempt 2 为 idle timeout，attempt 3 成功 | 总 attempt 恰好为 3，无独立 timeout 次数 | append |
| TC-005 | 普通错误与 timeout 共享 backoff | 初始 1s、倍率 2、上限 10s | 503 后 idle timeout | 分别等待 1s、2s，timeout 不重置序列 | append |
| TC-006 | 工具后第二轮 timeout 重启 query | 开关开启且有 credit；Provider 两次 query 均返回同一 tool-call | attempt 1 工具成功后第二轮 idle timeout；attempt 2 成功 | query subscription=2、tool callback=2，HTTP 轮次符合脚本，最终只提交 attempt 2 | append |
| TC-007 | 最后一次 timeout subtype 保持 | `maxAttempts=2` | 两次均为 idle timeout | 传播第二次原始 `StreamChunkIdleTimeoutException` | append |
| TC-008 | 失败 attempt 结构化日志留痕 | 开关开启 | timeout 后重试成功 | 日志包含 attempt、timeout、decision、remaining、backoff 字段 | append |
| TC-009 | Timeout retry 决策指标 | 开关开启 | 分别制造 scheduled、disabled、exhausted、hard-excluded | 对应低基数 counter 各增加一次，无高基数 tag | append |

### 3.2 异常场景

| 编号 | 场景名称 | 前置条件 | 输入 | 预期结果 | status |
|---|---|---|---|---|---|
| TC-101 | 功能开关关闭 | `retryOnStreamTimeout=false` | 首 Chunk timeout | 直接传播，只订阅一次，不消耗下一 credit | append |
| TC-102 | 普通 retry 总开关关闭 | `enabled=false`、timeout 开关为 true | idle timeout | 直接传播，只订阅一次 | append |
| TC-103 | retry credit 耗尽 | `maxAttempts=1` | 允许的 SSE timeout | 直接传播，无第二次订阅 | append |
| TC-104 | Query attempt timeout | 开关开启且有 credit | `LlmQueryAttemptTimeoutException` | hard exclusion，不重试 | append |
| TC-105 | 用户取消与 timeout 竞争 | timeout 与 downstream cancel 同 tick | 分别控制 cancel 先发生和 retry subscription 刚开始两种顺序 | cancel 先发生时无新订阅；新订阅已开始时立即被 cancel；均无后续 attempt | append |
| TC-106 | 客户端断开优先 | 开关开启 | `ClientDisconnectedException` 包装或伴随 SSE timeout | 不重试 | append |
| TC-107 | node timeout 取消 active attempt | retry 进行中 | 外层 future cancel/interrupt | active query 与 HTTP 被取消，无下一 attempt | append |
| TC-108 | node timeout 取消 backoff | 已进入退避 | backoff 中发生外层 cancel | 推进全部虚拟时间后仍无新订阅 | append |
| TC-109 | JDK connect timeout | 开关开启 | `HttpConnectTimeoutException` | 不由 Story 3 重试 | append |
| TC-110 | 其他通用 timeout | 开关开启 | `TimeoutException`、`SocketTimeoutException` | hard exclusion，不重试 | append |
| TC-111 | SSE/JSON decode error | 开关开启 | decode/codec/JSON processing 异常 | 不重试 | append |
| TC-112 | 工具执行异常 | 开关开启 | `ToolExecutionException` | 不重试 | append |
| TC-113 | 非 2xx HTTP 错误 | Story 2 未转换 status | 429、503、400 | 429/503 沿用 ordinary classifier；400 不重试 | append |
| TC-114 | 正常 EOF 或 empty complete | delegate 正常 complete | 无 Chunk 或缺少 raw `[DONE]` | 按成功完成，不触发 Story 3 | append |
| TC-115 | 异常 message 伪造 timeout | 无结构化 subtype | message 含 idle/timeout/90/429 | 不因 message 开启 Story 3 retry | append |
| TC-116 | 最终异常不聚合历史失败 | 多次不同错误后耗尽 | 最后一次为首 Chunk timeout | 只传播最后异常，不新增 composite/suppressed 历史内容 | append |
| TC-117 | SSE timeout 与 hard exclusion 冲突 | 同一 cause chain 同时含允许 subtype | 分别组合 attempt timeout、connect timeout、tool、decode、validation | hard exclusion 优先，所有组合均不重试 | append |

### 3.3 边界场景

| 编号 | 场景名称 | 前置条件 | 输入 | 预期结果 | status |
|---|---|---|---|---|---|
| TC-201 | 开关字段缺失 | 旧模型配置文本 | 不包含 `retryOnStreamTimeout` | 默认解析为 false，timeout 不重试 | append |
| TC-202 | 最大 attempt 安全上限 | `maxAttempts>10`、开关开启 | 持续 SSE timeout | 最多 10 个完整 query attempt | append |
| TC-203 | 零 backoff | `initialIntervalMs=0` | attempt 1 timeout，attempt 2 成功 | attempt 1 `doFinally(ERROR)` 且 active=0 后才订阅 attempt 2；并发 cancel 时 attempt 2 立即终止 | append |
| TC-204 | backoff 上限 | 倍率导致增长超过上限 | 连续混合错误 | 每次等待不超过 `maxIntervalMs` | append |
| TC-205 | 并发订阅隔离 | 同一 `RetryChatModel` 两个订阅 | 不同 timeout 序列 | credit、backoff、日志 attempt 和结果完全隔离 | append |
| TC-206 | 部分文本后 idle | attempt 1 已产生文本但未 complete | idle timeout 后 attempt 2 成功 | 下游看不到 attempt 1 文本 | append |
| TC-207 | 部分 tool-call delta 后 idle | tool callback 尚未执行 | idle timeout 后重试 | 旧 delta 不进入新 attempt，失败 attempt callback=0 | append |
| TC-208 | 工具已执行后的 first timeout | attempt 1 工具成功 | 第二轮首 Chunk timeout | 完整 query 重启，callback 可能再次执行 | append |
| TC-209 | cancel 与 timeout 同刻 | 虚拟时间控制同一 tick | cancel、idle timeout 同刻 | 单一终止结果，无 duplicate retry/log | append |
| TC-210 | attempt timeout 与 idle 同刻 | Story 2 同刻仲裁已生效 | cause chain 为 attempt owner | classifier 不重试 | append |
| TC-211 | 多次失败日志固定大小 | `maxAttempts=10` | 每次产生部分内容并 timeout | 无历史响应/异常集合；日志逐条存在且不含正文 | append |
| TC-212 | Headers 前真实 timeout 链路 | layered 模式、本地 HTTP server | request 发出后不返回 headers | Story 2 产生 first subtype，Story 3 创建全新 query attempt | append |
| TC-213 | 2xx headers 后首 Chunk 前真实 timeout 链路 | layered 模式、本地 HTTP server | headers 按时返回但 body 无 Chunk | first subtype 穿透并触发完整 query retry | append |
| TC-214 | 部分 body 后真实 idle 链路 | layered 模式、本地 HTTP server | 首 Chunk 后停止发送 | idle subtype 穿透并触发完整 query retry | append |
| TC-215 | Retry 获得全新 timeout policy | attempt 1 结构化 timeout 后 retry | 捕获两次 Reactor Context 和 watchdog 状态 | attempt 2 具有新 150 秒 deadline、新 policy 和新的 45/90 秒 watchdog，不复用过期状态 | append |

### 3.4 回归场景

| 编号 | 场景名称 | 前置条件 | 输入 | 预期结果 | status |
|---|---|---|---|---|---|
| TC-301 | 429 普通重试 | timeout 开关关闭或开启 | 429 后成功 | Story 1 次数、退避和结果不变 | append |
| TC-302 | 目标 5xx 普通重试 | retry enabled | 500/502/503/504 | 继续按 ordinary classifier 重试 | append |
| TC-303 | connection reset 普通重试 | retry enabled | reset/body I/O error | 继续按 Story 1 重试 | append |
| TC-304 | `nonRetryableErrorCodes` veto | 配置黑名单命中 | 普通 provider/HTTP code | 继续一票否决，不受 timeout 开关影响 | append |
| TC-305 | context overflow 压缩后 timeout | 压缩开启 | `1261` 完成压缩，随后 SSE timeout，再 retry 成功 | compression budget 与 ordinary budget 分离；timeout retry 复用压缩后的 `state.currentPrompt` | append |
| TC-306 | 同步 `call()` 重试 | timeout 开关开启 | 同步 429/5xx/timeout | 同步 `RetryStrategy` 行为完全不变 | append |
| TC-307 | Story 2 开关关闭但检测继续 | `retryOnStreamTimeout=false` | 45/90 秒 watchdog 到期 | Story 2 准确取消并抛 subtype，Story 3 不重试 | append |
| TC-308 | Story 2 stall | 30 秒无新 Chunk后恢复 | stall event | 只记录 stall，不产生 retry | append |
| TC-309 | 无 WebClient retry filter | Spring 装配 | 检查 filter 与请求计数 | Story 3 不新增 request replay 或隐藏 retry | append |
| TC-310 | 模型配置文本兼容 | 新旧配置混合 | 新字段 true/false/缺失 | 绑定结果稳定，其他 `RetryConfig` 字段不变 | append |
| TC-311 | Legacy 模式开关 no-op | 全局 `timeout-mode=legacy`、模型开关 true | 旧 timeout 发生 | 不触发 Story 3，记录一次配置告警且应用正常启动 | append |
| TC-312 | 两个模型开关隔离 | layered 模式；模型 A=true、模型 B=false | 两者分别发生相同 SSE timeout | A 重试、B 传播，预算和日志互不影响 | append |

---

## 4. 用例与代码映射

| 测试编号 | 建议测试方法 | 目标类/方法 | 覆盖类型 |
|---|---|---|---|
| TC-001~TC-003 | `should_retry_whole_query_when_allowed_stream_timeout_occurs()` | `StreamQueryRetryClassifier#isRetryable`、`RetryChatModel#stream` | 正常 |
| TC-004~TC-005 | `should_share_ordinary_budget_and_backoff_across_mixed_errors()` | `RetryChatModel.StreamState` | 正常 |
| TC-006 | `should_restart_entry_query_and_allow_tool_reexecution_after_second_round_timeout()` | Spring AI 工具集成边界 | 正常 |
| TC-007 | `should_propagate_last_original_stream_timeout_when_exhausted()` | `RetryChatModel#stream` | 正常 |
| TC-008、TC-211 | `should_log_attempt_failure_metadata_without_sensitive_content()` | retry 日志边界 | 正常/边界 |
| TC-009 | `should_count_each_stream_timeout_retry_decision_once()` | retry 决策指标 | 正常 |
| TC-101~TC-104 | `should_apply_stream_timeout_retry_switch_and_credit_rules()` | `RetryConfig`、classifier | 异常 |
| TC-105~TC-112 | `should_keep_cancellation_and_hard_exclusions_non_retryable()` | classifier | 异常 |
| TC-113~TC-115 | `should_not_reclassify_http_completion_or_message_as_stream_timeout()` | classifier + Spring AI 边界 | 异常 |
| TC-116 | `should_not_aggregate_previous_attempt_errors_when_exhausted()` | `RetryChatModel` | 异常 |
| TC-117 | `should_prioritize_conflicting_hard_exclusion_over_stream_timeout()` | classifier 冲突矩阵 | 异常 |
| TC-201、TC-310 | `should_bind_retry_on_stream_timeout_with_false_default()` | `AiClientModelVO.RetryConfig` | 边界/回归 |
| TC-202~TC-205 | `should_apply_attempt_limits_and_isolate_concurrent_retry_state()` | `RetryChatModel` | 边界 |
| TC-206~TC-208 | `should_discard_partial_attempt_state_before_query_restart()` | 原子 attempt + 工具集成 | 边界 |
| TC-209~TC-210 | `should_handle_timeout_cancel_and_deadline_races_without_late_retry()` | timeout/cancel 生命周期 + classifier | 边界 |
| TC-212~TC-215 | `should_retry_from_fresh_query_attempt_when_real_stream_watchdog_times_out()` | Story 2/3 HTTP 集成 | 边界 |
| TC-301~TC-306 | 现有 Story 1 retry/compression/call 测试 | 相邻能力 | 回归 |
| TC-307~TC-309 | Story 2 watchdog 与装配测试 | 跨 Story 边界 | 回归 |
| TC-310~TC-312 | `should_bind_and_isolate_timeout_retry_configuration()` | 配置绑定与模式兼容 | 回归 |

建议测试类：

- `StreamQueryRetryClassifierTest`
- `RetryChatModelStreamTimeoutRetryTest`
- `RetryChatModelAtomicAttemptTest`
- `OpenAiQueryRetryIntegrationTest`
- `OpenAiStreamTimeoutQueryRetryIntegrationTest`
- `AiClientModelNodeRetryTest`
- `AiClientModelVORetryConfigTest`

---

## 5. 关键校验点

### 5.1 数据正确性

- timeout attempt 的文本、metadata、reasoning 和 tool-call delta 均不进入最终结果。
- 成功结果只来自一个完整成功的 query attempt。
- 全部耗尽后不返回空成功、部分成功或 composite error。

### 5.2 状态流转正确性

- retry credit 只在确定调度下一 query attempt 时扣减。
- SSE timeout 与 ordinary error 共用同一 `ordinaryRetriesRemaining`。
- mixed error 不重置 backoff。
- attempt N active=0 后 attempt N+1 才能 subscribe。
- cancel 后无 backoff timer、query subscription 或迟到 HTTP request。
- 每个 retry attempt 获得新的 150 秒 deadline、Reactor Context policy 和 45/90 秒 watchdog。

### 5.3 异常处理正确性

- 两类允许的 SSE subtype 在开关开启时可重试。
- `LlmQueryAttemptTimeoutException`、cancel、connect timeout、decode 和工具异常始终不重试。
- cause chain 可识别 subtype，message 不能改变决策。
- cause chain 同时含允许 subtype 与其他 hard exclusion 时，hard exclusion 优先。
- 最终异常保持最后一次原始 subtype、message 和 cause chain。

### 5.4 日志、监控与告警

- 是否需要校验日志输出：是。
- 每次 timeout detection 和 retry decision 可以通过 `logicalCallId` 关联。
- retry 日志包含 attempt、remaining、backoff 和 decision。
- 日志不包含完整 Prompt、正文、reasoning、tool 参数或密钥。
- `timeoutType`、`retryDecision` 可作为低基数 tag；ID 不作为 tag。
- `llm_stream_timeout_retry_decisions_total` 对每次 decision 恰好计数一次。

---

## 6. 执行计划

### 6.1 自动化测试执行

| 步骤 | 内容 | 预期结果 | status |
|---|---|---|---|
| 1 | 编写 RetryConfig 与 classifier 单元测试 | 开关默认值和优先级通过 | append |
| 2 | 编写 query retry、预算和 backoff 测试 | attempt 数量与虚拟时间断言通过 | append |
| 3 | 编写取消、终止竞态和资源顺序测试 | 无重叠、迟到 retry 或残留订阅 | append |
| 4 | 编写 Story 2/3 HTTP 集成测试 | 结构化 timeout 穿透并触发完整 query retry | append |
| 5 | 编写 Spring AI 工具后第二轮 timeout 测试 | tool at-least-once 与结果隔离通过 | append |
| 6 | 执行 Story 1/2、同步 call 和压缩回归 | 无相邻能力回归 | append |
| 7 | 执行 domain 模块测试与全项目编译 | 测试及编译成功 | append |

建议命令：

```powershell
mvn -pl ai-agent-study-domain -am -Dtest=StreamQueryRetryClassifierTest,RetryChatModelStreamTimeoutRetryTest,RetryChatModelAtomicAttemptTest,OpenAiStreamTimeoutQueryRetryIntegrationTest,AiClientModelNodeRetryTest,AiClientModelVORetryConfigTest -Dsurefire.failIfNoSpecifiedTests=false test

mvn -pl ai-agent-study-domain -am test -Dsurefire.failIfNoSpecifiedTests=false

mvn compile -DskipTests
```

### 6.2 手工灰度验证

| 步骤 | 操作 | 预期结果 | status |
|---|---|---|---|
| 1 | 目标模型保持 `retryOnStreamTimeout=false` 并制造 timeout | Story 2 中断，Story 3 不重试 | append |
| 2 | 仅对目标模型开启开关 | timeout 后按相同 query attempt 预算重试 | append |
| 3 | 观察工具后第二轮 timeout | 日志明示完整 query 重启和 callback 可能重复 | append |
| 4 | timeout 后在 backoff 中断开客户端 | 无下一 query/HTTP 请求 | append |
| 5 | 关闭开关回滚 | ordinary 429/5xx retry 保持，SSE timeout retry 停止 | append |

---

## 7. 验收标准

| 编号 | 验收项 | 标准 | status |
|---|---|---|---|
| AC-001 | 功能开关 | 字段缺失/false 不重试，true 精确放行两类 subtype | append |
| AC-002 | Query 重试 | timeout 后从入口 Prompt 重启完整 query | append |
| AC-003 | 统一预算 | timeout 与 ordinary error 总 attempt 不超过 `maxAttempts` | append |
| AC-004 | 统一 backoff | 混合错误按同一退避序列，取消可终止等待 | append |
| AC-005 | 分类安全 | cancel、attempt timeout、connect timeout、decode/tool error 均不被翻转 | append |
| AC-006 | 结果隔离 | 失败 attempt 内容不进入最终结果 | append |
| AC-007 | 工具契约 | 工具后 timeout 测试明示 at-least-once，不承诺 exactly-once | append |
| AC-008 | 最终异常 | 耗尽后只传播最后一次原始 timeout subtype | append |
| AC-009 | 终止与资源 | complete/error/cancel 后 active=0，无迟到 retry/HTTP | append |
| AC-010 | 日志安全 | 前序失败可追踪且不包含敏感内容 | append |
| AC-011 | 同步兼容 | `call()` 与同步 `RetryStrategy` 全部回归通过 | append |
| AC-012 | Story 隔离 | 无 HTTP request replay、第二套 budget 或 node deadline 下传 | append |
| AC-013 | 构建门禁 | domain 测试与全项目编译通过 | append |
| AC-014 | 新 attempt 状态 | 每次 retry 重新创建 deadline、policy 与 watchdog | append |
| AC-015 | 模式和模型隔离 | legacy no-op；layered 下模型级开关互不影响 | append |
| AC-016 | 指标 | timeout retry decision counter 次数和 tag 正确 | append |

---

## 8. 风险与说明

| 风险点 | 影响 | 应对措施 |
|---|---|---|
| 工具重复执行 | 外部副作用可能发生多次 | 默认关闭、按模型灰度、限制 `maxAttempts`、业务工具幂等 |
| Provider 请求重复与计费 | timeout 前 Provider 可能已经接收请求 | 指标记录 attempt/request，灰度观察放大倍数 |
| node 临近到期仍启动 retry | 新 attempt 可能无法完成 | 依赖外层 cancel；测试 active/backoff cancellation |
| cancel 未到达 HTTP subscription | 产生迟到请求或资源占用 | HTTP 集成测试断言 cancel 与请求计数 |
| 零 backoff 订阅竞争 | 两个 attempt 可能短暂重叠 | TC-203 锁定终止顺序 |
| Spring AI 异常包装变化 | classifier 找不到 timeout subtype | cause-chain 契约测试作为升级门禁 |
| 日志重复或泄密 | 噪声、高成本或敏感数据暴露 | detection/decision 分层、字段白名单、内容禁止项 |

---

## 9. 执行结果记录

### 9.1 执行结果

| 项目 | 结果 |
|---|---|
| 单元测试 | 待执行 |
| HTTP 集成测试 | 待执行 |
| Spring AI 工具契约测试 | 待执行 |
| 回归测试 | 待执行 |
| 编译验证 | 待执行 |

### 9.2 问题记录

当前暂无问题记录；执行中发现问题后按 `BUG-001` 起追加。

### 9.3 结论

- 是否达到提测/合并条件：否。
- 结论说明：当前仅完成测试设计，所有自动化执行项和验收项初始状态均为 `append`。
