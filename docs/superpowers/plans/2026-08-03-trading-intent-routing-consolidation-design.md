# Trading 意图路由职责合并设计

## 状态

- 日期：2026-08-03
- 状态：设计已确认，待实施计划
- 对应 Story：`docs/superpowers/plans/2026-08-03-trading-intent-routing-consolidation-story.md`

## 背景

当前 AutoAgent 对单任务股票分析连续执行两次 LLM 意图识别：通用 `IntentRouteNode` 使用
`clientId=3201` 识别 `STOCK_ANALYSIS`，随后 `RoutingResultHandler` 又路由到
`tradingIntentRoutingNode/clientId=6001`。6001 带有独立 ChatMemory 和完整 Trading Tools，可能把
上一轮股票分析内容带入下一轮，并在意图阶段直接生成完整报告，最终导致 JSON 解析失败并降级为
`UNKNOWN`。

`StockInfoVO` 的正式来源不是任何 ChatClient。现有流程由 `TradingStarter.populateStockInfo()`
调用 `IStockDataProvider.getStockInfo()` 构造并写入 Trading 上下文。本设计保持该职责不变。

本文中的 3201 Skills/Tool 名称解析是 Story 1 可独立交付的过渡基线。联合实施 Story 2 后，3201
只提取原始名称或明确代码，Java `StockRequestResolver` 查询本地名称目录；Story 2 对下文涉及
3201 名称解析来源、槽位补齐责任和工具集合的要求具有覆盖效力。

## 目标

1. 删除 6001 节点及其第二次 LLM 意图识别。
2. 3201 成为唯一意图识别入口。
3. 3201 沿用现有 Skills/Tool 能力，将 A 股名称解析为 ticker，并填充股票槽位。
4. 使用固定 Java `TradingRequestNode` 校验槽位、构造 `StockAnalysisRequestVO` 并调用
   `TradingStarter`。
5. AutoAgent 路径在进入 `TradingStarter` 前创建权威 `TargetContext`；保持
   `TradingStarter.populateStockInfo()` 和 Trading pipeline 行为。
6. 修复连续两次股票分析时 6001 ChatMemory 污染和非 JSON 输出问题。

## 非目标

- 不专门实现不完整股票名称、简称、别名、前缀、后缀或子串匹配；本 Story 也不额外拦截
  `search_stock_by_name` 偶然成功解析的简称。
- 不实现候选消歧、跨请求 Pending 或二次澄清状态机。
- 不建设股票信息数据库快照或新的定时刷新任务。
- 不改变 `StockInfoVO` 获取与缓存策略。
- 不修改现有 `analysisDepth` 澄清规则。
- 不执行包含 `STOCK_ANALYSIS` 的多任务；后续 Story 再实现股票分析子任务的统一校验和执行。
- 不兼容实验性 `SPLIT` 路由的股票分析；本 Story 只以生产使用的 `UNIFIED` 路由为实施与验收范围。
- 不实现请求幂等键或前端重复提交去重；不改变现有通用会话历史和共享数据缓存策略。
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
       -> multiTask 包含 STOCK_ANALYSIS：登记 routingTerminalResponse 后返回
       -> STOCK_ANALYSIS: resolve tradingRequestNode
  -> TradingRequestNode (plain Java, no LLM, no ChatMemory)
       -> 校验失败：登记 routingTerminalResponse 后返回
       -> AnalysisTypeMapper 映射当前请求的分析师集合
       -> build StockAnalysisRequestVO
       -> TargetContextFactory 校验权威 stockCode + stockName
       -> 股票不存在：登记 CLARIFICATION 后返回
       -> Provider/数据完整性失败：登记 ERROR 后返回
       -> call TradingStarter with validated TargetContext
  -> TradingStarter (existing behavior)
       -> populateStockInfo via IStockDataProvider.getStockInfo()
       -> execute Trading pipeline
  -> 控制流返回 IntentRouteNode
       -> CLARIFICATION：发送 clarification + complete 业务事件
       -> ERROR：发送 error 业务事件
```

## 3201 股票槽位契约

现有 `StockSlot.stockCode` 在迁移期保留。为支持 Java 权威身份校验，新增 `stockName`：

```text
StockSlot
  stockCode: 603259
  stockName: 药明康德
  stockQueryType: ALL
  timeRange: null
```

规则如下：

- 用户明确输入 6 位 A 股代码时，`stockCode` 保存规范化代码；不要求 `stockName` 非空，由
  `TradingRequestNode` 的权威身份校验补齐。
- 用户输入股票名称时，3201 必须通过现有 Skills/`search_stock_by_name` 查询。
- 工具返回唯一结果时填写结果中的 `stockCode` 和 `stockName`。本 Story 不增加简称识别逻辑，
  也不额外比较用户原文与权威名称；正式的模糊匹配行为由后续 Story 定义。
- 0 个结果、多个结果或 ticker 格式非法时，本轮返回“请提供完整 A 股名称或 6 位代码”，不创建
  runId，也不保存跨请求 Pending。
- LLM 不得凭记忆生成 ticker；最终 ticker 必须来自用户明确代码或工具结果。
- `StockSlot.exchange` 不再由 3201 输出，也不参与股票身份构造和校验。

3201 的结构化输出 Schema、Prompt、Few-Shot 和 Validator 同步增加新字段。旧 `stockCode` 输入
继续兼容，避免破坏现有评测和调用方。

### 股票代码规范化

`stockCode` 是路由阶段唯一的候选代码来源，接受六位 A 股代码或标准 `ts_code`：

```text
603259
603259.SH
000001.SZ
920000.BJ
```

`TradingRequestNode` 对输入执行 `trim` 和大写归一化，只允许六位数字以及可选的
`.SH/.SZ/.BJ` 后缀。`TargetContextFactory` 完成权威查询后，使用返回的
`TargetContext.targetId` 作为唯一规范标识，并将 `StockAnalysisRequestVO.ticker` 改写为该值；后续
Trading 节点不得再从 `StockSlot` 推导标的。

现有 Java `StockSlot.exchange` 字段为兼容旧 JSON 暂时保留并标记废弃，任何值均被忽略；即使它与
`stockCode` 冲突，也以 `stockCode` 和最终权威 `targetId` 为准。3201 的新 Schema、Prompt 和
Few-Shot 删除 `exchange` 字段。代码自身携带后缀且与权威 `targetId` 不一致时，仍属于身份校验
失败。

该变更只废弃路由槽位中的 `exchange`。`StockSearchResultVO.exchange` 和 `StockInfoVO.exchange`
继续保留，用于工具结果、行情信息、报告展示与导出，不受影响。

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
- Story 1 独立交付时，`3201` 只装配 `read_skill` 和 `search_stock_by_name`；Story 2 生效后的最终态
  不为 3201 装配名称 Skill/Tool。
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
3. 使用 `AnalysisTypeMapper` 将 `stockQueryType` 映射为当前请求的 `selectedAnalysts`。
4. 构造 `StockAnalysisRequestVO`，透传 `stockName`，并设置现有默认辩论轮次、风控轮次和
   `sessionId`。
5. 调用 `TargetContextFactory` 查询并校验权威股票身份。
6. 校验成功后调用接收已验证 `TargetContext` 的 `TradingStarter` 启动入口。

`TargetContextFactory` 继续使用 `IStockDataProvider.findStockIdentities()` 查询权威身份，并增加名称
校验：

- 始终校验请求 ticker 与权威 `targetId` 一致。
- 名称输入场景校验请求 `stockName` 与权威 `stockName` 一致。
- 代码输入场景允许请求 `stockName` 为空，名称以权威记录为准。
- 校验通过后才创建 `runId` 和 `TargetContext`；失败时 `TradingRequestNode` 不调用
  `TradingStarter`。

`TradingRequestNode` 槽位校验失败时通过现有意图澄清 SSE 返回前端，并写入 Root 可持久化的最终
回复字段；本轮停止。

AutoAgent 路径调用新的 `TradingStarter` 启动重载，将已验证的 `TargetContext` 作为显式参数传入，
不得在 `TradingStarter` 内重复查询股票身份。该重载必须校验请求 ticker 与
`TargetContext.targetId` 一致，避免调用方传入不匹配对象。直接 `/trading/analysis` API 保留现有
入口，由 `TradingStarter` 内部创建 `TargetContext`，外部行为不变。

权威身份失败分为三类领域异常：

| 异常 | 含义 | AutoAgent 响应 |
|---|---|---|
| `StockIdentityNotFoundException` | 权威查询返回 0 条 | `CLARIFICATION`：请提供完整名称或 6 位代码 |
| `StockIdentityProviderException` | 超时、网络、鉴权或上游异常 | `ERROR`：股票数据服务暂时不可用，请稍后重试 |
| `StockIdentityValidationException` | 多条、非法记录或身份不一致 | `ERROR`：股票身份校验失败，本次分析已停止 |

Provider 原始异常作为 cause 保留并写入日志与观测数据；不得依赖异常消息字符串分类，也不在本
Story 中增加自动重试。

## 路由终止响应与 SSE 所有权

`TradingRequestNode` 和 `RoutingResultHandler` 不直接发送或关闭 SSE。需要在 Trading 启动前终止
本轮时，生产方执行以下动作：

1. 将用户可见文本写入 `DynamicContext.routingTerminalResponse`。
2. 将 `CLARIFICATION` 或 `ERROR` 写入 `DynamicContext.routingTerminalKind`。
3. `CLARIFICATION` 同时写入现有 `DynamicContext.clarificationPrompt`；`ERROR` 不伪装成信息缺失。
4. 返回该文本并停止进入后续执行节点。

`RootNode` 优先读取 `routingTerminalResponse` 作为本轮助手回复进行持久化。控制流返回
`IntentRoutingNode` 后，由它根据 `routingTerminalKind` 发送：

```text
type=summary, subType=clarification, content=<routingTerminalResponse>
type=complete

或：

type=error, content=<routingTerminalResponse>
```

SSE 所有权约束：

- `IntentRoutingNode` 只发送业务事件，不调用 `ResponseBodyEmitter.complete()`。
- `AutoAgentExecuteStrategy` 是 AutoAgent 请求中唯一负责物理关闭 emitter 的所有者。
- 每轮只允许发送一种终止协议；`ERROR` 事件自身为完成事件，不再追加 `complete`。
- 权威身份查询失败发生在 `TradingStarter` 之前，不发送 `trading/error`，也不创建 Trading run。
- `TradingStarter.populateStockInfo()` 等启动后的数据异常继续使用现有 `trading/error`。
- SSE 发送失败时不重复发送；控制流正常返回，由外层执行清理和关闭。

## 分析类型映射

删除 `TradingIntentRoutingService` 前，将其现有 `parseAnalysisType()` 逻辑提取为 Trading 模块内的
无状态 Java 组件 `AnalysisTypeMapper`，由 `TradingRequestNode` 调用。3201 只负责输出标准分析类型，
不直接构造 `AnalystTypeEnum` 列表。

标准映射保持现有语义：

| `stockQueryType` | `selectedAnalysts` |
|---|---|
| `ALL`、`null`、空值 | 使用 Trading 当前默认全部分析师 |
| `FUNDAMENTAL` | `FUNDAMENTAL` |
| `TECHNICAL` | `TECHNICAL` |
| `SENTIMENT` | `SENTIMENT` |
| `NEWS` | `NEWS` |
| 逗号分隔的合法枚举码 | 对应的多个分析师 |

兼容规则：

- 3201 Schema、Prompt、Few-Shot 和 Validator 将 `stockQueryType` 约束为上述标准枚举码或合法的
  逗号组合，不再输出“走势分析”等自由文本。
- Mapper 对输入执行 `trim` 和大小写归一化。
- 逗号组合沿用现有逻辑：忽略无法识别的项；至少存在一个合法项时使用合法子集。
- 输入全部无法识别时沿用现有降级语义，返回默认全部分析师，并记录警告日志。
- `TradingRequestNode` 将映射结果显式写入 `StockAnalysisRequestVO.selectedAnalysts`；不得依赖 LLM
  直接生成 `AnalystTypeEnum`。
- 直接 `/trading/analysis` API 的 `selectedAnalysts` 请求字段及默认行为不变。

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

### 数据库 DDL 与 DML

本 Story 不需要新增字段、索引、约束或数据表，DDL 为零变更。`ai_client_config` 是通过
`source_type/source_id` 和 `target_type/target_id` 表达的多态关系，现有索引已满足本次一次性精确
删除；不得为了删除一个 Client 修改通用表结构。

新增前向 Flyway 迁移 `V2030__remove_trading_intent_client_6001.sql`。不得修改已经发布的 `V2027`、
`V2028`、`V2029`，避免既有环境出现 Flyway checksum 不一致；全新环境依次执行到 `V2030` 后与
升级环境得到相同最终状态。

迁移 DML 使用类型化谓词并按引用在前、主记录在后的顺序执行：

```sql
DELETE FROM ai_agent_flow_config
WHERE client_id = '6001';

DELETE FROM ai_client_config
WHERE (source_type = 'client' AND source_id = '6001')
   OR (target_type = 'client' AND target_id = '6001');

DELETE FROM ai_client
WHERE client_id = '6001';
```

以下数据明确保留：

- `ai_client_system_prompt.prompt_id='6001'`，该记录属于既有“提示词优化”功能，不属于 Trading
  6001 Client。
- `ai_client_config(source_type='client', source_id='3001', target_type='prompt',
  target_id='6001')`。
- 被 6001 引用过的共享 model、advisor、prompt、MCP/tool 实体。
- 历史会话、审计和可观测性记录。

禁止使用不带实体类型的通用谓词，例如 `source_id='6001' OR target_id='6001'`，也禁止删除
`ai_client_system_prompt.prompt_id='6001'`。迁移保持幂等，目标行已经不存在时执行成功且不产生副作用。

迁移测试必须读取实际 SQL 并断言：

- 只删除 `ai_agent_flow_config.client_id='6001'`、类型为 `client` 的 `ai_client_config` 关系以及
  `ai_client.client_id='6001'`。
- SQL 不包含针对 `ai_client_system_prompt` 的 `DELETE`。
- 迁移前后 `prompt_id='6001'` 数量不变，`client 3001 -> prompt 6001` 关系仍存在。
- 迁移后 6001 Client、Flow 和类型化 Client 关系均为 0，3201 与 6002-6013 配置数量不变。

## 兼容行为

- `intent.routing.mode=UNIFIED` 是本 Story 唯一受支持的股票分析入口。
- `SPLIT` 仅用于对比实验：保留现有实验代码，但不更新其专属分解/切槽 Prompt、Schema 和评测集，
  也不承诺其 `STOCK_ANALYSIS` 在本 Story 后可执行。实验环境不得用于验收本 Story。
- `GENERAL_CHAT`、PE、巡检和其他意图的 3201 路由行为不变。
- 现有 `analysisDepth` 澄清先于可执行 `STOCK_ANALYSIS`；用户回答后继续使用合并后的原始股票名。
- 主会话历史继续按 `sessionId` 提供给 3201。
- AutoAgent 每次通过权威校验后创建新 runId 和 `TargetContext`，再进入 `TradingStarter`；不复用上一
  Trading run。
- 每个到达 AutoAgent 的前端请求都视为一次独立执行；即使 `sessionId`、Query 和股票完全相同，
  也重新执行 Trading pipeline 并生成不同 runId。
- 同一请求产生独立 `DynamicContext`；同一 `DynamicContext` 内部节点异常重入属于框架缺陷，不在本
  Story 中增加业务幂等状态机。
- `sessionId` 级通用会话历史继续复用，3201 可以结合历史完成意图识别、追问恢复和普通对话。
- 新 Trading run 只隔离 Trading 节点 ChatMemory、运行中间结果、最终决策和标的上下文；不得把上一
  run 的 Trading 角色消息或结果注入新 run 的 pipeline。
- 本 Story 不改变原始数据缓存的 Key 和过期策略，也不向 Key 增加 runId。当前 Provider 是否实际接入
  `TradingDataCache` 不属于本 Story 范围，不把跨 run 缓存命中作为既有事实或验收前提。
- Root 持久化的上一轮 Trading 最终回复仍属于主会话历史，可供后续 3201 或 `GENERAL_CHAT` 理解用户
  的追问，但不作为新 Trading run 的输入结论直接复用。
- `TradingStarter.populateStockInfo()` 和 `TradingContext.stockInfo` 保持现状。
- 不包含 `STOCK_ANALYSIS` 的多任务链路保持现状。
- `multiTask=true` 且任一子任务为 `STOCK_ANALYSIS` 时，`RoutingResultHandler` 在执行任何子任务前
  拒绝整轮请求，提示用户单独发起股票分析；不进入 `MultiTaskExecutionNode`，也不创建 Trading run。

## 错误处理

- 3201 非 JSON 或 Schema 校验失败：沿用统一路由现有重试和降级，不进入 Trading。
- 工具无结果、多个结果或调用失败：不填充可执行 ticker，不进入 Trading。
- `TradingRequestNode` 校验失败：返回澄清提示，不调用 `TradingStarter`。
- `TargetContextFactory` 返回未找到、Provider 失败或身份校验失败：登记对应路由终止响应，不调用
  `TradingStarter`。
- 多任务包含 `STOCK_ANALYSIS`：返回“股票分析暂不支持与其他任务同时执行，请单独发起股票分析”，
  不执行该任务列表中的任何子任务。
- `TradingStarter.getStockInfo()` 失败：沿用当前 Trading 错误处理；身份此前已确认，不回到意图路由。

## 测试策略

- 3201 对“对药明康德进行完整投资分析”调用搜索工具并输出 `603259`。
- 3201 对明确 6 位代码不调用名称搜索。
- 搜索无结果、多结果和工具异常不得生成 ticker。
- `TradingRequestNode` 拒绝非法 ticker 和空槽位。
- `TargetContextFactory` 同时校验 ticker 与名称；直接代码输入允许名称为空。
- 六位代码、带 `SH/SZ/BJ` 后缀代码及大小写/空格归一化后得到唯一权威 `targetId`。
- `StockSlot.exchange` 缺失或与代码冲突均不影响执行；非法 ticker 和代码自身错误后缀被拒绝。
- 权威查询空结果产生澄清事件；Provider 异常和非法权威数据产生错误事件，三者都不调用
  `TradingStarter`、不执行 pipeline。
- AutoAgent 成功路径只查询一次权威身份，并将同一个 `TargetContext` 传给 `TradingStarter`。
- 连续提交两个完全相同的股票分析请求时，两次均执行 Trading pipeline 并产生不同 runId；主会话
  历史继续复用，原始数据缓存 Key 契约保持不变，但 Trading 节点上下文彼此隔离。
- 直接 Trading API 仍由原入口创建 `TargetContext`，行为保持不变。
- `UNIFIED` 模式覆盖全部 Story 验收；不将 `SPLIT` 股票分析纳入回归门禁。
- `V2030` 在既有数据库和从零执行全部迁移的数据库上均只删除 Trading Client 6001；
  `prompt_id=6001` 及其 3001 绑定保持不变。
- `AnalysisTypeMapper` 保持原 6001 对 `ALL`、单个类型和逗号组合的映射行为；未知值降级为当前
  默认全部分析师。
- `RoutingResultHandler` 对单任务 `STOCK_ANALYSIS` 只路由到 `tradingRequestNode`。
- `RoutingResultHandler` 拒绝任何包含 `STOCK_ANALYSIS` 的多任务，且不调用
  `MultiTaskExecutionNode`、`TradingRequestNode` 或 `TradingStarter`。
- `TradingRequestNode` 校验失败和股票多任务门禁都登记终止响应；`IntentRoutingNode` 根据 kind
  发送一次 `clarification + complete` 或一个已完成的 `error` 事件，不混用两套协议。
- `RootNode` 从 `routingTerminalResponse` 持久化澄清或错误文本；`AutoAgentExecuteStrategy` 只关闭
  一次 emitter。
- 连续完成药明康德后分析兆易创新，全链路不调用 6001，不读取 6001 ChatMemory，并创建新 runId。
- 3201 路由期间不得调用 `get_stock_info` 或其他分析工具。
- Story 1 独立基线下，3201 的 Trading Tool 集合严格等于 `read_skill + search_stock_by_name`；
  联合 Story 2 的最终验收改为不装配名称 Skill/Tool。
- 未配置 Client 和空列表 Client 均不装配 Trading Tool；未知工具名导致启动失败。
- 6002-6013 的最终 Trading Tool 集合与改造前一致。
- `TradingStarter.populateStockInfo()` 仍调用一次 Provider 并写入 `TradingContext.stockInfo`。
- GENERAL_CHAT、PE、巡检、现有 analysisDepth、直接 Trading API 和 6002-6013 回归通过。
