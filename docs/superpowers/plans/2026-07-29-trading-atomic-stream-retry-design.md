# 交易 LLM 原子流式重试设计

## 背景

交易分析流程中的多个 LLM 节点已经收到部分流式响应，但随后连续 30 秒没有新分片，最终由 `RetryChatModel` 的 idle timeout 终止。当前 `RetryChatModel` 仅在尚未观察到响应时执行普通重试；一旦已经收到响应，异常会直接向上传播。因此一次可恢复的流中断会被记录为节点 `EXECUTION_FAILED`。

不能直接放开现有的“收到响应后不重试”限制。`RetryChatModel` 当前会把每个 `ChatResponse` 立即发送给下游；若第一次尝试已发送 `A+B` 后失败，第二次尝试再发送 `A+B+C+D`，下游最终会得到拼接后的 `A+B+A+B+C+D`。流式数据一旦发出便无法撤回。

交易节点目前通过 `StreamingChatResponseCollector` 聚合完整结果后再解析和提交，并不依赖逐分片展示。用户也接受重试期间新浪新闻、行情等只读工具被重复调用。因此交易调用适合采用“每次尝试独立缓冲，完整成功后一次性交付”的原子流式重试模式。

## 目标

- 继续由 `RetryChatModel` 统一拥有重试次数、错误分类、退避和模型调用预算。
- 交易 LLM 调用在收到部分分片后发生可重试异常时，能够重新执行完整模型调用。
- 失败尝试产生的所有分片在下游可见之前被整体丢弃，成功结果不混入残缺输出。
- 普通聊天继续实时收到分片，现有首响应前重试语义保持不变。
- 不在 `TradingLlmCallAudit`、交易节点或 Dispatcher 中复制另一套重试循环。
- 第一阶段不修改现有 first-content、idle、total 和 node timeout 数值，以便独立验证恢复机制。

## 非目标

- 不实现原始 HTTP SSE 心跳检测或代理层重连。
- 不持久化并复用工具调用结果。
- 不保证有副作用工具的幂等性；`ATOMIC` 模式第一阶段仅由交易分析调用启用。
- 不实现 Streaming 到非 Streaming 的模式降级。
- 不重试结构化输出解析失败、业务校验失败、身份边界错误或用户主动取消。

## 方案选择

### 方案一：在 `TradingLlmCallAudit` 中实现完整重试

每次失败后重新调用 `Supplier`，可以自然获得独立 Collector，但会和 `RetryChatModel` 形成两套重试预算，最坏出现次数相乘。该方案不采用。

### 方案二：全局缓存 `RetryChatModel` 的每次流式响应

所有调用均等待一次尝试完整成功后再向下游发送，可以直接重试，但会让普通聊天失去实时 Streaming。该方案不采用。

### 方案三：按调用选择 `LIVE` 或 `ATOMIC` 交付模式

`RetryChatModel` 保留唯一重试所有权。普通聊天使用 `LIVE`，继续边接收边发送；交易调用使用 `ATOMIC`，每次尝试先缓冲，成功后才发送。该方案同时保留普通聊天体验和交易调用的可恢复性，因此采用。

## 架构

### 流交付模式

在通用 Domain 层新增 `StreamDeliveryMode`：

```java
public enum StreamDeliveryMode {
    LIVE,
    ATOMIC
}
```

- `LIVE`：默认值。每个 `ChatResponse` 到达后立即向下游发送；收到响应后的异常不执行普通重试。
- `ATOMIC`：每次模型尝试使用独立缓冲区；只有收到正常终止信号后才将本次全部 `ChatResponse` 发送给下游。可重试异常会丢弃缓冲区并重新调用模型。

### 运行时上下文

`RetryRuntimeContext` 增加 `streamDeliveryMode`，构建器默认值为 `LIVE`，并提供不修改原对象的派生方法，例如 `forAtomicStreamDelivery()`。

`RetryRuntimeContextHolder` 保持现有栈式 `ThreadLocal` 语义。交易调用通过 `TradingLlmCallAudit.execute(...)` 在执行传入的 `Supplier` 时临时压入派生后的 `ATOMIC` 上下文，执行完成后自动恢复原上下文。`TradingLlmCallAudit` 不读取重试次数、不进行 sleep，也不判断异常是否可重试。

如果当前不存在 `RetryRuntimeContext`，交易审计边界不得凭空构造缺少 session、trace 和历史记录的上下文；应维持现有调用，并由 `RetryChatModel` 使用默认 `LIVE`。正常 AutoAgent 执行路径已经建立运行时上下文。

### RetryModel 行为

`RetryChatModel.stream(...)` 在订阅前捕获 `RetryRuntimeContext`，并据此选择交付方式。

`LIVE` 路径保持现有实现：

```text
delegate.stream
→ 立即发送 ChatResponse
→ 首响应前的普通错误可重试
→ 已观察到响应后的错误直接传播
```

`ATOMIC` 路径：

```text
逻辑调用总 timeout 开始
→ 创建 attempt 1 的独立缓冲区
→ 订阅 delegate.stream
→ ChatResponse 仅写入 attempt 1 缓冲区
→ 正常完成：把 attempt 1 按原顺序发送给下游
→ 可重试失败：清空 attempt 1，按现有 RetryConfig 退避
→ 创建 attempt 2 的独立缓冲区并重新订阅
→ 达到重试次数、模型调用安全上限或总 timeout：传播最终异常
```

`ATOMIC` 只改变分片的交付时机，不改变每次尝试的 first-content timeout 和 idle timeout。现有最外层 total timeout 仍作为一次逻辑调用的总预算，覆盖所有尝试及退避时间，防止重试无限延长节点耗时；不得为每次 attempt 重新启动一份 total timeout。

### 错误处理

`ATOMIC` 模式复用现有普通错误分类和 `RetryConfig.retryableErrorCodes`。以下行为保持明确：

- 用户取消、线程中断和 SSE 客户端断开立即终止，不重试。
- 上下文溢出继续走现有压缩恢复，不计作普通瞬时重试。
- 连接错误、配置允许的 HTTP 错误和流式 timeout 可在剩余预算内重试。
- 结构化输出解析和交易业务校验发生在成功流交付之后，不由 `RetryChatModel` 重试。
- 重试耗尽后保留最末次异常作为根因，`TradingLlmCallAudit` 只记录一次最终失败审计。

## 数据与副作用

每次 `ATOMIC` 尝试可以重复调用只读新闻、行情和检索工具。第一阶段明确接受该行为。

失败尝试中的 `ChatResponse` 不能进入 `StreamingChatResponseCollector`，因此不会混入最终业务结果。Chat Memory、工具侧缓存和供应商计费仍可能记录多次模型尝试；观测日志必须通过 attempt 编号区分这些调用。

如果未来交易客户端引入下单、写库、发消息等有副作用工具，必须先实现 `tool_call_id` 幂等键或工具结果持久化复用，之后才能继续使用 `ATOMIC` 的整次重试。

## 可观测性

`RetryChatModel` 在每次尝试完成或失败时记录：

- `streamDeliveryMode`
- 当前 attempt 和最大 attempts
- 是否已经观察到响应或有效文本
- 本次尝试耗时
- 丢弃的 chunk 数量
- 错误类型和提取后的错误码
- 下一次退避时间

`StreamingChatResponseCollector` 仅观察最终成功尝试，因此其 `chunkCount`、`responseLength` 和 latency 表示最终交付结果。整个节点耗时仍由 `TradingNodeObservability` 记录。

## 测试策略

### `RetryChatModelStreamTest`

- `LIVE` 模式收到有效内容后超时，保持不重试。
- `ATOMIC` 模式第一次收到部分内容后超时，第二次成功；断言下游只收到第二次完整响应。
- `ATOMIC` 模式连续失败直至重试耗尽；断言 delegate 调用次数等于配置预算。
- `ATOMIC` 模式在首响应前失败，继续复用现有普通重试逻辑。
- `ATOMIC` 模式用户取消或不可重试异常，断言只调用一次。
- 多次尝试和退避共同受逻辑调用 total timeout 限制。
- 上下文压缩调用不继承不正确的流交付状态。

### 运行时上下文测试

- 默认 `RetryRuntimeContext` 的模式为 `LIVE`。
- `forAtomicStreamDelivery()` 保留 session、trace、compression 标志和 recent messages。
- 嵌套 `RetryRuntimeContextHolder.withContext(...)` 在成功和异常路径均恢复外层上下文。

### 交易审计边界测试

- `TradingLlmCallAudit.execute(...)` 在 Supplier 内可观察到 `ATOMIC`。
- 执行完成或失败后恢复原上下文。
- 审计边界本身不重复调用 Supplier。
- 缺少运行时上下文时不构造伪造上下文、不改变现有调用语义。

## 推进顺序

1. 新增流交付模式及运行时上下文派生能力。
2. 在 `RetryChatModel` 中增加 `ATOMIC` 尝试缓冲和重试路径，保持 `LIVE` 路径不变。
3. 让 `TradingLlmCallAudit` 仅为交易 Supplier 建立 `ATOMIC` 上下文。
4. 增加聚焦单元测试并运行 Domain、Trading Domain 相关测试。
5. 使用现有交易分析场景复测，确认部分输出后的 timeout 会触发 RetryModel 重试，最终 Collector 不包含失败尝试内容。

## 风险与缓解

- **内存增加**：`ATOMIC` 会暂存一次完整模型响应。交易节点本来就聚合完整文本，且受模型输出 token 和 total timeout 限制，第一阶段可接受。
- **首个业务结果延迟**：交易节点当前不会逐 Chunk 展示，行为无变化；普通聊天仍为 `LIVE`。
- **只读工具重复调用**：已由用户明确接受，并通过模式仅限交易调用降低影响面。
- **重试放大成本**：继续使用模型现有 `RetryConfig` 和安全上限，不增加第二套次数配置。
- **上下文泄漏**：沿用栈式 `RetryRuntimeContextHolder` 的 `finally` 清理，并增加异常路径测试。

## 成功标准

- 交易 LLM 在收到部分分片后发生可重试 timeout，能够使用现有 RetryModel 预算重新调用。
- 失败尝试的分片不会出现在最终响应中。
- RetryModel 是唯一重试执行者，交易审计边界只选择交付模式。
- 普通聊天实时 Streaming 和现有测试保持不变。
- 重试耗尽、取消和不可重试错误仍能稳定传播并被现有审计记录。
