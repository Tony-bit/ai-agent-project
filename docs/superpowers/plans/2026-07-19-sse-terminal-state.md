# SSE 终态处理实现计划

status: append
owner: Codex
created_at: 2026-07-19

> **面向执行线程：** 必须使用子技能 superpowers:subagent-driven-development（推荐）或 superpowers:executing-plans，逐项执行本计划。各步骤使用 checkbox（`- [ ]`）语法跟踪状态。

**目标：** 可靠区分已完成、失败、状态未确认和已取消的 SSE 请求，同时确保嵌入式交易流程在外层 `auto_agent` 请求关闭前发送终态事件。

**架构：** 将明确的 SSE 终态事件作为业务完成的唯一依据。新增纯前端 EOF 判定函数和状态未确认警告展示，使后端事件发送器返回真实投递结果，并将 `auto_agent` emitter 的关闭职责完全交给 `AutoAgentExecuteStrategy`；独立交易端点继续使用 `TradingSseSession`。

**技术栈：** Java 17、Spring MVC `ResponseBodyEmitter`、Maven/JUnit 5/Mockito、原生 JavaScript、Node.js 测试运行器、HTML/Tailwind 工具类。

**设计文档：** `docs/superpowers/plans/2026-07-19-sse-terminal-state-design.md`

**状态规则：** 每个任务的初始状态均为 `append`。任务执行线程只能修改自己负责的任务，并且只有实现、定向测试和列出的验收检查全部通过后，才能将状态改为 `pass`。失败或未完成的任务保持 `append`。

---

### 任务 1：新增前端终态判定与状态未确认展示

| 任务 | status |
|------|------|
| 任务 1：新增前端终态判定与状态未确认展示 | pass |

**文件：**
- 修改：`docs/dev-ops/nginx/html/js/agent-ui-core.js:147-167`
- 修改：`docs/dev-ops/nginx/html/index.html:585-593`
- 修改：`docs/dev-ops/nginx/html/index.html:1135-1159`
- 修改：`docs/dev-ops/nginx/html/index.html:1787-1798`
- 修改：`docs/dev-ops/nginx/html/index.html:2021-2063`
- 修改：`docs/dev-ops/nginx/html/index.html:2321-2332`
- 测试：`docs/dev-ops/nginx/html/test/agent-ui-core.test.js`
- 测试：`docs/dev-ops/nginx/html/test/agent-ui-security-smoke.html`

- [x] **步骤 1：编写失败的 EOF 判定测试**

从 `agent-ui-core.js` 导入 `resolveStreamEnd`，并新增以下用例：

```javascript
test('resolveStreamEnd preserves explicit terminal outcomes', () => {
    assert.deepEqual(resolveStreamEnd({
        terminalSeen: true, outcome: 'completed', protocolErrors: 0
    }, true), { outcome: 'completed', notice: null });
    assert.deepEqual(resolveStreamEnd({
        terminalSeen: true, outcome: 'failed', protocolErrors: 0
    }, true), { outcome: 'failed', notice: null });
});

test('resolveStreamEnd marks EOF without a terminal event as indeterminate', () => {
    assert.deepEqual(resolveStreamEnd({
        terminalSeen: false, outcome: null, protocolErrors: 0
    }, true), {
        outcome: 'indeterminate',
        notice: '连接已结束，未确认任务状态。已收到的结果仍然保留。'
    });
    assert.deepEqual(resolveStreamEnd({
        terminalSeen: false, outcome: null, protocolErrors: 0
    }, false), {
        outcome: 'indeterminate',
        notice: '连接已结束，未收到任务完成状态。请稍后重试。'
    });
});

test('resolveStreamEnd does not replace protocol failure', () => {
    assert.deepEqual(resolveStreamEnd({
        terminalSeen: false, outcome: 'failed', protocolErrors: 1
    }, true), { outcome: 'failed', notice: null });
});
```

- [x] **步骤 2：运行前端单元测试并确认失败**

运行：

```powershell
node --test docs/dev-ops/nginx/html/test/agent-ui-core.test.js
```

预期：测试失败，因为尚未导出 `resolveStreamEnd`。

- [x] **步骤 3：实现纯函数 EOF 判定器**

新增并导出：

```javascript
function resolveStreamEnd(streamState, hasResult) {
    if (streamState.terminalSeen || streamState.protocolErrors > 0) {
        return { outcome: streamState.outcome || 'failed', notice: null };
    }
    return {
        outcome: 'indeterminate',
        notice: hasResult
            ? '连接已结束，未确认任务状态。已收到的结果仍然保留。'
            : '连接已结束，未收到任务完成状态。请稍后重试。'
    };
}
```

将两处重复的 EOF 分支替换为：

```javascript
const hasResult = document.getElementById('resultMessages').children.length > 1;
const streamEnd = resolveStreamEnd(streamState, hasResult);
streamState.outcome = streamEnd.outcome;
if (streamEnd.notice) {
    addStageMessage('warning', 'stream_interrupted', streamEnd.notice, null, 'thinking');
}
```

- [x] **步骤 4：新增区别于失败状态的警告展示**

在 `stageTypeMap` 中新增 `warning`，新增琥珀色 `.bubble-warning`，并让 `addStageMessage` 选择不带“操作失败”标题和重试页脚的警告样式：

```javascript
const isError = type === 'error' || subType === 'error';
const isWarning = type === 'warning';
const bubbleClass = isError ? 'bubble-error' : (isWarning ? 'bubble-warning' : 'bubble-ai');
const statusIndicator = isError
    ? '<div class="... text-red-600 ..."><span>!</span><span>操作失败</span></div>'
    : (isWarning
        ? '<div class="... text-amber-700 ..."><span>!</span><span>状态未确认</span></div>'
        : '');
```

头像、标题、正文和边框使用稳定的琥珀色样式类。不要修改成功结果的渲染方式。

- [x] **步骤 5：新增浏览器冒烟断言**

输入一个没有终态事件的结果事件，判定 EOF，渲染返回的提示，并执行以下断言：

```javascript
assert(streamEnd.outcome === 'indeterminate', 'Missing terminal must be indeterminate');
assert(thinking.textContent.includes('状态未确认'), 'Indeterminate warning was not rendered');
assert(!thinking.textContent.includes('操作失败'), 'Indeterminate warning was rendered as failure');
```

- [x] **步骤 6：运行前端测试并执行验收检查**

运行：

```powershell
node --test docs/dev-ops/nginx/html/test/agent-ui-core.test.js
```

预期：所有测试通过。

验收检查：

- `complete` 和 `trading_complete` 仍判定为 `completed`。
- 明确的 `error` 仍判定为 `failed`。
- 无论是否已收到结果，没有终态事件的 EOF 都判定为 `indeterminate`。
- 警告中不包含红色的“操作失败”标题。

- [x] **步骤 7：将任务 1 标记为 pass 并提交**

仅将任务 1 表格中的状态从 `append` 改为 `pass`，然后运行：

```powershell
git add docs/dev-ops/nginx/html/index.html docs/dev-ops/nginx/html/js/agent-ui-core.js docs/dev-ops/nginx/html/test/agent-ui-core.test.js docs/dev-ops/nginx/html/test/agent-ui-security-smoke.html docs/superpowers/plans/2026-07-19-sse-terminal-state.md
git commit -m "fix: distinguish indeterminate SSE completion"
```

---

### 任务 2：返回真实的后端 SSE 投递结果

| 任务 | status |
|------|------|
| 任务 2：返回真实的后端 SSE 投递结果 | pass |

**文件：**
- 新建：`ai-agent-study-domain/src/main/java/denny/ai/agent/domain/service/sse/SseEventSender.java`
- 修改：`ai-agent-study-domain/src/main/java/denny/ai/agent/domain/service/auto/step/AbstractExecuteSupport.java:118-180`
- 修改：`ai-agent-study-trading/ai-agent-study-trading-domain/src/main/java/denny/ai/agent/trading/domain/config/TradingStateContext.java:32-108`
- 修改：`ai-agent-study-trading/ai-agent-study-trading-domain/src/main/java/denny/ai/agent/trading/domain/config/TradingStateContext.java:183-236`
- 修改：`ai-agent-study-trading/ai-agent-study-trading-domain/src/main/java/denny/ai/agent/trading/domain/config/TradingStarter.java:57-93`
- 修改：`ai-agent-study-trigger/src/main/java/denny/ai/agent/trading/trigger/http/TradingAnalysisController.java:166-172`
- 修改：`ai-agent-study-trading/ai-agent-study-trading-domain/src/main/java/denny/ai/agent/trading/domain/node/IntentRoutingNode.java:115-125`
- 测试：`ai-agent-study-trading/ai-agent-study-trading-domain/src/test/java/denny/ai/agent/trading/domain/config/TradingStateContextTerminalTest.java`
- 测试：`ai-agent-study-trading/ai-agent-study-trading-domain/src/test/java/denny/ai/agent/trading/domain/node/IntentRoutingNodeSseForwardingTest.java`

- [x] **步骤 1：编写失败的终态投递测试**

新增测试，证明发送器拒绝投递时返回 `false`，并允许后续重试：

```java
@Test
void failedTerminalDeliveryReturnsFalseAndCanBeRetried() {
    AtomicInteger attempts = new AtomicInteger();
    TradingStateContext context = createContext((type, event) -> attempts.incrementAndGet() > 1);

    assertFalse(context.sendTerminalCompleteOnce());
    assertTrue(context.sendTerminalCompleteOnce());
    assertEquals(2, attempts.get());
}
```

更新意图路由转发测试，使其捕获用的覆盖实现返回 `true`，并断言传给 `TradingStarter` 的回调会返回实际转发结果。

- [x] **步骤 2：运行 trading-domain 定向测试并确认失败**

运行：

```powershell
mvn -pl ai-agent-study-trading/ai-agent-study-trading-domain -am -Dtest=TradingStateContextTerminalTest,IntentRoutingNodeSseForwardingTest -Dsurefire.failIfNoSpecifiedTests=false test
```

预期：测试失败，因为发送器和 `sendSseResult` 当前返回 `void`。

- [x] **步骤 3：新增携带结果的发送器契约**

新建：

```java
package denny.ai.agent.domain.service.sse;

@FunctionalInterface
public interface SseEventSender {
    boolean send(String eventName, Object payload);
}
```

将 `AbstractExecuteSupport.sendSseResult(...)` 的返回值改为 `boolean`：返回 sink 的接收结果；emitter 发送成功后返回 `true`；emitter 缺失、会话断开、sink 拒绝或捕获到异常时返回 `false`。

- [x] **步骤 4：沿交易链路传递发送结果**

在 `TradingStarter` 和 `TradingStateContext` 中使用 `SseEventSender` 替换 `BiConsumer<String, Object>`。

独立端点适配器：

```java
SseEventSender sseSender = (type, event) -> {
    if (event instanceof AutoAgentExecuteResultEntity entity) {
        entity.setStep(dynamicContext.getValue("step") != null
                ? (Integer) dynamicContext.getValue("step") : 0);
    }
    return sseSession.sendBusiness(type, event);
};
```

嵌入式 `auto_agent` 适配器：

```java
starter.start(tradingRequest, dynamicContext, (type, event) -> {
    if (event instanceof AutoAgentExecuteResultEntity result) {
        return sendSseResult(dynamicContext, result);
    }
    return false;
});
```

- [x] **步骤 5：让终态方法报告真实投递结果，并允许拒绝后重试**

使用现有原子保护，但在投递失败时重新开放：

```java
public boolean sendTerminalCompleteOnce() {
    if (!terminal.compareAndSet(false, true)) {
        return false;
    }
    boolean sent = sendSseResultBypassTerminalGuardWithResult(
            "trading", "trading_complete", "交易分析完成", true);
    if (!sent) {
        terminal.compareAndSet(true, false);
    }
    return sent;
}
```

对 `sendTerminalErrorOnce` 应用相同的结果处理。所有调用方改用 boolean 实现后，删除重复的 void 终态发送方法。

- [x] **步骤 6：新增终态接受/拒绝日志**

终态投递被接受时记录一条 INFO 日志，被拒绝或投递失败时记录 WARN 日志。日志应包含 `sessionId`、`type`、`subType`，以及路由使用 sink 还是 raw emitter。发送器返回 `true` 之前不得记录“sent”。

- [x] **步骤 7：运行定向测试和验收检查**

运行步骤 2 中的 Maven 命令。

预期：测试通过。

验收检查：

- 发送器拒绝投递时返回 `false`。
- 首次尝试被拒绝后，不会永久阻止后续重试。
- 成功的终态投递仍保证至多一次。
- 独立和嵌入式交易适配器都返回实际投递结果。

- [x] **步骤 8：将任务 2 标记为 pass 并提交**

仅将任务 2 表格中的状态改为 `pass`，然后暂存列出的 Java 文件和计划文件：

```powershell
git commit -m "fix: report SSE terminal delivery results"
```

---

### 任务 3：将 emitter 关闭职责交给外层请求所有者

| 任务 | status |
|------|------|
| 任务 3：将 emitter 关闭职责交给外层请求所有者 | pass |

**文件：**
- 修改：`ai-agent-study-trading/ai-agent-study-trading-domain/src/main/java/denny/ai/agent/trading/domain/config/TradingStarter.java:90-175`
- 修改：`ai-agent-study-domain/src/main/java/denny/ai/agent/domain/service/auto/AutoAgentExecuteStrategy.java:65-110`
- 测试：`ai-agent-study-trading/ai-agent-study-trading-domain/src/test/java/denny/ai/agent/trading/domain/config/TradingStarterPipelineTest.java`
- 测试：`ai-agent-study-app/src/test/java/denny/ai/agent/test/service/auto/AutoAgentStrategyTest.java`

- [x] **步骤 1：修改所有权测试预期并确认失败**

将 pipeline 测试重命名为 `embeddedTradingDoesNotCompleteOuterEmitter`，并执行以下断言：

```java
assertEquals(0, emitter.completeCount,
        "TradingStarter must not complete an outer auto_agent emitter");
assertEquals(1, events.stream()
        .filter(event -> "trading_complete".equals(event.getSubType()))
        .count());
```

运行：

```powershell
mvn -pl ai-agent-study-trading/ai-agent-study-trading-domain -am -Dtest=TradingStarterPipelineTest -Dsurefire.failIfNoSpecifiedTests=false test
```

预期：测试失败，因为 `TradingStarter` 当前仍会关闭 raw emitter。

- [x] **步骤 2：仅关闭独立交易路由拥有的 sink**

将 `TradingStarter` 中的 raw emitter 兜底关闭逻辑替换为仅关闭 sink 的逻辑：

```java
private void completeOwnedSseSession(DynamicContext dynamicContext) {
    SseEventSink sink = dynamicContext.getValue(SSE_EVENT_SINK_KEY);
    if (sink != null) {
        sink.complete();
        log.info("SSE sink close requested: state={}", sink.state());
        return;
    }
    log.info("SSE emitter close deferred to outer owner: owner=auto_agent");
}
```

在同步流程和旧版交易流程的 finally 路径中调用此方法。不得在 `TradingStarter.start(...)` 中调用 `ResponseBodyEmitter.complete()`。

- [x] **步骤 3：确认外层策略仍是 raw emitter 的唯一关闭者**

保留 `AutoAgentExecuteStrategy.safeComplete(...)` 作为外层所有者。新增或更新测试，覆盖策略成功执行，并验证节点链返回后 `emitter.complete()` 恰好调用一次。在成功关闭点新增一条包含 `owner=auto_agent` 的 INFO 日志。

- [x] **步骤 4：运行所有权测试**

运行：

```powershell
mvn -pl ai-agent-study-trading/ai-agent-study-trading-domain,ai-agent-study-app -am -Dtest=TradingStarterPipelineTest,AutoAgentStrategyTest -Dsurefire.failIfNoSpecifiedTests=false test
```

预期：测试通过。

验收检查：

- 嵌入式交易发送 `trading_complete`，但不关闭 emitter。
- 独立交易仍会请求 `SseEventSink.complete()`。
- `AutoAgentExecuteStrategy` 恰好关闭其 emitter 一次。
- `TradingStarter` 中不再保留 raw emitter 关闭逻辑。

- [x] **步骤 5：将任务 3 标记为 pass 并提交**

仅将任务 3 表格中的状态改为 `pass`，暂存列出的文件和计划文件，然后提交：

```powershell
git commit -m "fix: centralize auto agent SSE closure"
```

---

### 任务 4：完成协议回归与投递验证

| 任务 | status |
|------|------|
| 任务 4：完成协议回归与投递验证 | append |

**文件：**
- 修改：`docs/superpowers/test/2026-06-21-frontend-product-polish-test.md:170-176`
- 修改：`docs/superpowers/plans/2026-07-19-sse-terminal-state-design.md:1-10`
- 修改：`docs/superpowers/plans/2026-07-19-sse-terminal-state.md`
- 测试：`docs/dev-ops/nginx/html/test/agent-ui-core.test.js`
- 测试：`ai-agent-study-trading/ai-agent-study-trading-domain/src/test/java/denny/ai/agent/trading/domain/config/TradingStateContextTerminalTest.java`
- 测试：`ai-agent-study-trigger/src/test/java/denny/ai/agent/trading/trigger/http/TradingSseSessionTest.java`

- [x] **步骤 1：更新缺失终态事件的回归预期**

status: pass

保留 `disconnect-before-terminal` 的非成功判定，但将其预期界面从红色失败提示改为状态未确认警告：

```text
显示“状态未确认/连接已结束”警告，不得包装成成功，不得显示“操作失败”；
已有结果保留，按钮和 Loading 恢复。
```

- [x] **步骤 2：运行前后端定向测试集**

status: pass

运行：

```powershell
node --test docs/dev-ops/nginx/html/test/agent-ui-core.test.js
mvn -pl ai-agent-study-trading/ai-agent-study-trading-domain,ai-agent-study-trigger,ai-agent-study-app -am -Dtest=TradingStateContextTerminalTest,IntentRoutingNodeSseForwardingTest,TradingStarterPipelineTest,TradingSseSessionTest,AutoAgentStrategyTest -Dsurefire.failIfNoSpecifiedTests=false test
```

预期：所有选定测试通过。

- [x] **步骤 3：运行更广泛的模块回归测试**

status: pass

运行：

```powershell
mvn -pl ai-agent-study-domain,ai-agent-study-trading/ai-agent-study-trading-domain,ai-agent-study-trigger,ai-agent-study-app -am test
```

预期：构建成功。

- [ ] **步骤 4：验证原始 SSE 终态序列**

status: append

应用运行后，使用有效的本地请求载荷调用两个入口：

```powershell
$autoPayload = '{"message":"分析股票300502","sessionId":"sse-terminal-auto-test","userId":"test-user","maxStep":5}'
$tradingPayload = '{"ticker":"300502","selectedAnalysts":["FUNDAMENTAL","TECHNICAL"],"maxDebateRounds":2,"maxRiskRounds":1,"sessionId":"sse-terminal-trading-test"}'
curl.exe -N -H "Accept: text/event-stream" -H "Content-Type: application/json" --data-raw $autoPayload http://localhost:8090/api/v1/agent/auto_agent
curl.exe -N -H "Accept: text/event-stream" -H "Content-Type: application/json" --data-raw $tradingPayload http://localhost:8090/api/v1/trading/analysis
```

每个成功交易请求的预期结果：进程返回命令提示符前，可见一个包含 `"subType":"trading_complete"` 的 `data:` 帧。如果本地配置无法启动应用，则任务 4 保持 `append`，并在执行报告中记录环境阻塞；不得标记为 `pass`。

- [ ] **步骤 5：所有任务通过后更新文档级状态**

status: append

仅当任务 1-4 均显示 `pass` 时：

- 将本计划的顶层 `status: append` 改为 `status: pass`；
- 将设计文档状态从“实现计划已生成，待执行”改为“实现完成并验收通过”；
- 保留设计文档到以下文件的链接：
  `docs/superpowers/plans/2026-07-19-sse-terminal-state.md`.

- [ ] **步骤 6：将任务 4 标记为 pass 并提交**

status: append

步骤 1-5 和所有验收检查通过后，仅将任务 4 表格中的状态改为 `pass`，然后运行：

```powershell
git add docs/superpowers/test/2026-06-21-frontend-product-polish-test.md docs/superpowers/plans/2026-07-19-sse-terminal-state-design.md docs/superpowers/plans/2026-07-19-sse-terminal-state.md
git commit -m "test: verify SSE terminal state handling"
```

### 任务 4A：修复 TradingNodeInvoker 构造器注入

| 任务 | status |
|---|---|
| 任务 4A：修复 TradingNodeInvoker 构造器注入 | pass |

**文件：**
- 修改：`ai-agent-study-trading/ai-agent-study-trading-domain/src/main/java/denny/ai/agent/trading/domain/pipeline/TradingNodeInvoker.java`
- 修改：`ai-agent-study-trading/ai-agent-study-trading-domain/src/test/java/denny/ai/agent/trading/domain/pipeline/TradingNodeInvokerTest.java`
- 修改：`docs/superpowers/plans/2026-07-19-sse-terminal-state.md`
- 修改：`docs/superpowers/test/2026-06-21-frontend-product-polish-test.md`

- [x] **步骤 1：新增失败的 Spring 构造器选择测试**

status: pass

检查构造器注入元数据，并断言恰好一个构造器标有 `@Autowired`，其参数类型为 `ExecutorService` 和 `TradingAgentProperties`，且 executor 参数带有 `@Qualifier("tradingTaskExecutor")`。仅运行 `TradingNodeInvokerTest`，确认当前双构造器实现因未显式选择构造器而失败。

- [x] **步骤 2：为 Spring 显式指定配置构造器**

status: pass

在双参数构造器上使用构造器注入，为 executor 参数添加限定符，并仅将单参数构造器保留为测试便利入口。生产路径必须使用配置好的 `TradingAgentProperties` bean。

- [x] **步骤 3：运行构造器和协议定向测试**

status: pass

运行 `TradingNodeInvokerTest` 和任务 4 后端定向测试集。预期：所有选定测试通过。

- [x] **步骤 4：打包并启动应用**

status: pass

构建可执行应用 jar，从此 worktree 启动，并验证端口 8090 正在监听。只有构造器测试通过且应用开始监听后，才能将任务 4A 改为 `pass`。

#### 任务 4A 执行报告

| 验证项 | 结果 | 证据 | status |
|---|---|---|---|
| 构造器选择红灯测试 | 修复前按预期失败：预期存在一个 `@Autowired` 构造器，实际为零个 | `TradingNodeInvokerTest#should_expose_single_configured_constructor_for_spring` | pass |
| 构造器选择绿灯测试 | `TradingNodeInvokerTest` 3/3 通过 | trading-domain Maven 定向运行 | pass |
| 协议定向回归 | 选定的 17 个测试通过，失败/错误为 0；11 个模块的 Reactor 构建成功 | 任务 4A 步骤 3 的 Maven 运行结果 | pass |
| 打包 | 可执行 jar 重新构建成功 | `mvn -pl ai-agent-study-app -am -Dmaven.test.skip=true package`, `BUILD SUCCESS` | pass |
| 应用启动 | Spring 上下文成功创建 `TradingNodeInvoker`；应用启动并监听端口 8090 | `Tomcat started on port 8090`, PID 36880 | pass |

#### 任务 4 执行报告

| 验证项 | 结果 | 证据 | status |
|---|---|---|---|
| TC-102 预期 | 已更新为 `indeterminate`；明确既非成功也非失败 | `docs/superpowers/test/2026-06-21-frontend-product-polish-test.md` | pass |
| 前端定向测试集 | 28 个测试通过，失败为 0 | `node --test docs/dev-ops/nginx/html/test/agent-ui-core.test.js` | pass |
| 后端定向测试集 | 选定的 14 个测试通过，失败/错误为 0；Reactor 构建成功 | 步骤 2 的 Maven 定向命令 | pass |
| 更广泛的模块回归 | 11/11 个 Reactor 模块构建成功 | 步骤 3 的 Maven 命令，`BUILD SUCCESS` | pass |
| 应用启动 | 构造器注入阻塞已修复；可执行 jar 启动并监听端口 8090 | `target/task4a-app.out.log`, `Tomcat started on port 8090` | pass |
| `auto_agent` 原始 SSE | 请求已到达 SSE 端点，但在执行交易前返回明确错误；没有 `trading_complete` 帧 | 使用主工作区配置进行最终只读验证，记录于 `target/task4-main-config-auto.sse`：`Missing INTENT_ROUTING client configuration` | append |
| 独立交易原始 SSE | 请求已到达 SSE 端点，但返回明确的交易错误；没有 `trading_complete` 帧 | 使用主工作区配置进行最终只读验证，记录于 `target/task4-main-config-trading.sse`：`subType:error`，网络请求超时 | append |

任务 4 保持 `append`。构造器注入阻塞已修复，但自动化测试集不能替代通过两个运行中应用入口进行的原始 SSE 成功验证。最后一次尝试通过 Spring 外部配置位置以只读方式加载主工作区的 `application.yml`；应用已监听 8090，但两个端点仍返回上述明确错误。请恢复 `auto_agent` 所需的有效数据库/客户端配置，以及独立交易路由所需的网络/模型可用性，然后重新运行步骤 4 中的两个 curl 命令。只有两个响应都包含 `trading_complete` 后，TC-102、任务 4 和文档级状态才可考虑改为 `pass`。
