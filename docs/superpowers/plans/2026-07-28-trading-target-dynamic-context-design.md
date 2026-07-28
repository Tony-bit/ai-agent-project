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
- 保持全局 Trading ToolCallback 无请求状态，避免在单例中保存任何 Trading Run 数据。

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

### 方案三：通过 Spring AI ToolContext 传递 TargetContext

Trading LLM 创建请求时从 `dynamicContext` 读取 `TargetContext`，通过 `ChatClient.ChatClientRequestSpec.toolContext(Map<String, Object>)` 写入本次请求。Spring AI 在执行工具时构造 `ToolContext`，并调用 `ToolCallback.call(String, ToolContext)`。全局 ToolCallback 从该参数读取不可变的 `TargetContext`，不持有整个 `DynamicContext`。这是本设计采用的方案。

```java
TargetContext target = dynamicContext.getValue("target_context");
ChatClient.ChatClientRequestSpec request = chatClient.prompt()
        .toolContext(Map.of("target_context", target));
```

该方案仍然满足“统一收拢到 `dynamicContext`”：目标只由 `TradingStarter` 写入 `dynamicContext`，`ToolContext` 只是 Spring AI 为一次 LLM 请求建立的只读传递载体，不建立第二套业务状态或生命周期。

## 架构设计

### 唯一目标来源

`TradingStarter.exposeRunContext()` 继续负责写入：

```java
dynamicContext.setValue("target_context", targetContext);
```

其他代码不得自行解析股票代码并创建替代目标，也不得使用静态变量或线程变量保存当前目标。

应集中定义 `target_context` 键，避免不同节点重复硬编码字符串。读取时必须校验值存在且类型正确；缺失时继续抛出以 `IDENTITY_BOUNDARY_VIOLATION` 开头的异常，保留现有可观测性语义。

### 请求级 ToolContext

目标相关 Trading Tools 保持现有全局 `ToolCallback` Bean 注册方式，但所有回调必须保持无请求状态。Trading LLM 发起请求前执行以下流程：

```text
dynamicContext["target_context"]
        -> 校验并读取 TargetContext
        -> 写入 ChatClient request.toolContext
        -> Spring AI 在任意线程创建 ToolContext
        -> 调用全局 ToolCallback.call(input, toolContext)
        -> 回调使用 toolContext 中的 targetId 查询 Provider
```

`TradingToolCallbacks.AbstractToolCallback` 将单参数和双参数入口统一收口，避免直接调用单参数方法时绕过身份边界。Spring AI 对所有工具统一调用双参数方法，即使请求没有业务上下文，也会传入一个空 `ToolContext`；因此基类不能无条件要求目标存在，而应只负责规范化上下文、JSON 解析、错误转换和指标记录。

```java
@Override
public final String call(String functionInput) {
    return call(functionInput, new ToolContext(Map.of()));
}

@Override
public final String call(String functionInput, ToolContext toolContext) {
    ToolContext normalized = toolContext == null
            ? new ToolContext(Map.of()) : toolContext;
    Map<String, Object> input = parse(functionInput);
    return doExecute(input, normalized);
}
```

具体工具在 `doExecute(Map<String, Object>, ToolContext)` 中决定目标策略：

- `get_stock_info`、`get_historical_bars`、`get_technical_indicators`、`get_fundamental_data`、`get_sentiment`、`get_stock_news` 必须调用 `requireTarget(toolContext)`；空上下文和类型错误都以 `IDENTITY_BOUNDARY_VIOLATION` 拒绝。
- `search_stock_by_name` 调用 `currentTarget(toolContext)`；存在目标时拒绝搜索，不存在目标时按原逻辑执行。

这样无论生产代码通过双参数入口调用，还是测试或其他代码直接调用单参数入口，都执行同一套身份边界。

所有调用 `effectiveTicker()` 的工具必须统一改造，包括：

- `get_stock_info`
- `get_historical_bars`
- `get_technical_indicators`
- `get_fundamental_data`
- `get_sentiment`
- `get_stock_news`

`search_stock_by_name` 只用于 Trading Run 建立前的股票身份解析。该工具执行时检查 `ToolContext`：存在 `target_context` 表示已经进入 Trading Run，此时继续抛出 `IDENTITY_BOUNDARY_VIOLATION: stock search is disabled inside a trading run`；不存在目标时保持原搜索行为。这样删除 `TradingTargetScope` 后仍保留原身份保护。

### 请求配置边界

当前 `AiClientNode` 会收集 Spring 容器内全部 `ToolCallback` Bean 并注册为 ChatClient 的默认工具。本设计不改变该注册链；目标相关 ToolCallback 继续作为全局无状态单例存在，运行目标只从 Spring AI 为本次调用提供的 `ToolContext` 获取。

Trading LLM 请求通过现有 `trading-domain` 类 `TradingChatMemory.apply()` 集中挂载 Tool Context，避免在 12 个节点中复制目标校验和 Map 构造逻辑。该方法已经是所有 Trading LLM 请求配置 Chat Memory 的统一入口，不依赖 `trading-infra`，只使用 `ChatClient.ChatClientRequestSpec`、`TradingContextVO` 和 `DynamicContext`。

`TradingChatMemory.apply()` 负责：

1. 从 `dynamicContext` 读取并验证 `TargetContext`。
2. 将该目标与 `TradingContextVO.getTargetContext()` 的 `runId`、`targetId` 进行一致性校验；不一致时以 `IDENTITY_BOUNDARY_VIOLATION` 立即失败。
3. 创建只包含 `target_context` 的不可变 Map，不把整个 `DynamicContext` 传给工具层。
4. 通过 `request.toolContext(Map.of("target_context", target))` 写入本次请求。
5. 按现有逻辑配置 `ChatMemory.CONVERSATION_ID`。
6. 返回可继续执行 stream 的请求规格。

现有节点调用形式保持不变：

```java
TradingChatMemory.apply(
        chatClient.prompt().user(prompt),
        context,
        dynamicContext,
        nodeName);
```

`TradingChatMemory.apply()` 内部调整为：

```java
return request
        .toolContext(Map.of("target_context", target))
        .advisors(advisor -> advisor.param(
                ChatMemory.CONVERSATION_ID, memoryKey));
```

非 Trading 请求不注入 `target_context`：六个依赖权威目标的 Trading Tools 保持身份边界并拒绝执行，`search_stock_by_name` 保持原搜索行为。Trading ChatClient 原有的 MCP 工具和其他默认工具不受影响。项目使用 Spring AI `1.1.2`；`DefaultToolCallingManager` 会将请求中的 Tool Context 传给 `ToolCallback.call(String, ToolContext)`。

### 身份边界

目标相关工具从 `ToolContext` 强制读取 `TargetContext`，实际查询始终使用 `target.targetId()`，不信任 LLM 参数中的 `ticker`：

```java
String effectiveTicker(Map<String, Object> input, TargetContext target)
```

如果模型传入的 `ticker` 与权威目标不同，继续记录 `TOOL_TARGET_OVERRIDDEN` 日志和 `recordToolTargetOverride()` 指标，然后使用权威目标查询。

`TradingLlmCallAudit` 保留调用前 `targetContext` 非空校验和失败审计，但直接执行 `invocation.get()`，不再调用 `TradingTargetScope.call()`。

## DynamicContext 生命周期约束

`dynamicContext["target_context"]` 是目标的唯一存储位置，但同一个 `DynamicContext` 可能被串行子任务复用并写入下一次 Trading Run 的目标。因此请求入口必须在创建 LLM 请求时读取、校验并捕获 `TargetContext`，ToolCallback 执行期间不得再次查询可变的 `DynamicContext`。

该次读取结果由 Spring AI 请求的 `toolContext` Map 持有。即使外层 `DynamicContext` 随后被串行复用，已经创建的 LLM 请求和工具调用仍使用创建请求时的目标快照。

同一个 `DynamicContext` 上的多个 Trading Run 必须保持串行。未来若将股票子任务改为并行执行，必须先为每个子任务创建独立 `DynamicContext`，不得依靠共享 `HashMap` 隔离目标。

## 数据流

```text
TradingStarter 创建 TargetContext
        -> 写入 dynamicContext["target_context"]
        -> Trading Node 准备 LLM 请求
        -> 集中请求入口读取 TargetContext
        -> 写入 ChatClient request.toolContext
        -> LLM 按需发起 get_stock_info 等 tool call
        -> Spring AI 调用 ToolCallback.call(input, ToolContext)
        -> ToolCallback 使用 ToolContext 中的 targetId
        -> IStockDataProvider 查询并返回结果
```

`TargetContext` 是 `record`，其组成字段为不可变值，多个 Reactor 线程只读访问同一个实例不需要加锁。

## 错误处理

- `dynamicContext` 中缺少 `target_context`：在发起 LLM 请求前立即失败，不等待模型调用工具后才失败。
- `target_context` 类型错误：抛出明确的身份边界异常并记录节点、runId 和 clientId。
- `dynamicContext` 与 `TradingContextVO` 中的目标不一致：以 `IDENTITY_BOUNDARY_VIOLATION` 立即失败，不向 LLM 发送请求。
- 模型传入其他股票：记录覆盖告警，继续使用绑定目标。
- Provider 查询失败：保持现有 ToolCallback 错误转换和监控逻辑。
- 请求结束后：Spring AI 请求及其 Tool Context 正常释放，不需要显式清理 ThreadLocal。

## 测试策略

### 单元测试

- 从 `dynamicContext` 能正确读取 `TargetContext` 并写入 `ChatClient` 请求的 Tool Context。
- 缺少 `target_context` 时在 LLM 请求前失败。
- `get_stock_info` 通过双参数 `call` 使用 Tool Context 中的 `targetId` 查询 Provider。
- `get_stock_info` 的数据源仍为现有 `IStockDataProvider`，不改为读取初始化阶段的 `StockInfoVO` 快照。
- 模型传入不同 `ticker` 时仍查询绑定目标并记录覆盖指标。
- 六个目标相关工具全部使用 Tool Context 中的权威目标。
- `search_stock_by_name` 在 Tool Context 包含目标时拒绝执行，不包含目标时保持原行为。
- `dynamicContext` 目标与 `TradingContextVO` 目标不一致时在调用 LLM 前失败。
- `TradingLlmCallAudit` 不再依赖线程上下文，仍保留缺失目标和调用失败审计。

### 并发测试

- 两个独立 `dynamicContext` 分别绑定股票 A、股票 B，并发执行工具时不串标。
- ToolCallback 在 `boundedElastic` 上执行时仍能读取 Spring AI 传入的 Tool Context。
- 同一线程连续执行 A、B 两次请求时不残留前一次目标。
- 同一个 `dynamicContext` 串行复用时，每次请求生成各自 Tool Context；覆盖发生后，已创建的请求仍使用原目标。

### 集成回归

- 风控节点调用 `get_stock_info` 能正常完成，不再出现 `trading target scope is missing`。
- Trading Pipeline 的严格和宽松 Prompt 模式均正常完成。
- 非 Trading 请求不注入目标时，目标相关工具拒绝缺少权威目标的调用，`search_stock_by_name` 保持可用。

本次不增加针对 Spring AI 内部实现类 `DefaultToolCallingManager` 的字节码级或内部契约测试。项目依赖的 Spring AI `1.1.2` 已提供公开的 `toolContext(...)` 和 `ToolCallback.call(String, ToolContext)` API；验证范围保持在项目自身边界：ToolCallback 策略单元测试、`TradingChatMemory` 请求配置测试以及真实 Trading 流程回归。

## 迁移与兼容

本次改造一次性切换，不设置双轨开关。原因是旧 `TradingTargetScope` 路径已经在 Reactor 线程切换下稳定失败，保留回退路径没有业务价值。

删除 `TradingTargetScope` 前必须通过全仓搜索确认无剩余引用。工具名称、输入 Schema、输出格式、全局 Bean 注册、Provider 接口以及指标名称保持不变，因此不会影响 LLM Tool Calling 协议和外部数据源。

## 验收标准

- 代码库不存在 `TradingTargetScope` 或相关 ThreadLocal 传播代码。
- `target_context` 只由 Trading Run 初始化逻辑写入。
- 所有目标相关 Trading Tools 都通过 `ToolCallback.call(String, ToolContext)` 使用从 `dynamicContext` 注入的同一 `TargetContext`。
- `get_stock_info` 仍可由 Trading LLM 按需调用。
- 风控节点在 `boundedElastic` 上调用工具成功。
- 并发股票分析不串标，相关单元测试和模块测试全部通过。
