# GeneralChat 交易 Skills 完整调用链修复设计

## 背景

`GeneralChatNode` 固定使用 `clientId=3001`。当前运行时数据库已为 `3001` 绑定 `TradingSkill` Advisor，因此模型能够在系统消息中看到交易 skill 元数据，并会按照渐进式披露约定调用 `read_skill`。但 `AiClientNode` 会先从普通 Spring `ToolCallback` 中排除 `readTradingSkillToolCallback`，随后仅为 `spring.ai.trading.skills.enabled-client-ids` 中的 client 重新注册该回调。当前配置只包含 `6001`，导致 `3001` 出现“模型知道 `read_skill`，工具执行器却没有同名回调”的不一致，最终抛出 `No ToolCallback found for tool name: read_skill`。

此外，近期的工具隔离改动把交易数据工具从 `3001` 排除。即使只补上 `read_skill`，模型在读取 skill 文档后仍无法调用 `search_stock_by_name`、`get_historical_bars` 等实际数据工具。现有 `TradingToolCallbacks` 还无条件要求 `TargetContext`，其输入 schema 已移除 `ticker`，使这些共享工具只能在 Trading Run 内使用，与 skill 文档描述的独立调用方式不一致。

典型失败请求为“帮我查下德明利昨天的收盘价”。期望链路是先把中文名称解析为唯一股票代码，再查询指定日期的历史行情并提取收盘价，而不是要求 GeneralChat 在调用工具前已经拥有 Trading Run 的 `TargetContext`。

## 目标

- 让绑定 `TradingSkill` Advisor 的 `3001` 同时获得 skill 元数据、`read_skill` 回调和对应交易数据工具。
- 支持 GeneralChat 完成“股票名称解析 -> 唯一 ticker -> 历史行情 -> 收盘价”的多轮工具调用。
- 保留 Trading Run 的权威标的保护：存在 `TargetContext` 时，任何 LLM 传入的 ticker 都不能切换分析标的。
- 让 GeneralChat 使用显式且经过格式校验的 ticker，不要求创建虚假的 Trading Run 或 `runId`。
- 保留 `6001~6013` 现有交易工具能力，并兼容当前基于 YAML client 白名单的配置。
- 确保流式工具调用失败只产生错误终态，不再继续发送成功完成事件。

## 非目标

- 不改变 `RootNode` 将 Agent `8` 直接路由到 `GeneralChatNode` 的行为。
- 不把 GeneralChat 请求重定向到完整 Trading Agent Pipeline。
- 不修改 Tushare 数据获取逻辑、缓存策略或行情字段定义。
- 不为 GeneralChat 创建 `TargetContext`、Trading Run 或交易命名空间。
- 不把“昨天”解释为“上一交易日”；它表示当前日期减一天。若该日期无行情，明确返回无数据。

## 方案选择

### 方案一：只把 `3001` 加入 skills 白名单

该方案能消除 `read_skill` 缺失，但后续交易数据工具仍被隔离，且仍要求 `TargetContext`。只能修复第一处异常，无法完成用户请求，因此不采用。

### 方案二：把股票查询重新路由到 Trading Agent

该方案可以复用 Trading Run 的标的锁定机制，但会改变 Agent `8` 的通用聊天语义，并为一次简单行情查询启动完整分析流水线，成本和行为变化都过大，因此不采用。

### 方案三：统一 client 能力并让共享工具支持双模式

绑定 `TradingSkill` Advisor 的 client 自动获得完整 skill 能力；交易工具根据是否存在 `TargetContext` 区分 GeneralChat 独立模式与 Trading Run 锁定模式。该方案直接修复配置与运行时能力不一致，同时保留 Trading Agent 的身份边界，因此采用此方案。

## 总体设计

### Skill 与 Tool 的职责

- `SpringAiSkillAdvisor` 只把可用 skill 的名称和摘要加入系统消息。
- `read_skill` 只从 `SkillRegistry` 加载完整 `SKILL.md` 内容，为模型提供工具选择和参数说明。
- `search_stock_by_name`、`get_historical_bars` 等 `ToolCallback` 才执行真实数据查询。
- client 不能只拥有 Advisor 或只拥有 `read_skill`。一旦启用 Trading Skills，这三层能力必须作为一个完整集合装配。

### Client 能力判定

`AiClientNode` 在完成 Advisor 实例收集后判定 Trading Skills 能力：

1. Advisor 列表中存在 `SpringAiSkillAdvisor` 时，视为该 client 已启用 Trading Skills。
2. 为兼容现有 `6001` 配置，`spring.ai.trading.skills.enabled-client-ids` 仍作为显式启用来源。
3. 任一来源启用后，同时注册 `read_skill` 与 Trading Skills 对应的数据工具。
4. `spring.ai.trading.tools.enabled-client-ids` 继续保证没有 skill Advisor 的 Trading role client 仍能使用交易工具。
5. 日志输出 clientId、skill Advisor 是否存在、配置是否显式启用，以及最终工具名集合，便于定位装配不一致。

这使 `3001` 可由数据库中的 `TradingSkill` Advisor 自动补齐回调，同时不破坏 `6001~6013` 的现有配置。

### 交易工具双模式

除 `search_stock_by_name` 外，交易数据工具统一通过新的 ticker 选择逻辑执行：

#### GeneralChat 独立模式

- `ToolContext` 中不存在 `TradingTargetContextKeys.TARGET_CONTEXT`。
- 从工具输入读取 `ticker`。
- ticker 必须符合 `^[0-9]{6}(\\.(SH|SZ|BJ))?$`，否则返回明确参数错误且不访问数据 Provider。
- 使用校验后的 ticker 调用 `IStockDataProvider`。
- 不创建 `TargetContext`，也不生成 `runId`。

#### Trading Run 锁定模式

- `ToolContext` 中存在合法的 `TargetContext`。
- 始终使用 `TargetContext.targetId()` 调用数据 Provider。
- 若模型同时传入不同 ticker，记录 `TOOL_TARGET_OVERRIDDEN` 和监控指标，但不能切换标的。
- `TargetContext` 类型错误仍抛出 `IDENTITY_BOUNDARY_VIOLATION`。

`search_stock_by_name` 保持当前边界：仅在不存在 `TargetContext` 时允许调用；进入 Trading Run 后禁止重新解析或切换标的。

### 工具 Schema

以下工具的 input schema 恢复 `ticker` 属性：

- `get_stock_info`: `ticker`
- `get_historical_bars`: `ticker`、`startDate`、`endDate`
- `get_technical_indicators`: `ticker`、`startDate`、`endDate`
- `get_fundamental_data`: `ticker`
- `get_sentiment`: `ticker`
- `get_stock_news`: `ticker`、`limit`

共享 schema 不把 `ticker` 声明为全局必填，因为 Trading Run 已经通过 `TargetContext` 提供权威目标，现有角色调用可以继续省略 ticker。GeneralChat 独立模式在工具执行时要求 ticker 必须存在并通过格式校验；skill 文档继续指导模型显式传入 ticker。日期、limit 等业务参数保持各工具现有的必填规则。这样同一个工具定义可用于两种 client，同时不改变 Trading Run 的现有调用习惯。

## 目标数据流

以 2026-07-31 收到“帮我查下德明利昨天的收盘价”为例：

1. `GeneralChatNode` 在系统上下文中提供当前日期 `2026-07-31`。
2. `SpringAiSkillAdvisor` 向模型暴露 `search-stock-by-name` 和 `get-historical-bars` 等 skill 摘要。
3. 模型调用 `read_skill({"skill_name":"search-stock-by-name"})`。
4. 模型调用 `search_stock_by_name({"name":"德明利"})`。
5. 工具返回候选股票身份。唯一匹配为德明利 `001309.SZ` 时继续；零个或多个候选时停止后续数据调用并进行澄清。
6. 模型调用 `read_skill({"skill_name":"get-historical-bars"})`。
7. 模型根据当前日期把“昨天”解析为 `2026-07-30`。
8. 模型调用 `get_historical_bars({"ticker":"001309.SZ","startDate":"2026-07-30","endDate":"2026-07-30"})`。
9. 工具在 GeneralChat 独立模式下校验 ticker，并查询 OHLCV 数据。
10. 模型从唯一日期记录中提取 `close`，生成最终回答。

`StockSearchResultVO` 是名称解析阶段的权威候选结果，不要求先加载完整 `StockInfoVO`。后续行情工具只需要唯一 ticker；若未来业务要求展示公司行业等信息，可再调用 `get_stock_info`，但它不是查询收盘价的强制步骤。

## 当前日期注入

`GeneralChatNode.buildSystemPrompt` 在已有用户上下文之外增加当前日期，格式固定为 `yyyy-MM-dd`。实现使用可注入 `Clock`，生产环境默认系统时钟，测试使用固定时钟。日期只用于解析“今天、昨天、最近”等相对表达，不改变 Provider 的交易日判断。

## 错误处理

- 缺少 `read_skill` 或 skill 对应数据工具属于 client 装配错误，应在装配测试中发现；运行日志必须输出 client 的最终工具集合。
- 股票名称无匹配时，模型说明未找到并请求更准确的名称或代码。
- 股票名称存在多个候选时，模型列出候选并要求用户确认，不得自行选择。
- GeneralChat 工具缺少 ticker 或 ticker 格式非法时，返回参数错误，不标记为身份边界违规。
- Trading Run 缺少、伪造或类型错误的 `TargetContext` 时，继续抛出 `IDENTITY_BOUNDARY_VIOLATION`。
- 单日行情为空时，说明该日期可能为非交易日或数据暂不可用，不编造收盘价。
- 流式工具调用异常沿用 `GeneralChatNode.streamToEmitter` 的错误传播修复：发送 error SSE 后抛出异常，不再写入成功响应或发送 complete SSE。

## 兼容性

- `6001~6013` 的现有工具白名单继续生效。
- Trading role client 的工具名、skill 名和 Provider 接口保持不变。
- Trading Run 中 `TargetContext` 的创建、注入、匹配校验和 ticker 覆盖行为保持不变。
- GeneralChat 新增的是无 `TargetContext` 时的独立调用路径，不放宽存在 Trading Run 上下文时的约束。
- `GeneralChatNode` 的 SSE 事件类型和返回结构保持不变。

## 测试策略

### Client 装配测试

- `3001` 存在 `SpringAiSkillAdvisor` 时，最终工具集合包含 `read_skill`、`search_stock_by_name` 和交易数据工具。
- 即使 `3001` 不在 skills YAML 白名单中，只要绑定 Advisor 也必须注册完整能力。
- `6001` 的显式 skills 配置继续生效。
- 未启用 Trading Skills 且不在 trading tools 白名单中的普通 client 不暴露交易工具。
- `6002~6013` 等工具白名单 client 继续保留交易工具。

### 工具行为测试

- 无 `TargetContext` 时，`get_historical_bars` 使用合法输入 ticker。
- 无 `TargetContext` 且 ticker 缺失或非法时，不调用 Provider。
- 存在 `TargetContext` 时，输入 ticker 与目标不同时仍使用 `targetId`，并记录覆盖指标。
- `TargetContext` 类型非法时继续触发身份边界异常。
- `search_stock_by_name` 在 GeneralChat 模式可用，在 Trading Run 中被拒绝。
- 各交易工具 schema 包含与 skill 文档一致的 ticker 和业务参数，并验证 ticker 仅在 GeneralChat 独立模式下为运行时必填。

### GeneralChat 链路测试

- 固定时钟为 `2026-07-31`，验证系统上下文包含当前日期。
- 使用可控 ChatModel 或本地 OpenAI 兼容桩模拟 `read_skill -> search_stock_by_name -> read_skill -> get_historical_bars -> 最终文本` 的完整流式工具调用。
- 验证唯一候选时历史行情工具收到 `001309.SZ` 和 `2026-07-30`。
- 验证多候选时不调用历史行情工具。
- 验证工具异常只产生 error 终态，不产生成功 complete 终态。

### 回归测试

- 运行 `AiClientNodeToolIsolationTest`、`GeneralChatNodeTest` 和新增能力装配测试。
- 运行 `TradingToolCallbacksTest` 与 `TradingChatMemoryTest`，确认 Trading Run 的权威目标保护不退化。
- 运行 Trading Skills 的 registry、渐进式披露和 skill 内容测试。

## 结论

本修复不要求 GeneralChat 创建 Trading Run，也不把 `TargetContext` 变成 skills 的通用前置条件。它通过统一 client 的 Advisor、`read_skill` 和数据工具能力，并为共享交易工具明确区分独立模式与锁定模式，使 `GeneralChatNode` 能完成名称解析和行情查询，同时保留 Trading Agent 防止 LLM 切换标的的安全边界。
