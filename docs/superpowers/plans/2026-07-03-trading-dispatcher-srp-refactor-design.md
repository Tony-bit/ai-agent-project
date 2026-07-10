# TradingDispatcher 单一职责重构设计

> **创建时间:** 2026-07-03  
> **状态:** 待用户审阅  
> **目标范围:** 基于 SOLID 的单一职责原则，对 `TradingDispatcher` 做结构性拆分，把节点执行、阶段推进和事件分派从当前主流程中分离出来，同时保持交易分析的外部行为、SSE 事件和测试语义不变。

---

## 1. 背景

当前交易分析链路由 `TradingStarter` 创建请求级 `TradingStateContext` 和 `TradingDriver`，再通过 `TradingDispatcher.onEvent()` 驱动状态机。各交易节点在 `doApply()` 末尾通过 `TradingDriver` 回传事件，继续推进分析师并行执行、投资辩论、推荐决策、风控辩论和最终报告。

这条链路已经具备可工作的状态机结构，但 `TradingDispatcher` 承担了过多职责：

- 根据当前 `TradingPhase` 分派事件。
- 在各阶段内部判断合法事件和下一阶段。
- 选择并调用具体节点。
- 管理异步提交、超时、异常捕获和完成回调。
- 发送阶段性 SSE。
- 初始化辩论轮次、风控轮次等阶段数据。

这些职责集中在一个类中，导致主流程阅读成本高，也让后续修复风控顺序、超时策略、节点选择规则时容易相互影响。

---

## 2. 当前问题

### 2.1 事件分派和阶段逻辑混在一起

`TradingDispatcher.onEvent()` 负责根据 `currentPhase` 进入不同 handler，但各 handler 内又直接处理状态流转、节点调用、上下文写入和 SSE 发送。调用者无法只阅读一个明确的阶段对象来理解某个阶段的规则。

### 2.2 节点调用细节污染业务流程

`invokeNodeAsync()`、`invokeAnalystsInParallel()`、`invokeAnalystNode()` 等方法让调度器同时知道“下一步该做什么”和“如何用线程池执行”。这违反单一职责，也让测试很容易依赖私有执行细节。

### 2.3 节点选择规则重复且不够显式

风控阶段按 `AGGRESSIVE -> CONSERVATIVE -> NEUTRAL` 轮转；投资辩论按 `BULL -> BEAR -> RESEARCH_MANAGER` 轮转。这些规则目前散落在 switch 中。后续如果新增分析师或改轮次策略，容易改错阶段边界。

### 2.4 完成回调和节点自驱动混用

部分节点依赖 `onComplete` 回调推进，部分节点通过 `TradingDriver` 自己发事件推进。当前代码已通过传 `null` 避免风控阶段过早推进，但这一约束只体现在注释和调用参数上，不够显式。

### 2.5 错误和 latch 释放边界需要保留

当前工作区已有改动让 `TradingStateContext.sendError()` 触发 `countDownTaskLatch()`，并避免重复 countDown。重构必须保留这个行为，否则 SSE 可能无法关闭，或异步任务完成信号重复触发。

---

## 3. 设计目标

1. 让 `TradingDispatcher` 只负责接收事件、读取当前阶段并转交给阶段处理器。
2. 把节点异步执行、并行执行、超时和异常处理抽到独立执行组件。
3. 把不同阶段的业务推进规则拆成清晰的阶段处理器。
4. 保持 `TradingStarter`、`TradingDriver`、节点 `doApply()` 的外部接口不变。
5. 保持现有 SSE 类型、subType、完成时机和错误关闭语义不变。
6. 优先做结构性抽离，不顺手重写状态机框架，不引入新依赖。
7. 通过现有测试和少量新增测试证明行为未变。

---

## 4. 非目标

1. 不重写交易状态机为 Spring StateMachine 或其他框架。
2. 不改变 `TradingPhase`、`TradingEvent` 枚举含义。
3. 不改变节点内部 Prompt、LLM 调用、数据提供器或分析报告结构。
4. 不改变 `TradingDriver` 的 ThreadLocal 传递协议。
5. 不改变 `TradingStarter.start()` 和 `startForSubTask()` 的入口签名。
6. 不在本次重构中修复所有交易链路问题，只处理职责拆分和可测试性。

---

## 5. 方案比较

### 5.1 方案 A：只抽私有方法

把 `TradingDispatcher` 内部的大段 switch 拆成更多私有方法，例如 `startAggressiveRiskNode()`、`continueInvestmentDebate()`。

优点是改动小，风险低。缺点是类本身仍然承担所有职责，只是把长方法切短，单一职责问题没有真正解决。

### 5.2 方案 B：抽执行器 + 阶段处理器

新增节点执行器负责线程池、超时、异常、回调；新增阶段处理器负责每个阶段的状态流转和节点选择；`TradingDispatcher` 只保留事件入口和阶段路由。

优点是职责边界清晰，测试可以围绕执行器和阶段处理器分别展开。缺点是文件数量会增加，需要仔细保持 Spring 注入和现有测试兼容。

### 5.3 方案 C：引入完整状态机抽象

定义状态转移表、状态接口、事件处理 DSL 或独立状态机框架。

优点是长期扩展能力强。缺点是当前链路还不需要这么重，且会放大一次性改动风险。

**推荐采用方案 B。** 它能真正体现单一职责原则，又不会把一个已经能工作的业务链路重写成新框架。

---

## 6. 总体设计

本次重构把交易调度拆成三层：

1. `TradingDispatcher`：事件入口。只负责读取 `stateContext.getCurrentPhase()`，找到对应阶段处理器并调用。
2. 阶段处理器：每个处理器只负责一个或一组相邻阶段的业务推进规则。
3. `TradingNodeInvoker`：节点执行工具。统一封装异步执行、并行执行、超时、异常和可选完成回调。

建议拆分后的核心依赖方向：

```text
TradingStarter
  -> TradingDriver
      -> TradingDispatcher
          -> TradingPhaseHandlerRegistry
              -> TradingPhaseHandler
                  -> TradingNodeInvoker
                  -> TradingNodeDependencies
```

`TradingStateContext` 仍然是请求级状态载体，保留阶段、SSE、latch、交易上下文和当前发言人等字段。阶段处理器可以读写 `TradingStateContext`，但不直接管理线程池。

---

## 7. 组件设计

### 7.1 TradingDispatcher

职责：

- 作为 `TradingDriver` 调用的唯一入口。
- 记录事件日志。
- 根据 `TradingPhase` 查找阶段处理器。
- 捕获阶段处理器抛出的异常，并调用 `stateContext.sendError()`。
- 对 `ERROR` 终止状态不再处理事件。

不再负责：

- 具体节点调用。
- 线程池提交。
- 风控或辩论轮转细节。
- 初始化辩论对象或风控对象。

保留外部方法：

```java
public void onEvent(TradingEvent event, TradingStateContext stateContext)
```

这样 `TradingDriver`、`TradingStarter` 和现有测试的外部入口不需要改变。

### 7.2 TradingPhaseHandler

定义阶段处理接口：

```java
public interface TradingPhaseHandler {
    TradingPhase phase();

    void handle(TradingEvent event, TradingStateContext stateContext);
}
```

每个阶段处理器只处理自己声明的 `TradingPhase`。遇到无效事件时只记录 warn，不抛异常，保持当前容错风格。

建议的处理器拆分：

- `TradingInitPhaseHandler`：处理 `INIT` 阶段的 `START_TRADING`，进入分析师收集阶段。
- `AnalystCollectionPhaseHandler`：负责并行投放分析师，并在全部完成后进入投资辩论。
- `InvestmentDebatePhaseHandler`：负责 `BULL -> BEAR -> RESEARCH_MANAGER` 辩论轮转和 `DEBATE_FINISH`。
- `RecommendationPhaseHandler`：负责推荐完成后进入风控阶段，并启动首个激进风控节点。
- `RiskManagementPhaseHandler`：负责 `AGGRESSIVE -> CONSERVATIVE -> NEUTRAL` 风控轮转，轮次耗尽后进入最终报告。
- `FinalReportPhaseHandler`：负责组合经理完成后的 latch 释放。

### 7.3 TradingNodeInvoker

职责：

- 封装 `ThreadPoolExecutor tradingTaskExecutor`。
- 提供单节点异步执行。
- 提供分析师并行执行。
- 统一处理 `NODE_TIMEOUT_SECONDS`。
- 统一捕获异常并调用 `stateContext.sendError()`。
- 支持可选 `Runnable onComplete`，但明确命名为 dispatcher-owned transition callback。

建议接口：

```java
public class TradingNodeInvoker {
    public void invokeAsync(Callable<Void> nodeAction,
                            TradingStateContext stateContext,
                            Runnable onComplete);

    public void invokeAnalystsInParallel(List<AnalystTypeEnum> analysts,
                                         TradingStateContext stateContext,
                                         Consumer<TradingStateContext> onAllComplete);
}
```

执行器只关心“怎么执行”，不判断当前应该运行哪个节点。

### 7.4 TradingNodeDependencies

当前 `TradingDispatcher` 直接注入所有节点。为了避免每个阶段处理器重复注入大量节点，可以新增一个轻量依赖聚合组件：

```java
@Component
public class TradingNodeDependencies {
    // fundamentalAnalystNode, technicalAnalystNode, ...
}
```

阶段处理器通过这个组件获取节点引用。这个类只做依赖聚合，不写业务逻辑。

如果觉得第一批文件数过多，也可以先让各阶段处理器直接注入所需节点。两种方式都符合 SRP；推荐依赖聚合组件，因为它能让处理器构造参数更清晰。

### 7.5 TradingDebateCoordinator

投资辩论和风控辩论都有“按当前发言人选择下一个节点”的规则。可选新增协调器：

- `InvestmentDebateCoordinator`
- `RiskDebateCoordinator`

第一批不强制抽这个层。推荐先把规则放在对应阶段处理器中，等逻辑稳定或出现重复后再抽协调器，避免过度设计。

---

## 8. 数据流

### 8.1 启动流程

```text
TradingStarter.start()
  -> 创建 TradingStateContext
  -> 创建 TradingDriver
  -> 设置 trading_context 和 taskLatch
  -> TradingDriver.setCurrent(driver)
  -> TradingDispatcher.onEvent(START_TRADING, stateContext)
```

这部分保持不变。

### 8.2 事件分派流程

```text
TradingDriver.xxxComplete()
  -> TradingDispatcher.onEvent(event, stateContext)
  -> registry.get(stateContext.currentPhase)
  -> handler.handle(event, stateContext)
  -> handler 更新 phase / SSE / 调用 TradingNodeInvoker
```

`TradingDispatcher` 不再知道每个阶段的内部规则。

### 8.3 节点执行流程

```text
handler 决定要执行哪个 node
  -> TradingNodeInvoker.invokeAsync(nodeAction, context, optionalCallback)
  -> tradingTaskExecutor 异步运行
  -> 节点 doApply()
  -> 节点通过 TradingDriver 发送下一事件，或由 optionalCallback 触发 dispatcher-owned transition
```

风控节点这类已经由节点自己调用 `TradingDriver.riskDebateComplete()` 的场景，继续传 `null` callback，避免节点尚未完成时被 future 回调提前推进。

---

## 9. 错误处理设计

1. 阶段处理器内部不吞掉不可恢复异常，交给 `TradingDispatcher` 顶层 catch。
2. `TradingNodeInvoker` 捕获节点执行异常后调用 `stateContext.sendError("节点执行异常: ...")`。
3. 并行分析师任一异常仍记录错误并发送错误事件；是否继续进入后续阶段保持当前行为，不在本次重构中改变。
4. `TimeoutException` 继续按节点执行异常处理，不改变用户侧错误提示。
5. `TradingStateContext.sendError()` 继续负责进入 `ERROR` 阶段、发送 completed=true SSE、释放 task latch。
6. `countDownTaskLatch()` 保持幂等，避免重复释放造成误导日志。

---

## 10. 测试策略

### 10.1 保留并运行现有测试

重点保留：

- `TradingDispatcherThreadLocalTest`
- `TradingDispatcherRiskSequencingTest`

这些测试覆盖 ThreadLocal Driver 传递、风控节点顺序、错误时 latch 释放，是本次重构的核心回归保护。

### 10.2 新增或调整测试

建议新增：

1. `TradingDispatcherPhaseRoutingTest`
   - 验证 `INIT + START_TRADING` 会委派给 init handler。
   - 验证 `ERROR` 阶段忽略后续事件。
   - 验证 handler 抛异常时调用 `sendError()`。

2. `TradingNodeInvokerTest`
   - 验证 `invokeAsync()` 正常执行节点 action。
   - 验证 action 抛异常时进入 `ERROR`。
   - 验证 `onComplete` 只在非空时执行。

3. `RiskManagementPhaseHandlerTest`
   - 验证风控发言顺序仍是 `AGGRESSIVE -> CONSERVATIVE -> NEUTRAL`。
   - 验证轮次耗尽后进入 `FINAL_REPORT` 并启动组合经理。

如果为了控制改动量，第一批可以先迁移现有测试，等结构落地后补充更细测试。

### 10.3 验证命令

优先运行交易领域模块测试：

```powershell
mvn -pl ai-agent-study-trading/ai-agent-study-trading-domain -Dtest="TradingDispatcherThreadLocalTest,TradingDispatcherRiskSequencingTest" test
```

结构稳定后再运行默认测试：

```powershell
mvn clean test
```

---

## 11. 分步实施计划

### 第一批：抽节点执行器

1. 新增 `TradingNodeInvoker`。
2. 把 `invokeNodeAsync()`、`invokeAnalystsInParallel()`、`invokeAnalystNode()` 的执行细节迁移进去。
3. `TradingDispatcher` 先继续保留阶段 handler 私有方法，只调用 `TradingNodeInvoker`。
4. 跑现有交易调度测试，确认风控顺序和错误 latch 不变。

这一批先降低 `TradingDispatcher` 的执行职责，让线程池和超时策略有独立边界。

### 第二批：抽阶段处理器

1. 新增 `TradingPhaseHandler` 接口。
2. 先抽 `RiskManagementPhaseHandler` 和 `InvestmentDebatePhaseHandler`，因为它们最复杂。
3. 再抽 `Init`、`AnalystCollection`、`Recommendation`、`FinalReport`。
4. `TradingDispatcher` 改为通过 registry 或 `Map<TradingPhase, TradingPhaseHandler>` 分派。

这一批完成后，`TradingDispatcher` 只保留事件入口。

### 第三批：补测试与清理

1. 增加 handler 和 invoker 的单元测试。
2. 删除 `TradingDispatcher` 中不再使用的私有方法和 import。
3. 检查日志语义是否仍清楚。
4. 运行交易模块测试和必要的默认测试。

---

## 12. 风险与缓解

### 12.1 风控节点可能提前推进

风险：把 `onComplete` 迁移到执行器后，如果误给风控节点传入完成回调，会重新引入“future 完成回调早于节点自驱动事件”的问题。

缓解：在 `TradingNodeInvoker` 接口文档中明确 callback 只用于 dispatcher-owned transitions；风控阶段继续传 `null`；保留 `TradingDispatcherRiskSequencingTest`。

### 12.2 Spring 注入复杂度上升

风险：新增多个 handler 后 Bean 关系变多，构造注入或字段注入可能不一致。

缓解：统一使用 Spring Bean 管理；优先构造注入；如为了贴近现有风格可先使用 `@Resource`，但同一批内保持一致。

### 12.3 测试需要重写私有字段注入

风险：现有测试通过 `ReflectionTestUtils` 给 `TradingDispatcher` 塞节点和线程池。拆分后测试需要改为注入 `TradingNodeInvoker` 和 handler。

缓解：先保留 `TradingDispatcher` 外部入口不变；对测试提供最小 Bean/stub 组合，不依赖完整 Spring 上下文。

### 12.4 过度拆分类

风险：一次拆出过多 coordinator、registry、dependency holder，文件数增加但收益不明显。

缓解：第一批只强制抽 `TradingNodeInvoker` 和阶段处理器；辩论 coordinator 作为后续可选项。

---

## 13. 验收标准

1. `TradingDispatcher.onEvent()` 只负责事件入口、阶段处理器委派和顶层异常处理。
2. 线程池、超时、节点异常处理不再写在 `TradingDispatcher` 中。
3. 投资辩论和风控辩论的阶段推进逻辑分别位于独立阶段处理器。
4. `TradingStarter`、`TradingDriver`、交易节点的公开调用方式不变。
5. 风控顺序测试仍能证明 `NEUTRAL` 不会在 `CONSERVATIVE` 完成前启动。
6. 错误场景仍会进入 `ERROR` 阶段并释放 `taskLatch`。
7. 默认 SSE 完成语义不变：最终报告完成后由 `TradingStarter` 发送 `trading_complete` 并关闭 emitter。
8. 交易调度相关测试通过。

---

## 14. 用户审阅点

请重点确认以下取舍是否符合你的目标：

1. 本次只做结构性拆分，不改变交易分析业务结果。
2. 优先抽 `TradingNodeInvoker` 和阶段处理器，不引入完整状态机框架。
3. `TradingDispatcher` 的公开入口保持不变，降低对现有调用方的影响。
4. 第一批实现完成后先跑交易调度相关测试，再决定是否继续抽更细的 debate coordinator。
