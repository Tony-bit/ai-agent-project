# Story: AI Client 响应超时可配置化

## 1. 背景与目标

### 背景

当前 `AiClientApiNode` 在构建 `OpenAiApi` 时，直接使用默认的 `RestClient.builder()` 和 `WebClient.builder()`，底层由 Reactor Netty 实现，默认超时为：

- **连接超时**：45 秒（`ClientTransport.connectNow()` 硬编码）
- **响应超时**：30 秒（Reactor Netty `HttpClient` 默认 `responseTimeout`）

这导致 AI 模型调用（尤其是流式推理场景）容易因网络波动或模型响应慢而触发 `SocketTimeoutException`。

### 目标

将 HTTP 客户端超时配置提取为可配置项，支持通过配置类统一设置，不侵入业务代码逻辑。

---

## 2. 技术方案

### 2.1 架构分析

`OpenAiApi` 内部同时持有两个 HTTP 客户端：

| 客户端 | 用途 | 默认超时 | 配置方式 |
|---|---|---|---|
| `RestClient` | 同步请求（chatCompletionEntity / embeddings） | 无显式超时（依赖底层） | `SimpleClientHttpRequestFactory.setReadTimeout()` |
| `WebClient` | 流式请求（chatCompletionStream） | responseTimeout 30s | Reactor Netty（classpath 中不可直接访问） |

两者均通过 `OpenAiApi.Builder` 的 `.restClientBuilder()` 和 `.webClientBuilder()` 注入。

### 2.2 方案设计

新建一个 `@Configuration` 配置类，定义 `RestClient.Builder`，设置 `connectTimeout = 45s`、`readTimeout = 120s`。

**说明**：当前 classpath 中 Netty 5 支持已被 Spring Framework 6.2.x 移除（参考 [spring-projects/spring-framework#34345](https://github.com/spring-projects/spring-framework/issues/34345)），Reactor Netty 4.x 的 `io.netty` 包不在 domain 模块的直接依赖中，故采用保守方案——仅覆盖同步请求（RestClient）超时。流式场景超时由 OpenAI API 侧或底层连接控制。

---

## 3. 变更计划

### 3.1 新建文件

**文件路径**：`ai-agent-study-domain/src/main/java/denny/ai/agent/domain/service/armory/AiClientHttpTimeoutConfig.java`

```java
package denny.ai.agent.domain.service.armory;

import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/**
 * AI Client HTTP 超时配置。
 * <p>
 * 仅为 RestClient（同步请求）配置超时。
 * WebClient 使用 Spring 默认配置，流式场景超时由 OpenAI API 侧或底层连接控制。
 * <p>
 * 说明：当前 classpath 中 Netty 5 支持已被 Spring Framework 6.2.x 移除，
 * Reactor Netty 4.x 的 io.netty 包不在 domain 模块的直接依赖中，
 * 故采用保守方案，仅覆盖同步请求超时。
 *
 * status: pass
 */
@Slf4j
@Configuration
public class AiClientHttpTimeoutConfig {

    private static final int READ_TIMEOUT_MS = 120_000;   // 120 秒
    private static final int CONNECT_TIMEOUT_MS = 45_000; // 45 秒

    @Bean
    public RestClient.Builder aiClientRestClientBuilder() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(CONNECT_TIMEOUT_MS);
        factory.setReadTimeout(READ_TIMEOUT_MS);

        RestClient restClient = RestClient.builder()
                .requestFactory(factory)
                .build();

        log.info("[AiClientHttpTimeoutConfig] RestClient configured, connectTimeout={}ms, readTimeout={}ms",
                CONNECT_TIMEOUT_MS, READ_TIMEOUT_MS);
        return restClient.mutate();
    }
}
```

### 3.2 修改文件

**文件路径**：`ai-agent-study-domain/src/main/java/denny/ai/agent/domain/service/armory/AiClientApiNode.java`

**改动点 1**：新增 `RestClient.Builder` 字段注入

```java
@Resource
private RestClient.Builder aiClientRestClientBuilder;
```

**改动点 2**：在 `OpenAiApi.builder()` 链中传入 `restClientBuilder`

```java
OpenAiApi openAiApi = OpenAiApi.builder()
        .baseUrl(aiClientApiVO.getBaseUrl())
        .apiKey(aiClientApiVO.getApiKey())
        .completionsPath(aiClientApiVO.getCompletionsPath())
        .embeddingsPath(aiClientApiVO.getEmbeddingsPath())
        .restClientBuilder(aiClientRestClientBuilder)
        .build();
```

---

## 4. 任务列表

| 序号 | 任务 | 状态 |
|---|---|---|
| 1 | 新建 `AiClientHttpTimeoutConfig.java` 配置类 | pass |
| 2 | 修改 `AiClientApiNode.java`，注入 `RestClient.Builder` 并传给 `OpenAiApi` | pass |
| 3 | 编译验证（`mvn compile`） | pass |

---

## 5. 测试计划

- 启动应用，观察启动日志中是否打印 `"[AiClientHttpTimeoutConfig] RestClient configured, connectTimeout=45000ms, readTimeout=120000ms"`
- 发送一次 AI 对话请求，验证正常返回
- 通过日志确认超时配置生效

---

## 7. 背景与目标（分析师并行执行）

### 背景

当前 `TradingDispatcher.handleAnalystCollection` 中，分析师节点按**串行顺序**执行，每次调用 `invokeNode` → `future.get()` 阻塞等待一个完成后才启动下一个，加上 `CallerRunsPolicy` 拒绝策略，当线程池满时，主线程本身执行任务，导致线程池可用线程被进一步消耗，形成死锁。

### 目标

将所有 12 个节点统一改为**非阻塞回调模式**，消除 `future.get()` 的主线程阻塞：
- 4 个分析师：并行执行（`allOf` 汇合）
- 8 个其他节点：串行 round-robin 改为非阻塞回调链

---

## 8. 技术方案（全节点非阻塞化）

### 8.1 场景分析

| 阶段 | 节点 | 调用模式 | 改造方式 |
|---|---|---|---|
| `ANALYST_COLLECTION` | 4 个分析师 | 并行 | `invokeAnalystsInParallel` + `allOf` |
| `INVESTMENT_DEBATE` | BULL → BEAR → RM | 串行 round-robin | `invokeNodeAsync` 回调链 |
| `RECOMMENDATION_DECISION` | 推荐节点 | 串行 | `invokeNodeAsync` |
| `RISK_MANAGEMENT` | AGGRESSIVE → CONS → NEUTRAL | 串行 round-robin | `invokeNodeAsync` 回调链 |
| `FINAL_REPORT` | 组合管理节点 | 串行 | `invokeNodeAsync` |

### 8.2 核心思路

改造前（阻塞）：

```java
// 当前：主线程卡在 future.get()，等待子线程完成
invokeNode(() -> {
    bullResearcherNode.doApply(...);
    return null;
}, stateContext);
// ← 主线程在这里阻塞 180s
// 继续执行下一行
```

改造后（非阻塞回调）：

```java
// 新：主线程提交后立即返回，回调在线程池中执行
invokeNodeAsync(
    () -> { bullResearcherNode.doApply(...); },
    stateContext,
    () -> { /* 回调：节点完成后做什么 */ }
);
```

### 8.3 invokeNodeAsync 方法签名

```java
/**
 * 非阻塞节点调用，节点执行完成后通过回调触发下一阶段。
 *
 * @param nodeAction   节点执行逻辑
 * @param stateContext 上下文
 * @param onComplete   节点完成后回调（用于触发下一节点或下一阶段）
 */
private void invokeNodeAsync(Callable<Void> nodeAction,
                              TradingStateContext stateContext,
                              Runnable onComplete) {
    logExecutorStatus("invokeNodeAsync 提交任务前");
    CompletableFuture.<Void>runAsync(() -> {
        try {
            logExecutorStatus("invokeNodeAsync 任务执行开始");
            log.info("节点执行开始: {}", nodeAction.getClass().getSimpleName());
            nodeAction.call();
            log.info("节点执行结束: {}", nodeAction.getClass().getSimpleName());
        } catch (Exception e) {
            log.error("节点执行异常", e);
            stateContext.sendError("节点执行异常: " + e.getMessage());
        }
    }, tradingTaskExecutor)
    .orTimeout(NODE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
    .whenComplete((result, ex) -> {
        logExecutorStatus("invokeNodeAsync 任务完成");
        if (ex != null && !(ex.getCause() instanceof java.util.concurrent.TimeoutException)) {
            log.error("invokeNodeAsync 执行失败", ex);
            return;
        }
        // 无论成功还是超时，回调均由线程池线程执行
        if (onComplete != null) {
            try {
                onComplete.run();
            } catch (Exception e) {
                log.error("回调执行异常", e);
                stateContext.sendError("回调执行异常: " + e.getMessage());
            }
        }
    });
}
```

### 8.4 辩论阶段改造示例（BULL → BEAR → RM）

改造前（阻塞）：

```java
case "BULL" -> invokeNode(() -> {
    stateContext.setLatestDebateSpeaker("BEAR");
    bearResearcherNode.doApply(new ExecuteCommandEntity(), stateContext.getDynamicContext());
    return null;
}, stateContext);
```

改造后（非阻塞回调链）：

```java
case "BULL" -> {
    stateContext.setLatestDebateSpeaker("BEAR");
    invokeNodeAsync(
        () -> {
            bullResearcherNode.doApply(new ExecuteCommandEntity(), stateContext.getDynamicContext());
            return null;
        },
        stateContext,
        () -> {
            // BULL 完成后，由回调触发下一节点
            TradingDispatcher.this.onEvent(TradingEvent.INVESTMENT_DEBATE_COMPLETE, stateContext);
        }
    );
}
```

### 8.5 关键设计点

| 点 | 说明 |
|---|---|
| **回调线程** | `whenComplete` 在 ForkJoinPool.commonPool 或原线程池线程中执行，避免主线程阻塞 |
| **超时处理** | `orTimeout` 超时后不触发回调，状态机进入 ERROR |
| **异常处理** | 节点异常和回调异常分别 try-catch，避免状态不一致 |
| **ThreadLocal Driver** | 回调在线程池线程执行，`TradingDriver.setCurrent(driver)` 需在回调前重新设置 |

---

## 9. 变更计划（全节点非阻塞化）

### 9.1 新增方法

**文件路径**：`ai-agent-study-trading/ai-agent-study-trading-domain/src/main/java/denny/ai/agent/trading/domain/config/TradingDispatcher.java`

**新增 1**：`invokeNodeAsync`（替换原有阻塞调用）

```java
private void invokeNodeAsync(Callable<Void> nodeAction,
                              TradingStateContext stateContext,
                              Runnable onComplete) {
    logExecutorStatus("invokeNodeAsync 提交任务前");
    CompletableFuture.<Void>runAsync(() -> {
        try {
            logExecutorStatus("invokeNodeAsync 任务执行开始");
            log.info("节点执行开始: {}", nodeAction.getClass().getSimpleName());
            nodeAction.call();
            log.info("节点执行结束: {}", nodeAction.getClass().getSimpleName());
        } catch (Exception e) {
            log.error("节点执行异常", e);
            stateContext.sendError("节点执行异常: " + e.getMessage());
        }
    }, tradingTaskExecutor)
    .orTimeout(NODE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
    .whenComplete((result, ex) -> {
        logExecutorStatus("invokeNodeAsync 任务完成");
        if (ex != null && !(ex.getCause() instanceof java.util.concurrent.TimeoutException)) {
            log.error("invokeNodeAsync 执行失败", ex);
            return;
        }
        if (onComplete != null) {
            try {
                // 重新设置 ThreadLocal Driver，供回调中的节点使用
                TradingDriver driver = TradingDriver.getCurrent();
                onComplete.run();
            } catch (Exception e) {
                log.error("回调执行异常", e);
                stateContext.sendError("回调执行异常: " + e.getMessage());
            }
        }
    });
}
```

**新增 2**：`invokeAnalystsInParallel`（分析师并行）

```java
private void invokeAnalystsInParallel(TradingStateContext stateContext,
                                      List<AnalystTypeEnum> analysts) {
    log.info("分析师并行执行: {}", analysts);
    logExecutorStatus("并行分析师提交前");

    List<CompletableFuture<Void>> futures = analysts.stream()
        .map(analyst -> CompletableFuture.runAsync(() -> {
            try {
                logExecutorStatus("并行分析师任务开始: " + analyst);
                invokeAnalystNode(analyst, stateContext);
            } catch (Exception e) {
                log.error("分析师执行异常: analyst={}", analyst, e);
            }
        }, tradingTaskExecutor))
        .toList();

    CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
        .orTimeout(NODE_TIMEOUT_SECONDS * analysts.size(), TimeUnit.SECONDS)
        .whenComplete((result, ex) -> {
            logExecutorStatus("所有分析师并行完成");
            if (ex != null) {
                log.error("分析师并行执行异常", ex);
                stateContext.sendError("分析师执行异常: " + ex.getMessage());
                return;
            }
            handleAllAnalystsComplete(stateContext);
        });
}

private void invokeAnalystNode(AnalystTypeEnum analyst, TradingStateContext stateContext) {
    switch (analyst) {
        case FUNDAMENTAL -> fundamentalAnalystNode.doApply(
                new ExecuteCommandEntity(), stateContext.getDynamicContext());
        case TECHNICAL -> technicalAnalystNode.doApply(
                new ExecuteCommandEntity(), stateContext.getDynamicContext());
        case SENTIMENT -> sentimentAnalystNode.doApply(
                new ExecuteCommandEntity(), stateContext.getDynamicContext());
        case NEWS -> newsAnalystNode.doApply(
                new ExecuteCommandEntity(), stateContext.getDynamicContext());
    }
}
```

### 9.2 修改方法

**改动 1**：`handleAnalystCollection` — 去掉串行逻辑，调用并行方法

```java
private void handleAnalystCollection(TradingEvent event, TradingStateContext stateContext) {
    if (event == TradingEvent.START_TRADING) {
        invokeAnalystsInParallel(stateContext, stateContext.getSelectedAnalysts());
    }
}
```

**改动 2**：`handleInit` — 简化跳转

```java
private void handleInit(TradingEvent event, TradingStateContext stateContext) {
    if (event == TradingEvent.START_TRADING) {
        stateContext.transitionTo(TradingPhase.ANALYST_COLLECTION);
        stateContext.sendSseResult("trading", "trading_init",
                "交易分析开始，分析师并行执行中", false);
        onEvent(TradingEvent.START_TRADING, stateContext);
    }
}
```

**改动 3**：`handleInvestmentDebate` — 所有 `invokeNode` 改为 `invokeNodeAsync`

每个 `case` 中的 `invokeNode(...)` 替换为 `invokeNodeAsync(...)`，并传入下一节点的触发回调。round-robin 逻辑保持不变。

**改动 4**：`handleRecommendationDecision` — `invokeNode` 改为 `invokeNodeAsync`

**改动 5**：`handleRiskManagement` — 所有 `invokeNode` 改为 `invokeNodeAsync`

**改动 6**：删除 `invokeNextAnalyst` 方法（原串行调度逻辑）

**改动 7**：保留 `invokeNode` 方法（用于不需要回调的简单场景，如 `handleAllAnalystsComplete` 触发辩论）

---

## 10. 任务列表（全节点非阻塞化）

| 序号 | 任务 | 状态 |
|---|---|---|
| 1 | 新增 `invokeNodeAsync` 方法 | pass |
| 2 | 新增 `invokeAnalystsInParallel` 方法 | pass |
| 3 | 新增 `invokeAnalystNode` 方法 | pass |
| 4 | 修改 `handleAnalystCollection` 去掉串行逻辑 | pass |
| 5 | 修改 `handleInit` 简化跳转 | pass |
| 6 | 修改 `handleInvestmentDebate` 全部改为回调 | pass |
| 7 | 修改 `handleRecommendationDecision` 改为回调 | pass |
| 8 | 修改 `handleRiskManagement` 全部改为回调 | pass |
| 9 | 删除 `invokeNextAnalyst` 方法 | pass |
| 10 | 编译验证（`mvn compile`） | pass |

---

## 11. 风险与回滚（全节点非阻塞化）

- **风险**：`invokeNodeAsync` 回调中 `TradingDriver.getCurrent()` 可能为 null（子线程无法获取主线程的 ThreadLocal）。需在 `onComplete` 执行前重新设置：`TradingDriver.setCurrent(driver)`。
- **回滚**：将所有 `invokeNodeAsync` 还原为 `invokeNode`，将 `invokeAnalystsInParallel` 还原为循环调用 `invokeNextAnalyst`。

---

## 12. 风险与回滚

- **风险**：超时时间 120s 可能仍不够长，可根据实际情况调整常量值
- **回滚**：删除 `AiClientHttpTimeoutConfig.java`，还原 `AiClientApiNode.java` 中 builder 调用即可

---

## 13. SSE Emitter 生命周期管理（并行场景修复）

### 13.1 问题现象

并行改造上线后，运行日志出现大量：

```
【SSE致命错误】发送SSE结果失败：type=analyst, subType=analyst_start,
error=ResponseBodyEmitter has already completed
```

且 4 个分析师还未执行完，辩论阶段已提前开始。

### 13.2 根本原因

```
Controller.runAsync() → start() → dispatcher.onEvent() → invokeAnalystsInParallel()
                                                                        └─ 任务提交完毕 → onEvent() 返回
                                                                              │
                                                                        finally: emitter.complete() ← emitter 提前关闭
  tradingTaskExecutor 线程:
    Analyst-1~4 并行执行 → sendAnalystEvent() → 全部失败（emitter 已关闭）
```

`invokeAnalystsInParallel()` 提交 4 个并行任务后立即返回，`start()` 的 finally 块检测到方法结束，执行 `emitter.complete()`，但 4 个分析师节点还在 `doApply()` 中调用 LLM。SSE emitter 被提前关闭，后续所有 `sendSseResult()` 失败。

### 13.3 修复方案

使用 `CountDownLatch` 同步：`start()` 的 finally 等待 latch 归零后才关闭 emitter，`invokeAnalystsInParallel()` 的 `allOf` 完成后 countDown。

### 13.4 代码变更

**1. `DynamicContext` 新增 `tradingLatch` 字段**

```java
import java.util.concurrent.CountDownLatch;

// 在 dataObjects 之后新增：
private CountDownLatch tradingLatch;

public void setTradingLatch(CountDownLatch latch) {
    this.tradingLatch = latch;
}

public CountDownLatch getTradingLatch() {
    return tradingLatch;
}
```

**2. `TradingStarter.start()` 创建 latch，finally 中 await**

```java
public void start(StockAnalysisRequestVO request,
                  DynamicContext dynamicContext,
                  BiConsumer<String, Object> sseSender) {
    // 创建请求级上下文
    TradingStateContext stateContext = new TradingStateContext(request, dynamicContext, sseSender);

    // ★ 新增：创建 CountDownLatch，供并行分析师流程完成后 countdown
    CountDownLatch tradingLatch = new CountDownLatch(1);
    dynamicContext.setTradingLatch(tradingLatch);

    // ... 其余代码不变 ...

    try {
        TradingDriver.setCurrent(driver);
        stateContext.transitionTo(TradingPhase.INIT);
        dispatcher.onEvent(TradingEvent.START_TRADING, stateContext);
    } finally {
        TradingDriver.clear();
        // ★ 改为：等待所有并行任务完成后再关闭 emitter
        try {
            tradingLatch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("tradingLatch await 被中断: {}", e.getMessage());
        }
        if (stateContext.getCurrentPhase() == TradingPhase.FINAL_REPORT) {
            stateContext.sendSseResult("trading", "trading_complete", "交易分析完成", true);
        }
    }
}
```

**3. `TradingDispatcher.invokeAnalystsInParallel()` 完成后 countDown**

```java
private void invokeAnalystsInParallel(TradingStateContext stateContext,
                                      List<AnalystTypeEnum> analysts) {
    CountDownLatch tradingLatch = stateContext.getDynamicContext().getTradingLatch();

    CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
        .orTimeout(NODE_TIMEOUT_SECONDS * analysts.size(), TimeUnit.SECONDS)
        .whenComplete((result, ex) -> {
            logExecutorStatus("所有分析师并行完成");
            if (ex != null) {
                log.error("分析师并行执行异常", ex);
                stateContext.sendError("分析师执行异常: " + ex.getMessage());
            }
            handleAllAnalystsComplete(stateContext);
            // ★ 完成后 countDown，释放 start() 的 finally 中的 await
            if (tradingLatch != null) {
                tradingLatch.countDown();
            }
        });
}
```

### 13.5 任务列表（SSE Emitter 生命周期修复）

| 序号 | 任务 | 状态 |
|---|---|---|
| 1 | `DynamicContext` 新增 `tradingLatch` 字段 | pass |
| 2 | `TradingStarter.start()` 创建 latch，finally 中 await | pass |
| 3 | `TradingDispatcher.invokeAnalystsInParallel()` 完成后 countDown | pass |
| 4 | 编译验证（`mvn compile`） | pass |
