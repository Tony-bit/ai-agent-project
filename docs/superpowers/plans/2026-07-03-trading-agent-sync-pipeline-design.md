# Trading Agent 同步管道状态机改造设计

> **创建时间:** 2026-07-03  
> **状态:** 待用户审阅  
> **目标范围:** 在保持现有交易分析功能、SSE 输出、分析师并行、子任务同步返回和错误关闭语义不变的前提下，将当前 `TradingDriver + TradingDispatcher + 异步事件推进` 的实现方式，演进为更清晰的同步责任链/管道式状态机。

---

## 1. 背景

当前 trading agent 的核心流程由 `TradingStarter` 创建请求级 `TradingStateContext`、`TradingContextVO` 和 `TradingDriver`，再通过 `TradingDispatcher.onEvent()` 根据 `TradingPhase + TradingEvent` 推进。

当前主链路大致为：

```text
TradingAnalysisController / AutoAgent 路由
  -> TradingStarter.start() / startForSubTask()
  -> TradingDriver.setCurrent(driver)
  -> TradingDispatcher.onEvent(START_TRADING)
  -> Dispatcher 投递异步节点任务
  -> 节点完成后 TradingDriver.getCurrent().xxxComplete()
  -> Dispatcher 收到事件后继续投递下一节点
  -> 最终报告完成后 countDownTaskLatch()
  -> TradingStarter.await() 返回并关闭 SSE / 返回子任务结果
```

这个方案可以工作，但当前讨论中暴露出几个心智负担：

- Controller 已经异步返回 SSE，但 `TradingStarter` 内部又通过 `taskLatch.await()` 等待内部异步状态机完成。
- Dispatcher 内部存在多层 `CompletableFuture.runAsync(...)`，节点推进路径不够直观。
- 部分阶段由 `CompletableFuture.whenComplete` 推进，部分阶段由节点调用 `TradingDriver` 推进，流程控制权分散。
- `TradingDriver` 作为 ThreadLocal 请求级门面是实用折中，但它让节点和状态机推进产生隐式耦合。

经过讨论，当前 trading agent 更像稳定的分析管道：

```text
初始化
  -> 分析师集合
  -> 投资辩论
  -> 推荐决策
  -> 风控辩论
  -> 最终报告
```

因此可以考虑改造成同步责任链/管道式状态机：主流程同步调用各阶段，每个阶段内部封装自己的分支、循环、超时、异常和必要的局部并行。

---

## 2. 设计目标

1. 保持现有对外入口兼容：`TradingStarter.start(...)` 和 `startForSubTask(...)` 的签名不变。
2. 保持 HTTP SSE 行为兼容：前端仍然先拿到 `ResponseBodyEmitter`，过程事件继续流式发送，最终完成后关闭。
3. 保持 AutoAgent 子任务行为兼容：`startForSubTask(...)` 仍然同步返回最终文本，供 `MultiTaskExecutionNode` 汇总。
4. 保持分析师阶段并行：基本面、技术面、情绪、新闻分析仍然并发执行。
5. 将整体流程改为显式同步编排：阶段之间由 pipeline/stage 主动调用，而不是节点完成后通过 `TradingDriver` 反向发事件推进。
6. 降低 `TradingDriver` 和 ThreadLocal 的必要性，允许分阶段兼容保留，最终可逐步移除。
7. 统一 SSE 关闭、错误处理和最终结果构建时机。
8. 避免一次性大改，提供兼容迁移路径，降低回归风险。

---

## 3. 非目标

1. 不改变 Prompt 模板、LLM 调用内容、分析报告结构。
2. 不改变 `IStockDataProvider`、Tushare、Sina、Mock 等数据提供器接口。
3. 不改变前端消费的 SSE 基本事件语义。
4. 不要求一次提交移除 `TradingDispatcher`、`TradingDriver`、`TradingEvent`、`TradingPhase`。
5. 不引入 Spring StateMachine、Akka、Reactor workflow 等新框架。
6. 不在本次设计中实现暂停/恢复、持久化状态机或跨服务调度。

---

## 4. 当前功能兼容清单

| 功能 | 当前实现 | 同步管道后的保持方式 |
| --- | --- | --- |
| 独立 HTTP 分析接口 | `TradingAnalysisController` 创建 `ResponseBodyEmitter`，异步调用 `TradingStarter.start()` | Controller 仍后台执行 pipeline，立即返回 emitter |
| AutoAgent 股票分析 | 路由到 `tradingIntentRoutingNode` 或 `tradingStarter` | `TradingStarter` 入口保持不变 |
| 多任务子任务汇总 | `startForSubTask()` 等待流程完成后返回 String | pipeline 同步执行完成后构建并返回 String |
| 分析师并行 | Dispatcher 中 `CompletableFuture.allOf(...)` | `AnalystCollectionStage` 内部保留并行 |
| 投资辩论轮次 | Dispatcher 根据 latest speaker 和 debate 状态推进 | `InvestmentDebateStage` 内部封装轮次循环和结束判断 |
| 推荐决策 | RecommendationNode 完成后发事件进入风控 | `RecommendationStage` 调用节点后继续下一阶段 |
| 风控辩论轮次 | Risk node 通过 `TradingDriver.riskDebateComplete()` 推进 | `RiskManagementStage` 内部按固定顺序调用并判断轮次 |
| 最终报告 | Portfolio node 完成后触发 `PORTFOLIO_COMPLETE` 和 latch | `FinalReportStage` 完成后返回 pipeline，由 starter 统一关闭 |
| SSE 过程事件 | 节点通过 `dynamicContext` / `stateContext` 发送 | 节点发送方式尽量保留，stage 发送阶段事件 |
| 错误释放 | `TradingStateContext.sendError()` countDown latch | 同步 pipeline 通过 `sendTerminalErrorOnce(...)` 原子发送终态 error 并结束流程 |
| Markdown 导出 | `TradingResultExportService.export()` 异步执行 | 保留异步导出，但必须使用独立 `tradingExportExecutor` |

---

## 5. 推荐方案：同步 Pipeline + Stage 责任链

### 5.1 核心结构

新增一组明确的阶段组件：

```text
TradingPipeline
  -> AnalystCollectionStage
  -> InvestmentDebateStage
  -> RecommendationStage
  -> RiskManagementStage
  -> FinalReportStage
```

每个 stage 只关心自己阶段的规则：

```java
public interface TradingStage {
    String name();
    TradingPhase expectedPhase();
    TradingPhase nextPhase();
    void execute(TradingStateContext context);
}
```

主流程负责串联，也负责阶段契约校验。不能只在 `ERROR` 时 break，否则某个 stage 漏掉 transition 后，后续 stage 会继续读取半成品 context 并造成隐蔽污染。

```java
public class TradingPipeline {
    private final List<TradingStage> stages;

    public void execute(TradingStateContext context) {
        for (TradingStage stage : stages) {
            if (context.getCurrentPhase() == TradingPhase.ERROR) {
                break;
            }
            validateBefore(stage, context);
            stage.execute(context);
            validateAfter(stage, context);
        }
    }

    private void validateBefore(TradingStage stage, TradingStateContext context) {
        if (context.getCurrentPhase() != stage.expectedPhase()) {
            throw new TradingPipelineException(
                "Stage " + stage.name() + " expected " + stage.expectedPhase()
                    + " but was " + context.getCurrentPhase());
        }
    }

    private void validateAfter(TradingStage stage, TradingStateContext context) {
        if (context.getCurrentPhase() == TradingPhase.ERROR) {
            return;
        }
        if (context.getCurrentPhase() != stage.nextPhase()) {
            throw new TradingPipelineException(
                "Stage " + stage.name() + " must transition to " + stage.nextPhase()
                    + " but was " + context.getCurrentPhase());
        }
    }
}
```

阶段契约建议如下：

| Stage | expectedPhase | nextPhase |
| --- | --- | --- |
| AnalystCollectionStage | `INIT` | `INVESTMENT_DEBATE` |
| InvestmentDebateStage | `INVESTMENT_DEBATE` | `RECOMMENDATION_DECISION` |
| RecommendationStage | `RECOMMENDATION_DECISION` | `RISK_MANAGEMENT` |
| RiskManagementStage | `RISK_MANAGEMENT` | `FINAL_REPORT` |
| FinalReportStage | `FINAL_REPORT` | `FINAL_REPORT` |

`TradingPipelineException` 应由 `TradingStarter` 捕获并转换为 `stateContext.sendTerminalErrorOnce(...)`，然后停止流程、关闭 SSE。这样阶段漏 transition、阶段顺序配置错误、阶段提前进入错误状态都能 fail fast。

这不是把代码退化为大脚本，而是把状态机从“事件回调式”改为“显式阶段编排式”。状态仍然存在，只是由 stage/pipeline 主动推进。

### 5.2 目标调用链

```text
TradingStarter.start()
  -> 创建 TradingStateContext
  -> populateStockInfo()
  -> dynamicContext.setValue("trading_context", ...)
  -> tradingPipeline.execute(stateContext)
  -> finishAndCloseSse()
```

`startForSubTask()` 类似：

```text
TradingStarter.startForSubTask()
  -> 创建 TradingStateContext
  -> tradingPipeline.execute(stateContext)
  -> buildResultFromContext(stateContext)
  -> return String
```

#### 5.2.1 与当前 taskLatch 的兼容边界

同步 pipeline 分支必须完全绕开当前 `taskLatch.await()` 生命周期。`taskLatch` 只属于旧的异步事件状态机路径，用来把 `TradingDispatcher` 内部异步任务重新等回 `TradingStarter`。

因此迁移期 `TradingStarter` 需要明确分成两条互斥路径：

```java
if (useSyncPipeline) {
    tradingPipeline.execute(stateContext);
    finishAndCloseSse(stateContext, dynamicContext);
    return;
}

runLegacyAsyncDispatcherWithLatch(stateContext, dynamicContext);
```

同步 pipeline 路径的规则：

- 不创建 `CountDownLatch`，或至少不把 `taskLatch` 放入 `dynamicContext` 作为完成信号。
- 不调用 `taskLatch.await()`。
- `FinalReportStage` 不调用 `countDownTaskLatch()`。
- pipeline 正常返回即表示交易流程已完成。
- pipeline 抛出异常或进入 `ERROR` 即表示流程已终止，由 `TradingStarter` 通过 `sendTerminalErrorOnce(...)` 统一发送错误并关闭 SSE。

旧异步 Dispatcher 路径的规则：

- 继续创建 `taskLatch`。
- 继续由 `TradingStateContext.sendError()` 和 `PORTFOLIO_COMPLETE` 释放 latch。
- 继续在 `TradingStarter` 中等待 latch 后关闭 SSE。

这条边界是第一阶段切流量的硬约束。不要让同步 pipeline 执行完后再落入旧的 `finally { taskLatch.await(); }`，否则会因为没有任何 stage 释放 latch 而挂住。

### 5.3 阶段职责

#### AnalystCollectionStage

职责：

- 发送 `trading_init`、分析师阶段开始事件。
- 根据 `stateContext.getSelectedAnalysts()` 并行执行核心分析师节点。
- 等待核心分析师全部完成或阶段总超时，收集成功、失败、超时集合。
- 对核心分析师部分失败执行显式降级：发送 `partial_success` SSE，保留失败明细，然后继续进入辩论阶段。
- 全部核心分析师失败或超时时执行整体失败收口，不进入辩论阶段。
- 至少一个核心分析师成功后切换到 `INVESTMENT_DEBATE`。

并行方式：

```java
List<CompletableFuture<AnalystResult>> futures = analysts.stream()
    .map(analyst -> CompletableFuture.supplyAsync(
        () -> invokeAnalyst(analyst, context),
        tradingTaskExecutor))
    .toList();

CompletableFuture.allOf(...).join();
```

partial success 成为默认策略后，分析师阶段必须优先采用“并行任务返回结果，stage 单线程汇总写入 context”的模式。

如果短期为了兼容旧节点仍调用 `doApply()`，不能把真实共享 `TradingContextVO` 传给节点后只在 invoker 外层做 active 检查。旧节点会在 `doApply()` 内部直接 `context.setXxx(...)` 或修改嵌套对象，外层检查拦不住超时后的 late write。短期兼容必须改为：

- 为每个 analyst task 创建独立的 `TradingContextVO` 工作副本和 `DynamicContext` 副本。
- 副本中 `trading_context` 指向工作副本，不指向 pipeline 的真实共享 context。
- 副本中的 SSE sender 必须是带 `runId/stageToken` 检查的 guarded sender；阶段收口后，旧节点迟到发送的过程事件必须被丢弃。
- 旧节点只允许写工作副本；节点按时完成且 `runId`、阶段写入门禁仍 active 时，由 `AnalystCollectionStage` 把白名单字段合并回真实 context。
- 节点失败、超时、阶段已经 `partial_success` 收口、或 pipeline 已 terminal 时，直接丢弃工作副本。
- 工作副本必须是能隔离可变嵌套对象的深拷贝或 stage 专用快照，不能共享 `InvestmentDebateVO`、`RiskDebateVO`、report list 等可变引用。

失败与降级策略：

- 核心分析师定义：本次请求的 `stateContext.getSelectedAnalysts()`。如果请求未指定，则使用 `TradingStateContext` 的默认分析师集合，即 `FUNDAMENTAL`、`TECHNICAL`、`SENTIMENT`、`NEWS`。
- 默认规则为 **可降级继续但必须显式 partial_success**：核心分析师节点抛异常、返回失败结果、超过分析师阶段总超时，或 fan-in 后发现核心分析师缺少必要报告字段时，`AnalystCollectionStage` 必须记录失败分析师、失败原因和已有成功报告。
- 只要至少一个核心分析师成功产出报告，pipeline 不进入 `ERROR`，而是发送一次 `trading/partial_success` SSE，`completed=false`，内容包含 `succeededAnalysts`、`failedAnalysts`、`timedOutAnalysts` 和降级说明。
- 发送 `partial_success` 后继续切换到 `INVESTMENT_DEBATE`，并发送正常的 `debate_start`。辩论阶段的 prompt 必须读取缺失分析师列表，明确告诉研究员哪些视角缺失，不能把缺失报告当作空观点或中性观点。
- 只有核心分析师全部失败、全部超时，或没有任何核心分析师产出必要报告时，才由 `TradingStarter` 通过 `sendTerminalErrorOnce(...)` 发送一次 `trading/error` 且 `completed=true`，随后关闭 SSE。
- 对仍在执行的超时分析师 `Future` 执行 `cancel(true)`；取消是 best-effort，迟到结果必须通过 `runId/terminal` 或阶段 active 门禁丢弃，不能补写已经进入辩论阶段的 context。
- 未被用户选择的分析师不属于核心分析师，不参与完整性判断。
- 行情/新闻等外部数据为空但节点内部已经降级并正常产出报告时，视为该分析师成功；只有节点异常、超时、明确失败结果或必要报告缺失才视为分析师失败。
- 最终报告必须暴露本次分析是否为 `partial_success`，并列出缺失/失败的分析师，避免用户误以为四类视角全部完成。

#### InvestmentDebateStage

职责：

- 初始化 `InvestmentDebateVO`。
- 按 `BULL -> BEAR -> RESEARCH_MANAGER` 顺序同步调用节点。
- 由研究主管结果决定继续辩论或结束。
- 内部处理最大轮次。
- 完成后切换到 `RECOMMENDATION_DECISION`。

阶段内部可以有循环，但循环只存在于本阶段内部，主流程不感知。

#### RecommendationStage

职责：

- 初始化 `RiskDebateVO` 和最大风控轮次。
- 调用 `RecommendationNode` 生成投资建议。
- 触发或保留异步导出 Markdown。
- 完成后切换到 `RISK_MANAGEMENT`。

#### RiskManagementStage

职责：

- 按 `AGGRESSIVE -> CONSERVATIVE -> NEUTRAL` 顺序同步调用风控节点。
- 根据 `riskDebate.totalExchangeCount` 和 `maxRounds` 判断是否进入下一轮。
- 完成后切换到 `FINAL_REPORT`。

#### FinalReportStage

职责：

- 发送最终报告开始事件。
- 调用 `PortfolioManagerNode`。
- 写入 `tradingFinalDecision`。
- 发送最终完成事件。
- 不再通过 `PORTFOLIO_COMPLETE` 事件或 `countDownTaskLatch()` 释放 latch，而是返回 pipeline，由 `TradingStarter` 统一关闭。

---

## 6. 线程模型

### 6.1 必须保留的异步边界

Controller 层仍应异步执行 trading pipeline，避免占用 HTTP 请求线程：

```text
HTTP request thread
  -> create ResponseBodyEmitter
  -> submit orchestration task
  -> return emitter
```

### 6.2 必须拆分三个 executor

同步 pipeline 会在编排线程中等待阶段完成，因此线程池拆分是验收条件，不是优化项。必须区分：

```text
tradingOrchestrationExecutor
  用于执行一次完整 trading pipeline，可以同步等待阶段完成。Controller 必须把后台分析任务提交到此 executor。

tradingTaskExecutor
  用于分析师并行、节点 LLM/HTTP 调用等具体业务任务。

tradingExportExecutor
  用于 Markdown 导出、文件写入等低优先级后处理任务。
```

硬性规则：

- `TradingAnalysisController` 不得使用 `CompletableFuture.runAsync(...)` 的默认 common pool。当前 Controller 中无 executor 参数的 `runAsync` 需要改为显式提交到 `tradingOrchestrationExecutor`。
- `TradingStarter.start()` / `TradingPipeline.execute()` 不得提交到 `tradingTaskExecutor`。编排线程会等待分析师 fan-in 和串行节点返回，复用节点池会造成线程饥饿。
- `AnalystCollectionStage` 和其他节点调用只能使用 `tradingTaskExecutor`。
- `TradingResultExportService.export()` 不得继续使用 `@Async("tradingTaskExecutor")`，必须改为 `@Async("tradingExportExecutor")`。
- `tradingExportExecutor` 不需要传播 `TradingDriver` ThreadLocal；导出失败只记录日志，不影响 pipeline 阶段推进、SSE 完成和最终结论。
- 三个 executor 的线程名前缀必须可区分，例如 `trading-orchestration-`、`trading-task-`、`trading-export-`，方便日志和压测定位。

验收标准：

- Controller 正常入口通过注入的 `tradingOrchestrationExecutor` 提交后台任务，代码中不存在无 executor 参数的 `CompletableFuture.runAsync(...)`。
- pipeline 执行线程名来自 `tradingOrchestrationExecutor`，分析师/节点任务线程名来自 `tradingTaskExecutor`。
- 导出任务线程名来自 `tradingExportExecutor`，且不占用 `tradingTaskExecutor`。
- 在 `tradingTaskExecutor` 被分析师任务打满时，新的 HTTP 请求仍能被 Controller 提交到 `tradingOrchestrationExecutor` 并返回 emitter；在 `tradingExportExecutor` 堆积时，不影响节点执行和 SSE 正常完成。

### 6.3 ThreadLocal 的变化

同步 pipeline 后，stage 显式持有 `TradingStateContext`，节点也可以继续通过 `dynamicContext.getValue("trading_context")` 获取业务上下文。

因此 `TradingDriver` 不再是流程推进必需品。

兼容迁移建议：

1. pipeline 路径默认不设置真实 `TradingDriver`。
2. `TradingStarter` 进入 pipeline 前必须执行 `TradingDriver.clear()`，确保不会继承旧路径残留的 ThreadLocal。
3. `AnalystCollectionStage` 提交并行任务前也必须保证当前线程 `TradingDriver.getCurrent() == null`，因为 `TradingExecutorConfig` 会在提交任务时捕获当前 Driver。
4. 节点中现有的 `if (TradingDriver.getCurrent() != null) { ... }` 调用会因为 current 为 null 自然 no-op。
5. 新 stage 不依赖节点调用 `TradingDriver.xxxComplete()` 推进流程，而是读取 `TradingContextVO` 和 `TradingStateContext` 后自行决策。
6. 稳定后再逐步移除节点末尾的 `TradingDriver` 推进调用。

pipeline 路径禁止设置带真实 `TradingDispatcher` 的 Driver。否则 `RecommendationNode.recommendationComplete()`、风控节点 `riskDebateComplete()`、`ResearchManagerNode.debateFinish()/debateContinue()` 会重新触发旧 Dispatcher，导致新旧两套状态机同时推进。

如果短期必须避免 `ResearchManagerNode` 在 Driver 为 null 时打印 warning，可以引入专门的 `PipelineCompatDriver`，但它必须满足以下约束：

- 不持有真实 `TradingDispatcher`。
- 覆盖所有推进方法：`analystComplete()`、`allAnalystsComplete()`、`debateComplete()`、`debateContinue()`、`debateFinish()`、`recommendationComplete()`、`riskDebateComplete()`、`portfolioComplete()`，全部 no-op。
- 覆盖 `errorOccurred(String msg)`，不得调用 `stateContext.sendError(...)`，避免绕过 pipeline/starter 的统一错误收口。
- 覆盖 `sendSseResult(String type, String subType, String content, boolean completed)`，默认 no-op。若迁移期确实需要兼容旧节点过程事件，也只能通过 stage token guarded sender 转发 `completed=false` 的非终态过程事件；严禁发送 `trading_complete`、`trading/error`、`final_completed` 或任何 `completed=true` 事件。
- 不作为 SSE 关闭或终态完成事件入口，避免 `PortfolioManagerNode`、`FinalReportStage` 和 `TradingStarter` 重复发送最终完成事件。
- 只作为短期兼容层存在，不能成为新 pipeline 的流程推进机制。

推荐第一阶段采用“pipeline 不设置 Driver”的方案，而不是 no-op driver。Stage 需要补齐原本由 Driver 间接触发的行为：

- `InvestmentDebateStage` 在 `ResearchManagerNode` 返回后读取 `InvestmentDebateVO.needMoreDebate`、`isDebateComplete()` 和轮次信息，决定继续或结束。
- `RecommendationStage` 在 `RecommendationNode` 返回后主动切换到 `RISK_MANAGEMENT`。
- `RiskManagementStage` 在每个风险节点返回后主动调用下一个风险节点或进入最终报告。
- `FinalReportStage` 在 `PortfolioManagerNode` 返回后只写入最终报告和必要的过程事件，不发送 `trading_complete`，不关闭 emitter；终态完成事件统一由 `TradingStarter` 发送。

---

## 7. 状态与数据流

### 7.1 保留 TradingStateContext

`TradingStateContext` 继续作为请求级状态对象，保留：

- `request`
- `dynamicContext`
- `sseSender`
- `tradingContext`
- `currentPhase`
- `latestDebateSpeaker`
- `latestRiskSpeaker`
- `errorMessage`

同步改造后可以逐步弱化：

- `taskLatch`
- 事件驱动专用的 speaker 状态
- 与 `TradingDriver` 强绑定的完成事件

### 7.2 保留 TradingContextVO 黑板模型

`TradingContextVO` 继续作为所有节点共享的分析黑板：

```text
StockInfo
FundamentalReport
TechnicalReport
SentimentReport
NewsReport
InvestmentDebate
InvestmentPlan
RiskDebate
FinalDecision
```

并行分析师阶段需要关注线程安全：

- 默认：每个 analyst task 返回结果，由 `AnalystCollectionStage` 单线程汇总写入。
- 短期兼容：如果仍保留节点直接写 context，节点必须写入每个 task 独立的工作副本，不能写真实共享 context；stage 只在 active 时合并工作副本的白名单字段。

### 7.3 运行令牌与写入门禁

同步 pipeline 仍然会在分析师阶段使用并行任务。Java 的 timeout 不等于底层任务一定停止：LLM/HTTP 阻塞调用可能在 pipeline 已经进入 `ERROR`、进入 `partial_success` 后续阶段，或 SSE 已关闭后才返回。为避免 late write / late SSE，需要在请求级上下文中增加运行令牌、终止标记和阶段写入门禁。

建议在 `TradingStateContext` 中增加：

```java
private final String runId;
private final AtomicBoolean terminal = new AtomicBoolean(false);
private final AtomicBoolean terminalEventSent = new AtomicBoolean(false);
private final AtomicBoolean analystCollectionClosed = new AtomicBoolean(false);
private final AtomicReference<String> activeStageToken = new AtomicReference<>();

public boolean isTerminal() { return terminal.get(); }
private void markTerminalWithoutEvent(String reason) { terminal.compareAndSet(false, true); }
public boolean isActive(String taskRunId) {
    return !terminal.get() && Objects.equals(runId, taskRunId);
}
public boolean canWriteAnalystResult(String taskRunId) {
    return isActive(taskRunId) && !analystCollectionClosed.get();
}
public boolean canMergeStageResult(String stageToken) {
    return !terminal.get() && Objects.equals(activeStageToken.get(), stageToken);
}
public boolean canSendStageEvent(String stageToken) {
    return !terminal.get() && Objects.equals(activeStageToken.get(), stageToken);
}
public boolean sendTerminalCompleteOnce() {
    if (!terminalEventSent.compareAndSet(false, true)) return false;
    terminal.set(true);
    sendSseResultBypassTerminalGuard("trading", "trading_complete", "交易分析完成", true);
    return true;
}
public boolean sendTerminalErrorOnce(String message) {
    if (!terminalEventSent.compareAndSet(false, true)) return false;
    terminal.set(true);
    sendSseResultBypassTerminalGuard("trading", "error", message, true);
    return true;
}
```

规则：

- 每次 pipeline 执行创建唯一 `runId`。
- 每个并行任务提交时捕获当前 `runId`。
- 每个可超时 stage 开始时创建 `stageToken`，stage 收口后立即关闭或替换 token。
- 真实共享 `TradingContextVO` 只能由 stage 在 active 检查通过后写入。
- 带 timeout 的旧节点如果仍会在 `doApply()` 内直接写 context，必须拿到隔离的工作副本，不能拿到真实共享 context。
- 发送 SSE 前必须检查 `stateContext.isTerminal()`；阶段内过程事件还必须检查当前 `stageToken` 是否仍 active。
- 旧节点使用工作 `DynamicContext` 时，必须注入 guarded SSE sender，在 `stageToken` 失效后丢弃 late SSE。
- `AnalystCollectionStage` fan-in 完成并决定 `SUCCESS` / `PARTIAL_SUCCESS` / `ERROR` 后，必须关闭分析师写入门禁；之后迟到的分析师结果只能记录日志。
- 终态事件必须通过 `sendTerminalCompleteOnce()` 或 `sendTerminalErrorOnce(...)` 发送；这两个 API 内部原子地设置 terminal、发送唯一终态 SSE，并返回是否真实发送。
- 裸 `markTerminalWithoutEvent(...)` 只能作为私有实现细节，不能暴露给 stage/node/starter 作为终态收口入口。
- 普通 `sendSseResult(...)` 不允许发送 `completed=true` 终态事件；terminal 后普通过程事件一律丢弃。
- 串行节点超时、整体 `ERROR`、用户取消和最终完成都必须走一次性终态 API；如未来新增取消能力，也必须是 `sendTerminalCancelOnce(...)` 这类同等语义的 API，不能先裸 `markTerminal(...)` 再调用普通 `sendSseResult(...)`。
- terminal 后任何 late result 只能记录日志，不能写 context，不能发 SSE，不能推进 phase。

推荐的新节点行为是返回 `NodeResult`，由 stage 合并：

```java
NodeResult result = invoker.invokeWithTimeout(...);
if (stateContext.isActive(result.runId())) {
    stage.applyResult(result);
}
```

短期兼容旧节点直接写 context 时，invoker 必须隔离执行上下文：

```java
NodeExecutionScope scope = nodeInvoker.invokeWithIsolatedContext(
    nodeName,
    stateContext.snapshotTradingContext(),
    stateContext.getDynamicContext(),
    timeout,
    workingDynamicContext -> oldNode.doApply(command, workingDynamicContext)
);

if (stateContext.isActive(scope.runId()) && stateContext.canMergeStageResult(scope.stageToken())) {
    stage.mergeWhitelistedFields(scope.workingTradingContext());
}
```

这里的关键约束是：`oldNode.doApply(...)` 看到的 `dynamicContext.getValue("trading_context")` 必须是 `workingTradingContext`，不是 pipeline 真实共享 context；它拿到的 SSE sender 也必须是 guarded sender。外层 active 检查只负责“是否合并工作副本”，不能被当作阻止旧节点内部写入或 late SSE 的保护。

分析师节点从“直接写 context”改为“返回结果，由 stage 汇总写入”，是 partial success 默认策略的推荐实现方式；工作副本隔离只能作为迁移期兼容路径。

---

## 8. SSE 生命周期设计

同步 pipeline 后，SSE 关闭建议集中在 `TradingStarter`：

```java
try {
    tradingPipeline.execute(stateContext);
    if (stateContext.sendTerminalCompleteOnce()) {
        completeEmitterSafely(dynamicContext);
    }
} catch (Exception e) {
    if (stateContext.sendTerminalErrorOnce(toFriendlyError(e))) {
        completeEmitterSafely(dynamicContext);
    }
}
```

兼容要求：

- 同步 pipeline 路径不得进入旧的 `taskLatch.await()` 分支。
- 过程事件仍由节点或 stage 发送。
- 正常完成时只有 `TradingStarter` 调用 `stateContext.sendTerminalCompleteOnce()` 发送 `trading/trading_complete`，且 `completed=true`。
- 正常完成时只有 `TradingStarter` 调用 `ResponseBodyEmitter.complete()`。
- `FinalReportStage`、`PortfolioManagerNode`、`TradingDriver` 和 `Controller` 都不得在正常完成路径发送 `trading_complete` 或关闭 emitter。
- `TradingAnalysisController` 正常路径在 `executeAnalysis(request, emitter)` 返回后直接结束，不再调用 `emitter.complete()`；当前 `TradingAnalysisController.java` 中 `executeAnalysis(...)` 后的正常 `emitter.complete()` 需要移除。
- `TradingAnalysisController` 只保留异常兜底：当异常逃逸出 `TradingStarter` / pipeline，且 emitter 仍可写时，Controller 可发送 `system_error` 并 `complete()`。
- 异常时必须由 `TradingStarter` 调用 `stateContext.sendTerminalErrorOnce(...)` 发送唯一 `trading/error`，然后关闭 emitter。
- 不能重复关闭 emitter 导致用户可见错误。
- `TradingStateContext.sendSseResult(...)` 只用于非终态过程事件，必须检查 terminal 状态；terminal 后 late SSE 一律丢弃并记录 debug 日志。
- `sendTerminalCompleteOnce()` / `sendTerminalErrorOnce(...)` 是唯一允许发送 `completed=true` 终态 SSE 的 API，并且内部必须绕过普通 terminal guard 完成第一次终态发送。

验收标准：

- 正常成功链路中，SSE 流里恰好出现一次 `trading_complete`，来源为 `TradingStarter -> sendTerminalCompleteOnce()`。
- 正常成功链路中，`ResponseBodyEmitter.complete()` 恰好调用一次，来源为 `TradingStarter.completeEmitterSafely(...)` 或等价封装。
- Controller 正常路径不得调用 `emitter.complete()`；测试可通过 spy emitter 或封装后的 `SseEmitterCloser` 验证调用来源。
- Controller 异常兜底只在 `executeAnalysis(...)` 抛出且 Starter 未完成收尾时触发，不参与正常完成路径。
- `FinalReportStage` 可以发送最终报告过程事件，但事件 `completed` 必须为 `false`，不得发送 `trading_complete`，不得关闭 emitter。
- 测试需要覆盖先设置 terminal 的场景：`sendTerminalCompleteOnce()` / `sendTerminalErrorOnce(...)` 仍能发送第一次终态事件；普通 `sendSseResult(..., completed=true)` 必须被拒绝或不可用。

---

## 9. 错误、超时和取消

### 9.1 错误策略

错误收口必须走 `sendTerminalErrorOnce(...)`，不能先裸 `markTerminal(...)` 再调用普通 `sendSseResult(...)`。`sendTerminalErrorOnce(...)` 负责原子地设置 terminal、发送唯一 `trading/error` 终态 SSE，并告知调用方是否需要关闭 emitter。

每个 stage 捕获自己的可恢复异常，并转换为清晰的阶段错误：

```text
AnalystCollectionStage 失败
  -> 至少一个核心分析师成功时 partial_success 并继续进入 INVESTMENT_DEBATE
  -> 没有任何核心分析师成功时 ERROR，不进入 INVESTMENT_DEBATE

InvestmentDebateStage 失败
  -> ERROR，发送友好提示

RecommendationStage 失败
  -> ERROR

RiskManagementStage 失败
  -> ERROR

FinalReportStage 失败
  -> ERROR 或降级返回已有结论
```

### 9.2 超时策略

同步 stage 不能丢掉当前的超时保护。建议统一封装：

```java
TradingNodeInvoker.invokeWithTimeout(...)
TradingNodeInvoker.invokeAllWithTimeout(...)
```

分析师阶段可以使用总超时：

```text
NODE_TIMEOUT_SECONDS * analysts.size()
```

分析师阶段的总超时不等同于立刻整体 `ERROR`。`AnalystCollectionStage` 应按分析师降级策略收口：已成功的报告进入 context，未完成的分析师记为 `timedOutAnalysts` 并发送 `partial_success`；只有没有任何核心分析师成功时才进入 `ERROR`。

串行阶段可以使用单节点超时：

```text
NODE_TIMEOUT_SECONDS
```

超时处理必须明确：

- 串行节点的 `invokeWithTimeout` 超时后抛出 `TradingPipelineException`，由 `TradingStarter` 统一调用 `sendTerminalErrorOnce(...)` 转换为 `ERROR`。
- 分析师集合使用 `invokeAllWithTimeout`，超时后先按 partial policy 判断是否可继续；可继续时不标记 terminal。
- 对底层 `Future` 执行 `cancel(true)`，但这只是 best-effort。阻塞中的 LLM/HTTP 调用可能不响应中断。
- 整体 `ERROR` 或关闭 SSE 必须通过终态 API 设置 terminal，防止后续 late SSE。
- 分析师 partial timeout 后必须关闭分析师阶段写入门禁和 stage token；`TradingNodeInvoker` 必须丢弃 timeout 之后返回的 late analyst result 或工作副本，不能再合并到真实 context。
- 所有并行任务 fan-in 时，如果任一任务导致整体 terminal，其他任务返回后不得再写 context。
- 对不支持中断的外部调用，应依赖 HTTP client/read timeout，从源头缩短 late return 窗口。

推荐串行节点 `TradingNodeInvoker.invokeWithTimeout` 的行为：

```java
try {
    return future.get(timeout, TimeUnit.SECONDS);
} catch (TimeoutException e) {
    future.cancel(true);
    throw new TradingPipelineException("节点执行超时: " + nodeName, e);
}
```

### 9.3 前端断连和取消

当前代码主要是捕获 SSE 发送失败，不主动取消后台流程。同步改造可以先保持这个行为。

后续如果要支持取消，需要新增请求级 cancel token，而不是依赖 ThreadLocal。

---

## 10. 与原事件状态机的关系

本设计不是否定状态机，而是改变状态机表达方式：

```text
原方案：
  节点完成 -> 发事件 -> Dispatcher 根据 phase/event 推进

新方案：
  Pipeline 调用 Stage -> Stage 调用节点 -> Stage 根据 context 推进
```

两者都可以表达状态机。

当前 trading agent 的流程较固定，属于一次请求内跑完的分析管道。同步责任链的可读性、可测试性和 SOLID 边界更好。

如果未来需要以下能力，事件驱动或持久化状态机仍然更合适：

- 阶段暂停后等待用户确认。
- 服务重启后恢复状态机。
- 外部 webhook 推动下一步。
- 多入口事件同时改变流程。
- 长时间跨天任务。

---

## 11. 迁移策略

### 阶段 1：新增 Pipeline，不删除旧 Dispatcher

- 新增 `TradingPipeline` 和 `TradingStage` 接口。
- 新增五个 stage 实现。
- `TradingStarter` 通过配置或临时开关选择新 pipeline。
- 保留 `TradingDispatcher`、`TradingDriver`、节点末尾 driver 调用。

目的：验证新主流程能复用现有节点和 context。

### 阶段 2：让 TradingStarter 默认走 Pipeline

- `start()` 和 `startForSubTask()` 默认调用 `TradingPipeline.execute()`。
- 保持外部接口和 SSE 行为不变。
- pipeline 路径不设置真实 `TradingDriver`，进入 pipeline 前只执行 `TradingDriver.clear()`，确保不会继承旧路径残留的 ThreadLocal。
- 只有 legacy Dispatcher 分支继续执行 `TradingDriver.setCurrent(driver)` / `TradingDriver.clear()`。

目的：对外行为稳定，内部流程变清晰。

### 阶段 3：清理节点自推进逻辑

- 移除或废弃节点末尾的 `TradingDriver.getCurrent().xxxComplete()`。
- 节点只负责业务处理和写 context。
- stage 统一决定下一步。

目的：彻底消除隐式反向推进。

### 阶段 4：收敛旧事件状态机

- 若确认无调用方依赖，移除或降级 `TradingDispatcher`。
- `TradingEvent`、部分 `TradingPhase` 状态可保留为上下文标记，也可进一步简化。
- 删除 ThreadLocal 传递测试或改为 pipeline/stage 测试。

目的：减少长期维护成本。

---

## 12. 测试策略

### 12.1 保留现有行为测试

需要覆盖：

- 分析师并行仍然执行。
- 风控顺序仍然是 `AGGRESSIVE -> CONSERVATIVE -> NEUTRAL`。
- Controller 后台任务使用 `tradingOrchestrationExecutor`，不使用 common pool，也不复用 `tradingTaskExecutor`。
- 分析师/节点任务只使用 `tradingTaskExecutor`。
- Markdown 导出使用 `tradingExportExecutor`，不复用 `tradingTaskExecutor`，导出失败不影响 pipeline。
- 最终报告完成后由 `TradingStarter` 发送唯一 `trading_complete` 并关闭 SSE。
- Controller 正常路径不调用 `emitter.complete()`；Controller 只在异常逃逸时兜底发送 `system_error` 并关闭。
- `sendTerminalErrorOnce(...)` 后流程结束，不挂起，且终态 error SSE 只发送一次。
- `startForSubTask()` 返回可汇总文本。
- pipeline 路径下 `TradingDriver.getCurrent()` 为 null，节点末尾 driver 调用不会触发旧 Dispatcher。
- stage 未从 `expectedPhase` 正确 transition 到 `nextPhase` 时，pipeline fail fast 并进入错误收尾，不继续执行后续 stage。
- 节点超时后，即使底层任务稍后返回，也不会继续写 `TradingContextVO`、发送 SSE 或改变 phase。
- 选中的部分核心分析师失败或超时但至少一个核心分析师成功时，pipeline 发送 `partial_success`，继续发送 `debate_start`，并进入辩论节点。
- 选中的核心分析师全部失败或超时时，pipeline 进入 `ERROR`，不发送 `debate_start`，不执行辩论节点。
- 未选择的分析师缺失不影响流程；已选择分析师在节点内降级产出报告时仍视为成功。
- partial success 场景下，辩论 prompt 和最终报告都包含缺失/失败分析师列表。

### 12.2 关键回归测试集

新增 5 个验收级回归测试，作为同步 pipeline 切流量的最低门槛：

1. `pipeline_does_not_wait_legacy_latch`
   - Given 启用 pipeline 路径，`TradingStarter` 初始化 `TradingStateContext` 后执行完整 stub pipeline。
   - When pipeline 正常返回或进入 `ERROR`。
   - Then 不创建、不等待或不依赖旧 `taskLatch.await()`；测试应能在短超时内完成，且不需要 `FinalReportStage.countDownTaskLatch()`。

2. `legacy_driver_calls_do_not_reenter_dispatcher`
   - Given pipeline 路径下节点末尾仍保留 `TradingDriver.getCurrent().xxxComplete()` 兼容代码。
   - When `RecommendationNode`、`ResearchManagerNode`、风控节点、`PortfolioManagerNode` 等 stub 节点执行完成，并尝试调用 Driver 推进、`errorOccurred()` 或 `sendSseResult(..., completed=true)`。
   - Then `TradingDispatcher.onEvent(...)` 不被调用，phase 只由 stage/pipeline 推进；`TradingDriver.getCurrent()` 应为 null，或 compat driver 的推进方法、`errorOccurred()` 和终态 `sendSseResult()` 必须是 no-op。

3. `research_manager_progresses_without_driver`
   - Given `ResearchManagerNode` 运行时没有真实 Driver，并写入 `InvestmentDebateVO.needMoreDebate` / `isDebateComplete()` / round 信息。
   - When `InvestmentDebateStage` 读取研究主管结果。
   - Then stage 根据 `needMoreDebate` 和最大轮次决定继续 `BULL -> BEAR -> RESEARCH_MANAGER` 或切到 `RECOMMENDATION_DECISION`，不依赖 `ResearchManagerNode.debateContinue()` / `debateFinish()` 事件。

4. `late_sse_after_node_timeout_is_ignored`
   - Given 某个旧节点或分析师任务超过 `TradingNodeInvoker` 超时后才返回，并在 `doApply()` 内尝试写 `TradingContextVO` 或发送 SSE。
   - When pipeline 已经 `ERROR` 终止，或分析师阶段已经 `partial_success` 并关闭写入门禁。
   - Then late SSE 被丢弃，late context write 只落在工作副本或被拒绝，真实共享 context 不变化，phase 不再变化；日志可记录 late result，但前端不会收到迟到事件。

5. `http_sse_and_subtask_complete_once`
   - Given 同一套 stub pipeline 分别通过 HTTP SSE 入口和 `startForSubTask()` 入口执行。
   - When 流程正常完成。
   - Then HTTP SSE 只收到一次 `trading_complete`，emitter 只被 `TradingStarter` close 一次，Controller 正常路径不 close；`startForSubTask()` 返回完整汇总文本，不发送 SSE、不关闭 emitter、不等待 latch。

### 12.3 新增 stage 单元测试

建议新增：

- `AnalystCollectionStageTest`
- `InvestmentDebateStageTest`
- `RecommendationStageTest`
- `RiskManagementStageTest`
- `FinalReportStageTest`
- `TradingPipelineTest`

stage 测试使用 stub node，避免真实 LLM 调用。

### 12.4 兼容回归测试

建议构造一条完整 stub 流：

```text
Mock stock info
  -> four analyst stubs
  -> bull/bear/manager stubs
  -> recommendation stub
  -> risk stubs
  -> portfolio stub
```

断言：

- phase 顺序正确。
- context 字段完整。
- SSE 事件包含关键节点。
- emitter 最终关闭。
- 没有 latch 等待。

---

## 13. 风险与缓解

### 风险 1：节点末尾 Driver 调用导致重复推进

缓解：

- pipeline 路径默认不设置 `TradingDriver`，并在进入 pipeline 前显式 `TradingDriver.clear()`。
- 提交分析师并行任务前断言当前 Driver 为 null，避免线程池装饰器把真实 Driver 传入子线程。
- Stage 主动补齐推进逻辑，不依赖节点末尾的 Driver 调用。
- 若必须引入 no-op/compat driver，必须覆盖所有推进方法、`errorOccurred()` 和 `sendSseResult()`，禁止真实 Dispatcher、错误收口和终态 SSE 参与。
- 最终清理节点末尾 driver 调用。

### 风险 2：分析师并行写 context 的并发问题

缓解：

- 默认采用 Future 返回结果，stage 单线程汇总。
- 如短期保留旧节点直接写入，必须给每个 task 注入隔离的工作 `TradingContextVO`，不能传真实共享 context。
- stage 只在 `runId`、`stageToken` 和写入门禁都有效时合并工作副本的白名单字段，防止 partial success 后迟到结果污染辩论输入。

### 风险 3：SSE 关闭重复

缓解：

- 明确 `TradingStarter` 拥有关闭权。
- 移除 Controller 正常路径中的 `emitter.complete()`，Controller 只做异常兜底。
- 禁止 `FinalReportStage`、`PortfolioManagerNode` 和 `TradingDriver` 发送 `trading_complete` 或关闭 emitter。
- 封装 `completeEmitterSafely()`。
- 增加验收测试：正常成功链路中 `trading_complete` 和 `complete()` 均只发生一次，且来源为 `TradingStarter`。

### 风险 4：线程池饥饿

缓解：

- 编排线程、节点执行线程和导出线程三池分离。
- Controller 禁止使用 common pool，必须提交到 `tradingOrchestrationExecutor`。
- 不在 `tradingTaskExecutor` 中执行长期等待的 pipeline。
- 不在 `tradingTaskExecutor` 中执行 Markdown 导出，导出统一走 `tradingExportExecutor`。
- 增加压测/单元验收：节点池打满时 Controller 仍能返回 emitter；导出池堆积时不影响节点执行和 SSE 完成。

### 风险 5：同步化后丢失超时保护

缓解：

- 抽 `TradingNodeInvoker`，统一封装超时、异常和日志。
- 迁移前后对齐 `NODE_TIMEOUT_SECONDS` 语义。

### 风险 6：Stage 漏 transition 后继续污染后续阶段

缓解：

- `TradingStage` 显式声明 `expectedPhase()` 和 `nextPhase()`。
- `TradingPipeline` 在每个 stage 前后做 phase 校验。
- 校验失败立即抛出 `TradingPipelineException`，由 `TradingStarter` 转换为 error SSE 并停止流程。
- 新增测试覆盖漏 transition、错误阶段顺序和 stage 内进入 ERROR 的行为。

### 风险 7：超时后 late write / late SSE

缓解：

- `TradingStateContext` 增加 `runId`、terminal 标记、stage token 和分析师阶段写入门禁。
- 串行节点超时、整体 `ERROR`、取消和最终完成必须走 `sendTerminalErrorOnce(...)` / `sendTerminalCompleteOnce()` 或明确的取消终态 API，不能先裸 `markTerminal(...)` 再发送普通 SSE。
- 分析师 partial timeout 不标记 terminal，但必须关闭分析师写入门禁和当前 stage token。
- `TradingNodeInvoker` 超时后执行 `future.cancel(true)`，并丢弃之后返回的 late result 或工作副本。
- 旧节点直接写 context 的兼容路径必须使用工作副本隔离；不能只依赖 invoker 外层 active 检查。
- 旧节点使用的工作 `DynamicContext` 必须注入 guarded SSE sender；不能只依赖 terminal，因为 partial success 后 pipeline 仍会继续运行。
- 所有真实 context 写入只能由 stage 合并，且必须经过 active/terminal、stage token 或阶段写入门禁检查；所有阶段内 SSE 也必须经过 terminal 和 stage token 检查。
- 新增测试覆盖超时节点晚返回后不能写 context、不能发 SSE、不能推进 phase。

---

## 14. 推荐结论

基于当前 trading agent 的业务形态，推荐采用：

```text
Controller 后台执行
  + TradingPipeline 同步阶段编排
  + AnalystCollectionStage 内部并行
  + 其他阶段同步调用
  + TradingStarter 统一处理 SSE 完成/错误
```

这条路线不会天然破坏当前功能，反而能让流程控制权从节点和 ThreadLocal 中收回到 stage/pipeline，边界更符合 SOLID：

- Node 负责业务分析。
- Stage 负责阶段规则。
- Pipeline 负责总体顺序。
- Context 负责状态和结果。
- Starter 负责入口、初始化和生命周期收尾。

`TradingDriver + ThreadLocal + Dispatcher 事件推进` 可以作为 legacy 路径暂时保留，但不应参与同步 pipeline 的流程推进。pipeline 路径默认不设置真实 Driver；如果短期需要兼容 Driver 调用，只能使用完全 no-op 的 compat driver。

---

## 15. 执行任务状态

| 任务 | 范围 | 回归验证 | status |
| --- | --- | --- | --- |
| Task 1 | 新增 `TradingPipeline` / `TradingStage` / `TradingPipelineException`，覆盖阶段契约校验 | `TradingPipelineTest` | pass |
| Task 2 | 拆分 `tradingOrchestrationExecutor`、`tradingTaskExecutor`、`tradingExportExecutor`，修正 Controller 后台提交和导出线程池 | executor / Controller 相关回归测试 | pass |
| Task 3 | 改造 `TradingStateContext` 终态 SSE、terminal guard 与 emitter 单次关闭基础能力 | `TradingStateContext` / Starter SSE 回归测试 | pass |
| Task 4 | 新增五个同步 stage，保留分析师并行和各阶段节点调用顺序 | stage 单元测试与 stub 流程测试 | pass |
| Task 5 | `TradingStarter.start()` / `startForSubTask()` 默认走同步 pipeline，并保留 legacy 兼容路径 | starter 回归测试与子任务返回测试 | pass |
| Task 6 | 运行 trading 模块回归和编译验证，修复回归问题 | `mvn test` / `mvn clean compile` 范围验证 | pass |
