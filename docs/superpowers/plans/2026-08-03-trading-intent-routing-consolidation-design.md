# Trading 意图路由职责合并设计

## 状态

- 日期：2026-08-03
- 状态：设计已确认，待实施计划
- 对应 Story：`docs/trading-agent/2026-08-03-trading-intent-routing-consolidation-story.md`

## 背景

当前 AutoAgent 对单任务股票分析连续执行两次 LLM 意图识别：通用 `IntentRouteNode` 使用
`clientId=3201` 识别 `STOCK_ANALYSIS`，随后 `RoutingResultHandler` 又路由到
`tradingIntentRoutingNode/clientId=6001`。6001 带有独立 ChatMemory 和完整 Trading Tools，可能把
上一轮股票分析内容带入下一轮，并在意图阶段直接生成完整报告，最终导致 JSON 解析失败并降级为
`UNKNOWN`。

`StockInfoVO` 的正式来源不是任何 ChatClient。现有流程由 `TradingStarter.populateStockInfo()`
调用 `IStockDataProvider.getStockInfo()` 构造并写入 Trading 上下文。本设计保持该职责不变。

## 目标

1. 删除 6001 节点及其第二次 LLM 意图识别。
2. 3201 成为唯一意图识别入口。
3. 3201 沿用现有 Skills/Tool 能力，将 A 股名称解析为 ticker，并填充股票槽位。
4. 使用固定 Java `TradingRequestNode` 校验槽位、构造 `StockAnalysisRequestVO` 并调用
   `TradingStarter`。
5. 保持 `TradingStarter` 当前创建 `StockInfoVO`、`TargetContext` 和 Trading run 的行为。
6. 修复连续两次股票分析时 6001 ChatMemory 污染和非 JSON 输出问题。

## 非目标

- 不专门实现不完整股票名称、简称、别名、前缀、后缀或子串匹配；本 Story 也不额外拦截
  `search_stock_by_name` 偶然成功解析的简称。
- 不实现候选消歧、跨请求 Pending 或二次澄清状态机。
- 不建设股票信息数据库快照或新的定时刷新任务。
- 不改变 `StockInfoVO` 获取与缓存策略。
- 不修改现有 `analysisDepth` 澄清规则。
- 不执行包含 `STOCK_ANALYSIS` 的多任务；后续 Story 再实现股票分析子任务的统一校验和执行。
- 不改变独立 `/trading/analysis` API。

上述能力由后续“A股股票名称补全”Story 单独处理。

## 目标架构

```text
Query + session history
  -> IntentRouteNode / clientId=3201
       -> classify intent
       -> preserve existing analysisDepth behavior
       -> explicit 6-digit ticker: fill StockSlot directly
       -> A-share name: call search_stock_by_name through existing Skills/Tool
       -> require a unique result
       -> fill StockSlot with stockName and canonical stockCode
  -> RoutingResultHandler
       -> multiTask 包含 STOCK_ANALYSIS：拒绝整轮请求并提示单独发起股票分析
       -> STOCK_ANALYSIS: resolve tradingRequestNode
  -> TradingRequestNode (plain Java, no LLM, no ChatMemory)
       -> validate slot completeness and ticker format
       -> map current-query analysis slots
       -> build StockAnalysisRequestVO
       -> call TradingStarter
  -> TradingStarter (existing behavior)
       -> create TargetContext and runId
       -> populateStockInfo via IStockDataProvider.getStockInfo()
       -> execute Trading pipeline
```

## 3201 股票槽位契约

现有 `StockSlot.stockCode` 在迁移期保留。为支持 Java 权威身份校验，新增 `stockName`：

```text
StockSlot
  stockCode: 603259
  stockName: 药明康德
  stockQueryType: 综合分析
  timeRange: null
  exchange: SH
```

规则如下：

- 用户明确输入 6 位 A 股代码时，`stockCode` 保存规范化代码；不要求 `stockName` 非空，由 Trading
  初始化阶段进行权威身份查询。
- 用户输入股票名称时，3201 必须通过现有 Skills/`search_stock_by_name` 查询。
- 工具返回唯一结果时填写结果中的 `stockCode` 和 `stockName`。本 Story 不增加简称识别逻辑，
  也不额外比较用户原文与权威名称；正式的模糊匹配行为由后续 Story 定义。
- 0 个结果、多个结果或 ticker 格式非法时，本轮返回“请提供完整 A 股名称或 6 位代码”，不创建
  runId，也不保存跨请求 Pending。
- LLM 不得凭记忆生成 ticker；最终 ticker 必须来自用户明确代码或工具结果。

3201 的结构化输出 Schema、Prompt、Few-Shot 和 Validator 同步增加新字段。旧 `stockCode` 输入
继续兼容，避免破坏现有评测和调用方。

## 工具边界

Trading Tools 使用构建期白名单管理，配置模型为 `Map<String, List<String>>`：key 是
`clientId`，value 是该 Client 允许装配的 Trading Tool 名称。该白名单只管理 Trading
Skills/Spring Trading ToolCallbacks，不影响 MCP、会话记忆、`search_episodic_memory` 或其他通用
能力。

```yaml
spring:
  ai:
    trading:
      tools:
        allowed-by-client:
          "3201":
            - read_skill
            - search_stock_by_name
          "6002":
            - get_stock_info
            - get_historical_bars
            - get_fundamental_data
            - get_technical_indicators
            - get_sentiment
            - get_stock_news
```

装配规则：

- Map 中没有 Client 时，默认不装配任何 Trading Tool。
- Client 显式配置空列表时，禁止其使用全部 Trading Tool。
- 配置绑定后转换为不可变 `Set` 去重，再与可用 Trading ToolCallbacks 求交集。
- 配置包含不存在的 Trading Tool 名称时启动失败，避免拼写错误静默降级。
- `6001` 不出现在 Map 中。
- `3201` 最终只装配 `read_skill` 和 `search_stock_by_name`。
- `6002-6013` 必须逐项迁移当前实际工具集合，保证分析节点能力不变。
- 启动日志输出每个 Client 最终装配的 Trading Tool 集合。

白名单在构建 `ChatClient` 时完成物理隔离，不依赖 Prompt。3201 不得获得 `get_stock_info`、行情、
新闻、技术指标或基本面工具，从装配层防止意图识别演变为完整股票分析。

`get_stock_info` Tool 返回的是给 LLM 使用的文本，不是正式 `TradingContext.stockInfo` 来源。
正式 `StockInfoVO` 仍由 `TradingStarter.populateStockInfo()` 获取和写入。

## TradingRequestNode

新增无状态 Java Bean `tradingRequestNode`，替换 `tradingIntentRoutingNode` 的下游位置。它不调用
LLM，不读取主会话历史，也不维护 ChatMemory。

职责：

1. 从 `DynamicContext` 读取 `StockSlot`。
2. 校验 ticker 为 6 位 A 股代码或标准 `ts_code`。
3. 将 `stockQueryType` 映射为当前请求的 `selectedAnalysts`；未指定时使用现有默认值。
4. 构造 `StockAnalysisRequestVO`，透传 `stockName`，并设置现有默认辩论轮次、风控轮次和
   `sessionId`。
5. 调用 `TradingStarter.start()`。

`TradingStarter` 创建 `TargetContext` 时，`TargetContextFactory` 继续使用
`IStockDataProvider.findStockIdentities()` 查询权威身份，并增加名称校验：

- 始终校验请求 ticker 与权威 `targetId` 一致。
- 名称输入场景校验请求 `stockName` 与权威 `stockName` 一致。
- 代码输入场景允许请求 `stockName` 为空，名称以权威记录为准。
- 校验通过后才创建 `runId` 和 `TargetContext`；失败则按 Trading 初始化失败处理。

校验失败时通过现有意图澄清 SSE 返回前端，并写入 Root 可持久化的最终回复字段；本轮停止。

## 6001 删除范围

删除：

- `tradingIntentRoutingNode` 路由 Bean 和 LLM 调用。
- Trading `IntentRoutingPrompt`。
- `TradingIntentRoutingService` 及仅服务于它的测试。
- `intent_routing_{sessionId}` ChatMemory。
- `clientId=6001` 的应用配置、Skills 白名单、数据库客户端关系和启动装配项。
- `RoutingResultHandler` 对 `tradingIntentRoutingNode` 的 Bean 名引用。

`search_stock_by_name` Tool、`IStockDataProvider`、`TradingStarter`、分析节点 6002-6013 和直接 Trading
API 保留。

同一版本不保留 6001 运行时 fallback，回退依赖 Git 和发布系统。

## 兼容行为

- `GENERAL_CHAT`、PE、巡检和其他意图的 3201 路由行为不变。
- 现有 `analysisDepth` 澄清先于可执行 `STOCK_ANALYSIS`；用户回答后继续使用合并后的原始股票名。
- 主会话历史继续按 `sessionId` 提供给 3201。
- 每次进入 `TradingStarter` 都按现有逻辑创建新 runId；不复用上一 Trading run。
- `TradingStarter.populateStockInfo()` 和 `TradingContext.stockInfo` 保持现状。
- 不包含 `STOCK_ANALYSIS` 的多任务链路保持现状。
- `multiTask=true` 且任一子任务为 `STOCK_ANALYSIS` 时，`RoutingResultHandler` 在执行任何子任务前
  拒绝整轮请求，提示用户单独发起股票分析；不进入 `MultiTaskExecutionNode`，也不创建 Trading run。

## 错误处理

- 3201 非 JSON 或 Schema 校验失败：沿用统一路由现有重试和降级，不进入 Trading。
- 工具无结果、多个结果或调用失败：不填充可执行 ticker，不进入 Trading。
- `TradingRequestNode` 校验失败：返回澄清提示，不调用 `TradingStarter`。
- `TargetContextFactory` 发现请求代码或名称与权威身份不一致：初始化失败，不执行 Trading pipeline。
- 多任务包含 `STOCK_ANALYSIS`：返回“股票分析暂不支持与其他任务同时执行，请单独发起股票分析”，
  不执行该任务列表中的任何子任务。
- `TradingStarter.getStockInfo()` 失败：沿用当前初始化失败处理；这是数据获取错误，不回到意图路由。

## 测试策略

- 3201 对“对药明康德进行完整投资分析”调用搜索工具并输出 `603259`。
- 3201 对明确 6 位代码不调用名称搜索。
- 搜索无结果、多结果和工具异常不得生成 ticker。
- `TradingRequestNode` 拒绝非法 ticker 和空槽位。
- `TargetContextFactory` 同时校验 ticker 与名称；直接代码输入允许名称为空。
- `RoutingResultHandler` 对单任务 `STOCK_ANALYSIS` 只路由到 `tradingRequestNode`。
- `RoutingResultHandler` 拒绝任何包含 `STOCK_ANALYSIS` 的多任务，且不调用
  `MultiTaskExecutionNode`、`TradingRequestNode` 或 `TradingStarter`。
- 连续完成药明康德后分析兆易创新，全链路不调用 6001，不读取 6001 ChatMemory，并创建新 runId。
- 3201 路由期间不得调用 `get_stock_info` 或其他分析工具。
- 3201 的最终 Trading Tool 集合严格等于 `read_skill + search_stock_by_name`。
- 未配置 Client 和空列表 Client 均不装配 Trading Tool；未知工具名导致启动失败。
- 6002-6013 的最终 Trading Tool 集合与改造前一致。
- `TradingStarter.populateStockInfo()` 仍调用一次 Provider 并写入 `TradingContext.stockInfo`。
- GENERAL_CHAT、PE、巡检、现有 analysisDepth、直接 Trading API 和 6002-6013 回归通过。
