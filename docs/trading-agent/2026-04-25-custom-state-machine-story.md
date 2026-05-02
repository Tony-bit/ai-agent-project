# Story 任务跟踪文档

> **Story 名称**：Trading 模块状态机重构 — 用自定义轻量状态机替换 Spring State Machine
> **创建日期**：2026-04-25
> **背景**：Spring State Machine 引入 Reactive 线程模型、Context 传递复杂、过度设计。改用自定义状态机，同步执行，上下文透明，无外部依赖。

---

## 任务清单

> 每完成一个任务后，在此表中更新 `状态` 列，并在下方「执行记录」中追加一行。

| # | 任务 | 状态 | 依赖 |
|---|---|---|---|
| 1 | 创建 `TradingPhase.java` — 阶段枚举（6个阶段） | pass | — |
| 2 | 创建 `TradingEvent.java` — 事件枚举（7个事件） | pass | Task 1 |
| 3 | 创建 `TradingStateContext.java` — 请求级上下文 | pass | Task 1 |
| 4 | 创建 `TradingDriver.java` — 简化驱动（ThreadLocal 传递） | pass | Task 3 |
| 5 | 创建 `TradingDispatcher.java` — 核心调度器 | pass | Task 1,2,3,4 |
| 6 | 创建 `TradingStarter.java` — 请求入口 | pass | Task 3,4,5 |
| 7 | 重构 `TradingAnalysisController.java` | pass | Task 6 |
| 8 | 重构 `IntentRoutingNode.java` | pass | Task 6 |
| 9 | 删除 `spring-statemachine-core` 依赖 | pass | Task 1-8 |
| 10 | **编译验证子模块**（提前到删除文件前） | pass | Task 1-9 |
| 11 | 删除 `TradingStateMachineConfig.java` | pass | Task 10 |
| 12 | 删除 `TradingStateMachineDriver.java` | pass | Task 10 |
| 13 | 删除 `TradingStateMachineStarter.java` | pass | Task 10 |
| 14 | 删除 `DriverContext.java` | pass | Task 10 |
| 15 | 删除 `TradingPhaseEnum.java`、`TradingEventEnum.java` | pass | Task 10 |
| 16 | 编译验证子模块 | pass | Task 11-15 |
| 17 | 编译验证父工程 | pass | Task 16 |

---

## 架构设计

### 核心原则

1. **同步执行**：节点直接调用，不需要 Reactive subscribe
2. **上下文透明**：`TradingStateContext` 是请求级对象，节点通过 ThreadLocal 持有
3. **无外部依赖**：不需要 spring-statemachine，只用 JDK
4. **请求级隔离**：每次请求 new 一个 `TradingStateContext`，请求间不共享

### 文件清单

| 文件 | 路径 | 职责 |
|---|---|---|
| `TradingPhase` | `domain/config/` | 阶段枚举（6个阶段） |
| `TradingEvent` | `domain/config/` | 事件枚举（7个事件） |
| `TradingStateContext` | `domain/config/` | 请求级上下文，持有所有共享数据 |
| `TradingDriver` | `domain/config/` | 节点末尾调用，ThreadLocal 传递，委托 Dispatcher |
| `TradingDispatcher` | `domain/config/` | 核心调度器，switch 驱动流转 |
| `TradingStarter` | `domain/config/` | 请求入口，new Context + Driver + Dispatcher |

### 流程图

```
TradingStarter.start()
    ├── new TradingStateContext + TradingDriver + TradingDispatcher
    ├── populateStockInfo()
    └── dispatcher.onEvent(START_TRADING)
            ├── INIT → ANALYST_COLLECTION: 初始化股票信息
            ├── ANALYST_COLLECTION: 逐个调用分析师，analystIndex++ 自循环，全部完成后进入辩论
            ├── INVESTMENT_DEBATE: Bull → Bear → RM 自循环，RM 决定继续/结束
            ├── TRADER_DECISION: 调用交易员
            ├── RISK_MANAGEMENT: Aggressive → Conservative → Neutral 自循环，轮次耗尽后进入最终报告
            └── FINAL_REPORT: 调用组合经理，finally 发送 completed=true → 清理 ThreadLocal
```

---

## 核心类设计

### TradingPhase — 阶段枚举

```java
public enum TradingPhase {
    INIT,                    // 起始状态
    ANALYST_COLLECTION,      // 分析师收集（含内部循环）
    INVESTMENT_DEBATE,       // 投资辩论（含内部循环）
    TRADER_DECISION,        // 交易员决策
    RISK_MANAGEMENT,         // 风控管理（含内部循环）
    FINAL_REPORT,           // 终止状态
    ERROR                    // 终止状态
}
```

### TradingEvent — 事件枚举

```java
public enum TradingEvent {
    START_TRADING,           // INIT → ANALYST_COLLECTION
    ANALYST_COMPLETE,        // ANALYST_COLLECTION 自循环
    ALL_ANALYSTS_COMPLETE,  // ANALYST_COLLECTION → INVESTMENT_DEBATE
    INVESTMENT_DEBATE_COMPLETE, // INVESTMENT_DEBATE 自循环
    CONTINUE_DEBATE,         // RM 决定继续
    DEBATE_FINISH,          // RM 决定结束 → TRADER_DECISION
    TRADER_COMPLETE,        // TRADER_DECISION → RISK_MANAGEMENT
    RISK_DEBATE_COMPLETE,   // RISK_MANAGEMENT 自循环
    PORTFOLIO_COMPLETE,     // RISK_MANAGEMENT → FINAL_REPORT
    ERROR_OCCURRED           // 任意状态 → ERROR
}
```

### TradingStateContext — 请求级上下文

设计要点：
- **每次请求 new 一个**，不是 Spring Bean
- 持有 TradingContextVO、DynamicContext、SSE sender
- 字段：currentPhase、analystIndex、riskDebateRound、latestDebateSpeaker、latestRiskSpeaker、errorMessage

关键方法：
- `transitionTo(phase)`：阶段变更
- `sendError(msg)`：设置 ERROR 状态，发送 `completed=true` SSE
- `sendSseResult(type, subType, content, completed)`：发送 SSE，含异常捕获

构造函数中直接内联分析师列表逻辑（删除 `determineAnalysts()` 方法），简化如下：

```java
public TradingStateContext(StockAnalysisRequestVO request, DynamicContext dynamicContext,
                          BiConsumer<String, Object> sseSender) {
    this.request = request;
    this.dynamicContext = dynamicContext;
    this.sseSender = sseSender;
    this.tradingContext = TradingContextVO.empty();
    this.currentPhase = TradingPhase.INIT;
    this.analystIndex = 0;
    this.riskDebateRound = 0;
    this.selectedAnalysts = (request != null && !request.getSelectedAnalysts().isEmpty())
            ? request.getSelectedAnalysts()
            : List.of(FUNDAMENTAL, TECHNICAL, SENTIMENT, NEWS);
}
```

### TradingDriver — 节点末尾调用（ThreadLocal 方案）

```java
public class TradingDriver {
    private static final ThreadLocal<TradingDriver> CURRENT = new ThreadLocal<>();

    public static void setCurrent(TradingDriver driver) { CURRENT.set(driver); }
    public static TradingDriver getCurrent() { return CURRENT.get(); }
    public static void clear() { CURRENT.remove(); }

    private final TradingStateContext stateContext;
    private final TradingDispatcher dispatcher;

    // 节点末尾调用 API
    public void analystComplete() { dispatcher.onEvent(ANALYST_COMPLETE); }
    public void allAnalystsComplete() { dispatcher.onEvent(ALL_ANALYSTS_COMPLETE); }
    public void debateComplete() { dispatcher.onEvent(INVESTMENT_DEBATE_COMPLETE); }
    public void debateContinue() { dispatcher.onEvent(CONTINUE_DEBATE); }
    public void debateFinish() { dispatcher.onEvent(DEBATE_FINISH); }
    public void traderComplete() { dispatcher.onEvent(TRADER_COMPLETE); }
    public void riskDebateComplete() { dispatcher.onEvent(RISK_DEBATE_COMPLETE); }
    public void portfolioComplete() { dispatcher.onEvent(PORTFOLIO_COMPLETE); }
    public void errorOccurred(String msg) { stateContext.sendError(msg); }
}
```

节点 `doApply()` 末尾调用方式：

```java
if (TradingDriver.getCurrent() != null) {
    TradingDriver.getCurrent().analystComplete();
}
```

### TradingDispatcher — 核心调度器

```java
@Component
public class TradingDispatcher {
    @Resource private FundamentalAnalystNode fundamentalAnalystNode;
    // ... 其余 11 个节点注入

    private final TradingStateContext stateContext;

    public void onEvent(TradingEvent event) {
        TradingPhase current = stateContext.getCurrentPhase();
        log.info("Dispatcher 收到事件: event={}, 当前阶段={}", event, current);
        try {
            switch (current) {
                case INIT -> handleInit(event);
                case ANALYST_COLLECTION -> handleAnalystCollection(event);
                case INVESTMENT_DEBATE -> handleInvestmentDebate(event);
                case TRADER_DECISION -> handleTraderDecision(event);
                case RISK_MANAGEMENT -> handleRiskManagement(event);
                case FINAL_REPORT -> handleFinalReport(event);
                case ERROR -> { /* 终止状态 */ }
            }
        } catch (Exception e) {
            log.error("阶段处理异常: phase={}, event={}", current, event, e);
            stateContext.sendError("阶段处理异常: " + e.getMessage());
        }
    }
}
```

各阶段 handler 注意事项：
- `handleInvestmentDebate` 的 `default` 分支：`log.warn()`，避免静默忽略意外事件
- `handleFinalReport`：终止状态，加 javadoc 说明，保留为扩展点
- 节点调用统一 try-catch，异常时 `sendError()`，不向上抛

### TradingStarter — 请求入口

```java
@Service
public class TradingStarter {
    @Resource private IStockDataProvider dataProvider;

    public void start(StockAnalysisRequestVO request, DynamicContext dynamicContext,
                      BiConsumer<String, Object> sseSender) {
        TradingStateContext stateContext = new TradingStateContext(request, dynamicContext, sseSender);
        TradingDriver driver = new TradingDriver(stateContext);
        TradingDispatcher dispatcher = new TradingDispatcher(stateContext);

        try {
            TradingDriver.setCurrent(driver);
            populateStockInfo(stateContext);
            stateContext.transitionTo(TradingPhase.INIT);
            dispatcher.onEvent(TradingEvent.START_TRADING);
        } finally {
            TradingDriver.clear();
            if (stateContext.getCurrentPhase() == TradingPhase.FINAL_REPORT) {
                stateContext.sendSseResult("trading", "trading_complete", "交易分析完成", true);
            }
        }
    }
}
```

---

## 节点末尾调用改造

12 个节点的 `doApply()` 末尾统一改造：删除旧的 `driver.*Complete(sm)` 调用，改为：

```java
if (TradingDriver.getCurrent() != null) {
    TradingDriver.getCurrent().analystComplete(); // 对应节点的方法
}
```

节点末尾调用对照表：

| 节点 | 新调用 |
|---|---|
| Fundamental/Technical/Sentiment/NewsAnalystNode | `analystComplete()` |
| Bull/BearResearcherNode | `debateComplete()` |
| ResearchManagerNode | `debateFinish()` 或 `debateContinue()` |
| TraderNode | `traderComplete()` |
| Aggressive/Conservative/NeutralRiskAnalystNode | `riskDebateComplete()` |
| PortfolioManagerNode | `portfolioComplete()` |

---

## 重构 Controller

删除：`ThreadPoolExecutor` 注入、`TradingRootNode` 注入
新增：`TradingStarter` 注入

`executeAnalysis()` 中直接调用 `tradingStarter.start()`，删除旧的 while 反射循环。

---

## 删除废弃文件

按顺序执行（Task 10 编译通过后）：

1. `TradingStateMachineConfig.java`
2. `TradingStateMachineDriver.java`
3. `TradingStateMachineStarter.java`
4. `DriverContext.java`
5. `TradingPhaseEnum.java`、`TradingEventEnum.java`

删除 `pom.xml` 中的 `spring-statemachine-core` 依赖。

---

## 边界条件

| # | 边界条件 | 处理方式 |
|---|---|---|
| BC-1 | 未选择分析师 | 使用默认 4 个 |
| BC-2 | 股票数据获取失败 | Starter 捕获，返回 ERROR |
| BC-3 | 节点 LLM 调用失败 | Dispatcher try-catch，sendError |
| BC-4 | 辩论/风控轮次耗尽 | `exchanges >= maxExchanges` 强制进入下一阶段 |
| BC-5 | 分析师索引越界 | `index < analysts.size()` 先判断 |
| BC-6 | ERROR 状态 | 发送 SSE completed=true，流程终止 |
| BC-7 | SSE 断连 | `sendSseResult` try-catch，记录日志，不中断流程 |
| BC-8 | 节点执行超时 | `CompletableFuture.get(timeout)` 包装，60s 超时 |

---

## 遗漏场景处理方案

### S-1: ThreadLocal 统一清理
SSE 请求线程可能被复用，清理遗漏会导致跨请求 driver 泄漏。
→ Starter 层 try-finally 统一清理，Dispatcher 各 `invokeXxx()` 不单独清理。

### S-2: 意外事件增加警告日志
`default -> {}` 静默忽略意外事件，流程卡死无告警。
→ 各 handler 的 `default` 改为 `log.warn()`。

### S-3: 正常流程结束 SSE completed
ERROR 有 `completed=true`，但 FINAL_REPORT 正常结束没有。
→ Starter finally 中判断 `FINAL_REPORT` 后发送 `completed=true` SSE。

### S-4: 清理冗余 `debateRound`
`stateContext.debateRound` 初始化但从未使用，辩论推进依赖 `InvestmentDebateVO.exchangeCount`。
→ 删除 `debateRound` 字段及 setter/getter，删除 `initDebateContext()` 中的 `setDebateRound(0)`。

### S-5: 构造函数简化
`determineAnalysts()` 方法混合赋值与业务逻辑。
→ 删除方法，逻辑内联到构造函数中。

### S-6: SSE 断连检测
SSE 断连后静默返回，后端继续但前端无法感知。
→ `sendSseResult()` 加 try-catch，记录 `errorMessage`，建议仅记录日志不中断流程。

### S-7: 节点超时控制
`node.doApply()` 无超时保护，LLM hang 住时无限阻塞。
→ `CompletableFuture.runAsync().get(60, TimeUnit.SECONDS)` 包装，默认 60s 超时，超时发送 ERROR。

### S-8: 删除废弃文件放编译验证后
节点引用路径变化后直接删文件无法提前发现。
→ Task 10 编译验证前置到删除前，确认编译通过再删。

### S-9: `handleFinalReport` 明确标注
FINAL_REPORT 是终止状态，空实现容易被误认为遗漏。
→ 加 javadoc 说明终止状态，保留为扩展点。

### S-10: 任务编号统一
清单 Task 7/8 与正文编号不一致。
→ 已按上方任务清单统一（1-17），正文中对应调整。

---

## 执行记录

> 每完成一个任务，在此追加一行。

| 时间 | 执行人 | Task # | 状态 | 备注 |
|---|---|---|---|---|
| 2026-04-25 08:10 | Claude | 1-17 | pass | 全部任务完成，全项目编译通过 |

---

## 状态说明

- **pending**：待执行
- **pass**：执行成功
- **fail**：执行失败（需记录原因）
