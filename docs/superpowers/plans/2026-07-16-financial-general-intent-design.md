# 通用金融意图路由设计

> 日期：2026-07-16  
> 状态：待用户审阅  
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

用户未选择、回复无法识别或澄清状态过期时，默认转换为 `FINANCIAL_GENERAL`。

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
  -> 保存 pending clarification 上下文
  -> 用户选择“快速了解”
  -> FINANCIAL_GENERAL
  -> generalChatNode
```

澄清上下文至少保存原始请求、已识别金融实体及股票槽位、会话 ID、澄清类型和过期时间。澄清完成后复用这些信息，不重新依赖一句“快速了解”推断股票对象。

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

## 7. 失败与兼容策略

- LLM 返回非法 JSON、非法 intent 或空任务时，沿用现有安全降级路径，不启动 TradingAgent。
- 无法可靠区分 `FINANCIAL_GENERAL` 与 `STOCK_ANALYSIS` 时必须澄清，不允许依靠低置信度猜测触发 TradingAgent。
- 澄清失败默认进入 `FINANCIAL_GENERAL`。
- 历史 Few-shot 中把普通行情或走势查询标注为 `STOCK_ANALYSIS` 的样本必须迁移，否则新旧标签会产生冲突。
- 已明确传入 TradingAgent 专用入口的请求不经过本次自动意图澄清，保持显式调用优先。

## 8. 代码影响范围

预计修改以下位置：

- `IntentTypeEnum`：新增 `FINANCIAL_GENERAL`。
- `IntentRoutingPrompt`：新增意图说明，收窄 `STOCK_ANALYSIS`，加入边界示例和澄清规则。
- `RoutingStructuredOutputValidator`：允许新 intent。
- `IntentRoutingService`：将新 intent 归一化到 `generalChatNode`。
- `RoutingResultHandler`：将新 intent 映射到现有 `generalChatNode`。
- `MultiTaskExecutionNode` 相关映射：保证多任务执行可解析新 intent 对应的 executor。
- 路由单元测试、Prompt 测试和本地评测数据集：加入正例、反例、模糊例和多任务例。

Trading 模块内部的 `IntentRoutingNode` 不负责区分普通金融查询；只有顶层已经确定为 `STOCK_ANALYSIS` 后才会进入该模块。

## 9. 验证标准

- 明确金融知识和信息查询不会启动 TradingAgent。
- 明确投资决策请求仍能启动 TradingAgent。
- 模糊金融请求返回固定的二选一澄清问题。
- 澄清选择能复用原始股票实体并进入正确 Agent。
- 非金融意图的现有路由行为不变。
- 单任务、拆分路由和统一多任务路由均接受 `FINANCIAL_GENERAL`。
- 结构化输出校验拒绝未知 intent，同时接受 `FINANCIAL_GENERAL`。
- 离线评测分别统计 `FINANCIAL_GENERAL` 与 `STOCK_ANALYSIS` 的 precision、recall，并重点监控普通查询误触发 TradingAgent 的比例。

## 10. 关键决策

1. 新增 `FINANCIAL_GENERAL` intent，而不是新增 `taskMode`。
2. 保持 `intent -> Spring Bean` 的现有架构约定。
3. `FINANCIAL_GENERAL` 复用现有 `generalChatNode`，不新增 Agent 或 Bean。
4. `STOCK_ANALYSIS` 仅表示完整投资研究和交易决策。
5. 模糊请求先澄清，失败时默认走 `FINANCIAL_GENERAL`。
