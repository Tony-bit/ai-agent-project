# Trading Target 统一收拢到 DynamicContext 设计

## 背景

Trading Agent 在调用 Spring AI 工具时，会从业务执行线程切换到 Reactor `boundedElastic` 线程。当前 `TradingTargetScope` 使用静态 `ThreadLocal` 保存 `TargetContext`，线程切换后工具回调无法读取目标，导致 `get_stock_info` 抛出 `IDENTITY_BOUNDARY_VIOLATION: trading target scope is missing`。

`TradingStarter` 已在每次 Trading Run 初始化阶段将不可变的 `TargetContext` 写入 `dynamicContext["target_context"]`。因此不再需要额外维护一份基于线程的目标作用域。

## 目标

- 以 `dynamicContext["target_context"]` 作为 Trading Run 的唯一目标来源。
- 删除 `TradingTargetScope` 及其 `ThreadLocal` 生命周期管理。
- 保留 Trading LLM 按需调用 `get_stock_info` 和其他股票工具的能力。
- 工具调用无论在哪个线程执行，都固定使用本次 LLM 请求绑定的 `TargetContext`。
- 保留模型传入错误 `ticker` 时的覆盖、告警和监控行为。
- 避免 Trading Tool 作为全局单例被非 Trading LLM 或不同 Trading Run 共享。

## 非目标

- 不改变 `IStockDataProvider` 的查询逻辑、缓存策略和返回结构。
- 不取消 `get_stock_info` 或强制所有节点使用初始化时的 `StockInfoVO` 快照。
- 不引入 `InheritableThreadLocal`、`ThreadLocalAccessor` 或 Reactor Context Propagation。
- 不重构 Trading Pipeline、SSE 或 Prompt 合约。

## 方案比较

### 方案一：继续传播 ThreadLocal

通过 Micrometer `ThreadLocalAccessor` 将 `TradingTargetScope` 传播到 Reactor 线程。改动较小，但保留了隐式线程上下文，目标数据仍同时存在于 `dynamicContext` 和 `ThreadLocal` 两处，不符合统一收拢要求。

### 方案二：工具执行时读取整个 DynamicContext

让 ToolCallback 持有 `DynamicContext`，执行时读取 `target_context`。实现直观，但 ToolCallback 会持有一个包含 `HashMap`、`StringBuilder`、SSE 状态等可变数据的较大对象，并扩大基础设施层对执行上下文的依赖。

### 方案三：请求开始时读取并绑定 TargetContext

Trading LLM 创建请求时从 `dynamicContext` 读取 `TargetContext`，然后创建仅属于本次请求的 ToolCallback。回调只捕获不可变的 `TargetContext`，不持有整个 `DynamicContext`。这是本设计采用的方案。

该方案仍然满足“统一收拢到 `dynamicContext`”：目标只由 `TradingStarter` 写入 `dynamicContext`，每个消费者都从该位置取得目标；请求级回调只是持有本次读取结果，不建立第二套可变状态或生命周期。

## 架构设计

### 唯一目标来源

`TradingStarter.exposeRunContext()` 继续负责写入：

```java
dynamicContext.setValue("target_context", targetContext);
```

其他代码不得自行解析股票代码并创建替代目标，也不得使用静态变量或线程变量保存当前目标。

应集中定义 `target_context` 键，避免不同节点重复硬编码字符串。读取时必须校验值存在且类型正确；缺失时继续抛出以 `IDENTITY_BOUNDARY_VIOLATION` 开头的异常，保留现有可观测性语义。

### 请求级 Trading Tools

目标相关 Trading Tools 不再作为全局 `ToolCallback` Bean 注册。Trading LLM 发起请求前执行以下流程：

```text
dynamicContext["target_context"]
        -> 校验并读取 TargetContext
        -> 创建本次请求的 Trading ToolCallbacks
        -> 挂载到 ChatClient request
        -> Spring AI 在任意线程执行回调
        -> 回调使用已绑定的 targetId 查询 Provider
```

`TradingToolCallbacks` 保持无请求状态的工厂职责。每个 `get...Callback(TargetContext target)` 方法返回一个新回调，回调通过构造参数或闭包只读持有 `TargetContext`。

所有调用 `effectiveTicker()` 的工具必须统一改造，包括：

- `get_stock_info`
- `get_historical_bars`
- `get_technical_indicators`
- `get_fundamental_data`
- `get_sentiment`
- `get_stock_news`

`search_stock_by_name` 只用于 Trading Run 建立前的股票身份解析，不向已经绑定 `TargetContext` 的 Trading LLM 暴露。Trading Run 内的请求级工具集合不包含该工具，防止模型在目标确定后搜索其他股票并将结果混入当前分析。

### 工具注册边界

当前 `AiClientNode` 会收集 Spring 容器内全部 `ToolCallback` Bean 并注册为每个 ChatClient 的默认工具。目标相关 Trading ToolCallback Bean 必须从这条全局注册链移除，否则请求级工具与全局工具会重复，且全局工具没有可信目标。

Trading LLM 请求通过一个集中入口挂载目标相关工具，避免在每个节点复制绑定逻辑。模块依赖采用依赖倒置：`trading-domain` 定义请求级 Trading Tool 工厂接口，接口接收 `TargetContext` 并返回 Spring AI 标准 `ToolCallbackProvider`；`trading-infra` 实现该接口并调用 `IStockDataProvider`。`trading-domain` 不得依赖 `trading-infra` 具体类型。

该集中入口负责：

1. 从 `dynamicContext` 读取并验证 `TargetContext`。
2. 将该目标与 `TradingContextVO.getTargetContext()` 的 `runId`、`targetId` 进行一致性校验；不一致时以 `IDENTITY_BOUNDARY_VIOLATION` 立即失败。
3. 通过 Trading Tool 工厂创建请求级 `ToolCallbackProvider`，工具回调捕获校验后的不可变目标。
4. 将 Provider 传给 `chatClient.prompt().toolCallbacks(...)`。项目使用 Spring AI `1.1.2`；`tools(Object...)` 面向包含 `@Tool` 方法的对象，已有 `ToolCallback` 必须通过 `toolCallbacks(ToolCallback...)` 或 `toolCallbacks(ToolCallbackProvider...)` 挂载。
5. 返回可继续配置 memory、user prompt 和 stream 的请求规格。

非 Trading ChatClient 不获得这些目标相关工具。Trading ChatClient 原有的 MCP 工具和非目标相关工具不受影响。`search_stock_by_name` 不进入 Trading Run 的请求级工具集合；股票名称解析继续由 Run 建立前的现有身份解析流程负责。

### 身份边界

工具实际查询代码始终使用 `target.targetId()`，不信任 LLM 参数中的 `ticker`：

```java
String effectiveTicker(Map<String, Object> input, TargetContext target)
```

如果模型传入的 `ticker` 与权威目标不同，继续记录 `TOOL_TARGET_OVERRIDDEN` 日志和 `recordToolTargetOverride()` 指标，然后使用权威目标查询。

`TradingLlmCallAudit` 保留调用前 `targetContext` 非空校验和失败审计，但直接执行 `invocation.get()`，不再调用 `TradingTargetScope.call()`。

## DynamicContext 生命周期约束

`dynamicContext["target_context"]` 是目标的唯一存储位置，但同一个 `DynamicContext` 可能被串行子任务复用并写入下一次 Trading Run 的目标。因此请求入口必须在创建 LLM 请求时读取、校验并捕获 `TargetContext`，ToolCallback 执行期间不得再次查询可变的 `DynamicContext`。

同一个 `DynamicContext` 上的多个 Trading Run 必须保持串行。未来若将股票子任务改为并行执行，必须先为每个子任务创建独立 `DynamicContext`，不得依靠共享 `HashMap` 隔离目标。

## 数据流

```text
TradingStarter 创建 TargetContext
        -> 写入 dynamicContext["target_context"]
        -> Trading Node 准备 LLM 请求
        -> 集中请求入口读取 TargetContext
        -> 创建请求级 Trading ToolCallbacks
        -> LLM 按需发起 get_stock_info 等 tool call
        -> ToolCallback 使用绑定的 targetId
        -> IStockDataProvider 查询并返回结果
```

`TargetContext` 是 `record`，其组成字段为不可变值，多个 Reactor 线程只读访问同一个实例不需要加锁。

## 错误处理

- `dynamicContext` 中缺少 `target_context`：在发起 LLM 请求前立即失败，不等待模型调用工具后才失败。
- `target_context` 类型错误：抛出明确的身份边界异常并记录节点、runId 和 clientId。
- `dynamicContext` 与 `TradingContextVO` 中的目标不一致：以 `IDENTITY_BOUNDARY_VIOLATION` 立即失败，不向 LLM 发送请求。
- 模型传入其他股票：记录覆盖告警，继续使用绑定目标。
- Provider 查询失败：保持现有 ToolCallback 错误转换和监控逻辑。
- 请求结束后：请求级 ToolCallback 随请求释放，不需要显式清理 ThreadLocal。

## 测试策略

### 单元测试

- 从 `dynamicContext` 能正确读取 `TargetContext` 并创建工具集合。
- 缺少 `target_context` 时在 LLM 请求前失败。
- `get_stock_info` 使用绑定的 `targetId` 查询 Provider。
- `get_stock_info` 的数据源仍为现有 `IStockDataProvider`，不改为读取初始化阶段的 `StockInfoVO` 快照。
- 模型传入不同 `ticker` 时仍查询绑定目标并记录覆盖指标。
- 六个目标相关工具全部使用绑定目标。
- Trading Run 的请求级工具集合不包含 `search_stock_by_name`。
- `dynamicContext` 目标与 `TradingContextVO` 目标不一致时在调用 LLM 前失败。
- `TradingLlmCallAudit` 不再依赖线程上下文，仍保留缺失目标和调用失败审计。

### 并发测试

- 两个独立 `dynamicContext` 分别绑定股票 A、股票 B，并发执行工具时不串标。
- ToolCallback 在 `boundedElastic` 上执行时仍能读取请求绑定目标。
- 同一线程连续执行 A、B 两次请求时不残留前一次目标。
- 同一个 `dynamicContext` 串行复用时，每次请求捕获各自目标；覆盖发生后，已创建的回调仍使用原目标。

### 集成回归

- 风控节点调用 `get_stock_info` 能正常完成，不再出现 `trading target scope is missing`。
- Trading Pipeline 的严格和宽松 Prompt 模式均正常完成。
- 非 Trading ChatClient 的默认工具集合不再包含目标相关 Trading Tools。

## 迁移与兼容

本次改造一次性切换，不设置双轨开关。原因是旧 `TradingTargetScope` 路径已经在 Reactor 线程切换下稳定失败，保留回退路径没有业务价值。

删除 `TradingTargetScope` 前必须通过全仓搜索确认无剩余引用。工具名称、输入 Schema、输出格式、Provider 接口以及指标名称保持不变，因此不会影响 LLM Tool Calling 协议和外部数据源。

## 验收标准

- 代码库不存在 `TradingTargetScope` 或相关 ThreadLocal 传播代码。
- `target_context` 只由 Trading Run 初始化逻辑写入。
- 所有目标相关 Trading Tools 都是请求级回调，并使用从 `dynamicContext` 取得的同一 `TargetContext`。
- `get_stock_info` 仍可由 Trading LLM 按需调用。
- 风控节点在 `boundedElastic` 上调用工具成功。
- 并发股票分析不串标，相关单元测试和模块测试全部通过。
