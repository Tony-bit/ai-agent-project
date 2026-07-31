# Story 2：LLM SSE Chunk 分层超时实现计划

> **致智能体工作者：** 必需子技能：使用 `executing-plans` 按任务逐步实现本计划。步骤使用复选框（`- [ ]`）跟踪。

**目标：** 将旧的外层有效文本超时替换为每个 query attempt 的绝对上限和每个 HTTP/SSE response body 的原始 `DataBuffer` watchdog，同时保留显式 legacy 回滚能力。

**架构：** `RetryChatModel` 在每次 delegate subscription 前创建不可变的 `StreamChunkTimeoutPolicy`，通过 Reactor Context 传给 WebClient filter。filter 让 HTTP headers 与首个 body Chunk 共用一个绝对 deadline，并在首 Chunk 后分别执行只观测的 stall timer 与 hard idle timeout；Story 1 的原子 attempt 聚合负责施加绝对 attempt timeout。同步调用链保持不变。

**技术栈：** Java 17、Spring AI 1.1.2、Spring WebFlux、Reactor、JUnit 5、Mockito、Reactor Test、Maven。

---

### 任务 1：配置模型与迁移规则

| 任务 | status |
|------|------|
| 任务 1：配置模型与迁移规则 | pass |

**文件：**
- 修改：`ai-agent-study-domain/src/main/java/denny/ai/agent/domain/service/armory/AiStreamingProperties.java`
- 修改：`ai-agent-study-domain/src/main/java/denny/ai/agent/domain/model/valobj/AiClientModelVO.java`
- 测试：`ai-agent-study-domain/src/test/java/denny/ai/agent/domain/service/armory/AiStreamingPropertiesTest.java`

- [x] **步骤 1：补充失败测试**

覆盖默认 `layered`、显式 `legacy`、新字段优先于旧字段、旧字段 fallback、非正值以及以下关系校验：

```java
connectTimeout < firstChunkTimeout < queryAttemptTimeout
stallThreshold < chunkIdleTimeout < queryAttemptTimeout
```

- [x] **步骤 2：运行配置测试确认失败**

运行：`mvn -pl ai-agent-study-domain -am -Dtest=AiStreamingPropertiesTest -Dsurefire.failIfNoSpecifiedTests=false test`
预期：新增 API 或断言失败。

- [x] **步骤 3：实现配置解析**

新增 `TimeoutMode { LAYERED, LEGACY }`，绑定 nullable 新旧字段，并让 resolver 返回：

```java
new StreamingTimeouts(mode, connectTimeout, firstChunkTimeout,
        stallThreshold, chunkIdleTimeout, queryAttemptTimeout,
        legacyFirstContentTimeout, legacyIdleTimeout, legacyTotalTimeout)
```

模型级优先级固定为“模型新字段 > 模型旧字段 > 全局新字段 > 全局旧字段 > 代码默认值”。

- [x] **步骤 4：运行配置测试确认通过**

运行：`mvn -pl ai-agent-study-domain -am -Dtest=AiStreamingPropertiesTest -Dsurefire.failIfNoSpecifiedTests=false test`
预期：BUILD SUCCESS。

### 任务 2：结构化异常与 retry hard exclusion

| 任务 | status |
|------|------|
| 任务 2：结构化异常与 retry hard exclusion | pass |

**文件：**
- 创建：`ai-agent-study-domain/src/main/java/denny/ai/agent/domain/service/armory/stream/LlmTimeoutException.java`
- 创建：`ai-agent-study-domain/src/main/java/denny/ai/agent/domain/service/armory/stream/FirstStreamChunkTimeoutException.java`
- 创建：`ai-agent-study-domain/src/main/java/denny/ai/agent/domain/service/armory/stream/StreamChunkIdleTimeoutException.java`
- 创建：`ai-agent-study-domain/src/main/java/denny/ai/agent/domain/service/armory/stream/LlmQueryAttemptTimeoutException.java`
- 创建：`ai-agent-study-domain/src/main/java/denny/ai/agent/domain/service/armory/stream/MissingStreamTimeoutPolicyException.java`
- 修改：`ai-agent-study-domain/src/main/java/denny/ai/agent/domain/service/armory/factory/element/StreamQueryRetryClassifier.java`
- 测试：`ai-agent-study-domain/src/test/java/denny/ai/agent/domain/service/armory/factory/element/StreamQueryRetryClassifierTest.java`

- [x] **步骤 1：编写 cause-chain hard exclusion 测试**

断言三种 subtype 及其外层包装均不可重试，而 `ConnectException` 仍可重试。

- [x] **步骤 2：运行测试确认失败**

运行：`mvn -pl ai-agent-study-domain -am -Dtest=StreamQueryRetryClassifierTest -Dsurefire.failIfNoSpecifiedTests=false test`
预期：异常类型不存在或被误判可重试。

- [x] **步骤 3：实现异常事实字段与基类分类**

公共字段为 `configuredTimeout`、`effectiveTimeout`、`deadlineOwner`、`elapsed`、`observedChunkCount`、`logicalCallId`、`modelId`；classifier 只识别 `LlmTimeoutException` 基类，不检查 message。

- [x] **步骤 4：运行分类测试确认通过**

运行：`mvn -pl ai-agent-study-domain -am -Dtest=StreamQueryRetryClassifierTest -Dsurefire.failIfNoSpecifiedTests=false test`
预期：BUILD SUCCESS。

### 任务 3：Policy、deadline 仲裁与 stall observer

| 任务 | status |
|------|------|
| 任务 3：Policy、deadline 仲裁与 stall observer | pass |

**文件：**
- 创建：`ai-agent-study-domain/src/main/java/denny/ai/agent/domain/service/armory/stream/StreamChunkTimeoutPolicy.java`
- 创建：`ai-agent-study-domain/src/main/java/denny/ai/agent/domain/service/armory/stream/StreamTimeoutContext.java`
- 创建：`ai-agent-study-domain/src/main/java/denny/ai/agent/domain/service/armory/stream/StallObserver.java`
- 测试：`ai-agent-study-domain/src/test/java/denny/ai/agent/domain/service/armory/stream/StallObserverTest.java`

- [x] **步骤 1：编写虚拟时间和并发终止测试**

覆盖 29/30/89 秒边界、同一 gap 只记录一次、新 Chunk 产生新 generation，以及 complete/cancel 后 task 被清理。

- [x] **步骤 2：运行测试确认失败**

运行：`mvn -pl ai-agent-study-domain -am -Dtest=StallObserverTest -Dsurefire.failIfNoSpecifiedTests=false test`
预期：类型不存在。

- [x] **步骤 3：实现 subscription-local observer**

使用注入的共享 `Scheduler` 和单调纳秒时间；锁内只维护 generation、计数、task 与 terminated，日志回调在锁外执行，observer 不接触 `DataBuffer`。

- [x] **步骤 4：运行 observer 测试确认通过**

运行：`mvn -pl ai-agent-study-domain -am -Dtest=StallObserverTest -Dsurefire.failIfNoSpecifiedTests=false test`
预期：BUILD SUCCESS。

### 任务 4：WebClient SSE Chunk watchdog

| 任务 | status |
|------|------|
| 任务 4：WebClient SSE Chunk watchdog | pass |

**文件：**
- 创建：`ai-agent-study-domain/src/main/java/denny/ai/agent/domain/service/armory/stream/SseChunkTimeoutFilter.java`
- 测试：`ai-agent-study-domain/src/test/java/denny/ai/agent/domain/service/armory/stream/SseChunkTimeoutFilterTest.java`

- [x] **步骤 1：编写 filter 失败测试**

用可控 `ExchangeFunction`、`ClientResponse` 和 `VirtualTimeScheduler` 覆盖：headers 超时、headers 与首 Chunk 共用 deadline、30 秒 stall、90 秒 hard idle、非 2xx 绕过、empty complete、上游 error、cancel、缺少 policy fail closed、原 body 只订阅一次。

- [x] **步骤 2：运行 filter 测试确认失败**

运行：`mvn -pl ai-agent-study-domain -am -Dtest=SseChunkTimeoutFilterTest -Dsurefire.failIfNoSpecifiedTests=false test`
预期：类型不存在或行为不满足断言。

- [x] **步骤 3：实现单次函数式 body 变换**

核心构造必须使用：

```java
response.mutate()
        .body(original -> watchBody(original, policy, firstChunkDeadline))
        .build();
```

`next.exchange(request)` 和首 Chunk 使用同一绝对 deadline；仅 2xx 目标请求安装 body watchdog，终止型超时使用 Reactor `timeout` operator，所有结束路径清理 stall task。

- [x] **步骤 4：运行 filter 测试确认通过**

运行：`mvn -pl ai-agent-study-domain -am -Dtest=SseChunkTimeoutFilterTest -Dsurefire.failIfNoSpecifiedTests=false test`
预期：BUILD SUCCESS。

### 任务 5：RetryChatModel layered attempt 绝对超时

| 任务 | status |
|------|------|
| 任务 5：RetryChatModel layered attempt 绝对超时 | pass |

**文件：**
- 修改：`ai-agent-study-domain/src/main/java/denny/ai/agent/domain/service/armory/factory/element/RetryChatModel.java`
- 修改：`ai-agent-study-domain/src/test/java/denny/ai/agent/domain/service/armory/factory/element/RetryChatModelStreamTest.java`
- 修改：`ai-agent-study-domain/src/test/java/denny/ai/agent/domain/service/armory/factory/element/RetryChatModelAtomicAttemptTest.java`

- [x] **步骤 1：补充绝对 attempt timeout 测试**

覆盖持续收到 `ChatResponse` 仍在固定 deadline 终止、每次 ordinary retry 获得新的完整窗口、timeout 不消费 retry credit、cancel 不启动后续 attempt，以及 legacy 仍保留旧行为。

- [x] **步骤 2：运行测试确认失败**

运行：`mvn -pl ai-agent-study-domain -am -Dtest=RetryChatModelStreamTest,RetryChatModelAtomicAttemptTest -Dsurefire.failIfNoSpecifiedTests=false test`
预期：layered 仍使用旧相邻信号 timeout。

- [x] **步骤 3：实现 policy 注入和 attempt 完成信号 timeout**

每个 `delegate.stream(...)` subscription 计算新 deadline，并执行：

```java
delegate.stream(state.currentPrompt)
        .contextWrite(StreamTimeoutContext.withPolicy(policy))
        .collectList()
        .timeout(effectiveAttemptTimeout,
                Mono.error(queryAttemptTimeout(policy)))
        .flatMapMany(Flux::fromIterable);
```

layered 删除外层 first-content/idle/total operator；legacy 继续走现有路径。同步 `call()` 不修改。

- [x] **步骤 4：运行 attempt 测试确认通过**

运行：`mvn -pl ai-agent-study-domain -am -Dtest=RetryChatModelStreamTest,RetryChatModelAtomicAttemptTest -Dsurefire.failIfNoSpecifiedTests=false test`
预期：BUILD SUCCESS。

### 任务 6：装配、Trading timeout 与回滚配置

| 任务 | status |
|------|------|
| 任务 6：装配、Trading timeout 与回滚配置 | pass |

**文件：**
- 修改：`ai-agent-study-domain/src/main/java/denny/ai/agent/domain/service/armory/AiClientHttpTimeoutConfig.java`
- 修改：`ai-agent-study-domain/src/main/java/denny/ai/agent/domain/service/armory/AiClientModelNode.java`
- 修改：`ai-agent-study-app/src/main/resources/application.yml`
- 修改：`ai-agent-study-trading/ai-agent-study-trading-domain/src/main/java/denny/ai/agent/trading/domain/config/TradingAgentProperties.java`
- 修改：`ai-agent-study-trading/ai-agent-study-trading-domain/src/main/java/denny/ai/agent/trading/domain/config/TradingTimeoutPropertiesValidator.java`
- 测试：`ai-agent-study-domain/src/test/java/denny/ai/agent/domain/service/armory/AiClientHttpTimeoutConfigTest.java`
- 测试：`ai-agent-study-domain/src/test/java/denny/ai/agent/domain/service/armory/AiClientModelNodeRetryTest.java`
- 测试：`ai-agent-study-trading/ai-agent-study-trading-domain/src/test/java/denny/ai/agent/trading/domain/config/TradingTimeoutPropertiesValidatorTest.java`

- [x] **步骤 1：补充装配失败测试**

断言 layered 安装且 legacy 不安装 filter、WebClient 只配置 JDK connect timeout、modelId 进入 policy、node 默认 240 秒，并保留同步 RestClient 行为。

- [x] **步骤 2：运行装配测试确认失败**

运行：`mvn -pl ai-agent-study-domain,ai-agent-study-trading/ai-agent-study-trading-domain -am -Dtest=AiClientHttpTimeoutConfigTest,AiClientModelNodeRetryTest,TradingTimeoutPropertiesValidatorTest -Dsurefire.failIfNoSpecifiedTests=false test`
预期：filter 和新默认值断言失败。

- [x] **步骤 3：完成 Bean 装配和配置说明**

layered 将共享 Scheduler 与 `SseChunkTimeoutFilter` 安装到专用 builder，legacy 不安装；`application.yml` 只增加注释形式的紧急回滚配置，不写入环境密钥。

- [x] **步骤 4：运行装配测试确认通过**

运行：`mvn -pl ai-agent-study-domain,ai-agent-study-trading/ai-agent-study-trading-domain -am -Dtest=AiClientHttpTimeoutConfigTest,AiClientModelNodeRetryTest,TradingTimeoutPropertiesValidatorTest -Dsurefire.failIfNoSpecifiedTests=false test`
预期：BUILD SUCCESS。

### 任务 7：跨 Story 回归与状态收口

| 任务 | status |
|------|------|
| 任务 7：跨 Story 回归与状态收口 | pass |

**文件：**
- 修改：`docs/superpowers/plans/2026-07-30-story-2-sse-chunk-timeout-design.md`
- 修改：`docs/superpowers/plans/2026-07-31-story-2-sse-chunk-timeout-implementation.md`

- [x] **步骤 1：运行领域层专项回归**

运行：`mvn -pl ai-agent-study-domain -am -Dtest=AiStreamingPropertiesTest,SseChunkTimeoutFilterTest,StallObserverTest,StreamQueryRetryClassifierTest,RetryChatModelStreamTest,RetryChatModelAtomicAttemptTest,AiClientHttpTimeoutConfigTest,AiClientModelNodeRetryTest,RetryChatModelTest,RetryChatModelCompressionTest,RetryStrategyTest,CompressionRetryIntegrationTest,OpenAiQueryRetryIntegrationTest -Dsurefire.failIfNoSpecifiedTests=false test`
预期：BUILD SUCCESS。

- [x] **步骤 2：运行 Trading timeout 回归**

运行：`mvn -pl ai-agent-study-trading/ai-agent-study-trading-domain -am -Dtest=TradingTimeoutPropertiesValidatorTest -Dsurefire.failIfNoSpecifiedTests=false test`
预期：BUILD SUCCESS。

- [x] **步骤 3：运行全量编译和测试**

运行：`mvn clean compile -q`
预期：退出码 0。

运行：`mvn test -q`
预期：退出码 0；若仓库既有环境依赖测试失败，必须记录准确测试名与原因，不能把任务标为 `pass`。

- [x] **步骤 4：更新 Story 状态和验收结论**

设计文档状态更新为“实现完成，专项与全量回归通过”，实现计划中所有任务状态更新为 `pass`，所有步骤勾选为 `[x]`。

## 执行结论

- 领域层专项回归：115 个测试通过，0 失败。
- Trading timeout 回归：2 个测试通过，0 失败。
- 全仓编译：`mvn clean compile -q` 退出码 0。
- 全量测试：`mvn test -q` 退出码 0。
- 真实 Spring AI 工具循环已通过 production WebClient filter，验证多轮 HTTP/SSE 请求中的 Reactor Context 契约。
