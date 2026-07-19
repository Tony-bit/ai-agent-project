# 通用金融意图路由设计

> 日期：2026-07-16
>
> 状态：待用户审阅
>
> 范围：统一意图路由，不新增 Agent 或 Spring Bean

## 1. 背景

当前 `STOCK_ANALYSIS` 同时覆盖股票行情、金融知识、财报解读和投资决策，所有此类请求都会映射到 TradingAgent。这会导致简单信息查询误触发高成本、长耗时的深度投资分析流程。

系统现有路由约定是 `intent -> Spring Bean`。为保持该约定，本次不引入 `taskMode` 复合路由键，而是新增独立意图 `FINANCIAL_GENERAL`，并收窄 `STOCK_ANALYSIS` 的语义边界。

## 2. 目标与非目标

### 2.1 目标

- 金融知识、行情、财报和新闻等客观查询路由到现有通用 Agent。
- 明确要求买卖判断、投资价值、仓位、目标价或止损的请求路由到 TradingAgent。
- 对“最近怎么样”“帮我看看”等无法判断分析深度的表达先向用户澄清。
- 保持单任务和多任务路径上的 `intent -> Spring Bean` 一致。
- 保持现有股票实体槽位解析结果可在澄清后复用。

### 2.2 非目标

- 不新增金融查询 Agent。
- 不新增 Spring Bean。
- 不改变 TradingAgent 内部的股票解析、分析师选择和状态机流程。
- 不让 LLM 直接输出 Spring Bean 名称作为可信路由决策。
- 不在本次改造中引入 `intent + taskMode` 复合映射。

## 3. 意图定义

### 3.1 `FINANCIAL_GENERAL`

用于不要求形成交易决策的金融请求，包括：

- 金融概念和术语解释。
- 股票、基金、指数等行情或基础信息查询。
- 财报、公告、新闻、估值指标的客观摘要和一般解读。
- 不包含明确买卖结论的市场现象说明。

执行目标：现有 `generalChatNode`。

### 3.2 `STOCK_ANALYSIS`

仅用于需要完整投资研究或交易决策的请求，包括：

- 是否值得买入、持有或卖出。
- 投资价值、机会与风险的综合判断。
- 个股之间以投资选择为目的的比较。
- 仓位、入场时机、目标价、止损和持有周期建议。
- 明确要求启动完整、多维或深度投资分析。

执行目标：现有 `tradingStarter`，随后进入 TradingAgent 内部流程。

### 3.3 模糊请求

“茅台最近怎么样”“帮我分析一下宁德时代”等表达只有金融对象，没有明确任务深度。统一路由返回：

```json
{
  "multiTask": false,
  "needsClarification": true,
  "missingInfo": ["analysisDepth"],
  "clarificationPrompt": "你需要快速了解，还是进行完整投资分析？",
  "reasoning": "用户未明确查询深度",
  "taskList": []
}
```

澄清选项的确定性转换：

| 用户选择 | 转换后的 intent | 执行目标 |
|---|---|---|
| 快速了解 | `FINANCIAL_GENERAL` | `generalChatNode` |
| 完整投资分析 | `STOCK_ANALYSIS` | `tradingStarter` |

用户未选择或回复无法识别时，默认转换为 `FINANCIAL_GENERAL`。

## 4. 路由映射

统一路由保持单键映射：

| Intent | Spring Bean / 执行节点 |
|---|---|
| `FINANCIAL_GENERAL` | `generalChatNode` |
| `STOCK_ANALYSIS` | `tradingStarter` |
| `PE_REASONING` / `PE_CALCULATION` / `PE_RETRIEVAL` | `step1AnalyzerNode` |
| `INSPECTION` | `intelligentInspection` |
| `GENERAL_CHAT` / `AMBIGUOUS` / `UNKNOWN` | `generalChatNode` 或现有澄清路径 |

`executorNode` 继续由后端根据 intent 归一化生成。即使模型输出 executor 信息，后端也不能将其作为可信映射来源。

## 5. 数据流

### 5.1 明确的信息查询

```text
“贵州茅台当前市盈率是多少”
  -> FINANCIAL_GENERAL
  -> generalChatNode
```

### 5.2 明确的投资决策

```text
“贵州茅台现在是否值得买入”
  -> STOCK_ANALYSIS
  -> tradingStarter
  -> TradingAgent 内部参数解析与分析状态机
```

### 5.3 模糊请求

```text
“贵州茅台最近怎么样”
  -> needsClarification=true
  -> RootNode 将原请求和 clarificationPrompt 持久化为现有会话记录
  -> 用户选择“快速了解”
  -> IntentRoutingNode 将会话历史与当前回复一并注入统一路由 Prompt
  -> FINANCIAL_GENERAL
  -> generalChatNode
```

金融澄清复用现有会话记录和上下文注入，不新增 pending cache 或第二套状态管理。统一路由 Prompt 根据历史中的原请求、澄清问题和当前选项完成确定性转换，并在 task content 中恢复原金融对象，不能只用一句“快速了解”作为下游请求。当前回复无法识别为固定选项时，安全转换为 `FINANCIAL_GENERAL`。

### 5.4 多任务请求

每个 `SubTask` 独立携带 intent。例如“先告诉我茅台当前估值，再判断是否值得买入”拆为：

1. `FINANCIAL_GENERAL`：获取并解释当前估值。
2. `STOCK_ANALYSIS`：执行完整投资判断，可依赖第一个任务结果。

多任务执行器仍根据每个子任务的 intent 解析执行节点，不引入额外映射字段。

## 6. 边界规则

| 请求 | 预期 intent |
|---|---|
| 什么是市盈率 | `FINANCIAL_GENERAL` |
| 查询贵州茅台当前股价 | `FINANCIAL_GENERAL` |
| 总结贵州茅台最近一期财报 | `FINANCIAL_GENERAL` |
| 贵州茅台财报反映了什么问题 | `FINANCIAL_GENERAL` |
| 贵州茅台是否值得买入 | `STOCK_ANALYSIS` |
| 贵州茅台和五粮液哪个更值得投资 | `STOCK_ANALYSIS` |
| 给出贵州茅台的仓位和止损建议 | `STOCK_ANALYSIS` |
| 对贵州茅台做一次完整投资分析 | `STOCK_ANALYSIS` |
| 贵州茅台最近怎么样 | 澄清 |
| 帮我分析一下贵州茅台 | 澄清 |

“分析”一词本身不能作为 `STOCK_ANALYSIS` 的充分条件；必须存在投资决策目标或明确的完整分析要求。

## 7. Prompt 分类契约

新增枚举和 Bean 映射只是执行层改造，路由效果主要由 Prompt 分类契约决定。所有会直接输出 intent 的模板都必须同步更新，包括统一路由模板、单任务切槽模板和仍被兼容链路使用的旧单任务模板。纯任务拆解模板如果不输出 intent，则不重复维护分类规则。

### 7.1 判定优先级

Prompt 按以下顺序判断，后续规则不能覆盖前面已经明确的语义：

1. 用户明确要求买入、卖出、持有、投资价值、仓位、目标价、止损或完整投资分析时，判为 `STOCK_ANALYSIS`。
2. 用户明确要求金融知识、行情、事实、财报、新闻、公告或指标的查询、摘要和一般解读，且未要求交易决策时，判为 `FINANCIAL_GENERAL`。
3. 用户只给出金融对象并使用“看看”“怎么样”“分析一下”等未限定深度的表达时，返回 `needsClarification=true`，`missingInfo=["analysisDepth"]`。
4. 出现股票名称、代码、“分析”或“走势”等单个关键词，不能单独作为 `STOCK_ANALYSIS` 的判定依据。
5. 否定表达优先，例如“不是要投资建议，只查一下市盈率”必须判为 `FINANCIAL_GENERAL`。
6. 当前消息含义明确时以当前消息为准；只有省略主语或任务目标时才继承历史上下文。

### 7.2 Prompt 中的对比式边界示例

Prompt 固定保留少量紧邻决策边界的对比示例，避免完全依赖动态检索结果：

| 输入 | 输出 |
|---|---|
| 查询贵州茅台当前股价和市盈率 | `FINANCIAL_GENERAL` |
| 贵州茅台当前估值是否适合买入 | `STOCK_ANALYSIS` |
| 总结宁德时代最近一期财报 | `FINANCIAL_GENERAL` |
| 结合财报判断宁德时代是否值得长期持有 | `STOCK_ANALYSIS` |
| 我不是要买卖建议，只想了解市盈率是什么意思 | `FINANCIAL_GENERAL` |
| 帮我看看贵州茅台最近怎么样 | 澄清 `analysisDepth` |

固定示例只负责稳定核心边界，数量保持精简；长尾表达继续由动态 Few-shot 覆盖。

### 7.3 统一结构化输出

`FINANCIAL_GENERAL` 与 `STOCK_ANALYSIS` 都使用现有 `taskList` 协议，不新增输出字段。澄清场景返回空 `taskList`，不虚构一个 `AMBIGUOUS` 执行任务。结构化校验器必须同时更新允许值，确保统一路由与拆分后的单任务路由接受相同 intent 集合。

## 8. Few-shot 数据治理

### 8.1 现有链路

保留当前主链：

```text
用户 query
  -> IntentFewshotService.retrieveTopK(query, 5)
  -> PGvector 召回相似样本
  -> Prompt 注入“query + exampleJson”
  -> LLM 输出统一路由结果
```

Few-shot 检索失败时仍降级为无动态示例的 Zero-shot 路由，但固定边界规则和固定对比示例必须继续存在。

### 8.2 历史样本迁移

上线新 intent 前必须清理所有启用状态的 `STOCK_ANALYSIS` 样本：

1. 行情、财报、新闻、指标和金融知识查询重标为 `FINANCIAL_GENERAL`。
2. 明确投资决策、交易建议和完整投资分析保留为 `STOCK_ANALYSIS`。
3. “看看某股票”“最近怎么样”等深度不明确的样本改为澄清输出，`needsClarification=true`、`missingInfo=["analysisDepth"]`。
4. 无法确定业务标签的样本先禁用，不允许带着旧标签继续召回。
5. 同步更新 MySQL 中的 `intentCode`、`exampleJson`，以及 PGvector 文档 metadata；不能只改其中一份。

样本更新后需要重新同步向量存储。虽然 query 文本未变化时 embedding 可以复用，但 `intentCode` 和 `exampleJson` metadata 必须与 MySQL 一致，并验证同一 sample ID 不产生新旧两份冲突记录。

### 8.3 首批新增样本

首批至少补充 30 条边界样本：

| 类别 | 最少数量 | 必须覆盖 |
|---|---:|---|
| `FINANCIAL_GENERAL` | 12 | 金融知识、行情、财报、新闻、指标、否定投资建议 |
| `STOCK_ANALYSIS` | 10 | 是否买入、投资比较、持仓建议、目标价止损、完整分析 |
| 澄清 | 8 | “怎么样”“看看”“分析一下”、省略任务目标、上下文追问 |

每类样本同时包含正式表达、口语、短句、错别字、否定句和带历史上下文的追问。不能只使用 Prompt 固定示例的同义改写，以免评测变成记忆测试。

### 8.4 动态示例质量约束

- 只注入启用且 JSON 可通过当前结构化校验的样本。
- 注入前过滤未知或已废弃 intent，防止旧标签污染 Prompt。
- Top-K 默认保持 5，先通过评测判断是否需要调整，避免无依据扩大上下文。
- 示例位于分类硬规则之后、当前用户输入之前，并明确“示例仅供参考，当前输入和历史上下文优先”。
- 样本迁移脚本必须幂等，并在变更前导出启用样本快照，支持出现准确率回退时恢复旧数据。

## 9. 失败与兼容策略

- LLM 返回非法 JSON、非法 intent 或空任务时，沿用现有安全降级路径，不启动 TradingAgent。
- 无法可靠区分 `FINANCIAL_GENERAL` 与 `STOCK_ANALYSIS` 时必须澄清，不允许依靠低置信度猜测触发 TradingAgent。
- 澄清失败默认进入 `FINANCIAL_GENERAL`。
- 历史 Few-shot 和在线评测集中把普通行情或走势查询标注为 `STOCK_ANALYSIS` 的样本必须迁移，否则新旧标签会产生冲突。
- 已明确传入 TradingAgent 专用入口的请求不经过本次自动意图澄清，保持显式调用优先。

## 10. 代码影响范围

预计修改以下位置：

- `IntentTypeEnum`：新增 `FINANCIAL_GENERAL`。
- `IntentRoutingPrompt`：新增意图说明，收窄 `STOCK_ANALYSIS`，加入边界示例和澄清规则。
- `RoutingStructuredOutputValidator`：允许新 intent。
- `IntentRoutingService`：将新 intent 归一化到 `generalChatNode`。
- `IntentFewshotService` 及其仓储实现：支持安全迁移样本标签和 JSON，并同步 PGvector metadata。
- `RoutingResultHandler`：将新 intent 映射到现有 `generalChatNode`。
- `MultiTaskExecutionNode` 相关映射：保证多任务执行可解析新 intent 对应的 executor。
- Few-shot 初始化或数据迁移脚本：重标、禁用冲突样本并补充首批边界样本。
- 路由单元测试、Prompt 测试、本地解析评测集和在线模型评测集：加入正例、反例、模糊例和多任务例。

Trading 模块内部的 `IntentRoutingNode` 不负责区分普通金融查询；只有顶层已经确定为 `STOCK_ANALYSIS` 后才会进入该模块。

## 11. 验证标准

- 明确金融知识和信息查询不会启动 TradingAgent。
- 明确投资决策请求仍能启动 TradingAgent。
- 模糊金融请求返回固定的二选一澄清问题。
- 澄清选择能复用原始股票实体并进入正确 Agent。
- 非金融意图的现有路由行为不变。
- 单任务、拆分路由和统一多任务路由均接受 `FINANCIAL_GENERAL`。
- 结构化输出校验拒绝未知 intent，同时接受 `FINANCIAL_GENERAL`。
- 离线评测分别统计 `FINANCIAL_GENERAL` 与 `STOCK_ANALYSIS` 的 precision、recall，并重点监控普通查询误触发 TradingAgent 的比例。
- Prompt 单测确认所有会输出 intent 的模板包含新意图、收窄后的 `STOCK_ANALYSIS` 定义和 `analysisDepth` 澄清规则。
- Few-shot 测试确认新旧标签不会同时召回、非法样本不会注入 Prompt、检索失败可以安全降级。
- 在线评测集中原有“最近走势”等股票样本按新业务定义完成重标，不能沿用旧 baseline。
- 发布门槛要求关键普通金融查询集合中不存在误触发 TradingAgent 的 case；整体准确率和一致率沿用现有在线评测门槛，并单独输出两类金融意图的混淆矩阵。

## 12. 关键决策

1. 新增 `FINANCIAL_GENERAL` intent，而不是新增 `taskMode`。
2. 保持 `intent -> Spring Bean` 的现有架构约定。
3. `FINANCIAL_GENERAL` 复用现有 `generalChatNode`，不新增 Agent 或 Bean。
4. `STOCK_ANALYSIS` 仅表示完整投资研究和交易决策。
5. 模糊请求先澄清，失败时默认走 `FINANCIAL_GENERAL`。
6. Prompt 硬规则、固定边界示例、动态 Few-shot 和评测数据必须作为同一版本发布。
