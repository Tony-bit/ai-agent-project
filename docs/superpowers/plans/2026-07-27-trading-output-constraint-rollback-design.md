# Trading Agent 输出约束分层回退设计

| 字段 | 内容 |
|---|---|
| 日期 | 2026-07-27 |
| 状态 | Draft，待评审 |
| 范围 | `ai-agent-study-trading` 中 `6002` 至 `6013` 的 12 个 LLM 节点 |
| 目标 | 将过度结构化输出回退为“9 个叙事节点 + 3 个最小决策节点”，同时保证 Java 股票身份、数据查询范围和最终交易标的不可被 LLM 输出改变 |
| 本阶段不包含 | 修改业务代码、激活数据库 Prompt、改变线上配置 |

## 1. 最终结论

当前 V2 将 12 个节点全部约束为完整 JSON：75 个顶层字段、展开后 129 个字段，其中 52 个字段带枚举、范围或集合约束。对照 `TauricResearch/TradingAgents@a33fd4c0`，其结构化节点为 `4/12`、结构字段 17 个，其余节点使用自然语言并允许结构失败后回退。

V3 采用以下最终方案：

| 关注点 | V2 | V3 |
|---|---|---|
| LLM 输出 | 12 个完整 JSON DTO | 9 个自然语言节点；`6008/6009/6013` 各保留 2 个字段 |
| 结构字段数 | 75 个顶层字段 | 6 个顶层字段，无嵌套结构 |
| 股票身份 | LLM `targetEcho` 与 Java 身份双重表达 | `TargetContext` 是唯一权威身份；V3 不输出 `targetEcho` |
| 正文校验 | 实体、数字和格式问题可能拒绝结果 | 不检查自然语言股票主体，不从正文生成身份或执行参数 |
| 决策信号 | 直接读取旧 DTO 可空字段 | 统一读取有来源、版本和可用状态的 `DecisionSignalSet` |
| 失败语义 | 单节点失败可能终止 SSE | 中间节点独立降级；只有 Java 身份边界或必要终止条件失败才终止 run |
| 发布与回滚 | V2 全量生效 | V3 完整 Prompt 集合原子激活；V2 保留到回滚窗口关闭 |

系统接受 V3 自然语言正文可能分析错误公司的剩余风险，但必须保证：

- LLM 不能修改本次 run 的 `targetId`；
- LLM 不能触发其他股票的数据查询或缓存读写；
- rawText 不能直接生成股票身份、价格、仓位或执行参数；
- 最终 `BUY/SELL/HOLD/SKIP` 始终由 Java 绑定当前 `TargetContext`。

## 2. 节点输出契约

### 2.1 逐节点契约

当前 `TradingNodeObservability` 对 `6009～6012` 的 clientId 映射存在漂移，实施前必须先按下表修正。

| clientId | 节点 | V3 LLM 输出 | Java 处理 | 失败回退 |
|---|---|---|---|---|
| `6002` | Fundamental Analyst | 基本面正文 | `fundamental-rating-v1` 生成 rating | 正文非空即可提交；rating 可为 `UNAVAILABLE` |
| `6003` | Technical Analyst | 技术正文 | `technical-signal-v1` 生成 rating/trendSignal | 正文非空即可提交；信号可为 `UNAVAILABLE` |
| `6004` | Sentiment Analyst | 情绪正文 | `sentiment-rating-v1` 生成 rating；权威输入提供 sentimentScore | 正文非空即可提交 |
| `6005` | News Analyst | 新闻影响正文 | `news-rating-v1` 生成 rating/overallSentiment；保留原始 newsItems/source ids | 无可评分新闻时信号为 `UNAVAILABLE` |
| `6006` | Bull Researcher | 多头论证正文 | Java 注入 `role=BULL` | 正文非空即可进入辩论历史 |
| `6007` | Bear Researcher | 空头论证正文 | Java 注入 `role=BEAR` | 同上 |
| `6008` | Research Manager | `recommendation + reasoning` | Java 派生 status/overallScore/needMoreDebate/warnings | 无法解析时 `INSUFFICIENT_DATA` |
| `6010` | Neutral Risk Analyst | 中性风险正文 | Java 注入 `role=NEUTRAL` | 单节点失败不影响其他风险节点 |
| `6011` | Conservative Risk Analyst | 保守风险正文 | Java 注入 `role=CONSERVATIVE` | 同上 |
| `6012` | Aggressive Risk Analyst | 激进风险正文 | Java 注入 `role=AGGRESSIVE` | 同上 |
| `6013` | Recommendation Node | `action + rationale` | 下游只读取动作、理由和权威行情 | 无法解析时 `HOLD` |
| `6009` | Portfolio Manager | `decision + reasoning` | Java 计算 overallRating/confidence/warnings，并绑定最终 targetId | 无法解析时 `SKIP` |

三个最小决策 DTO 的枚举域为：

- `6008.recommendation`：`BUY/SELL/HOLD/INSUFFICIENT_DATA`；
- `6013.action`：`BUY/SELL/HOLD`；
- `6009.decision`：`BUY/SELL/HOLD/SKIP`。

解析器允许大小写、中文同义词和 JSON 外包裹文本的确定性归一化，但不能从 reasoning/rawText 提取股票、价格或仓位。

### 2.2 统一叙事模型

`6006/6007/6010/6011/6012` 在解析边界后统一为：

```java
public record NarrativeNodeResult(
        String role,
        String rawText
) {}
```

- role 只由 Java 按节点类型注入，LLM 不能覆盖；
- rawText 保存 trim 后非空的完整正文，不摘要、不改写；
- `runId/targetId/nodeName/generatedAt` 由 `NodeResultEnvelope<NarrativeNodeResult>` 承载；
- mode、validationStatus、schemaVersion 和降级原因保存在 envelope/registry，不进入叙事模型；
- `ResearchArgumentPayload`、`RiskAssessmentPayload` 只允许存在于 `STRICT_V2` 解析适配层。

双模式适配：

```text
STRICT_V2
  -> 解析并校验旧 Payload
  -> 固定模板完整渲染为 rawText
  -> Java 注入 role

RELAXED_V3
  -> 读取非空自然语言正文
  -> Java 注入 role

两种模式
  -> NarrativeNodeResult
  -> NodeResultEnvelope
  -> 统一下游
```

### 2.3 V2 Payload 转换

`research-argument-markdown-v1`：

```markdown
## {role} 观点

{summary}

### 关键证据
- [{type}/{confidence}] {claim}

### 风险
- {risk}
```

`risk-assessment-markdown-v1`：

```markdown
## {role} 风险意见

{summary}

### V2 风险评分
{riskScore}/5

### 风险项
- {riskItem}

### 缓解措施
- {mitigation}
```

转换器必须保留全部字段、原始顺序和重复项，不概括、不去重；空列表省略对应章节，不写“无风险”。`targetEcho` 不进入 rawText，role 使用 Java 值。

统一消费者包括 Debate/Risk 历史、Research/Portfolio Prompt、SSE、`TradingResultVO` 和 Markdown 导出。V2 的证据、风险项和缓解措施通过上述模板完整保留。

## 3. Java 身份与执行边界

### 3.1 唯一权威身份

`TargetContext` 在 run 创建时由 Java 根据 Query 和权威股票数据解析，此后不可变。以下对象必须引用同一实例或同一不可变快照：

- 所有节点和 `NodeResultEnvelope`；
- Prompt 快照与缓存命名空间；
- SSE 元数据和审计记录；
- 最终结果与报告标题。

`NodeResultEnvelope` 只保护 Java 编排完整性，不用于证明 LLM 正文正确。envelope 的 runId、targetId、nodeName 与当前上下文不一致时拒绝该节点结果。

`STRICT_V2` 继续保留现有 `targetEcho` schema、Bean Validation 和一致性校验；`RELAXED_V3` 不生成、不解析、不校验 `targetEcho`。兼容展示需要目标身份时，由 Java 从 `TargetContext` 注入。

### 3.2 自然语言边界

V3 不实现正则、关键词、公司名称字典或二次 LLM 的正文主体检查：

- rawText 提到其他公司不会触发拒绝、隔离或降级；
- 不从 rawText/reasoning 提取股票代码、公司名称、价格、仓位或交易动作；
- rawText 只能用于叙事展示和下游 LLM 分析输入，不能写回运行上下文或执行对象。

### 3.3 Provider 与工具调用

行情、财务、技术、新闻和情绪查询只接收 `TargetContext.targetId`：

```text
TargetContext.targetId
  -> provider/tool adapter
  -> effective ticker
  -> cache namespace
```

- 工具 schema 不向 LLM 暴露可自由指定的 ticker；
- 底层兼容接口必须保留 ticker 时，Java adapter 无条件覆盖为当前 targetId；
- adapter 无法保证覆盖时，禁止调用 provider；
- 审计同时记录 original/effective 参数；不一致时记录 `TOOL_TARGET_OVERRIDDEN`；
- provider 返回结果按当前 targetId 归档，禁止使用 LLM 参数生成缓存键。

### 3.4 最终动作绑定

三个决策 DTO 均不包含 ticker、stockName、targetId、价格或仓位身份字段。最终结果由 Java 创建：

```text
FinalTradeDecision.targetId  = TargetContext.targetId
FinalTradeDecision.stockName = TargetContext.stockName
FinalTradeDecision.decision  = normalized LLM decision
```

任何从 rawText/reasoning 生成身份、价格、仓位或执行参数的代码均违反本设计。Java 无法保证 provider、缓存、对外身份或最终动作仍绑定当前 `TargetContext` 时，记录 `IDENTITY_BOUNDARY_VIOLATION` 并在外部调用或结果提交前终止 run。

## 4. 校验与失败语义

### 4.1 双模式校验

| 模式 | 输出处理 |
|---|---|
| `STRICT_V2` | 旧 DTO 严格解析、Bean Validation、targetEcho 和现有数值一致性校验 |
| `RELAXED_V3` 叙事节点 | 只要求 rawText 非空；不扫描实体和数字 |
| `RELAXED_V3` 决策节点 | 校验最小枚举和非空理由；失败时进入确定性安全回退 |

V3 不产生 `TARGET_MISMATCH` 或 `FOREIGN_ENTITY` 正文校验结果。Java 可确定的指标直接进入 Report/Signal，不从 LLM 正文反向解析。

### 4.2 失败作用域

节点结果级拒绝用于：

- envelope 与当前 run 不一致；
- 结果无法证明属于当前节点；
- provider/tool adapter 无法强制绑定当前 targetId。

run 级终止仅用于：

- `IDENTITY_BOUNDARY_VIOLATION`；
- 四个 Analyst 全部不可用；
- 请求取消；
- 最终节点失败且 Java 无法生成不依赖失败正文的确定性 `SKIP`。

格式错误、同义词、非关键字段缺失、单个中间节点异常和中间正文数字疑似冲突均不得直接终止 run。

### 4.3 阶段行为

| 阶段 | 最终行为 |
|---|---|
| AnalystCollection | 收集全部并行结果；至少一个 Analyst 可用即继续；缺失报告进入 warnings |
| InvestmentDebate | 单边论点可用时继续并允许 `INSUFFICIENT_DATA`；双方失败时跳过辩论；轮数由 Java 控制 |
| Recommendation | action 归一化；无法解析时 `HOLD`；动作由 Java 绑定当前目标 |
| RiskManagement | 三个角色独立提交；至少一个可用即继续；全部失败时最终只允许 `HOLD/SKIP` |
| FinalReport | decision 是最后一道结构字段；无法解析时 `SKIP`；reasoning 只展示，不参与身份或执行参数生成 |

每个节点必须在实际 LLM 调用边界捕获并记录完整异常，日志至少包含 runId、targetId、clientId、nodeName 和堆栈；中间节点记录 `EXECUTION_FAILED` 后不得阻止其他并行节点提交。

节点状态统一为 `VALID/DEGRADED/EXECUTION_FAILED/SAFE_FALLBACK`。身份边界使用独立审计事件 `TOOL_TARGET_OVERRIDDEN/IDENTITY_BOUNDARY_VIOLATION`。

## 5. 决策信号与字段迁移

### 5.1 统一未知值

状态机、`RatingEngine`、Recommendation Prompt 和 Portfolio Prompt 统一读取：

```text
DecisionSignal<T>
  status: AVAILABLE | UNAVAILABLE
  value: 仅 AVAILABLE 时存在
  source: LLM_V2 | AUTHORITATIVE_INPUT | DETERMINISTIC_V3 | DERIVED_V3
  algorithmVersion: 确定性算法必填
  reason: UNAVAILABLE 或降级原因
```

- 未知 rating 只能表示为 `UNAVAILABLE`，不能使用 `null/0/3`；
- `0` 不是合法 rating；`3` 只表示算法实际计算出的中性结果；
- 旧 VO 的 nullable 投影不具备决策语义，不得再直接拆箱；
- V3 不存在的旧字段从 V3 输出模型中省略，禁止置空后继续交给旧消费者。

### 5.2 字段来源与消费

| 节点/字段组 | V3 来源与缺失语义 | 决策规则 |
|---|---|---|
| 6002 rating | `fundamental-rating-v1`；覆盖不足为 `UNAVAILABLE` | 进入 RatingEngine |
| 6002 content/rawData | LLM 完整正文；权威 `FundamentalDataVO` 快照 | 正文供下游，rawData 供评分和事实输入 |
| 6003 rating/trendSignal | `technical-signal-v1`；必要指标缺失为 `UNAVAILABLE` | 进入 RatingEngine/Recommendation |
| 6003 content/indicators | LLM 完整正文；权威 `TechnicalIndicatorsVO` 快照 | 不解析正文趋势词 |
| 6004 rating/sentimentScore | `sentiment-rating-v1`；sentimentScore 直接取权威 overallScore | 缺失不从正文反推 |
| 6005 rating/overallSentiment | `news-rating-v1`；无有效分数为 `UNAVAILABLE` | 进入 RatingEngine/Recommendation |
| 6005 newsItems/dataQuality/sourceIds | 权威新闻快照；Java 计算覆盖度并收集 source ids | dataQuality 进入 warnings；原始证据保留 |
| 6005 confidence | V3 不生成 LLM confidence；Java 单独记录数据覆盖度 | 不作为决策置信度，不用固定值伪装 |
| 所有 Analyst content | trim 后非空的完整 LLM 正文 | 作为下游叙事输入，不要求 V2/V3 字面一致 |
| Analyst 旧列表字段 | `keyFindings/keyPatterns/keySentiments/newsThemes/events/riskWarnings` 不在 V3 生成 | 信息保留在 content；不得用空列表表示“不存在” |
| 6006/6007 | role 由 Java 注入；V2 完整转换、V3 原文进入 rawText | 下游只读取 `NarrativeNodeResult` |
| 6010～6012 | role 由 Java 注入；V2 riskScore/items/mitigations 完整转换，V3 只保留正文 | V3 riskScore 为 `UNAVAILABLE`，不得默认 3 |
| RiskDebate 聚合字段 | V3 不伪造 riskLevel/riskItems/mitigations | 空值不能解释为低风险或无风险 |
| 6008 | LLM recommendation/reasoning；Java 派生 status/overallScore/needMoreDebate/warnings | overallScore 映射版本化并可审计 |
| 6013 | LLM action/rationale | V3 不生成仓位、价格、止损、止盈、周期和风险收益比 |
| 6009 | LLM decision/reasoning；Java 生成 overallRating/confidence/warnings | reasoning 仅展示；decision 由 Java 绑定目标 |
| 所有 targetEcho | V3 不生成；展示身份来自 `TargetContext` | 不参与 V3 决策 |

6008 派生规则：

```text
recommendation: BUY=2, HOLD=0, SELL=-2, INSUFFICIENT_DATA=UNAVAILABLE
status: INSUFFICIENT_DATA 或 DECIDED
needMoreDebate: currentRound + 1 < maxRounds && bull/bear 本轮均有效
```

### 5.3 确定性算法

所有算法把版本号写入审计结果；阈值变化必须新建版本。

#### `fundamental-rating-v1`

ROE、毛利率、净利率、营收增长率各计 0～2 分：

- ROE：`>20/+2, >10/+1`；
- 毛利率：`>40/+2, >20/+1`；
- 净利率：`>20/+2, >10/+1`；
- 营收增长率：`>15/+2, >5/+1`。

```text
rating = roundHalfUp(1 + 4 * earnedPoints / (2 * availableDimensions))
rating = clamp(rating, 1, 5)
```

至少两个维度可用才计算；缺失维度不按 0 分参与。

#### `technical-signal-v1`

要求 `rsi6/macdHistogram/ma5/ma20` 全部存在：

```text
trendSignal = UP       when ma5 > ma20 and macdHistogram > 0
trendSignal = DOWN     when ma5 < ma20 and macdHistogram < 0
trendSignal = SIDEWAYS otherwise

score = RSI(30..70:2, other:1)
      + MACD(>0:2, <=0:1)
      + MA(ma5>ma20:2, other:0)
      + Trend(UP:2, SIDEWAYS:1, DOWN:0)
rating = clamp(floor(score / 2) + 2, 1, 5)
```

任一必要指标缺失时 rating/trendSignal 均为 `UNAVAILABLE`。现有布林带百分位与中文趋势词混用逻辑不能直接复用。

#### `sentiment-rating-v1`

输入为 `overallScore/fearGreedIndex/bullRatio`，至少两个维度存在才计算：

```text
score = overallScore(>0.6:+3, >0.4:+2, >0.2:+1)
      + fearGreedIndex([40,60]:+2, other:+1)
      + bullRatio(>0.6:+1)
rating = clamp(floor(score / 2) + 2, 1, 5)
```

`sentimentScore` 直接取 overallScore，不从 rating 反推。

#### `news-rating-v1`

仅使用非空 `NewsItemVO.sentimentScore` 的平均值：

```text
rating: >0.5 => 5, >0.2 => 4, >-0.2 => 3, >-0.5 => 2, else 1
overallSentiment: >0.3 => positive, >-0.3 => mixed, else negative
```

无有效分数时两个信号均为 `UNAVAILABLE`。

### 5.4 RatingEngine 与消费者

- RatingEngine 只收集 `AVAILABLE` 的 Analyst rating；至少一个可用时等权平均，零个时 overallRating=`UNAVAILABLE`、decision=`HOLD`、confidence=`LOW`；
- 6008 overallScore 可用时沿用 `overallScore * 0.25` 调整；
- riskScore 可用时沿用 `(riskScore - 3) * 0.1` 调整；V3 riskScore 不可用时不调整并写 warning；
- adjustedRating 限制在 `[1,5]`；有效 Analyst 少于两个时 confidence 强制 `LOW`；
- V2 信号 source=`LLM_V2`，V3 使用 `DETERMINISTIC_V3/DERIVED_V3`；同一 run 禁止混算；
- V3 激活前在 V2 流量上 shadow 计算差异，shadow 值不得影响 V2 决策。

V3 激活前必须完成以下消费者迁移：

- RatingEngine 和所有决策 Prompt 改读 `DecisionSignalSet`；
- Debate/Risk 历史、Research/Portfolio Prompt 改读 `NarrativeNodeResult`；
- Risk Prompt 只读取 action/rationale、权威行情指标和风险正文；
- SSE 输出 role/rawText 及独立的 mode/validationStatus/schemaVersion；
- `TradingResultVO`、缓存和 Markdown 使用版本化模型；不可用信号显示 `N/A（原因）`，不显示 `null/5`、`0/5` 或虚构 `3/5`；
- 任何消费者仍读取 V3 已移除字段时，禁止激活 V3。

最终结果必须记录 mode、signal source、algorithmVersion、available analyst count 和 unavailable reasons。

## 6. Prompt 与版本兼容

### 6.1 Prompt 契约

数据库保留 V1 归档和 V2 回滚版本，新增 V3；禁止覆盖 V2 正文。占位符按 `PromptContractMode + promptId` 精确维护：

| clientId | V2 必须占位符 | V3 必须占位符 |
|---|---|---|
| `6002～6005` | `targetContext, stockData, outputContract` | `targetContext, stockData` |
| `6006～6007` | `targetContext, analystReports, debateHistory, outputContract` | `targetContext, analystReports, debateHistory` |
| `6008` | `targetContext, analystReports, debateHistory, validationStatus, currentRound, outputContract` | 同左，但使用 `minimalOutputContract` 替代 `outputContract` |
| `6009` | `targetContext, analystReports, debateHistory, riskReports, validationStatus, outputContract` | 同左，但使用 `minimalOutputContract` 替代 `outputContract` |
| `6010～6012` | `targetContext, investmentPlan, riskReports, outputContract` | `targetContext, investmentPlan, riskReports` |
| `6013` | `targetContext, analystReports, debateHistory, validationStatus, outputContract` | 同左，但使用 `minimalOutputContract` 替代 `outputContract` |

V3 叙事 Prompt 不得包含任何 contract；三个决策 Prompt 的 `minimalOutputContract` 必须由对应两字段 DTO 生成，不能复用旧 DTO 后把字段标成可选。

### 6.2 快照和渲染

- `TradingPromptSnapshotFactory` 读取完整 12 条记录，验证版本一致后绑定唯一 `PromptContractMode`；
- mode 在 run 生命周期内冻结，不能按节点猜测或切换；
- renderer 按 mode 校验实际占位符集合必须与契约完全相等；
- V2 注入完整 `outputContract`；V3 叙事节点不注入 contract，决策节点只注入 `minimalOutputContract`；
- 版本、占位符或 contract 不匹配时在激活/创建快照阶段失败。

数据库 V3 Prompt 负责改变 LLM 指令；Java 代码负责 mode、占位符、注入和解析。两侧必须同时发布。

### 6.3 原子激活

1. 发布兼容 V2/V3 的代码；
2. 插入未激活的完整 12 条 V3 Prompt；
3. 执行静态校验和集成测试；
4. 在单个事务内激活完整 V3 集合；
5. 新 run 使用 V3，运行中的 V2 继续使用冻结快照。

禁止混合激活 V2/V3。双模式代码必须保留到对应模式的活动 run、重试和可恢复快照全部排空。

## 7. 回滚

### 7.1 Prompt 回滚

在事务内重新激活完整 V2 集合。新 run 使用 `STRICT_V2`；运行中的 V3 继续使用冻结快照。Prompt 回滚只切换新流量，不等于可以立即部署仅支持 V2 的旧代码。

### 7.2 正常代码回滚

仅在以下条件全部满足后部署仅支持 V2 的旧代码：

- 完整 V2 Prompt 集合已激活；
- `active_relaxed_v3_runs=0`；
- V3 重试、延迟任务和非终态可恢复快照均为 0；
- 新 V2 smoke run 已通过。

### 7.3 紧急代码回滚

无法等待 V3 排空时：

1. 激活完整 V2 集合，阻止新 V3 run；
2. 将非终态 V3 run 置为 `ERROR`，节点置为 `CANCELLED`，审计原因记为 `CODE_ROLLBACK`；
3. 关闭对应 SSE，并由双模式代码拒绝迟到结果；
4. 确认无 V3 任务继续执行后再部署旧代码；
5. 需要重试时创建新的 V2 run，不在旧 V3 run 中续跑。

历史 V2/V3 报告保持可读；V3 降级状态对旧前端映射为 warning；`CODE_ROLLBACK` 只作为审计原因，不新增旧代码无法识别的终态枚举。

## 8. 测试与验收场景

| 测试域 | 必须覆盖 |
|---|---|
| Prompt/快照 | 12 节点 V2/V3 显式 fixture、混合版本拒绝、占位符精确匹配、嵌套 JSON `}}`、mode 冻结、原子激活 |
| 解析与适配 | V2 全 DTO；V3 三个最小 DTO；中文/大小写/包裹文本归一化；两个 Markdown golden file；V3 原文不改写 |
| 身份边界 | V2 targetEcho；V3 不扫描正文；TargetContext 不可变；provider effective ticker 固定；工具 ticker 覆盖与审计 |
| 决策信号 | 四个算法阈值、覆盖不足、版本；nullable 不拆箱；AVAILABLE 过滤；零信号 HOLD；debate/risk 调整 |
| Pipeline | Analyst 至少一个可用继续；Bull/Bear 单边继续；Risk 至少一个可用继续；身份边界违规终止 |
| 消费与导出 | 下游只读 `NarrativeNodeResult/DecisionSignalSet`；V2 字段无损转换；V3 省略不存在字段；SSE/缓存兼容 |
| 架构边界 | 旧 Payload 仅由 V2 adapter 引用；rawText 不能写入 TargetContext、provider 参数或执行对象 |
| 回滚 | 运行中快照不变；正常回滚排空门禁；紧急回滚终态、迟到结果和新建 V2 run |
| 清理 | dry-run 计数、门禁、幂等执行、历史数据保护、过期缓存/快照清除、清理后引用扫描 |

关键验收场景：

1. `601318 中国平安` run 的 rawText 明确分析 `001309 德明利`，正文仍可提交，但 targetId、provider 查询、缓存和最终交易标的不变；
2. LLM 工具参数指定其他 ticker，Java 覆盖为当前 targetId，并审计 original/effective ticker；
3. 正文出现其他公司价格、仓位和买卖动作，不能产生任何执行参数；
4. 弱模型返回 Markdown、中文枚举或缺字段时，中间节点降级，决策节点进入对应安全回退；
5. V2/V3 Prompt 集合、快照冻结、正常与紧急回滚均按第 6、7 节工作。

测试 fixture 必须显式标注 `STRICT_V2` 或 `RELAXED_V3`，不得用生产 `requiredPlaceholders()` 动态生成期望值。

## 9. 实施顺序

1. 修正 clientId 观测映射，并补齐每个节点 LLM 调用异常日志；
2. 增加 `DecisionSignalSet` 和四个版本化算法，在 V2 流量上 shadow 计算；
3. 增加 `NarrativeNodeResult`、两个 V2-to-Markdown adapter 和架构边界测试；
4. 迁移 RatingEngine、状态机、Prompt、历史、SSE、缓存、Result VO 和 Markdown 消费者；
5. 加固 TargetContext、provider/tool ticker、最终动作和身份审计边界；
6. 增加 V2/V3 mode、精确占位符校验和双模式解析；
7. 回退 9 个叙事节点，缩减 3 个决策 DTO；
8. 插入 V3 Prompt，完成单元、集成、Pipeline、回滚和端到端测试；
9. 灰度激活 V3，观察信号差异、降级率、工具目标覆盖率、身份边界违规率和安全回退率；
10. 全量验收并稳定观察后，执行第 11 节冗余清理。

## 10. 验收标准

- 仅 `6008/6009/6013` 保留最小结构，LLM 顶层字段由 75 个降到 6 个；
- `TargetContext` 是唯一身份来源，所有 provider、工具 effective ticker、缓存、SSE、快照、审计、报告和最终动作均绑定该目标；
- rawText 可包含其他股票，但不能改变身份、查询范围或执行参数；
- 中间节点失败按第 4 节继续或降级，不提前关闭其他并行结果；
- 决策消费者全部读取 `DecisionSignalSet`，未知值只使用 `UNAVAILABLE`；
- Bull/Bear/Risk 的 V2/V3 下游全部读取 `NarrativeNodeResult`，V2 golden tests 证明字段无损；
- V3 不生成旧列表、价格和仓位字段，Result/Markdown 不用空值伪造兼容；
- Prompt 占位符矩阵、完整集合激活、快照冻结和两类回滚测试通过；
- 正文错股场景转为 Java 身份边界测试，V2 targetEcho 测试继续保留；
- 所有新增测试与现有 Trading Pipeline 回归测试通过。

## 11. 功能验收后的冗余清理

### 11.1 执行门禁

只有同时满足以下条件才允许物理清理：

- 第 10 节全部验收通过；
- V3 全量稳定观察窗口不短于最长 run 生命周期、重试 TTL 和恢复快照 TTL；
- `active_strict_v2_runs=0`，V2 重试和非终态恢复快照均为 0；
- 发布负责人关闭 V2 代码回滚窗口；
- 所有消费者完成 V3 迁移，历史 V2 Prompt/报告只读方案已验证。

门禁不满足时只允许 dry-run 和停止新增冗余。

### 11.2 Dry-run 和执行

dry-run 报告至少包含：

- 旧 Payload/Report 字段、`STRICT_V2`、大型 contract、正文实体扫描器和旧字典的代码引用；
- V1/V2/V3 Prompt 的 active/archive/重复/孤立数量；
- 各 mode 的活动 run、重试、快照、缓存和双写记录；
- shadow 明细保留期；
- 拟删除、归档和保留对象的数量及主键范围。

人工确认报告后按顺序执行：

1. 停止旧字段双写、V2 shadow 输入和临时开关；
2. 删除无运行时消费者的 V2 解析/校验、旧 DTO、旧 schema、正文实体扫描代码和配置；必要历史类型移入只读 legacy 边界；
3. 仅保留完整 V3 Prompt active；V1/V2 改为 archive，不删除正文、版本、hash 和激活审计；
4. 按 runId/outputMode 精确清理过 TTL 的 V2 快照、缓存、失败重试和临时结果；
5. 保留 shadow 聚合报告后，按保留期删除逐 run 明细；
6. 停止 V3 旧字段投影；历史 V2 报告保持原样；
7. 删除临时迁移测试和 feature flag，保留历史只读、V3 主路径及清理门禁测试。

清理命令默认 `--dry-run`；物理清理要求 `--execute` 和已确认批次号。数据库使用可重入小批次事务，缓存使用精确 namespace；每批记录数量、主键边界和备份位置，失败立即停止。

### 11.3 保留和完成标准

必须保留：

- 已发布 Prompt 的正文、版本、hash 和激活审计；
- 历史 V2/V3 报告及 mode、signal source、algorithmVersion；
- TargetContext、工具 original/effective ticker、身份边界和最终决策审计；
- 清理报告、审批记录和未过保留期的运行日志。

完成标准：

- 新 V3 run 不再写旧字段、旧 schema 或 V2 专用缓存；
- 生产路径不再引用旧 nullable rating、旧价格仓位字段和大型 V2 DTO；
- 仅一套完整 V3 Prompt active，过 TTL 的 V2 运行数据为 0；
- 历史 V2 报告可读，V3 报告与导出无回归；
- 再次 dry-run 返回无待清理冗余，并生成最终报告。

## 12. 风险与取舍

- **正文语义风险**：V3 rawText 仍可能分析错误公司并被下游引用；系统只保证错误正文不能突破 Java 身份、查询和执行边界。
- **槽位数据减少**：UI/统计不能继续读取 `keyFindings/riskItems` 等旧结构；正文承载叙事，确定性信号承载决策，不创建空列表。
- **安全回退偏保守**：`HOLD/SKIP/INSUFFICIENT_DATA` 会降低决策覆盖率，但未知值不得伪装成中性 3 分。
- **双模式复杂度**：这是灰度和回滚的临时代价；对应 mode 的 run、重试、快照排空且回滚窗口关闭后，按第 11 节删除兼容代码。
