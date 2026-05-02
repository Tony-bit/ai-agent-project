# Story 任务跟踪文档

> **Story 名称**：Trading 模块 State Machine 重构 — 用 Spring State Machine 彻底消除循环依赖
> **创建日期**：2026-04-24
> **背景**：`TradingRootNode` 通过 `TradingNodeRegistry` 拉引用，Registry 又注入所有节点，形成双向循环。本 Story 用 Spring State Machine 替换手写路由，彻底消除节点间的相互引用。

---

## 任务清单

> 每完成一个任务后，在此表中更新 `状态` 列（`pending` → `pass` / `fail`），并在下方「执行记录」中追加一行。

| # | 任务 | 状态 | 执行时间 | 执行人 | 备注 |
|---|---|---|---|---|---|
| 1 | 创建 `TradingPhaseEnum.java` — 阶段枚举 | pending | — | — | |
| 2 | 创建 `TradingEventEnum.java` — 事件枚举（含 CONTINUE_DEBATE） | pending | — | — | |
| 3 | 追加 Spring State Machine 依赖 `pom.xml` | pending | — | — | |
| 4 | 创建 `DriverContext.java` — 常量定义 | pending | — | — | |
| 5 | 创建 `TradingStateMachineDriver.java` — 驱动（无状态单例） | pending | — | — | |
| 6 | 创建 `TradingStateMachineStarter.java` — 请求级启动入口 | pending | — | — | |
| 7 | 创建 `TradingStateMachineConfig.java` — 完整配置（核心） | pending | — | — | |
| 8 | 重构 `TradingContextVO` — 新增 `RiskDebateVO.setMaxRounds()` | pending | — | — | Task5 依赖 |
| 9 | 重构 `StockAnalysisRequestVO` — 新增 `maxRiskRounds` 字段 | pending | — | — | |
| 10 | 重构 `IntentRoutingNode` — 注入 `TradingStateMachineStarter` | pending | — | — | |
| 11 | 重构 `TradingRootNode` — 删除 `TradingNodeRegistry` | pending | — | — | |
| 12 | 重构 `FundamentalAnalystNode` — 末尾调用 `driver.analystComplete(sm)` | pending | — | — | |
| 13 | 重构 `TechnicalAnalystNode` — 末尾调用 `driver.analystComplete(sm)` | pending | — | — | |
| 14 | 重构 `SentimentAnalystNode` — 末尾调用 `driver.analystComplete(sm)` | pending | — | — | |
| 15 | 重构 `NewsAnalystNode` — 末尾调用 `driver.analystComplete(sm)` | pending | — | — | |
| 16 | 重构 `BullResearcherNode` — 末尾调用 `driver.debateComplete(sm)` | pending | — | — | |
| 17 | 重构 `BearResearcherNode` — 末尾调用 `driver.debateComplete(sm)` | pending | — | — | |
| 18 | 重构 `ResearchManagerNode` — 末尾调用 `driver.debateFinish(sm)` 或 `driver.debateContinue(sm)` | pending | — | — | |
| 19 | 重构 `TraderNode` — 末尾调用 `driver.traderComplete(sm)` | pending | — | — | |
| 20 | 重构 `AggressiveRiskAnalystNode` — 末尾调用 `driver.riskDebateComplete(sm)` | pending | — | — | |
| 21 | 重构 `ConservativeRiskAnalystNode` — 末尾调用 `driver.riskDebateComplete(sm)` | pending | — | — | |
| 22 | 重构 `NeutralRiskAnalystNode` — 末尾调用 `driver.riskDebateComplete(sm)` | pending | — | — | |
| 23 | 重构 `PortfolioManagerNode` — 末尾调用 `driver.portfolioComplete(sm)` | pending | — | — | |
| 24 | 删除 `TradingNodeRegistry.java` 及所有节点中的 `nodeRegistry` 注入 | pending | — | — | |
| 25 | 编译验证子模块 `mvn clean compile` | pending | — | — | |
| 26 | 编译验证父工程 `mvn clean compile` | pending | — | — | |

---

## 核心架构

### 问题根因

```
TradingRootNode → 12 个节点各自注入 TradingNodeRegistry → TradingNodeRegistry 注入所有节点
                                                                              ↑
TradingRootNode ← ← ← ← ← ← ← ← ← ← ← ← ← ← ← ← ← ← ← ← ← ← ← ← ← ← ← ← ← ←
```

### State Machine 方案

```
StateMachineFactory（Spring Bean）
    │
    └── 每次请求 getStateMachine() → 新实例（ExtendedState 天然隔离）
            │
            ├── Config 持有所有节点引用，节点不持有 Config 引用
            ├── 节点 doApply() 末尾调用 driver 异步发送事件（不阻塞）
            └── Action 中读取 ExtendedState 决定下一步路由
```

**并发安全原则**：Driver 是无状态单例，不持有 `stateMachine`；`stateMachine` 由 Starter 在请求级别持有，请求间互不影响。

---

## 枚举设计

### TradingPhaseEnum

路径：`ai-agent-study-trading-api/.../api/vo/TradingPhaseEnum.java`

```java
package denny.ai.agent.trading.api.vo;

public enum TradingPhaseEnum {
    INIT,                // 初始化
    ANALYST_COLLECTION,  // 分析师收集阶段（含内部 round-robin）
    INVESTMENT_DEBATE,   // 多空辩论阶段（含内部 round-robin）
    TRADER_DECISION,     // 交易员决策阶段
    RISK_MANAGEMENT,     // 风控阶段（含内部 round-robin）
    FINAL_REPORT,        // 最终报告阶段（终点）
    ERROR;               // 异常状态（终点）
}
```

### TradingEventEnum

路径：`ai-agent-study-trading-api/.../api/vo/TradingEventEnum.java`

```java
package denny.ai.agent.trading.api.vo;

public enum TradingEventEnum {
    START_TRADING,              // 启动
    ANALYST_COMPLETE,           // 单个分析师完成
    ALL_ANALYSTS_COMPLETE,      // 所有分析师完成
    INVESTMENT_DEBATE_COMPLETE, // 辩论节点完成（自循环路由）
    DEBATE_FINISH,             // 辩论结束
    CONTINUE_DEBATE,           // 辩论继续（ResearchManager 决定继续时发送）
    TRADER_COMPLETE,           // 交易员完成
    RISK_DEBATE_COMPLETE,     // 风控节点完成
    PORTFOLIO_COMPLETE,        // 组合经理完成
    ERROR_OCCURRED;            // 异常
}
```

---

## 状态转换图

```
                              ┌─────────────────────────────────────┐
                              │                                     │
                              ▼                                     │
INIT ──[START_TRADING]──► ANALYST_COLLECTION ◄────────────────────┤
  │                           │                                      │
  │                           │ [analystProgressAction]               │
  │                           │ 读取 analystIndex，逐个调用分析师       │
  │                           │                                      │
  │                           ├──── 未完 ──► 调用下一个分析师          │
  │                           │              ↑                       │
  │                           │        [ANALYST_COMPLETE]            │
  │                           │              │（自循环）              │
  │                           └──── 完毕 ──► [ALL_ANALYSTS_COMPLETE]  │
  │                                                                │
  └───────────────────────────────────────────────────────────────┘
                                                                    │
                                                                    ▼
                                              INVESTMENT_DEBATE ◄───┐
                                                    │                │
                                                    │ [debateProgressAction]
                                                    │ Bull→Bear→RM→Bull
                                                    │ 或 totalExchanges>=2*max
                                                    │
                                                    ├────► [DEBATE_FINISH]
                                                    └────► [CONTINUE_DEBATE]
                                                               │
                                                               ▼
                                                        TRADER_DECISION
                                                               │
                                                               │ [TRADER_COMPLETE]
                                                               ▼
                                                        RISK_MANAGEMENT ◄──┐
                                                               │               │
                                                               │ [riskDebateProgressAction]
                                                               │ Aggressive→Conservative
                                                               │ →Neutral→Aggressive
                                                               │
                                                               └────► [PORTFOLIO_COMPLETE]
                                                                          ▼
                                                                 FINAL_REPORT
                                                                    │
                                                                    │ [finalReportEntryAction]
                                                                    ▼
                                                               FINAL_REPORT (终点)

ANY_STATE ──[ERROR_OCCURRED]──► ERROR (终点)
```

---

## 核心代码

### Task 3 — Spring State Machine 依赖

路径：`ai-agent-study-trading/ai-agent-study-trading-domain/pom.xml`

```xml
<dependency>
    <groupId>org.springframework.statemachine</groupId>
    <artifactId>spring-statemachine-core</artifactId>
    <version>4.3.1</version>
</dependency>
```

### Task 4 — TradingStateMachineConfig.java

路径：`ai-agent-study-trading/ai-agent-study-trading-domain/src/main/java/denny/ai/agent/trading/domain/config/TradingStateMachineConfig.java`

**关键设计决策**：
- 所有 `invoke*Node()` 调用**同步执行**（节点返回后才继续），但节点内部 AI 调用是异步的，`driver.sendEvent()` 也是**异步发送**（无 `blockLast()`），不阻塞状态机
- `ExtendedState` 是唯一数据源，DriverContext 不做双向同步
- 每个 Action 入口处重置上下文变量（保证请求隔离）

```java
package denny.ai.agent.trading.domain.config;

import denny.ai.agent.domain.model.entity.ExecuteCommandEntity;
import denny.ai.agent.domain.service.auto.step.factory.DefaultAutoAgentExecuteStrategyFactory;
import denny.ai.agent.trading.api.vo.*;
import denny.ai.agent.trading.domain.node.*;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.statemachine.action.Action;
import org.springframework.statemachine.config.EnableStateMachineFactory;
import org.springframework.statemachine.config.EnumStateMachineConfigurerAdapter;
import org.springframework.statemachine.config.builders.*;
import org.springframework.statemachine.listener.StateMachineListener;
import org.springframework.statemachine.state.State;
import org.springframework.messaging.support.MessageBuilder;
import reactor.core.publisher.Mono;

import java.util.EnumSet;
import java.util.List;

@Slf4j
@Configuration
@EnableStateMachineFactory
public class TradingStateMachineConfig
        extends EnumStateMachineConfigurerAdapter<TradingPhaseEnum, TradingEventEnum> {

    // Config 持有所有节点引用，节点不持有 Config 引用
    @Resource private FundamentalAnalystNode fundamentalAnalystNode;
    @Resource private TechnicalAnalystNode technicalAnalystNode;
    @Resource private SentimentAnalystNode sentimentAnalystNode;
    @Resource private NewsAnalystNode newsAnalystNode;
    @Resource private BullResearcherNode bullResearcherNode;
    @Resource private BearResearcherNode bearResearcherNode;
    @Resource private ResearchManagerNode researchManagerNode;
    @Resource private TraderNode traderNode;
    @Resource private AggressiveRiskAnalystNode aggressiveRiskAnalystNode;
    @Resource private ConservativeRiskAnalystNode conservativeRiskAnalystNode;
    @Resource private NeutralRiskAnalystNode neutralRiskAnalystNode;
    @Resource private PortfolioManagerNode portfolioManagerNode;
    @Resource private TradingStateMachineDriver driver;

    private static final List<AnalystTypeEnum> DEFAULT_ANALYST_LIST = List.of(
            AnalystTypeEnum.FUNDAMENTAL, AnalystTypeEnum.TECHNICAL,
            AnalystTypeEnum.SENTIMENT, AnalystTypeEnum.NEWS);

    // ===== 状态机配置 =====

    @Override
    public void configure(StateMachineConfigurationConfigurer<TradingPhaseEnum, TradingEventEnum> config)
            throws Exception {
        config.withConfiguration()
                .autoStartup(false)
                .listener(stateMachineListener());
    }

    @Override
    public void configure(StateMachineStateConfigurer<TradingPhaseEnum, TradingEventEnum> states) throws Exception {
        states.withStates()
                .initial(TradingPhaseEnum.INIT)
                .states(EnumSet.allOf(TradingPhaseEnum.class))
                .end(TradingPhaseEnum.FINAL_REPORT)
                .end(TradingPhaseEnum.ERROR);
    }

    @Override
    public void configure(StateMachineTransitionConfigurer<TradingPhaseEnum, TradingEventEnum> transitions)
            throws Exception {
        transitions
            // INIT → ANALYST_COLLECTION
            .withExternal()
                .source(TradingPhaseEnum.INIT).target(TradingPhaseEnum.ANALYST_COLLECTION)
                .event(TradingEventEnum.START_TRADING)
                .action(initAction())
                .and()

            // ANALYST_COLLECTION：自循环选下一个分析师
            .withExternal()
                .source(TradingPhaseEnum.ANALYST_COLLECTION)
                .target(TradingPhaseEnum.ANALYST_COLLECTION)
                .event(TradingEventEnum.ANALYST_COMPLETE)
                .action(analystProgressAction())
                .and()
            .withExternal()
                .source(TradingPhaseEnum.ANALYST_COLLECTION)
                .target(TradingPhaseEnum.INVESTMENT_DEBATE)
                .event(TradingEventEnum.ALL_ANALYSTS_COMPLETE)
                .action(analystCollectionCompleteAction())
                .and()

            // INVESTMENT_DEBATE：辩论 round-robin 自循环
            .withExternal()
                .source(TradingPhaseEnum.INVESTMENT_DEBATE)
                .target(TradingPhaseEnum.INVESTMENT_DEBATE)
                .event(TradingEventEnum.INVESTMENT_DEBATE_COMPLETE)
                .action(debateProgressAction())
                .and()
            .withExternal()
                .source(TradingPhaseEnum.INVESTMENT_DEBATE)
                .target(TradingPhaseEnum.TRADER_DECISION)
                .event(TradingEventEnum.DEBATE_FINISH)
                .action(debateFinishAction())
                .and()
            .withExternal()
                .source(TradingPhaseEnum.INVESTMENT_DEBATE)
                .target(TradingPhaseEnum.INVESTMENT_DEBATE)
                .event(TradingEventEnum.CONTINUE_DEBATE)
                .action(debateContinueAction())
                .and()

            // TRADER_DECISION → RISK_MANAGEMENT
            .withExternal()
                .source(TradingPhaseEnum.TRADER_DECISION)
                .target(TradingPhaseEnum.RISK_MANAGEMENT)
                .event(TradingEventEnum.TRADER_COMPLETE)
                .action(traderCompleteAction())
                .and()

            // RISK_MANAGEMENT：风控 round-robin 自循环
            .withExternal()
                .source(TradingPhaseEnum.RISK_MANAGEMENT)
                .target(TradingPhaseEnum.RISK_MANAGEMENT)
                .event(TradingEventEnum.RISK_DEBATE_COMPLETE)
                .action(riskDebateProgressAction())
                .and()
            .withExternal()
                .source(TradingPhaseEnum.RISK_MANAGEMENT)
                .target(TradingPhaseEnum.FINAL_REPORT)
                .event(TradingEventEnum.PORTFOLIO_COMPLETE)
                .action(portfolioCompleteAction())
                .and()

            // FINAL_REPORT：进入时调用 PortfolioManagerNode
            .withEntry()
                .state(TradingPhaseEnum.FINAL_REPORT, finalReportEntryAction())
                .and()

            // ERROR（全状态可进入）
            .withExternal()
                .source(TradingPhaseEnum.INIT).target(TradingPhaseEnum.ERROR)
                .event(TradingEventEnum.ERROR_OCCURRED).action(errorAction())
                .and()
            .withExternal()
                .source(TradingPhaseEnum.ANALYST_COLLECTION).target(TradingPhaseEnum.ERROR)
                .event(TradingEventEnum.ERROR_OCCURRED).action(errorAction())
                .and()
            .withExternal()
                .source(TradingPhaseEnum.INVESTMENT_DEBATE).target(TradingPhaseEnum.ERROR)
                .event(TradingEventEnum.ERROR_OCCURRED).action(errorAction())
                .and()
            .withExternal()
                .source(TradingPhaseEnum.TRADER_DECISION).target(TradingPhaseEnum.ERROR)
                .event(TradingEventEnum.ERROR_OCCURRED).action(errorAction())
                .and()
            .withExternal()
                .source(TradingPhaseEnum.RISK_MANAGEMENT).target(TradingPhaseEnum.ERROR)
                .event(TradingEventEnum.ERROR_OCCURRED).action(errorAction());
    }

    // ===== Action 实现 =====

    @Bean
    public Action<TradingPhaseEnum, TradingEventEnum> initAction() {
        return ctx -> {
            log.info("状态机: INIT → ANALYST_COLLECTION，开始初始化");

            // 重置 ExtendedState（每次启动清理残留数据）
            ctx.getExtendedState().getVariables().clear();
            ctx.getExtendedState().getVariables()
                    .put(DriverContext.KEY_ANALYST_INDEX, 0);
            ctx.getExtendedState().getVariables()
                    .put(DriverContext.KEY_ERROR_MESSAGE, null);

            StockAnalysisRequestVO request = ctx.getExtendedState()
                    .get(DriverContext.KEY_TRADING_REQUEST, StockAnalysisRequestVO.class);
            if (request == null) {
                sendError(ctx, "未找到 trading_request");
                return;
            }

            var dataProvider = ctx.getExtendedState()
                    .get(DriverContext.KEY_STOCK_PROVIDER,
                         denny.ai.agent.trading.api.provider.IStockDataProvider.class);
            if (dataProvider == null) {
                sendError(ctx, "StockDataProvider 未注入");
                return;
            }

            TradingContextVO context = TradingContextVO.empty();
            ctx.getExtendedState().getVariables().put(DriverContext.KEY_TRADING_CONTEXT, context);

            var stockInfo = dataProvider.getStockInfo(request.getTicker());
            if (stockInfo == null) {
                sendError(ctx, "无法获取股票信息 ticker=" + request.getTicker());
                return;
            }
            context.setStockInfo(stockInfo);

            List<AnalystTypeEnum> analysts = determineAnalysts(request);
            if (analysts.isEmpty()) {
                // 跳过收集阶段，直接进入辩论
                sendEventAsync(ctx, TradingEventEnum.ALL_ANALYSTS_COMPLETE);
                return;
            }
            ctx.getExtendedState().getVariables()
                    .put(DriverContext.KEY_SELECTED_ANALYSTS, analysts);
            ctx.getExtendedState().getVariables()
                    .put(DriverContext.KEY_ANALYST_INDEX, 0);
            driver.sendSseResult("trading", "trading_init",
                    com.alibaba.fastjson.JSON.toJSONString(stockInfo), false);
            log.info("交易 Agent 初始化完成: ticker={}, analysts={}",
                    stockInfo.getTicker(), analysts);
        };
    }

    @Bean
    public Action<TradingPhaseEnum, TradingEventEnum> analystProgressAction() {
        return ctx -> {
            Integer index = ctx.getExtendedState()
                    .get(DriverContext.KEY_ANALYST_INDEX, Integer.class);
            index = (index == null) ? 0 : index;

            List<AnalystTypeEnum> analysts = ctx.getExtendedState()
                    .get(DriverContext.KEY_SELECTED_ANALYSTS, List.class);
            if (analysts == null) analysts = DEFAULT_ANALYST_LIST;

            if (index < analysts.size()) {
                AnalystTypeEnum current = analysts.get(index);
                log.info("执行分析师: {}, 索引: {}/{}",
                        current, index + 1, analysts.size());
                invokeAnalystNode(current, ctx);
                // 索引递增，等待 ANALYST_COMPLETE 事件再次触发本 Action
                ctx.getExtendedState().getVariables()
                        .put(DriverContext.KEY_ANALYST_INDEX, index + 1);
            } else {
                log.info("所有分析师执行完毕，进入辩论阶段");
                sendEventAsync(ctx, TradingEventEnum.ALL_ANALYSTS_COMPLETE);
            }
        };
    }

    @Bean
    public Action<TradingPhaseEnum, TradingEventEnum> analystCollectionCompleteAction() {
        return ctx -> {
            log.info("状态机: 所有分析师完成，进入辩论阶段");
            TradingContextVO context = getContext(ctx);
            if (context.getInvestmentDebate() == null) {
                StockAnalysisRequestVO request = ctx.getExtendedState()
                        .get(DriverContext.KEY_TRADING_REQUEST, StockAnalysisRequestVO.class);
                int maxRounds = (request != null && request.getMaxDebateRounds() > 0)
                        ? request.getMaxDebateRounds() : 2;
                context.setInvestmentDebate(
                        TradingContextVO.InvestmentDebateVO.createNew(maxRounds));
            }
            driver.sendSseResult("debate", "debate_start", "辩论阶段开始", false);
        };
    }

    @Bean
    public Action<TradingPhaseEnum, TradingEventEnum> debateProgressAction() {
        return ctx -> {
            TradingContextVO context = getContext(ctx);
            if (context == null) return;

            TradingContextVO.InvestmentDebateVO debate = context.getInvestmentDebate();
            if (debate == null) {
                StockAnalysisRequestVO request = ctx.getExtendedState()
                        .get(DriverContext.KEY_TRADING_REQUEST, StockAnalysisRequestVO.class);
                int maxRounds = (request != null && request.getMaxDebateRounds() > 0) ? 2 : 2;
                debate = TradingContextVO.InvestmentDebateVO.createNew(maxRounds);
                context.setInvestmentDebate(debate);
            }

            String latest = debate.getLatestSpeaker();
            StockAnalysisRequestVO request = ctx.getExtendedState()
                    .get(DriverContext.KEY_TRADING_REQUEST, StockAnalysisRequestVO.class);
            int maxRounds = (request != null && request.getMaxDebateRounds() > 0)
                    ? request.getMaxDebateRounds() : 2;
            int totalExchanges = debate.getTotalExchangeCount();

            if (totalExchanges >= 2 * maxRounds) {
                // 轮次耗尽，ResearchManager 最终判断后结束辩论
                log.info("辩论轮次耗尽({}轮)，调用研究主管做最终判断", maxRounds);
                debate.setLatestSpeaker("RESEARCH_MANAGER");
                invokeResearchManagerNode(ctx);
                sendEventAsync(ctx, TradingEventEnum.DEBATE_FINISH);
                return;
            }

            // 按 BULL → BEAR → RESEARCH_MANAGER → BULL 循环
            switch (latest == null ? "" : latest) {
                case "BULL" -> invokeBearResearcherNode(ctx);
                case "BEAR" -> invokeResearchManagerNode(ctx);
                case "RESEARCH_MANAGER" -> invokeBullResearcherNode(ctx);
                default -> invokeBullResearcherNode(ctx);
            }
        };
    }

    @Bean
    public Action<TradingPhaseEnum, TradingEventEnum> debateFinishAction() {
        return ctx -> {
            log.info("状态机: 辩论结束，进入交易员阶段");
            driver.sendSseResult("debate", "debate_complete", "辩论结束，进入交易员决策", false);
        };
    }

    @Bean
    public Action<TradingPhaseEnum, TradingEventEnum> debateContinueAction() {
        return ctx -> {
            log.info("状态机: ResearchManager 决定继续辩论");
            // CONTINUE_DEBATE 由 ResearchManager.debateContinue() 触发
            // 路由到 debateProgressAction 继续下一次循环
            debateProgressAction().execute(ctx);
        };
    }

    @Bean
    public Action<TradingPhaseEnum, TradingEventEnum> traderCompleteAction() {
        return ctx -> {
            log.info("状态机: 交易员完成，进入风控阶段");
            TradingContextVO context = getContext(ctx);
            if (context.getRiskDebate() == null) {
                context.setRiskDebate(new TradingContextVO.RiskDebateVO());
            }
            StockAnalysisRequestVO request = ctx.getExtendedState()
                    .get(DriverContext.KEY_TRADING_REQUEST, StockAnalysisRequestVO.class);
            int maxRiskRounds = (request != null && request.getMaxRiskRounds() > 0)
                    ? request.getMaxRiskRounds() : 1;
            context.getRiskDebate().setMaxRounds(maxRiskRounds);
            context.getRiskDebate().setLatestSpeaker("AGGRESSIVE");
            driver.sendSseResult("risk", "risk_start", "风控阶段开始", false);
        };
    }

    @Bean
    public Action<TradingPhaseEnum, TradingEventEnum> riskDebateProgressAction() {
        return ctx -> {
            TradingContextVO context = getContext(ctx);
            if (context == null) return;

            TradingContextVO.RiskDebateVO riskDebate = context.getRiskDebate();
            if (riskDebate == null) {
                riskDebate = new TradingContextVO.RiskDebateVO();
                context.setRiskDebate(riskDebate);
            }

            String latest = riskDebate.getLatestSpeaker();
            int maxRounds = riskDebate.getMaxRounds();
            int totalExchanges = riskDebate.getTotalExchangeCount();

            if (totalExchanges >= 3 * maxRounds) {
                log.info("风控轮次耗尽，进入最终报告");
                invokeNeutralRiskAnalystNode(ctx);
                sendEventAsync(ctx, TradingEventEnum.PORTFOLIO_COMPLETE);
                return;
            }

            // 按 Aggressive → Conservative → Neutral → Aggressive 循环
            switch (latest == null ? "" : latest) {
                case "AGGRESSIVE" -> invokeConservativeNode(ctx);
                case "CONSERVATIVE" -> invokeNeutralNode(ctx);
                case "NEUTRAL" -> invokeAggressiveNode(ctx);
                default -> invokeAggressiveNode(ctx);
            }
        };
    }

    @Bean
    public Action<TradingPhaseEnum, TradingEventEnum> portfolioCompleteAction() {
        return ctx -> {
            log.info("状态机: 风控完成，进入最终报告阶段");
            driver.sendSseResult("final", "final_report_start", "最终报告生成中", false);
        };
    }

    @Bean
    public Action<TradingPhaseEnum, TradingEventEnum> finalReportEntryAction() {
        return ctx -> {
            log.info("状态机: 进入最终报告阶段，调用 PortfolioManagerNode");
            try {
                portfolioManagerNode.doApply(
                        new ExecuteCommandEntity(), driver.getDynamicContext());
                // PortfolioManagerNode.doApply() 末尾发送最终 SSE 结果（completed=true）
            } catch (Exception e) {
                log.error("PortfolioManagerNode 执行失败", e);
                sendError(ctx, "生成最终报告时发生错误: " + e.getMessage());
            }
        };
    }

    @Bean
    public Action<TradingPhaseEnum, TradingEventEnum> errorAction() {
        return ctx -> {
            String errorMsg = ctx.getExtendedState()
                    .get(DriverContext.KEY_ERROR_MESSAGE, String.class);
            log.error("交易流程进入 ERROR 状态: {}", errorMsg);
            driver.sendSseResult("trading", "error",
                    errorMsg != null ? errorMsg : "交易分析过程中发生错误，请重试", true);
        };
    }

    @Bean
    public StateMachineListener<TradingPhaseEnum, TradingEventEnum> stateMachineListener() {
        return new StateMachineListener<>() {
            @Override
            public void stateChanged(State<TradingPhaseEnum, TradingEventEnum> from,
                                    State<TradingPhaseEnum, TradingEventEnum> to) {
                if (from != null && to != null) {
                    log.info("状态变更: {} → {}", from.getId(), to.getId());
                }
            }
        };
    }

    // ===== 私有辅助方法 =====

    private List<AnalystTypeEnum> determineAnalysts(StockAnalysisRequestVO request) {
        if (request != null && request.getSelectedAnalysts() != null
                && !request.getSelectedAnalysts().isEmpty()) {
            return request.getSelectedAnalysts();
        }
        return DEFAULT_ANALYST_LIST;
    }

    private TradingContextVO getContext(
            org.springframework.statemachine.StateContext<TradingPhaseEnum, TradingEventEnum> ctx) {
        return ctx.getExtendedState()
                .get(DriverContext.KEY_TRADING_CONTEXT, TradingContextVO.class);
    }

    /**
     * 异步发送事件（无 blockLast()），避免嵌套阻塞。
     * 用于 Action 内部主动触发下一个转换。
     */
    private void sendEventAsync(
            org.springframework.statemachine.StateContext<TradingPhaseEnum, TradingEventEnum> ctx,
            TradingEventEnum event) {
        ctx.getStateMachine().sendEvent(Mono.just(
            MessageBuilder.withPayload(event).build()
        )).subscribe(); // 异步，不阻塞当前线程
    }

    private void sendError(
            org.springframework.statemachine.StateContext<TradingPhaseEnum, TradingEventEnum> ctx,
            String msg) {
        log.error(msg);
        ctx.getExtendedState().getVariables().put(DriverContext.KEY_ERROR_MESSAGE, msg);
        sendEventAsync(ctx, TradingEventEnum.ERROR_OCCURRED);
    }

    private void invokeAnalystNode(AnalystTypeEnum analyst,
            org.springframework.statemachine.StateContext<TradingPhaseEnum, TradingEventEnum> ctx) {
        try {
            ExecuteCommandEntity request = new ExecuteCommandEntity();
            switch (analyst) {
                case FUNDAMENTAL -> fundamentalAnalystNode.doApply(request, driver.getDynamicContext());
                case TECHNICAL -> technicalAnalystNode.doApply(request, driver.getDynamicContext());
                case SENTIMENT -> sentimentAnalystNode.doApply(request, driver.getDynamicContext());
                case NEWS -> newsAnalystNode.doApply(request, driver.getDynamicContext());
            }
        } catch (Exception e) {
            handleNodeError(analyst.name(), e, ctx);
        }
    }

    private void invokeBullResearcherNode(
            org.springframework.statemachine.StateContext<TradingPhaseEnum, TradingEventEnum> ctx) {
        try {
            getContext(ctx).getInvestmentDebate().setLatestSpeaker("BULL");
            bullResearcherNode.doApply(new ExecuteCommandEntity(), driver.getDynamicContext());
        } catch (Exception e) { handleNodeError("BullResearcherNode", e, ctx); }
    }

    private void invokeBearResearcherNode(
            org.springframework.statemachine.StateContext<TradingPhaseEnum, TradingEventEnum> ctx) {
        try {
            getContext(ctx).getInvestmentDebate().setLatestSpeaker("BEAR");
            bearResearcherNode.doApply(new ExecuteCommandEntity(), driver.getDynamicContext());
        } catch (Exception e) { handleNodeError("BearResearcherNode", e, ctx); }
    }

    private void invokeResearchManagerNode(
            org.springframework.statemachine.StateContext<TradingPhaseEnum, TradingEventEnum> ctx) {
        try {
            getContext(ctx).getInvestmentDebate().setLatestSpeaker("RESEARCH_MANAGER");
            researchManagerNode.doApply(new ExecuteCommandEntity(), driver.getDynamicContext());
        } catch (Exception e) { handleNodeError("ResearchManagerNode", e, ctx); }
    }

    private void invokeTraderNode(
            org.springframework.statemachine.StateContext<TradingPhaseEnum, TradingEventEnum> ctx) {
        try {
            traderNode.doApply(new ExecuteCommandEntity(), driver.getDynamicContext());
        } catch (Exception e) { handleNodeError("TraderNode", e, ctx); }
    }

    private void invokeAggressiveNode(
            org.springframework.statemachine.StateContext<TradingPhaseEnum, TradingEventEnum> ctx) {
        try {
            getContext(ctx).getRiskDebate().setLatestSpeaker("AGGRESSIVE");
            aggressiveRiskAnalystNode.doApply(new ExecuteCommandEntity(), driver.getDynamicContext());
        } catch (Exception e) { handleNodeError("AggressiveRiskAnalystNode", e, ctx); }
    }

    private void invokeConservativeNode(
            org.springframework.statemachine.StateContext<TradingPhaseEnum, TradingEventEnum> ctx) {
        try {
            getContext(ctx).getRiskDebate().setLatestSpeaker("CONSERVATIVE");
            conservativeRiskAnalystNode.doApply(new ExecuteCommandEntity(), driver.getDynamicContext());
        } catch (Exception e) { handleNodeError("ConservativeRiskAnalystNode", e, ctx); }
    }

    private void invokeNeutralNode(
            org.springframework.statemachine.StateContext<TradingPhaseEnum, TradingEventEnum> ctx) {
        try {
            getContext(ctx).getRiskDebate().setLatestSpeaker("NEUTRAL");
            neutralRiskAnalystNode.doApply(new ExecuteCommandEntity(), driver.getDynamicContext());
        } catch (Exception e) { handleNodeError("NeutralRiskAnalystNode", e, ctx); }
    }

    private void invokeNeutralRiskAnalystNode(
            org.springframework.statemachine.StateContext<TradingPhaseEnum, TradingEventEnum> ctx) {
        try {
            getContext(ctx).getRiskDebate().setLatestSpeaker("NEUTRAL");
            neutralRiskAnalystNode.doApply(new ExecuteCommandEntity(), driver.getDynamicContext());
        } catch (Exception e) { handleNodeError("NeutralRiskAnalystNode", e, ctx); }
    }

    private void handleNodeError(String nodeName, Exception e,
            org.springframework.statemachine.StateContext<TradingPhaseEnum, TradingEventEnum> ctx) {
        log.error("{} 执行失败", nodeName, e);
        ctx.getExtendedState().getVariables()
                .put(DriverContext.KEY_ERROR_MESSAGE, nodeName + " 执行失败: " + e.getMessage());
        sendEventAsync(ctx, TradingEventEnum.ERROR_OCCURRED);
    }
}
```

### Task 5 — TradingStateMachineDriver.java

路径：`ai-agent-study-trading/ai-agent-study-trading-domain/src/main/java/denny/ai/agent/trading/domain/config/TradingStateMachineDriver.java`

**设计要点**：
- **无状态单例**：Driver 不持有 `stateMachine`、`dynamicContext`、`sseSender` 引用
- **并发安全**：多线程并发调用各自传入独立的上下文，不会互相覆盖
- `sendEvent()` 接收外部传入的 `stateMachine`，而非从成员变量获取

```java
package denny.ai.agent.trading.domain.config;

import denny.ai.agent.domain.model.entity.AutoAgentExecuteResultEntity;
import denny.ai.agent.domain.service.auto.step.factory.DefaultAutoAgentExecuteStrategyFactory;
import denny.ai.agent.trading.api.vo.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.statemachine.*;
import org.springframework.statemachine.config.StateMachineFactory;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.util.function.BiConsumer;

@Slf4j
@Component
public class TradingStateMachineDriver {

    private final StateMachineFactory<TradingPhaseEnum, TradingEventEnum> stateMachineFactory;

    public TradingStateMachineDriver(
            StateMachineFactory<TradingPhaseEnum, TradingEventEnum> stateMachineFactory) {
        this.stateMachineFactory = stateMachineFactory;
    }

    // ===== 事件发送 API =====

    /**
     * 异步发送事件（无 blockLast()）。
     * 由节点 doApply() 末尾调用，通知状态机当前阶段完成。
     */
    public void sendEvent(StateMachine<TradingPhaseEnum, TradingEventEnum> stateMachine,
                          TradingEventEnum event) {
        if (stateMachine == null) {
            throw new IllegalStateException("StateMachine 未启动");
        }
        log.info("Driver 发送事件: {}", event);
        Message<TradingEventEnum> message = MessageBuilder.withPayload(event).build();
        // 异步发送，不阻塞调用线程
        stateMachine.sendEvent(Mono.just(message)).subscribe();
    }

    // ===== 简化事件 API（节点调用，stateMachine 由调用方传入）=====

    public void analystComplete(StateMachine<TradingPhaseEnum, TradingEventEnum> sm) {
        sendEvent(sm, TradingEventEnum.ANALYST_COMPLETE);
    }
    public void debateComplete(StateMachine<TradingPhaseEnum, TradingEventEnum> sm) {
        sendEvent(sm, TradingEventEnum.INVESTMENT_DEBATE_COMPLETE);
    }
    public void debateFinish(StateMachine<TradingPhaseEnum, TradingEventEnum> sm) {
        sendEvent(sm, TradingEventEnum.DEBATE_FINISH);
    }
    public void debateContinue(StateMachine<TradingPhaseEnum, TradingEventEnum> sm) {
        sendEvent(sm, TradingEventEnum.CONTINUE_DEBATE);
    }
    public void traderComplete(StateMachine<TradingPhaseEnum, TradingEventEnum> sm) {
        sendEvent(sm, TradingEventEnum.TRADER_COMPLETE);
    }
    public void riskDebateComplete(StateMachine<TradingPhaseEnum, TradingEventEnum> sm) {
        sendEvent(sm, TradingEventEnum.RISK_DEBATE_COMPLETE);
    }
    public void portfolioComplete(StateMachine<TradingPhaseEnum, TradingEventEnum> sm) {
        sendEvent(sm, TradingEventEnum.PORTFOLIO_COMPLETE);
    }

    // ===== SSE 发送（由 Starter 初始化后 Driver 持有）=====

    private DefaultAutoAgentExecuteStrategyFactory.DynamicContext dynamicContext;
    private BiConsumer<String, Object> sseSender;

    public void init(DefaultAutoAgentExecuteStrategyFactory.DynamicContext dynamicContext,
                     BiConsumer<String, Object> sseSender) {
        this.dynamicContext = dynamicContext;
        this.sseSender = sseSender;
    }

    public DefaultAutoAgentExecuteStrategyFactory.DynamicContext getDynamicContext() {
        return dynamicContext;
    }

    public void sendSseResult(String type, String subType, String content, boolean completed) {
        if (sseSender == null || dynamicContext == null) return;
        AutoAgentExecuteResultEntity event = AutoAgentExecuteResultEntity.builder()
                .type(type).subType(subType).step(dynamicContext.getStep())
                .content(content).completed(completed)
                .timestamp(System.currentTimeMillis())
                .build();
        sseSender.accept(type, event);
    }

    public void stop(StateMachine<TradingPhaseEnum, TradingEventEnum> sm) {
        if (sm != null) {
            sm.stopReactively().subscribe();
            log.info("TradingStateMachine 已停止");
        }
    }
}
```

### Task 6 — TradingStateMachineStarter.java

路径：`ai-agent-study-trading/ai-agent-study-trading-domain/src/main/java/denny/ai/agent/trading/domain/config/TradingStateMachineStarter.java`

**设计要点**：
- Starter 是请求级入口，每次 `start()` 创建新的 `stateMachine` 实例
- `stateMachine` 在 Starter 生命周期内持有，请求结束时 `stop()`

```java
package denny.ai.agent.trading.domain.config;

import denny.ai.agent.domain.service.auto.step.factory.DefaultAutoAgentExecuteStrategyFactory;
import denny.ai.agent.trading.api.provider.IStockDataProvider;
import denny.ai.agent.trading.api.vo.*;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.statemachine.StateMachine;
import org.springframework.statemachine.state.ExtendedState;
import org.springframework.stereotype.Service;

import java.util.function.BiConsumer;

@Slf4j
@Service
public class TradingStateMachineStarter {

    @Resource private TradingStateMachineDriver driver;
    @Resource private IStockDataProvider dataProvider;

    /**
     * 开始交易分析流程。
     * 每次调用创建独立的 stateMachine 实例，请求间互不影响。
     */
    public void start(StockAnalysisRequestVO request,
                      DefaultAutoAgentExecuteStrategyFactory.DynamicContext dynamicContext,
                      BiConsumer<String, Object> sseSender) {
        // 1. 初始化 Driver 上下文（Driver 无状态，此处注入本次请求的上下文）
        driver.init(dynamicContext, sseSender);

        // 2. 从工厂获取新实例（每个请求独立）
        StateMachine<TradingPhaseEnum, TradingEventEnum> sm =
                driver.stateMachineFactory().getStateMachine();

        // 3. 预置初始变量（由 initAction 读取）
        ExtendedState extendedState = sm.getExtendedState();
        extendedState.getVariables().put(DriverContext.KEY_TRADING_REQUEST, request);
        extendedState.getVariables().put(DriverContext.KEY_STOCK_PROVIDER, dataProvider);
        extendedState.getVariables().put(DriverContext.KEY_ANALYST_INDEX, 0);
        extendedState.getVariables().put(DriverContext.KEY_ERROR_MESSAGE, null);

        // 4. 启动状态机
        sm.startReactively().block();

        // 5. 发送首个事件，触发 INIT → ANALYST_COLLECTION
        driver.sendEvent(sm, TradingEventEnum.START_TRADING);

        log.info("交易状态机已启动: ticker={}, smId={}",
                request.getTicker(), sm.getId());
    }
}
```

**注意**：`TradingStateMachineDriver` 需要暴露 `stateMachineFactory()` getter 供 Starter 调用，或将 factory 单独注入到 Starter。推荐方案：在 `Starter` 中直接注入 `StateMachineFactory`，Driver 只负责发送事件。

```java
// 推荐：Starter 同时注入 factory 和 driver
@Resource private StateMachineFactory<TradingPhaseEnum, TradingEventEnum> stateMachineFactory;
@Resource private TradingStateMachineDriver driver;
@Resource private IStockDataProvider dataProvider;

public void start(...) {
    StateMachine<TradingPhaseEnum, TradingEventEnum> sm = stateMachineFactory.getStateMachine();
    // ...
    driver.sendEvent(sm, TradingEventEnum.START_TRADING);
}
```

---

## DriverContext 常量

路径：`ai-agent-study-trading/ai-agent-study-trading-domain/src/main/java/denny/ai/agent/trading/domain/config/DriverContext.java`

```java
package denny.ai.agent.trading.domain.config;

public final class DriverContext {
    private DriverContext() {}

    public static final String KEY_TRADING_REQUEST   = "trading_request";
    public static final String KEY_TRADING_CONTEXT   = "trading_context";
    public static final String KEY_SELECTED_ANALYSTS = "selected_analysts";
    public static final String KEY_ANALYST_INDEX     = "analyst_index";
    public static final String KEY_DEBATE_ROUND       = "debate_round";
    public static final String KEY_STOCK_PROVIDER     = "stock_provider";
    public static final String KEY_ERROR_MESSAGE       = "error_message";
}
```

---

## 节点重构模式（Task 7~16 统一模板）

所有节点改动原则一致：

1. **删除** `TradingNodeRegistry nodeRegistry` 注入
2. `doApply()` 末尾业务逻辑完成后，调用 `driver` 简化 API（**传入当前 stateMachine**）
3. `get()` 方法改为返回 `null`（状态机接管路由）

**关键变更**：所有 `driver.*Complete()` API 签名变了，需要传入 `StateMachine`：

```java
// 旧：driver.analystComplete();
// 新：
driver.analystComplete(currentStateMachine);
```

**各节点调用方式**：

| 节点 | 调用 Driver 方法 |
|---|---|
| 4 个 AnalystNode | `driver.analystComplete(sm)` |
| Bull/Bear ResearcherNode | `driver.debateComplete(sm)` |
| ResearchManagerNode | `driver.debateFinish(sm)` 或 `driver.debateContinue(sm)` |
| TraderNode | `driver.traderComplete(sm)` |
| 3 个 RiskAnalystNode | `driver.riskDebateComplete(sm)` |
| PortfolioManagerNode | `driver.portfolioComplete(sm)`（仅发完成信号，SSE 由 node 自行发送） |

**StateMachine 获取方式**：节点 `doApply()` 的 `StrategyHandler` 上下文参数中，应能获取当前 `stateMachine`。若框架层不直接提供，建议在 `doApply()` 入口处通过 `DriverContext` 注入：

```java
// 方案：在 DriverContext 中添加 sm 字段
public class DriverContext {
    public void setStateMachine(StateMachine<TradingPhaseEnum, TradingEventEnum> sm) {
        this.stateMachine = sm;
    }
    public StateMachine<TradingPhaseEnum, TradingEventEnum> getStateMachine() {
        return stateMachine;
    }
}

// Starter.start() 中：
DriverContext ctx = new DriverContext(dynamicContext, sm);
driver.setContext(ctx);

// 节点调用：
driver.analystComplete(ctx.getStateMachine());
```

**ResearchManagerNode 辩论结束判断**：
```java
// doApply() 末尾
private void decideDebateContinuation(InvestmentDebateVO debate) {
    boolean roundExhausted = debate.getCurrentRound() >= debate.getMaxRounds();
    boolean debateComplete = debate.isDebateComplete();
    boolean needMore = debate.isNeedMoreDebate();
    StateMachine<TradingPhaseEnum, TradingEventEnum> sm = driverContext.getStateMachine();

    if (roundExhausted || debateComplete) {
        driver.debateFinish(sm); // 轮次耗尽优先
    } else if (needMore) {
        driver.debateContinue(sm); // 继续辩论
    } else {
        driver.debateFinish(sm); // 兜底保守结束
    }
}
```

**IntentRoutingNode**：
```java
@Resource private TradingStateMachineStarter starter;
// doApply() 中：
starter.start(tradingRequest, dynamicContext, this::sendSseResult);
```

---

## 边界条件

| # | 边界条件 | 处理方式 |
|---|---|---|
| BC-1 | 未选择分析师 | `initAction` 直接发送 `ALL_ANALYSTS_COMPLETE` 跳过收集阶段 |
| BC-2 | 股票数据获取失败 | 发送 `ERROR_OCCURRED` 进入 ERROR |
| BC-3 | 节点 LLM 调用失败 | `invoke*Node()` 的 try-catch → 发送 `ERROR_OCCURRED` |
| BC-4 | 辩论/风控轮次耗尽 | Action 中优先判断，强制进入下一阶段 |
| BC-5 | 分析师索引越界 | `analystProgressAction` 先判断 `index < size()` 再调用 |
| BC-6 | ERROR 状态 | `errorAction` 发送 SSE 错误事件并记录日志 |
| BC-7 | 并发请求隔离 | 每个请求从 `StateMachineFactory` 获取独立实例，ExtendedState 不共享 |
| BC-8 | ExtendedState 残留 | `initAction` 入口执行 `clear()`，每次启动清理干净 |

---

## 执行记录

> 每完成一个任务，在此追加一行。例如：
> `2026-04-24 14:30 | 张三 | Task 3 | pass | 依赖版本 4.3.1`

| 时间 | 执行人 | Task # | 状态 | 备注 |
|---|---|---|---|---|
| — | — | — | — | — |

---

## 状态说明

- **pending**：待执行
- **pass**：执行成功
- **fail**：执行失败（需记录原因）
