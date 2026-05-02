# 股票智能分析 Agent — Spring AI 移植版设计文档

> **项目代号**：Spring Trading Agent（STA）
> **版本**：v1.0
> **日期**：2026-04-21
> **状态**：实施中

---

## 📊 任务总览

| Phase | 内容 | 任务数 | 已完成 |
|-------|------|--------|--------|
| Phase 0 | 模块骨架、配置、Domain 模型 | 11 | 11 |
| Phase 1 | IntentRoutingNode + 数据 Provider | 8 | 8 |
| Phase 2 | 4 个分析师节点（Mock数据） | 9 | 9 |
| Phase 3 | 多空辩论团队 + Research Manager | 4 | 4 |
| Phase 4 | Trader + 风控团队 + Portfolio Manager | 6 | 6 |
| Phase 5 | 流式 SSE 集成 + 可观测性 | 3 | 3 |
| Phase 6 | 真实数据源接入（Yahoo Finance） | 3 | 3 |
| Phase 7 | Prompt 调优 + 五档评分机制 | 3 | 3 |
| Phase 8 | 意图置信度优化 + 独立端点 | 2 | 2 |
| **合计** | | **49** | **49** |

---

## 🎯 Phase 0：模块骨架、配置、Domain 模型

### 阶段目标

新建完整的 Maven 多模块结构，创建所有值对象（VO）和枚举（Enum），建立 STA 模块与现有工程的集成点。

---

**[T0-01]** `Status: [PASS]` **创建 ai-agent-study-trading 父 POM**

- **文件**: `ai-agent-study-trading/pom.xml`
- **内容**: 定义 `<groupId>denny.ai.agent</groupId>`、`<artifactId>ai-agent-study-trading</artifactId>`、`<packaging>pom</packaging>`，声明 3 个子模块：`ai-agent-study-trading-api`、`ai-agent-study-trading-domain`、`ai-agent-study-trading-infra`
- **状态标记**: `[ ]` = 待做 · `[PASS]` = 已完成

---

**[T0-02]** `Status: [PASS]` **创建 trading-api 子模块 POM**

- **文件**: `ai-agent-study-trading/ai-agent-study-trading-api/pom.xml`
- **依赖**: `ai-agent-study-types`、`ai-agent-study-domain`
- **状态标记**: `[ ]` = 待做 · `[PASS]` = 已完成

---

**[T0-03]** `Status: [PASS]` **创建 trading-domain 子模块 POM**

- **文件**: `ai-agent-study-trading/ai-agent-study-trading-domain/pom.xml`
- **依赖**: `ai-agent-study-domain`、`ai-agent-study-trading-api`
- **状态标记**: `[ ]` = 待做 · `[PASS]` = 已完成

---

**[T0-04]** `Status: [PASS]` **创建 trading-infra 子模块 POM**

- **文件**: `ai-agent-study-trading/ai-agent-study-trading-infra/pom.xml`
- **依赖**: `ai-agent-study-trading-domain`、`yahoofinance-api`（可选，Phase 6 启用）
- **状态标记**: `[ ]` = 待做 · `[PASS]` = 已完成

---

**[T0-05]** `Status: [PASS]` **在父工程 pom.xml 中注册 trading 模块**

- **文件**: `ai-agent-study/pom.xml`（现有文件，追加 `<module>` 声明）
- **内容**: 在 `<modules>` 中添加 `<module>ai-agent-study-trading</module>`
- **状态标记**: `[ ]` = 待做 · `[PASS]` = 已完成

---

**[T0-06]** `Status: [PASS]` **创建 `IntentEnumVO` 意图枚举**

- **文件**: `ai-agent-study-trading/ai-agent-study-trading-api/src/main/java/denny/ai/agent/trading/api/vo/IntentEnumVO.java`
- **枚举值**: `STOCK_ANALYSIS`（股票分析）、`GENERAL_CHAT`（普通对话）、`UNKNOWN`（未知）
- **状态标记**: `[ ]` = 待做 · `[PASS]` = 已完成

---

**[T0-07]** `Status: [PASS]` **创建 `AnalystTypeEnum` 分析师类型枚举**

- **文件**: `ai-agent-study-trading/ai-agent-study-trading-api/src/main/java/denny/ai/agent/trading/api/vo/AnalystTypeEnum.java`
- **枚举值**: `FUNDAMENTAL`（基本面）、`TECHNICAL`（技术面）、`SENTIMENT`（情绪面）、`NEWS`（新闻面）
- **状态标记**: `[ ]` = 待做 · `[PASS]` = 已完成

---

**[T0-08]** `Status: [PASS]` **创建 `TradeDecisionEnum` 交易决策枚举**

- **文件**: `ai-agent-study-trading/ai-agent-study-trading-api/src/main/java/denny/ai/agent/trading/api/vo/TradeDecisionEnum.java`
- **枚举值**: `BUY`（买入）、`SELL`（卖出）、`HOLD`（持有）、`SKIP`（跳过）
- **状态标记**: `[ ]` = 待做 · `[PASS]` = 已完成

---

**[T0-09]** `Status: [PASS]` **创建 `ConfidenceEnum` 置信度枚举**

- **文件**: `ai-agent-study-trading/ai-agent-study-trading-api/src/main/java/denny/ai/agent/trading/api/vo/ConfidenceEnum.java`
- **枚举值**: `HIGH`（高）、`MEDIUM`（中）、`LOW`（低）
- **状态标记**: `[ ]` = 待做 · `[PASS]` = 已完成

---

**[T0-10]** `Status: [PASS]` **创建 `StockAnalysisRequestVO` 股票分析请求值对象**

- **文件**: `ai-agent-study-trading/ai-agent-study-trading-api/src/main/java/denny/ai/agent/trading/api/vo/StockAnalysisRequestVO.java`
- **字段**: `ticker`（String，股票代码）、`tradeDate`（String，分析日期）、`selectedAnalysts`（List<AnalystTypeEnum>，启用的分析师列表）、`maxDebateRounds`（int，辩论轮次，默认2）、`sessionId`（String，会话ID）
- **状态标记**: `[ ]` = 待做 · `[PASS]` = 已完成

---

**[T0-11]** `Status: [PASS]` **创建 `TradingAgentProperties` 配置属性类**

- **文件**: `ai-agent-study-trading/ai-agent-study-trading-domain/src/main/java/denny/ai/agent/trading/domain/config/TradingAgentProperties.java`
- **字段**: `enabled`（boolean，默认true）、`defaultAnalysts`（List<String>）、`maxDebateRounds`（int，默认2）、`rating` 配置子对象（`buyThreshold=3.5`、`sellThreshold=2.0`）
- **注解**: `@ConfigurationProperties(prefix = "spring.ai.trading")`
- **状态标记**: `[ ]` = 待做 · `[PASS]` = 已完成

---

## 🎯 Phase 1：IntentRoutingNode + 数据 Provider（Mock）

### 阶段目标

实现意图识别与切槽节点（前置路由），以及股票数据获取的基础设施层（Provider 接口 + Mock 实现）。

### ⚠️ 数据策略说明

> **Phase 1-5 使用 Mock 数据跑通链路，Phase 6 再接入 Yahoo Finance 真实数据。**
>
> Mock 数据是硬编码的假数据（股票代码、价格、PE 等写死在代码里），目的是：
> - 不依赖网络，快速验证 Agent 链路是否正确
> - 后续 Phase 6 替换 Provider 实现时，上层节点逻辑**无需改动**（接口不变）
>
> 当前使用的 Mock 实现：`MockStockDataProvider`（T1-02）
> 后续替换为：`YahooFinanceStockDataProvider`（Phase 6, T6-01）

---

**[T1-01]** `Status: [PASS]` **创建 `IStockDataProvider` 股票数据 Provider 接口**

- **文件**: `ai-agent-study-trading/ai-agent-study-trading-infra/src/main/java/denny/ai/agent/trading/infra/provider/IStockDataProvider.java`
- **接口方法**:
  - `StockInfoVO getStockInfo(String ticker)` — 股票基本信息
  - `List<OHLCVBarVO> getHistoricalBars(String ticker, String startDate, String endDate)` — OHLCV 日线数据
  - `TechnicalIndicatorsVO getTechnicalIndicators(String ticker, String startDate, String endDate)` — 技术指标
  - `FundamentalDataVO getFundamentalData(String ticker)` — 财务数据
  - `List<NewsItemVO> getNews(String ticker, int limit)` — 新闻
  - `SentimentDataVO getSentiment(String ticker)` — 情绪数据
- **状态标记**: `[ ]` = 待做 · `[PASS]` = 已完成

---

**[T1-02]** `Status: [PASS]` **创建 `MockStockDataProvider` Mock 数据实现**

- **文件**: `ai-agent-study-trading/ai-agent-study-trading-infra/src/main/java/denny/ai/agent/trading/infra/provider/MockStockDataProvider.java`
- **实现**: 实现 `IStockDataProvider`，返回预定义的 Mock 数据（硬编码 NVDA/AAPL 等股票数据），用于 Phase 2-4 开发调试
- **注意事项**: 返回数据需包含合理的模拟数值（股价、PE、ROE、MACD、RSI 等）
- **状态标记**: `[ ]` = 待做 · `[PASS]` = 已完成

---

**[T1-03]** `Status: [PASS]` **创建 `TradingDataSourceProperties` 数据源配置类**

- **文件**: `ai-agent-study-trading/ai-agent-study-trading-infra/src/main/java/denny/ai/agent/trading/infra/config/TradingDataSourceProperties.java`
- **字段**: `provider`（String，可选值：mock/yahoo-finance/alpha-vantage）、`cache` 子对象（`historicalBarsTtl`、`fundamentalDataTtl`、`newsTtl`）
- **注解**: `@ConfigurationProperties(prefix = "spring.ai.trading.data-source")`
- **状态标记**: `[ ]` = 待做 · `[PASS]` = 已完成

---

**[T1-04]** `Status: [PASS]` **创建 `ProviderFactory` 数据 Provider 工厂类**

- **文件**: `ai-agent-study-trading/ai-agent-study-trading-infra/src/main/java/denny/ai/agent/trading/infra/provider/ProviderFactory.java`
- **职责**: 根据 `TradingDataSourceProperties.provider` 配置，创建对应的 `IStockDataProvider` 实例（策略模式）
- **状态标记**: `[ ]` = 待做 · `[PASS]` = 已完成

---

**[T1-05]** `Status: [PASS]` **创建 `IntentRoutingPrompt` 意图识别 Prompt 常量**

- **文件**: `ai-agent-study-trading/ai-agent-study-trading-domain/src/main/java/denny/ai/agent/trading/domain/prompt/IntentRoutingPrompt.java`
- **内容**: 定义意图识别的 System Prompt，包含识别规则、股票代码提取示例、置信度判断标准
- **状态标记**: `[ ]` = 待做 · `[PASS]` = 已完成

---

**[T1-06]** `Status: [PASS]` **创建 `IntentRoutingService` 意图识别服务**

- **文件**: `ai-agent-study-trading/ai-agent-study-trading-domain/src/main/java/denny/ai/agent/trading/domain/service/IntentRoutingService.java`
- **方法**:
  - `IntentRoutingResult route(String userMessage)` — 调用 LLM 进行意图分类
  - `StockAnalysisRequestVO parseStockRequest(String userMessage, IntentEnumVO intent)` — 解析股票代码和日期
- **依赖**: Spring AI `ChatClient`
- **状态标记**: `[ ]` = 待做 · `[PASS]` = 已完成

---

**[T1-07]** `Status: [PASS]` **创建 `IntentRoutingNode` 意图路由节点**

- **文件**: `ai-agent-study-trading/ai-agent-study-trading-domain/src/main/java/denny/ai/agent/trading/domain/node/IntentRoutingNode.java`
- **继承**: `AbstractExecuteSupport`（现有框架）
- **职责**: 接收 `ExecuteCommandEntity.message`，调用 `IntentRoutingService`，根据结果设置路由标志到 `DynamicContext`
- **路由逻辑**:
  - `STOCK_ANALYSIS` + 高置信度 → 设置 `dynamicContext.setValue("trading_request", vo)`
  - `STOCK_ANALYSIS` + 中置信度 → 设置询问标志
  - `GENERAL_CHAT` 或低置信度 → 不修改，正常流转到 Step1AnalyzerNode
- **状态标记**: `[ ]` = 待做 · `[PASS]` = 已完成

---

**[T1-08]** `Status: [PASS]` **在现有 Agent 编排入口注册 IntentRoutingNode**

- **文件**: 现有 `ai-agent-study-domain` 中负责编排的类（需先查看现有代码确定注入点）
- **修改**: 在 `Step1AnalyzerNode` 之前插入 `IntentRoutingNode`
- **前置条件**: T1-07 完成
- **状态标记**: `[ ]` = 待做 · `[PASS]` = 已完成

---

## 🎯 Phase 2：4 个分析师节点（基础版，Mock数据）

### 阶段目标

实现 4 个分析师节点（基本面/技术面/情绪面/新闻面），使用 Mock 数据，通过流式 SSE 输出各节点的分析进展。

---

**[T2-01]** `Status: [PASS]` **创建 `TradingContextVO` 交易上下文值对象**

- **文件**: `ai-agent-study-trading/ai-agent-study-trading-domain/src/main/java/denny/ai/agent/trading/domain/vo/TradingContextVO.java`
- **字段**:
  - `stockInfo`（StockInfoVO）
  - `fundamentalReport`（FundamentalReportVO，nullable）
  - `technicalReport`（TechnicalReportVO，nullable）
  - `sentimentReport`（SentimentReportVO，nullable）
  - `newsReport`（NewsReportVO，nullable）
  - `investmentDebate`（InvestmentDebateVO）
  - `investmentPlan`（InvestmentPlanVO）
  - `riskDebate`（RiskDebateVO）
  - `finalDecision`（FinalTradeDecisionVO）
- **状态标记**: `[ ]` = 待做 · `[PASS]` = 已完成

---

**[T2-02]** `Status: [PASS]` **创建 `StockInfoVO` 股票基本信息值对象**

- **文件**: `ai-agent-study-trading/ai-agent-study-trading-api/src/main/java/denny/ai/agent/trading/api/vo/StockInfoVO.java`
- **字段**: `ticker`、`name`、`exchange`、`currentPrice`、`peRatio`、`pbRatio`、`marketCap`、`volume`
- **状态标记**: `[ ]` = 待做 · `[PASS]` = 已完成

---

**[T2-03]** `Status: [PASS]` **创建 4 个 ReportVO 值对象**

- **文件组**:
  - `FundamentalReportVO.java` — 字段：`rating`（int 1-5）、`keyFindings`（List<String>）、`riskWarnings`（List<String>）、`summary`（String）、`rawData`（FundamentalDataVO）
  - `TechnicalReportVO.java` — 字段：`rating`（int 1-5）、`trendSignal`（String）、`keyPatterns`（List<String>）、`summary`（String）、`indicators`（TechnicalIndicatorsVO）
  - `SentimentReportVO.java` — 字段：`rating`（int 1-5）、`sentimentScore`（double -1~1）、`keySentiments`（List<String>）、`summary`（String）
  - `NewsReportVO.java` — 字段：`rating`（int 1-5）、`newsItems`（List<NewsItemVO>）、`overallSentiment`（String）、`summary`（String）
- **状态标记**: `[ ]` = 待做 · `[PASS]` = 已完成

---

**[T2-04]** `Status: [PASS]` **创建 `TradingRootNode` 交易 Agent 根节点**

- **文件**: `ai-agent-study-trading/ai-agent-study-trading-domain/src/main/java/denny/ai/agent/trading/domain/node/TradingRootNode.java`
- **继承**: `AbstractExecuteSupport`
- **职责**:
  1. 从 `DynamicContext` 中获取 `trading_request`
  2. 初始化 `TradingContextVO`
  3. 调用 `IStockDataProvider` 获取 `StockInfoVO`
  4. 根据配置决定并行触发哪些分析师节点
  5. 管理辩论轮次计数
- **状态标记**: `[ ]` = 待做 · `[PASS]` = 已完成

---

**[T2-05]** `Status: [PASS]` **创建 `FundamentalAnalystNode` 基本面分析节点**

- **文件**: `ai-agent-study-trading/ai-agent-study-trading-domain/src/main/java/denny/ai/agent/trading/domain/node/FundamentalAnalystNode.java`
- **继承**: `AbstractExecuteSupport`
- **职责**:
  1. 调用 `IStockDataProvider.getFundamentalData()` 获取财务数据
  2. 使用 `ChatClient` + System Prompt（见设计文档 7.1 节）生成分析报告
  3. 生成 `FundamentalReportVO` 并写入 `TradingContextVO`
  4. 通过 `sendSseResult()` 发送流式进度事件（`analyst_start` → `analyst_progress` → `analyst_report`）
- **Prompt 模板**: 参考设计文档第 7.1 节
- **状态标记**: `[ ]` = 待做 · `[PASS]` = 已完成

---

**[T2-06]** `Status: [PASS]` **创建 `TechnicalAnalystNode` 技术分析节点**

- **文件**: `ai-agent-study-trading/ai-agent-study-trading-domain/src/main/java/denny/ai/agent/trading/domain/node/TechnicalAnalystNode.java`
- **继承**: `AbstractExecuteSupport`
- **职责**: 与 T2-05 类似，调用 `IStockDataProvider.getTechnicalIndicators()`，分析 MA/RSI/MACD/KDJ/布林带，生成 `TechnicalReportVO`
- **Prompt 重点**: 技术指标解读、趋势判断、买卖信号识别
- **状态标记**: `[ ]` = 待做 · `[PASS]` = 已完成

---

**[T2-07]** `Status: [PASS]` **创建 `SentimentAnalystNode` 情绪分析节点**

- **文件**: `ai-agent-study-trading/ai-agent-study-trading-domain/src/main/java/denny/ai/agent/trading/domain/node/SentimentAnalystNode.java`
- **继承**: `AbstractExecuteSupport`
- **职责**: 调用 `IStockDataProvider.getSentiment()`，分析市场情绪（社交媒体/分析师评级），生成 `SentimentReportVO`
- **Prompt 重点**: 情绪分解读、短期市场心理、舆情风险
- **状态标记**: `[ ]` = 待做 · `[PASS]` = 已完成

---

**[T2-08]** `Status: [PASS]` **创建 `NewsAnalystNode` 新闻分析节点**

- **文件**: `ai-agent-study-trading/ai-agent-study-trading-domain/src/main/java/denny/ai/agent/trading/domain/node/NewsAnalystNode.java`
- **继承**: `AbstractExecuteSupport`
- **职责**: 调用 `IStockDataProvider.getNews()`，分析近期新闻和公告，生成 `NewsReportVO`
- **Prompt 重点**: 新闻事件对股价的影响、公告解读、研报摘要
- **状态标记**: `[ ]` = 待做 · `[PASS]` = 已完成

---

**[T2-09]** `Status: [PASS]` **创建分析师节点的 Prompt 常量类**

- **文件**: `ai-agent-study-trading/ai-agent-study-trading-domain/src/main/java/denny/ai/agent/trading/domain/prompt/AnalystPrompts.java`
- **内容**: 集中管理 4 个分析师的 System Prompt 模板，使用占位符 `{data}` 注入数据
- **状态标记**: `[ ]` = 待做 · `[PASS]` = 已完成

---

## 🎯 Phase 3：多空辩论团队 + Research Manager

### 阶段目标

实现 Bull/Bear 研究员的多轮辩论节点，以及 Research Manager 的综合判断节点。

---

**[T3-01]** `Status: [PASS]` **创建 `InvestmentDebateVO` 多空辩论状态值对象**

- **文件**: `ai-agent-study-trading/ai-agent-study-trading-domain/src/main/java/denny/ai/agent/trading/domain/vo/InvestmentDebateVO.java`
- **字段**:
  - `currentRound`（int，当前辩论轮次）
  - `maxRounds`（int，最大辩论轮次）
  - `bullHistory`（List<String>，多头研究员论点历史）
  - `bearHistory`（List<String>，空头研究员论点历史）
  - `history`（List<String>，完整辩论历史）
  - `judgeDecision`（String，研究主管判断结果）
  - `bullOpinion`（String，当前多头观点）
  - `bearOpinion`（String，当前空头观点）
- **状态标记**: `[ ]` = 待做 · `[PASS]` = 已完成

---

**[T3-02]** `Status: [PASS]` **创建 `BullResearcherNode` 多头研究员节点**

- **文件**: `ai-agent-study-trading/ai-agent-study-trading-domain/src/main/java/denny/ai/agent/trading/domain/node/BullResearcherNode.java`
- **继承**: `AbstractExecuteSupport`
- **职责**:
  1. 读取 `TradingContextVO` 中所有分析师报告
  2. 调用 `ChatClient` + 多头研究员 Prompt（见设计文档 7.2 节）生成看多论点
  3. 将论点追加到 `InvestmentDebateVO.bullHistory`
  4. SSE 发送 `debate_round` 事件（多头视角）
- **Prompt 重点**: 从分析师报告中挖掘一切支持买入的论据，评估潜在收益，评估对手观点的薄弱环节
- **状态标记**: `[ ]` = 待做 · `[PASS]` = 已完成

---

**[T3-03]** `Status: [PASS]` **创建 `BearResearcherNode` 空头研究员节点**

- **文件**: `ai-agent-study-trading/ai-agent-study-trading-domain/src/main/java/denny/ai/agent/trading/domain/node/BearResearcherNode.java`
- **继承**: `AbstractExecuteSupport`
- **职责**: 与 T3-02 对称，生成看空论点，追加到 `InvestmentDebateVO.bearHistory`
- **状态标记**: `[ ]` = 待做 · `[PASS]` = 已完成

---

**[T3-04]** `Status: [PASS]` **创建 `ResearchManagerNode` 研究主管节点**

- **文件**: `ai-agent-study-trading/ai-agent-study-trading-domain/src/main/java/denny/ai/agent/trading/domain/node/ResearchManagerNode.java`
- **继承**: `AbstractExecuteSupport`
- **职责**:
  1. 读取 `InvestmentDebateVO` 中当前轮次的双方论点
  2. 调用 `ChatClient`（deep_think_model）进行综合判断
  3. 决定是否需要下一轮辩论（如果当前轮 < maxRounds 且分歧明显）
  4. 若辩论结束，输出 `judgeDecision`（综合判断结果）
  5. SSE 发送 `debate_complete` 事件
- **状态标记**: `[ ]` = 待做 · `[PASS]` = 已完成

---

## 🎯 Phase 4：Trader + 风控团队 + Portfolio Manager

### 阶段目标

实现交易员节点、3 个风控辩论节点，以及组合经理的最终审批节点。

---

**[T4-01]** `Status: [PASS]` **创建 `InvestmentPlanVO` 投资计划值对象**

- **文件**: `ai-agent-study-trading/ai-agent-study-trading-domain/src/main/java/denny/ai/agent/trading/domain/vo/InvestmentPlanVO.java`
- **字段**:
  - `position`（String，仓位建议：满仓/半仓/轻仓/空仓）
  - `entryTiming`（String，入场时机描述）
  - `targetPrice`（BigDecimal，目标价）
  - `stopLossPrice`（BigDecimal，止损价）
  - `holdingPeriod`（String，持仓周期）
  - `rationale`（String，制定理由）
  - `riskLevel`（String，风险等级：HIGH/MEDIUM/LOW）
- **状态标记**: `[ ]` = 待做 · `[PASS]` = 已完成

---

**[T4-02]** `Status: [PASS]` **创建 `TraderNode` 交易员节点**

- **文件**: `ai-agent-study-trading/ai-agent-study-trading-domain/src/main/java/denny/ai/agent/trading/domain/node/TraderNode.java`
- **继承**: `AbstractExecuteSupport`
- **职责**:
  1. 汇总 `TradingContextVO` 中所有分析师报告 + `InvestmentDebateVO` 的辩论结论
  2. 调用 `ChatClient` 生成 `InvestmentPlanVO`
  3. 将结果写入 `TradingContextVO.investmentPlan`
  4. SSE 发送 `trader_plan` 事件
- **Prompt 重点**: 综合多空辩论结果，给出仓位、入场时机、目标价、止损价建议
- **状态标记**: `[ ]` = 待做 · `[PASS]` = 已完成

---

**[T4-03]** `Status: [PASS]` **创建 `RiskDebateVO` 风控辩论状态值对象**

- **文件**: `ai-agent-study-trading/ai-agent-study-trading-domain/src/main/java/denny/ai/agent/trading/domain/vo/RiskDebateVO.java`
- **字段**:
  - `currentRound`（int，当前轮次）
  - `maxRounds`（int，最大轮次）
  - `aggressiveHistory`（List<String>）
  - `conservativeHistory`（List<String>）
  - `neutralHistory`（List<String>）
  - `history`（List<String>）
  - `judgeDecision`（String，组合经理最终决策）
  - `adjustedPlan`（InvestmentPlanVO，经调整后的计划）
- **状态标记**: `[ ]` = 待做 · `[PASS]` = 已完成

---

**[T4-04]** `Status: [PASS]` **创建 3 个风控分析师节点**

- **文件组**:
  - `AggressiveRiskAnalystNode.java` — 激进风控分析师（Prompt 风格：激进，参考设计文档 7.3 节）
  - `ConservativeRiskAnalystNode.java` — 保守风控分析师（Prompt 风格：保守）
  - `NeutralRiskAnalystNode.java` — 中性风控分析师（Prompt 风格：协调平衡）
- **共同职责**:
  1. 读取 `TradingContextVO.investmentPlan`
  2. 根据自身风格给出风控意见（仓位调整/止损建议/风险提示/是否通过）
  3. 追加意见到 `RiskDebateVO` 对应历史
  4. SSE 发送 `risk_debate` 事件
- **状态标记**: `[ ]` = 待做 · `[PASS]` = 已完成

---

**[T4-05]** `Status: [PASS]` **创建 `PortfolioManagerNode` 组合经理节点**

- **文件**: `ai-agent-study-trading/ai-agent-study-trading-domain/src/main/java/denny/ai/agent/trading/domain/node/PortfolioManagerNode.java`
- **继承**: `AbstractExecuteSupport`
- **职责**:
  1. 读取 `RiskDebateVO` 中所有风控意见
  2. 调用 `ChatClient`（deep_think_model）进行最终审批
  3. 可能调整 `InvestmentPlanVO`（降低仓位/收紧止损）
  4. 输出最终交易决策写入 `TradingContextVO.finalDecision`
  5. SSE 发送 `final_decision` 事件
- **状态标记**: `[ ]` = 待做 · `[PASS]` = 已完成

---

**[T4-06]** `Status: [PASS]` **创建 `FinalTradeDecisionVO` 最终交易决策值对象**

- **文件**: `ai-agent-study-trading/ai-agent-study-trading-domain/src/main/java/denny/ai/agent/trading/domain/vo/FinalTradeDecisionVO.java`
- **字段**:
  - `decision`（TradeDecisionEnum，BUY/SELL/HOLD/SKIP）
  - `confidence`（ConfidenceEnum，HIGH/MEDIUM/LOW）
  - `ratings` — 内嵌对象：`technicalRating`（int 1-5）、`fundamentalRating`（int 1-5）、`sentimentRating`（int 1-5）、`riskRating`（int 1-5）、`overallRating`（double）
  - `rationale`（String，详细理由）
  - `adjustedPlan`（InvestmentPlanVO，最终经风控调整后的计划）
- **状态标记**: `[ ]` = 待做 · `[PASS]` = 已完成

---

## 🎯 Phase 5：流式 SSE 集成 + 可观测性

### 阶段目标

完善 SSE 流式输出链路，接入 Langfuse 可观测性，跟踪完整 Trace。

---

**[T5-01]** `Status: [PASS]` **统一 SSE 事件格式，定义 `TradingSseEventEnum`**

- **文件**: `ai-agent-study-trading/ai-agent-study-trading-api/src/main/java/denny/ai/agent/trading/api/vo/TradingSseEventEnum.java`
- **枚举值及字段**:
  - `ANALYST_START` — `analyst`（String）、`step`（int）
  - `ANALYST_PROGRESS` — `analyst`（String）、`content`（String）
  - `ANALYST_REPORT` — `analyst`（String）、`rating`（int）、`summary`（String）
  - `DEBATE_START` — `round`（int）
  - `DEBATE_ROUND` — `round`（int）、`bullOpinion`（String）、`bearOpinion`（String）
  - `DEBATE_COMPLETE` — `judgeDecision`（String）
  - `TRADER_PLAN` — `plan`（InvestmentPlanVO）
  - `RISK_DEBATE` — `round`（int）、`aggressive`（String）、`conservative`（String）、`neutral`（String）
  - `FINAL_DECISION` — `decision`（TradeDecisionEnum）、`confidence`（ConfidenceEnum）、`ratings`（AnalystRatingVO）
  - `ERROR` — `code`（String）、`message`（String）
- **状态标记**: `[ ]` = 待做 · `[PASS]` = 已完成

---

**[T5-02]** `Status: [PASS]` **在现有可观测性服务中集成 STA Trace 埋点`**

- **文件**: 现有 `ai-agent-study-infrastructure` 中的 `ObservabilityService`（或新建 `TradingObservabilityService`）
- **修改**: 参考设计文档 9.1 节，为每个 STA 节点创建对应的 Langfuse Span
- **Span 命名**: `sta.{node_type}.{node_name}`（如 `sta.analyst.fundamental`）
- **前置条件**: T2-05 ~ T4-05 所有节点已完成
- **状态标记**: `[ ]` = 待做 · `[PASS]` = 已完成

---

**[T5-03]** `Status: [PASS]` **添加 STA 专属指标埋点（Micrometer）`**

- **文件**: 新建 `ai-agent-study-trading/ai-agent-study-trading-domain/.../metrics/TradingMetrics.java`
- **指标**:
  - `sta.analyst.duration`（Timer）— 各分析师执行耗时，按 analyst type 分 tag
  - `sta.debate.rounds`（Gauge）— 实际辩论轮次
  - `sta.decision.count`（Counter）— 决策计数，按 decision（BUY/SELL/HOLD）分 tag
  - `sta.rating.gauge`（Gauge）— 各维度评分分布
- **状态标记**: `[ ]` = 待做 · `[PASS]` = 已完成

---

## 🎯 Phase 6：真实数据源接入（Yahoo Finance）

### 阶段目标

实现真实股票数据获取，从 Mock 切换到 Yahoo Finance API。

---

**[T6-01]** `Status: [PASS]` **创建 `YahooFinanceStockDataProvider` Yahoo Finance 实现**

- **文件**: `ai-agent-study-trading/ai-agent-study-trading-infra/src/main/java/denny/ai/agent/trading/infra/provider/YahooFinanceStockDataProvider.java`
- **依赖**: `com.yahoofinance-api:yahoofinance`（Maven 依赖在 T0-04 已声明）
- **实现**: 实现 `IStockDataProvider` 接口全部方法，使用 `YahooFinance` API 获取真实数据
- **异常处理**: 超时时降级到 Mock 数据（参考设计文档 10 节）
- **状态标记**: `[ ]` = 待做 · `[PASS]` = 已完成

---

**[T6-02]** `Status: [PASS]` **实现技术指标计算逻辑**

- **文件**: `ai-agent-study-trading/ai-agent-study-trading-infra/src/main/java/denny/ai/agent/trading/infra/calculator/TechnicalIndicatorCalculator.java`
- **实现**: 基于 OHLCV 数据计算 MA、EMA、MACD、RSI、KDJ、布林带等技术指标
- **依赖**: 可使用 `org.ta4j:ta4j`（Java 技术分析库）或自行实现
- **指标列表**: 参考设计文档 5.2 节
- **状态标记**: `[ ]` = 待做 · `[PASS]` = 已完成

---

**[T6-03]** `Status: [PASS]` **实现缓存策略（按设计文档 5.3 节）**

- **文件**: `ai-agent-study-trading/ai-agent-study-trading-infra/src/main/java/denny/ai/agent/trading/infra/cache/TradingDataCache.java`
- **缓存策略**:
  - 日线数据：按 `ticker+startDate+endDate` 缓存，TTL=1天
  - 财务数据：按 `ticker` 缓存，TTL=1小时
  - 新闻数据：按 `ticker` 缓存，TTL=30分钟
- **实现**: 使用 Spring Cache（`@Cacheable`）或 `Caffeine` 本地缓存
- **状态标记**: `[ ]` = 待做 · `[PASS]` = 已完成

---

## 🎯 Phase 7：Prompt 调优 + 五档评分机制

### 阶段目标

完善 Prompt 模板，建立评分量化体系，实现评分阈值触发决策逻辑。

---

**[T7-01]** `Status: [PASS]` **创建 Prompt 管理与版本化机制**

- **文件**: `ai-agent-study-trading/ai-agent-study-trading-domain/src/main/java/denny/ai/agent/trading/domain/prompt/PromptVersionManager.java`
- **功能**:
  1. 支持从配置文件加载 Prompt（支持热更新）
  2. 支持 Prompt 版本管理
  3. 支持 A/B Prompt 测试（通过配置切换版本）
- **状态标记**: `[ ]` = 待做 · `[PASS]` = 已完成

---

**[T7-02]** `Status: [PASS]` **实现评分量化引擎**

- **文件**: `ai-agent-study-trading/ai-agent-study-trading-domain/src/main/java/denny/ai/agent/trading/domain/service/RatingEngine.java`
- **逻辑**:
  1. 汇总 4 个分析师的 rating（1-5 分）
  2. 计算综合评分（加权平均）
  3. 基于 `TradingAgentProperties.rating.buyThreshold`（3.5）和 `sellThreshold`（2.0）触发决策
  4. 置信度判定（综合评分 ≥ 4.0 → HIGH，2.5~4.0 → MEDIUM，< 2.5 → LOW）
- **前置条件**: T2-03（ReportVO 含 rating 字段）
- **状态标记**: `[ ]` = 待做 · `[PASS]` = 已完成

---

**[T7-03]** `Status: [PASS]` **完善所有 Agent Prompt，实现五档评分输出**

- **文件**: 修改 `AnalystPrompts.java` 和各节点
- **目标**: 确保每个分析师节点的 Prompt 都能引导 LLM 输出结构化的评分（1-5 分）和理由
- **后置处理**: 各分析师节点在接收 LLM 响应后，解析出 rating 字段填入 ReportVO
- **状态标记**: `[ ]` = 待做 · `[PASS]` = 已完成

---

## 🎯 Phase 8：意图置信度优化 + 独立端点

### 阶段目标

完善意图识别置信度机制，新增独立的股票分析 HTTP 端点，实现完整的隐式/显式双入口。

---

**[T8-01]** `Status: [PASS]` **创建独立 HTTP 端点 `TradingAnalysisController`**

- **文件**: `ai-agent-study-trading/ai-agent-study-trading-api/src/main/java/denny/ai/agent/trading/api/controller/TradingAnalysisController.java`
- **端点**: `POST /api/v1/trading/analysis`
- **请求 DTO**: `TradingAnalysisRequestDTO`（字段同 `StockAnalysisRequestVO`）
- **响应**: `ResponseBodyEmitter`（SSE 流式输出，格式参考设计文档 6.1 节）
- **职责**: 显式调用入口，用户无需意图识别，直接指定股票代码和分析参数
- **状态标记**: `[ ]` = 待做 · `[PASS]` = 已完成

---

**[T8-02]** `Status: [PASS]` **优化意图识别置信度机制**

- **文件**: 修改 `IntentRoutingService.java`（T1-06）
- **优化内容**:
  1. 支持 3 档置信度（高/中/低）的显式输出
  2. 中置信度时，构造确认问题发送给用户（"您是想分析 {ticker} 的股票吗？"）
  3. 低置信度时，自动回退到普通对话，不阻断用户
  4. 添加置信度反馈学习（记录用户对路由结果的修正，后续可训练优化）
- **状态标记**: `[ ]` = 待做 · `[PASS]` = 已完成

---

## 📋 附录

### A. 参考资料

- TradingAgents 原版论文：https://arxiv.org/abs/2412.20138
- Spring AI 官方文档：https://docs.spring.io/spring-ai/reference/
- Yahoo Finance Java SDK：https://github.com/sstrickx/yahoofinance-api

### B. 术语表

| 术语 | 说明 |
|------|------|
| STA | Spring Trading Agent，本项目的股票分析 Agent |
| TA | Technical Analysis，技术分析 |
| FA | Fundamental Analysis，基本面分析 |
| OHLCV | Open/High/Low/Close/Volume，开盘最高收盘成交量 |
| ROE | Return on Equity，净资产收益率 |
| MACD | Moving Average Convergence Divergence，指数平滑异同移动平均线 |
| RSI | Relative Strength Index，相对强弱指标 |
| BOLL | Bollinger Bands，布林带 |

### C. 任务状态标记说明

| 标记 | 含义 |
|------|------|
| `[ ]` | 待做（TODO） |
| `[>]` | 进行中（In Progress） |
| `[PASS]` | 已完成（Done） |
| `[FAIL]` | 失败（Blocked/Failed，需人工介入） |

### D. 实施约定

- **提交规范**：每个 Task 完成后单独提交，Commit Message 格式：`sta: [T{n}-{nn}] {简要描述}`
- **代码风格**：遵循 `ai-agent-study` 现有代码风格
- **包命名**：`denny.ai.agent.trading.{api,domain,infra}.*`
- **测试要求**：每个 Provider 和 Service 类需附带单元测试（使用 JUnit 5 + Mockito）
