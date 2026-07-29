# Trading 流式调用输入输出调试日志设计

## 背景

当前 12 个 Trading 分析节点统一通过 `AbstractExecuteSupport.collectStreamingResponse()` 发起流式 LLM 调用并聚合完整响应。现有 `StreamingChatResponseCollector` 只记录首内容延迟、耗时、chunk 数量和响应长度，无法在本地调试时查看实际 Prompt 和最终完整响应。

本次增加仅用于本地排查的 `DEBUG` 内容日志，不改变模型调用、流式聚合、重试、超时、取消或 SSE 行为。

## 目标

- 在 Trading 聚合式流调用开始前记录完整 Prompt。
- 仅在流正常聚合完成后一次性记录完整响应。
- 日志使用 `DEBUG` 级别，默认不开启时不输出内容。
- 通过公共方法集中实现，避免 12 个 Trading 节点重复日志代码。

## 非目标

- 不记录底层原始 `ChatResponse`、role、usage、空 delta、tool call 或 finish reason。
- 不修改 `GeneralChatNode` 的前端真流式输出。
- 不修改 Trading `IntentRoutingNode` 的直接流式聚合路径。
- 不改变现有重试与超时状态机。
- 不对调试日志内容做截断或脱敏。

## 方案

修改 `AbstractExecuteSupport.collectStreamingResponse()`，增加一个表示最终 Prompt 的 `String inputContent` 参数。12 个调用该方法的 Trading 节点将其已经构建完成的 Prompt 传入。

公共方法执行顺序：

1. 当 `DEBUG` 开启时，以带 `operationName` 的多行日志记录完整输入。
2. 调用 `requestSpec.stream().content()` 并交给 `StreamingChatResponseCollector` 聚合。
3. 仅当聚合正常返回时，以带 `operationName` 的多行日志一次性记录完整输出。
4. 将完整输出返回调用节点。

建议日志格式：

```text
LLM streaming input | operation=BearResearcherNode | content=
<完整 Prompt>

LLM streaming output | operation=BearResearcherNode | content=
<完整响应>
```

使用 `log.isDebugEnabled()` 保护内容日志。流发生异常、超时或取消时，`collector.collect()` 直接抛出异常，因此不会打印残缺输出；现有聚合指标和错误日志保持不变。

## 影响范围

- 修改 `AbstractExecuteSupport.collectStreamingResponse()` 的方法签名和日志逻辑。
- 修改当前调用该方法的 12 个 Trading 节点，传入各自最终 Prompt。
- 不新增第二套聚合或订阅逻辑。

## 错误处理

- 输入日志打印后发生调用失败，不补充伪造的输出日志。
- 输出日志只表示聚合器正常完成，不改变节点后续解析和校验结果。
- 日志框架异常不应改变业务行为；沿用 SLF4J 参数化日志。

## 验证策略

- 编译 Domain 与 Trading 相关模块，确认 12 个调用点均已适配新签名。
- 运行 `StreamingChatResponseCollectorTest` 和 Trading 节点相关测试，确认流式聚合行为不变。
- 本地开启 `AbstractExecuteSupport` 的 `DEBUG` 日志，执行一次 Trading 分析，确认输入在调用前输出、完整响应只在聚合完成后输出。
- 制造超时或错误流，确认不会打印残缺的 `LLM streaming output`。

## 风险与约束

完整 Prompt 和响应可能包含用户数据、业务数据且体积较大。本功能仅使用 `DEBUG` 级别，适合本地临时排查；生产环境不应为该类开启 `DEBUG` 内容日志。
