# Story 任务跟踪文档

> **Story 名称**：Trading 模块端到端接入 — 让底层数据接入上层 Agent 链路
> **创建日期**：2026-04-23
> **更新日期**：2026-04-24
> **背景**：`TradingRootNode` + 4 个分析师节点（基本面/技术面/情绪面/新闻面）的编排链路已写好，`TradingToolCallbacks` 的 6 个 ToolCallback Bean 已注册，`IStockDataProvider` 已接入 Tushare（真实 A股数据）和新浪财经（新闻）。本 Story 完成端到端接入：`TradingAnalysisController.executeAnalysis()` → `TradingRootNode` → 4 个分析师串行执行 → 辩论阶段 → 交易员 → 3 个风控分析师 → 组合经理最终决策。

---

## 任务清单

> ⚠️ **执行顺序**：先 Task 1 → Task 2 → Task 5.1 → Task 6。

| # | 任务 | 状态 | 备注 |
|---|---|---|---|
| 1 | 修改 `TradingAnalysisController.executeAnalysis()` — 替换空壳为真正执行链路 | pass | 核心入口修复 |
| 2 | 修改 `TradingRootNode` — `selectNextAnalyst()` 返回 `this`；注入辩论/风控节点；完善 `get()` 路由；支持 `selectedAnalysts` 过滤 | pass | 核心链路打通 |
| 2.1 | 修改 4 个分析师节点的 `get()` 方法 — 返回 `tradingRootNode` 形成闭环 | pass | 分析师串行→串行改造 |
| 5.1 | 修改 8 个辩论/风控/交易员节点的 `get()` 方法 — 返回 `tradingRootNode` 形成闭环（`PortfolioManagerNode` 除外） | pass | 辩论链路闭环 |
| 6 | 编译验证 `mvn clean compile -f ai-agent-study-trading/pom.xml` | pass | |
| 7 | 编译验证 `mvn clean compile -f pom.xml`（父工程） | pass | 验证跨模块依赖 |

---

## 核心架构理解

### 执行链路概览

```
TradingAnalysisController.analyze()
    ↓
executeAnalysis()  [Task 1 — 当前空壳，改为循环驱动]
    ↓
TradingRootNode.doApply()
    ├→ IStockDataProvider.getStockInfo()        ✅ 已接入 Tushare
    ├→ sendSseResult(trading_init)
    └→ return "analyst_collection_started"
         ↓ (框架调用 get() 选择下一个 Handler)
TradingRootNode.get()  [Task 2]
    ├→ selectNextAnalyst() → 遍历 4 个分析师节点（串行）
    │     FundamentalAnalystNode.doApply() → get() → returns TradingRootNode
    │     TechnicalAnalystNode.doApply()   → get() → returns TradingRootNode
    │     SentimentAnalystNode.doApply()   → get() → returns TradingRootNode
    │     NewsAnalystNode.doApply()        → get() → returns TradingRootNode
    │         ↓ 所有分析师完成后
    └→ step = "investment_debate" → 返回 this → framework 再次调用 get()
         ├→ [Phase 3] BullResearcherNode → BearResearcherNode → ResearchManagerNode
         ├→ [Phase 4] TraderNode
         ├→ [Phase 4] AggressiveRiskAnalystNode → ConservativeRiskAnalystNode → NeutralRiskAnalystNode
         └→ [Phase 5] PortfolioManagerNode → null（终点）
```

### 框架路由机制（重要）

`AbstractMultiThreadStrategyRouter` 的关键行为：

- `doApply()` 执行当前节点逻辑后，框架内部调用 `get()` 获取下一个 Handler
- `get()` 返回**下一个 `StrategyHandler`** → 框架继续执行下一个 Handler（循环调用 `apply()` → `get()` → `apply()` ...）
- `get()` 返回 `null` → 框架**停止路由，流程结束**

### 当前问题根因

1. **`TradingAnalysisController.executeAnalysis()` 是空壳**：只打印提示文字，未驱动 `TradingRootNode`
2. **`selectNextAnalyst()` 返回下一个分析师节点后，该节点 `get()` 返回 `null`**：分析师节点 `get()` 返回 `null`，导致框架在执行完一个分析师后停止，不会继续执行下一个分析师
3. **`selectNextAnalyst()` 全部完成后返回 `null`**：应返回 `this`（`TradingRootNode` 本身），让框架重新调用 `get()` 进入辩论阶段
4. **`TradingRootNode.get()` 在辩论/风控阶段返回 `null`**：辩论/风控阶段未接入

---

## 详细任务说明

### Task 1 - 修改 `TradingAnalysisController.executeAnalysis()`

**文件路径**：`ai-agent-study-trigger/src/main/java/denny/ai/agent/trading/trigger/http/TradingAnalysisController.java`

**改动内容**：将 `executeAnalysis()` 方法（第 112-139 行）从空壳改为真正执行链路。

**改动前**（第 112-139 行）：

```java
private void executeAnalysis(TradingAnalysisRequestDTO request, ResponseBodyEmitter emitter) throws Exception {
    StockAnalysisRequestVO tradingRequest = StockAnalysisRequestVO.builder()
            .ticker(request.getTicker().toUpperCase().trim())
            .tradeDate(request.getTradeDate())
            .selectedAnalysts(request.getSelectedAnalysts() != null
                    ? request.getSelectedAnalysts()
                    : List.of(AnalystTypeEnum.FUNDAMENTAL, AnalystTypeEnum.TECHNICAL,
                            AnalystTypeEnum.SENTIMENT, AnalystTypeEnum.NEWS))
            .maxDebateRounds(request.getMaxDebateRounds() != null ? request.getMaxDebateRounds() : 2)
            .sessionId(request.getSessionId())
            .build();

    ExecuteCommandEntity executeCommandEntity = ExecuteCommandEntity.builder()
            .message("请分析股票 " + tradingRequest.getTicker())
            .sessionId(tradingRequest.getSessionId())
            .maxStep(20)
            .agentType("trading")
            .build();

    // 只是打印提示，未驱动链路
    sendEvent(emitter, "progress", "正在初始化分析链路...");
    sendEvent(emitter, "progress", "请使用 /api/v1/agent/auto_agent 端点...");
    sendEvent(emitter, "complete", "请使用 POST /api/v1/agent/auto_agent 触发完整链路");
}
```

**改动后**（完整替换 `executeAnalysis` 方法）：

```java
private void executeAnalysis(TradingAnalysisRequestDTO request, ResponseBodyEmitter emitter) throws Exception {
    // 1. 构建交易请求对象
    StockAnalysisRequestVO tradingRequest = StockAnalysisRequestVO.builder()
            .ticker(request.getTicker().toUpperCase().trim())
            .tradeDate(request.getTradeDate())
            .selectedAnalysts(request.getSelectedAnalysts() != null
                    ? request.getSelectedAnalysts()
                    : List.of(AnalystTypeEnum.FUNDAMENTAL, AnalystTypeEnum.TECHNICAL,
                            AnalystTypeEnum.SENTIMENT, AnalystTypeEnum.NEWS))
            .maxDebateRounds(request.getMaxDebateRounds() != null ? request.getMaxDebateRounds() : 2)
            .sessionId(request.getSessionId() != null ? request.getSessionId() : UUID.randomUUID().toString())
            .build();

    // 2. 构建 ExecuteCommandEntity
    ExecuteCommandEntity executeCommandEntity = ExecuteCommandEntity.builder()
            .message("请分析股票 " + tradingRequest.getTicker())
            .sessionId(tradingRequest.getSessionId())
            .maxStep(20)
            .agentType("trading")
            .build();

    // 3. 构建 DynamicContext
    DefaultAutoAgentExecuteStrategyFactory.DynamicContext dynamicContext =
            new DefaultAutoAgentExecuteStrategyFactory.DynamicContext();
    dynamicContext.setMaxStep(20);
    dynamicContext.setExecutionHistory(new StringBuilder());
    dynamicContext.setCurrentTask(executeCommandEntity.getMessage());
    dynamicContext.setValue("emitter", emitter);
    dynamicContext.setValue(TradingRootNode.TRADING_REQUEST_KEY, tradingRequest);

    // 4. 驱动 TradingRootNode 链路
    try {
        StrategyHandler<ExecuteCommandEntity, DefaultAutoAgentExecuteStrategyFactory.DynamicContext, String> handler = tradingRootNode;
        int step = 0;

        while (handler != null) {
            String result = handler.apply(executeCommandEntity, dynamicContext);
            log.info("节点执行结果: {}, handler={}", result, handler.getClass().getSimpleName());
            step++;

            // 检查 step 是否超限
            if (step >= dynamicContext.getMaxStep()) {
                log.warn("达到最大 step 数 {}，强制结束链路", dynamicContext.getMaxStep());
                sendEvent(emitter, "complete", "分析完成（达到最大步数限制）");
                break;
            }

            handler = handler.get(executeCommandEntity, dynamicContext);
        }

        // 5. 发送最终完成事件
        sendEvent(emitter, "complete", "股票分析完成: " + tradingRequest.getTicker());
    } catch (Exception e) {
        log.error("股票分析执行异常: ticker={}, error={}", tradingRequest.getTicker(), e.getMessage(), e);
        sendEvent(emitter, "error", "分析失败: " + e.getMessage());
    }
}
```

**需要注入的依赖**（在类顶部新增字段）：

```java
@Resource
private TradingRootNode tradingRootNode;
```

**需要新增的 import**：

```java
import denny.ai.agent.trading.domain.node.TradingRootNode;
import java.util.UUID;
import cn.bugstack.wrench.design.framework.tree.StrategyHandler;
```

> **注意**：`tradingRootNode` 在 `ai-agent-study-trading-domain` 中定义，`ai-agent-study-trigger` 通过传递 `trading_request` 到 `DynamicContext` 来驱动链路，无需跨模块直接注入。但为简化获取，建议在 `TradingAnalysisController` 中通过 `ApplicationContext.getBean()` 获取，或确认 trigger 模块是否已有 trading-domain 的间接依赖。

---

### Task 2 - 修改 `TradingRootNode` — 核心链路打通

**文件路径**：`ai-agent-study-trading-domain/.../node/TradingRootNode.java`

#### 改动 2.1 — 注入辩论/交易员/风控节点

在现有 `@Resource` 字段（4 个分析师节点）之后，新增：

```java
// ======== Phase 3: 辩论节点 ========
@Resource
private BullResearcherNode bullResearcherNode;

@Resource
private BearResearcherNode bearResearcherNode;

@Resource
private ResearchManagerNode researchManagerNode;

// ======== Phase 4: 交易员 + 风控节点 ========
@Resource
private TraderNode traderNode;

@Resource
private AggressiveRiskAnalystNode aggressiveRiskAnalystNode;

@Resource
private ConservativeRiskAnalystNode conservativeRiskAnalystNode;

@Resource
private NeutralRiskAnalystNode neutralRiskAnalystNode;

@Resource
private PortfolioManagerNode portfolioManagerNode;
```

#### 改动 2.2 — `selectNextAnalyst()` 支持 `selectedAnalysts` 过滤 + 返回 `this`

**改动前**（第 127-148 行）：

```java
private StrategyHandler<ExecuteCommandEntity, DefaultAutoAgentExecuteStrategyFactory.DynamicContext, String> selectNextAnalyst(
        TradingContextVO context,
        DefaultAutoAgentExecuteStrategyFactory.DynamicContext dynamicContext) {

    if (context.getFundamentalReport() == null) {
        return fundamentalAnalystNode;
    }
    if (context.getTechnicalReport() == null) {
        return technicalAnalystNode;
    }
    if (context.getSentimentReport() == null) {
        return sentimentAnalystNode;
    }
    if (context.getNewsReport() == null) {
        return newsAnalystNode;
    }

    // 所有分析师都完成了，进入辩论阶段
    log.info("所有分析师完成，进入辩论阶段");
    dynamicContext.setValue(TRADING_STEP_KEY, "investment_debate");
    return null; // ❌ 返回 null 导致流程终止
}
```

**改动后**：

```java
private StrategyHandler<ExecuteCommandEntity, DefaultAutoAgentExecuteStrategyFactory.DynamicContext, String> selectNextAnalyst(
        TradingContextVO context,
        DefaultAutoAgentExecuteStrategyFactory.DynamicContext dynamicContext) {

    StockAnalysisRequestVO request = dynamicContext.getValue(TRADING_REQUEST_KEY);
    List<AnalystTypeEnum> selectedAnalysts = request != null && request.getSelectedAnalysts() != null
            ? request.getSelectedAnalysts()
            : List.of(AnalystTypeEnum.FUNDAMENTAL, AnalystTypeEnum.TECHNICAL,
                    AnalystTypeEnum.SENTIMENT, AnalystTypeEnum.NEWS);

    if (selectedAnalysts.contains(AnalystTypeEnum.FUNDAMENTAL) && context.getFundamentalReport() == null) {
        log.info("选择下一个分析师: FundamentalAnalystNode");
        return fundamentalAnalystNode;
    }
    if (selectedAnalysts.contains(AnalystTypeEnum.TECHNICAL) && context.getTechnicalReport() == null) {
        log.info("选择下一个分析师: TechnicalAnalystNode");
        return technicalAnalystNode;
    }
    if (selectedAnalysts.contains(AnalystTypeEnum.SENTIMENT) && context.getSentimentReport() == null) {
        log.info("选择下一个分析师: SentimentAnalystNode");
        return sentimentAnalystNode;
    }
    if (selectedAnalysts.contains(AnalystTypeEnum.NEWS) && context.getNewsReport() == null) {
        log.info("选择下一个分析师: NewsAnalystNode");
        return newsAnalystNode;
    }

    // 所有已选分析师都完成，进入辩论阶段
    log.info("所有分析师完成，进入辩论阶段");
    dynamicContext.setValue(TRADING_STEP_KEY, "investment_debate");
    return this; // ✅ 返回 this（TradingRootNode），框架重新调用 get() 进入辩论阶段
}
```

#### 改动 2.3 — `get()` 方法替换为完整路由

**改动前**（第 97-122 行），辩论/风控阶段均返回 `null`：

```java
@Override
public StrategyHandler<...> get(...) {
    return switch (currentStep) {
        case "analyst_collection" -> selectNextAnalyst(context, dynamicContext);
        case "investment_debate" -> null; // TODO: Phase 3 实现
        case "trader_decision" -> null;   // TODO: Phase 4 实现
        case "risk_management" -> null;   // TODO: Phase 4 实现
        case "final_report" -> null;      // TODO: Phase 5 实现
        default -> null;
    };
}
```

**改动后**（完整替换 `get()` 方法）：

```java
@Override
public StrategyHandler<ExecuteCommandEntity, DefaultAutoAgentExecuteStrategyFactory.DynamicContext, String> get(
        ExecuteCommandEntity requestParameter,
        DefaultAutoAgentExecuteStrategyFactory.DynamicContext dynamicContext) throws Exception {

    TradingContextVO context = dynamicContext.getValue(TRADING_CONTEXT_KEY);
    if (context == null) {
        log.error("TradingContextVO 为空");
        return null;
    }

    String currentStep = dynamicContext.getValue(TRADING_STEP_KEY);
    log.info("TradingRootNode.get() 当前步骤: {}", currentStep);

    return switch (currentStep) {
        // Phase 2: 分析师串行执行（由 selectNextAnalyst 驱动）
        case "analyst_collection" -> selectNextAnalyst(context, dynamicContext);
        // Phase 3: 多空辩论
        case "investment_debate" -> {
            InvestmentDebateVO debate = context.getInvestmentDebate();
            StockAnalysisRequestVO tradingRequest = dynamicContext.getValue(TRADING_REQUEST_KEY);
            if (debate == null) {
                // 首次进入辩论阶段，初始化辩论上下文
                int maxRounds = (tradingRequest != null && tradingRequest.getMaxDebateRounds() != null)
                        ? tradingRequest.getMaxDebateRounds() : 2;
                debate = InvestmentDebateVO.createNew(maxRounds);
                context.setInvestmentDebate(debate);
                log.info("辩论阶段初始化，最大轮次: {}", debate.getMaxRounds());
                yield bullResearcherNode;
            }
            if (debate.getBullOpinion() == null) {
                yield bullResearcherNode;
            } else if (debate.getBearOpinion() == null) {
                yield bearResearcherNode;
            } else if (debate.getJudgeDecision() == null) {
                yield researchManagerNode;
            } else {
                // 辩论结束，进入交易员阶段
                dynamicContext.setValue(TRADING_STEP_KEY, "trader_decision");
                yield traderNode;
            }
        }
        // Phase 4: 交易员
        case "trader_decision" -> {
            log.info("交易员阶段，生成投资计划");
            yield traderNode;
        }
        // Phase 4: 风控
        case "risk_management" -> {
            RiskDebateVO riskDebate = context.getRiskDebate();
            if (riskDebate == null) {
                riskDebate = new RiskDebateVO();
                riskDebate.setRiskItems(new java.util.ArrayList<>());
                riskDebate.setAggressiveHistory(new java.util.ArrayList<>());
                riskDebate.setConservativeHistory(new java.util.ArrayList<>());
                riskDebate.setNeutralHistory(new java.util.ArrayList<>());
                context.setRiskDebate(riskDebate);
                yield aggressiveRiskAnalystNode;
            }
            if (riskDebate.getAggressiveHistory().isEmpty()) {
                yield aggressiveRiskAnalystNode;
            }
            if (riskDebate.getConservativeHistory().isEmpty()) {
                yield conservativeRiskAnalystNode;
            }
            if (riskDebate.getNeutralHistory().isEmpty()) {
                yield neutralRiskAnalystNode;
            }
            // 3 个风控分析师都完成，进入组合经理
            yield portfolioManagerNode;
        }
        // Phase 5: 最终决策
        case "final_report" -> {
            log.info("最终决策阶段");
            yield portfolioManagerNode;
        }
        default -> {
            log.warn("未知的交易步骤: {}，流程结束", currentStep);
            yield null;
        }
    };
}
```

**需要新增的 import**：

```java
import denny.ai.agent.trading.domain.vo.TradingContextVO.InvestmentDebateVO;
import denny.ai.agent.trading.domain.vo.TradingContextVO.RiskDebateVO;
import denny.ai.agent.trading.domain.node.BullResearcherNode;
import denny.ai.agent.trading.domain.node.BearResearcherNode;
import denny.ai.agent.trading.domain.node.ResearchManagerNode;
import denny.ai.agent.trading.domain.node.TraderNode;
import denny.ai.agent.trading.domain.node.AggressiveRiskAnalystNode;
import denny.ai.agent.trading.domain.node.ConservativeRiskAnalystNode;
import denny.ai.agent.trading.domain.node.NeutralRiskAnalystNode;
import denny.ai.agent.trading.domain.node.PortfolioManagerNode;
```

> **辩论路由说明**：借鉴参考工程 TradingAgents 的设计，辩论阶段通过 `bullOpinion / bearOpinion / judgeDecision` 是否为 null 来判断辩论进度：
> - `bullOpinion == null` → 轮到多头发言
> - `bearOpinion == null` → 轮到空头发言
> - `judgeDecision == null` → 轮到研究主管判断
> - 全部不为 null → 辩论结束，进入交易员
>
> 注：参考工程用 `count` 计数器 + `latestSpeaker` 字段控制，更直观；当前方案用 null 判断也可行，功能等价。

---

### Task 2.1 - 修改 4 个分析师节点的 `get()` 方法

**文件路径**：分别对应 4 个分析师节点

| 节点 | 文件路径 | `get()` 当前值 | `get()` 改为 |
|------|----------|---------------|-------------|
| `FundamentalAnalystNode` | `.../domain/node/FundamentalAnalystNode.java` | `return null` | `return tradingRootNode` |
| `TechnicalAnalystNode` | `.../domain/node/TechnicalAnalystNode.java` | `return null` | `return tradingRootNode` |
| `SentimentAnalystNode` | `.../domain/node/SentimentAnalystNode.java` | `return null` | `return tradingRootNode` |
| `NewsAnalystNode` | `.../domain/node/NewsAnalystNode.java` | `return null` | `return tradingRootNode` |

**统一改动模式**（以 `FundamentalAnalystNode` 为例）：

1. 新增注入（在现有 `@Resource` 后追加）：

```java
@Resource
private TradingRootNode tradingRootNode;
```

2. 修改 `get()` 方法（当前第 88-93 行）：

```java
@Override
public StrategyHandler<ExecuteCommandEntity, DefaultAutoAgentExecuteStrategyFactory.DynamicContext, String> get(
        ExecuteCommandEntity requestParameter,
        DefaultAutoAgentExecuteStrategyFactory.DynamicContext dynamicContext) throws Exception {
    return tradingRootNode;  // ✅ 返回 TradingRootNode，继续调用 get() 选下一个分析师或进入辩论阶段
}
```

3. 添加 import：

```java
import denny.ai.agent.trading.domain.node.TradingRootNode;
```

> **注意**：Spring Bean 循环依赖不会发生。`TradingRootNode` → `@Resource` 注入各分析师节点（正向依赖），各分析师节点 → `@Resource` 注入 `TradingRootNode`（反向依赖）。Spring 默认支持单向 `@Resource` 字段注入的循环依赖，无需额外配置。

---

### Task 5.1 - 修改 8 个辩论/风控/交易员节点的 `get()` 方法

> ⚠️ **此 Task 极易遗漏但至关重要**，所有辩论/风控节点执行完一个后必须返回 `TradingRootNode`，否则后续节点永远无法执行。

| 节点 | 文件路径 | `get()` 当前值 | `get()` 改为 |
|------|----------|---------------|-------------|
| `BullResearcherNode` | `.../domain/node/BullResearcherNode.java` | `return null` | `return tradingRootNode` |
| `BearResearcherNode` | `.../domain/node/BearResearcherNode.java` | `return null` | `return tradingRootNode` |
| `ResearchManagerNode` | `.../domain/node/ResearchManagerNode.java` | `return null` | `return tradingRootNode` |
| `TraderNode` | `.../domain/node/TraderNode.java` | `return null` | `return tradingRootNode` |
| `AggressiveRiskAnalystNode` | `.../domain/node/AggressiveRiskAnalystNode.java` | `return null` | `return tradingRootNode` |
| `ConservativeRiskAnalystNode` | `.../domain/node/ConservativeRiskAnalystNode.java` | `return null` | `return tradingRootNode` |
| `NeutralRiskAnalystNode` | `.../domain/node/NeutralRiskAnalystNode.java` | `return null` | `return tradingRootNode` |
| `PortfolioManagerNode` | `.../domain/node/PortfolioManagerNode.java` | `return null` | `return null`（终点，保持不变） |

**统一改动模式**（以 `BullResearcherNode` 为例）：

1. 新增注入（在现有 `@Resource` 后追加）：

```java
@Resource
private TradingRootNode tradingRootNode;
```

2. 修改 `get()` 方法（当前第 78-82 行）：

```java
@Override
public StrategyHandler<ExecuteCommandEntity, DefaultAutoAgentExecuteStrategyFactory.DynamicContext, String> get(
        ExecuteCommandEntity requestParameter,
        DefaultAutoAgentExecuteStrategyFactory.DynamicContext dynamicContext) throws Exception {
    return tradingRootNode;  // ✅ 返回 TradingRootNode，框架重新调用 get() 继续路由
}
```

3. 添加 import：

```java
import denny.ai.agent.trading.domain.node.TradingRootNode;
```

**各节点 `doApply()` 中已正确设置 `TRADING_STEP_KEY`**：
- `BullResearcherNode.doApply()` → `setValue(TRADING_STEP_KEY, "investment_debate")`
- `BearResearcherNode.doApply()` → `setValue(TRADING_STEP_KEY, "investment_debate")`
- `ResearchManagerNode.doApply()` → `setValue(TRADING_STEP_KEY, "trader_decision")` 或 `"investment_debate_next_round"`
- `TraderNode.doApply()` → `setValue(TRADING_STEP_KEY, "risk_management")`
- 3 个风控节点 `doApply()` → `setValue(TRADING_STEP_KEY, "risk_management")` 或 `"final_report"`
- `PortfolioManagerNode.doApply()` → 流程终点

---

### Task 6 - 编译验证 trading 模块

**执行命令**：

```bash
mvn clean compile -f ai-agent-study-trading/pom.xml
```

**验证点**：
- [ ] BUILD SUCCESS，无 error
- [ ] 无新增 linter warning

---

### Task 7 - 编译验证父工程

**执行命令**：

```bash
mvn clean compile -f pom.xml
```

**验证点**：
- [ ] BUILD SUCCESS，trading 模块与 app/trigger/domain 模块无编译冲突

---

## 执行记录

| 时间 | 执行人 | Task # | 状态变更 | 备注 |
|---|---|---|---|---|
| 2026-04-23 | Agent | 新增 Story 5.1~5.8 | pending | 新增 Task 5.1~5.8 补充设计缺陷和边界条件 |
| 2026-04-24 | Agent | 整合方案，更新文档 | pending | 简化 Task 结构，移除冗余，聚焦核心改动 |

---

## 状态说明

- **pending**：待执行
- **pass**：已通过
- **fail**：执行失败（需记录失败原因）
