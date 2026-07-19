# SSE Terminal State Handling Implementation Plan

status: append
owner: Codex
created_at: 2026-07-19

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Reliably distinguish completed, failed, indeterminate, and cancelled SSE requests while ensuring the embedded trading flow sends its terminal event before the outer `auto_agent` request closes.

**Architecture:** Keep explicit SSE terminal events as the only proof of business completion. Add a pure frontend EOF resolver and an indeterminate warning presentation, make backend event senders return real delivery results, and assign `auto_agent` emitter closure exclusively to `AutoAgentExecuteStrategy`; the dedicated trading endpoint continues using `TradingSseSession`.

**Tech Stack:** Java 17, Spring MVC `ResponseBodyEmitter`, Maven/JUnit 5/Mockito, native JavaScript, Node.js test runner, HTML/Tailwind utilities.

**Design:** `docs/superpowers/plans/2026-07-19-sse-terminal-state-design.md`

**Status rule:** Every task starts as `append`. The task execution thread may change only its own status to `pass`, and only after the implementation, targeted tests, and listed acceptance checks all pass. A failed or incomplete task remains `append`.

---

### Task 1: Add frontend terminal-state resolution and indeterminate presentation

| Task | status |
|------|------|
| Task 1: Add frontend terminal-state resolution and indeterminate presentation | append |

**Files:**
- Modify: `docs/dev-ops/nginx/html/js/agent-ui-core.js:147-167`
- Modify: `docs/dev-ops/nginx/html/index.html:585-593`
- Modify: `docs/dev-ops/nginx/html/index.html:1135-1159`
- Modify: `docs/dev-ops/nginx/html/index.html:1787-1798`
- Modify: `docs/dev-ops/nginx/html/index.html:2021-2063`
- Modify: `docs/dev-ops/nginx/html/index.html:2321-2332`
- Test: `docs/dev-ops/nginx/html/test/agent-ui-core.test.js`
- Test: `docs/dev-ops/nginx/html/test/agent-ui-security-smoke.html`

- [ ] **Step 1: Write failing EOF-resolution tests**

Import `resolveStreamEnd` from `agent-ui-core.js` and add these cases:

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

- [ ] **Step 2: Run the frontend unit test and verify it fails**

Run:

```powershell
node --test docs/dev-ops/nginx/html/test/agent-ui-core.test.js
```

Expected: FAIL because `resolveStreamEnd` is not exported.

- [ ] **Step 3: Implement the pure EOF resolver**

Add and export:

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

Replace both duplicated EOF branches with:

```javascript
const hasResult = document.getElementById('resultMessages').children.length > 1;
const streamEnd = resolveStreamEnd(streamState, hasResult);
streamState.outcome = streamEnd.outcome;
if (streamEnd.notice) {
    addStageMessage('warning', 'stream_interrupted', streamEnd.notice, null, 'thinking');
}
```

- [ ] **Step 4: Add a warning presentation that is distinct from failure**

Add `warning` to `stageTypeMap`, add an amber `.bubble-warning`, and make `addStageMessage` select warning styling without the “操作失败” header or retry footer:

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

Use stable amber classes for the avatar, title, body, and border. Do not change successful result rendering.

- [ ] **Step 5: Add browser smoke assertions**

Feed a result event without a terminal event, resolve EOF, render the returned notice, and assert:

```javascript
assert(streamEnd.outcome === 'indeterminate', 'Missing terminal must be indeterminate');
assert(thinking.textContent.includes('状态未确认'), 'Indeterminate warning was not rendered');
assert(!thinking.textContent.includes('操作失败'), 'Indeterminate warning was rendered as failure');
```

- [ ] **Step 6: Run frontend tests and perform acceptance checks**

Run:

```powershell
node --test docs/dev-ops/nginx/html/test/agent-ui-core.test.js
```

Expected: all tests PASS.

Acceptance checks:

- `complete` and `trading_complete` remain `completed`.
- explicit `error` remains `failed`.
- EOF without a terminal becomes `indeterminate` regardless of result presence.
- the warning does not contain the red “操作失败” heading.

- [ ] **Step 7: Mark Task 1 pass and commit**

Change only the Task 1 table from `append` to `pass`, then run:

```powershell
git add docs/dev-ops/nginx/html/index.html docs/dev-ops/nginx/html/js/agent-ui-core.js docs/dev-ops/nginx/html/test/agent-ui-core.test.js docs/dev-ops/nginx/html/test/agent-ui-security-smoke.html docs/superpowers/plans/2026-07-19-sse-terminal-state.md
git commit -m "fix: distinguish indeterminate SSE completion"
```

---

### Task 2: Return real backend SSE delivery results

| Task | status |
|------|------|
| Task 2: Return real backend SSE delivery results | append |

**Files:**
- Create: `ai-agent-study-domain/src/main/java/denny/ai/agent/domain/service/sse/SseEventSender.java`
- Modify: `ai-agent-study-domain/src/main/java/denny/ai/agent/domain/service/auto/step/AbstractExecuteSupport.java:118-180`
- Modify: `ai-agent-study-trading/ai-agent-study-trading-domain/src/main/java/denny/ai/agent/trading/domain/config/TradingStateContext.java:32-108`
- Modify: `ai-agent-study-trading/ai-agent-study-trading-domain/src/main/java/denny/ai/agent/trading/domain/config/TradingStateContext.java:183-236`
- Modify: `ai-agent-study-trading/ai-agent-study-trading-domain/src/main/java/denny/ai/agent/trading/domain/config/TradingStarter.java:57-93`
- Modify: `ai-agent-study-trigger/src/main/java/denny/ai/agent/trading/trigger/http/TradingAnalysisController.java:166-172`
- Modify: `ai-agent-study-trading/ai-agent-study-trading-domain/src/main/java/denny/ai/agent/trading/domain/node/IntentRoutingNode.java:115-125`
- Test: `ai-agent-study-trading/ai-agent-study-trading-domain/src/test/java/denny/ai/agent/trading/domain/config/TradingStateContextTerminalTest.java`
- Test: `ai-agent-study-trading/ai-agent-study-trading-domain/src/test/java/denny/ai/agent/trading/domain/node/IntentRoutingNodeSseForwardingTest.java`

- [ ] **Step 1: Write failing terminal-delivery tests**

Add tests proving a rejected sender returns false and permits a later retry:

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

Update the intent-routing forwarding test so its capturing override returns `true`, and assert that the callback supplied to `TradingStarter` returns the forwarding result.

- [ ] **Step 2: Run targeted trading-domain tests and verify they fail**

Run:

```powershell
mvn -pl ai-agent-study-trading/ai-agent-study-trading-domain -am -Dtest=TradingStateContextTerminalTest,IntentRoutingNodeSseForwardingTest -Dsurefire.failIfNoSpecifiedTests=false test
```

Expected: FAIL because the sender and `sendSseResult` currently return `void`.

- [ ] **Step 3: Add the result-bearing sender contract**

Create:

```java
package denny.ai.agent.domain.service.sse;

@FunctionalInterface
public interface SseEventSender {
    boolean send(String eventName, Object payload);
}
```

Change `AbstractExecuteSupport.sendSseResult(...)` to return `boolean`: return the sink acceptance result, `true` after a successful emitter send, and `false` for missing emitters, disconnected sessions, rejected sinks, and caught exceptions.

- [ ] **Step 4: Propagate the sender result through trading paths**

Replace `BiConsumer<String, Object>` with `SseEventSender` in `TradingStarter` and `TradingStateContext`.

Dedicated endpoint adapter:

```java
SseEventSender sseSender = (type, event) -> {
    if (event instanceof AutoAgentExecuteResultEntity entity) {
        entity.setStep(dynamicContext.getValue("step") != null
                ? (Integer) dynamicContext.getValue("step") : 0);
    }
    return sseSession.sendBusiness(type, event);
};
```

Embedded `auto_agent` adapter:

```java
starter.start(tradingRequest, dynamicContext, (type, event) -> {
    if (event instanceof AutoAgentExecuteResultEntity result) {
        return sendSseResult(dynamicContext, result);
    }
    return false;
});
```

- [ ] **Step 5: Make terminal methods report real delivery and allow retry after rejection**

Use the existing atomic guard, but reopen it when delivery fails:

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

Apply the same result handling to `sendTerminalErrorOnce`. Remove the duplicate void terminal sender after all callers use the boolean implementation.

- [ ] **Step 6: Add accepted/rejected terminal logs**

Log one INFO entry for accepted terminal delivery and WARN for rejected or failed delivery. Include `sessionId`, `type`, `subType`, and whether the route uses a sink or raw emitter. Do not log “sent” before the sender returns `true`.

- [ ] **Step 7: Run targeted tests and acceptance checks**

Run the Maven command from Step 2.

Expected: tests PASS.

Acceptance checks:

- sender rejection returns `false`.
- a rejected first attempt does not permanently suppress a retry.
- successful terminal delivery remains at-most-once.
- both dedicated and embedded trading adapters return actual delivery results.

- [ ] **Step 8: Mark Task 2 pass and commit**

Change only the Task 2 table to `pass`, then stage the listed Java files and plan file:

```powershell
git commit -m "fix: report SSE terminal delivery results"
```

---

### Task 3: Assign emitter closure to the outer request owner

| Task | status |
|------|------|
| Task 3: Assign emitter closure to the outer request owner | append |

**Files:**
- Modify: `ai-agent-study-trading/ai-agent-study-trading-domain/src/main/java/denny/ai/agent/trading/domain/config/TradingStarter.java:90-175`
- Modify: `ai-agent-study-domain/src/main/java/denny/ai/agent/domain/service/auto/AutoAgentExecuteStrategy.java:65-110`
- Test: `ai-agent-study-trading/ai-agent-study-trading-domain/src/test/java/denny/ai/agent/trading/domain/config/TradingStarterPipelineTest.java`
- Test: `ai-agent-study-app/src/test/java/denny/ai/agent/test/service/auto/AutoAgentStrategyTest.java`

- [ ] **Step 1: Change the ownership test expectation and verify failure**

Rename the pipeline test to `embeddedTradingDoesNotCompleteOuterEmitter` and assert:

```java
assertEquals(0, emitter.completeCount,
        "TradingStarter must not complete an outer auto_agent emitter");
assertEquals(1, events.stream()
        .filter(event -> "trading_complete".equals(event.getSubType()))
        .count());
```

Run:

```powershell
mvn -pl ai-agent-study-trading/ai-agent-study-trading-domain -am -Dtest=TradingStarterPipelineTest -Dsurefire.failIfNoSpecifiedTests=false test
```

Expected: FAIL because `TradingStarter` currently completes the raw emitter.

- [ ] **Step 2: Close only a sink owned by the dedicated trading route**

Replace the raw-emitter fallback in `TradingStarter` with sink-only closure:

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

Call this method from synchronous and legacy trading-finally paths. Do not call `ResponseBodyEmitter.complete()` from `TradingStarter.start(...)`.

- [ ] **Step 3: Verify the outer strategy remains the single raw-emitter closer**

Keep `AutoAgentExecuteStrategy.safeComplete(...)` as the outer owner. Add or update a test that exercises a successful strategy execution and verifies `emitter.complete()` exactly once after the node chain returns. Add an INFO log containing `owner=auto_agent` at the successful close point.

- [ ] **Step 4: Run ownership tests**

Run:

```powershell
mvn -pl ai-agent-study-trading/ai-agent-study-trading-domain,ai-agent-study-app -am -Dtest=TradingStarterPipelineTest,AutoAgentStrategyTest -Dsurefire.failIfNoSpecifiedTests=false test
```

Expected: tests PASS.

Acceptance checks:

- embedded trading sends `trading_complete` but does not close the emitter.
- dedicated trading still requests `SseEventSink.complete()`.
- `AutoAgentExecuteStrategy` closes its emitter exactly once.
- no raw emitter close remains in `TradingStarter`.

- [ ] **Step 5: Mark Task 3 pass and commit**

Change only the Task 3 table to `pass`, stage the listed files and plan file, then commit:

```powershell
git commit -m "fix: centralize auto agent SSE closure"
```

---

### Task 4: Complete protocol regression and delivery verification

| Task | status |
|------|------|
| Task 4: Complete protocol regression and delivery verification | append |

**Files:**
- Modify: `docs/superpowers/test/2026-06-21-frontend-product-polish-test.md:170-176`
- Modify: `docs/superpowers/plans/2026-07-19-sse-terminal-state-design.md:1-10`
- Modify: `docs/superpowers/plans/2026-07-19-sse-terminal-state.md`
- Test: `docs/dev-ops/nginx/html/test/agent-ui-core.test.js`
- Test: `ai-agent-study-trading/ai-agent-study-trading-domain/src/test/java/denny/ai/agent/trading/domain/config/TradingStateContextTerminalTest.java`
- Test: `ai-agent-study-trigger/src/test/java/denny/ai/agent/trading/trigger/http/TradingSseSessionTest.java`

- [ ] **Step 1: Update the regression expectation for missing terminal events**

Keep `disconnect-before-terminal` as non-success, but change its expected UI from a red failure to an indeterminate warning:

```text
显示“状态未确认/连接已结束”警告，不得包装成成功，不得显示“操作失败”；
已有结果保留，按钮和 Loading 恢复。
```

- [ ] **Step 2: Run the focused frontend and backend suites**

Run:

```powershell
node --test docs/dev-ops/nginx/html/test/agent-ui-core.test.js
mvn -pl ai-agent-study-trading/ai-agent-study-trading-domain,ai-agent-study-trigger,ai-agent-study-app -am -Dtest=TradingStateContextTerminalTest,IntentRoutingNodeSseForwardingTest,TradingStarterPipelineTest,TradingSseSessionTest,AutoAgentStrategyTest -Dsurefire.failIfNoSpecifiedTests=false test
```

Expected: all selected tests PASS.

- [ ] **Step 3: Run the broader module regression**

Run:

```powershell
mvn -pl ai-agent-study-domain,ai-agent-study-trading/ai-agent-study-trading-domain,ai-agent-study-trigger,ai-agent-study-app -am test
```

Expected: BUILD SUCCESS.

- [ ] **Step 4: Verify the raw SSE terminal sequence**

With the application running, invoke both entry points using valid local request payloads:

```powershell
$autoPayload = '{"message":"分析股票300502","sessionId":"sse-terminal-auto-test","userId":"test-user","maxStep":5}'
$tradingPayload = '{"ticker":"300502","selectedAnalysts":["FUNDAMENTAL","TECHNICAL"],"maxDebateRounds":2,"maxRiskRounds":1,"sessionId":"sse-terminal-trading-test"}'
curl.exe -N -H "Accept: text/event-stream" -H "Content-Type: application/json" --data-raw $autoPayload http://localhost:8090/api/v1/agent/auto_agent
curl.exe -N -H "Accept: text/event-stream" -H "Content-Type: application/json" --data-raw $tradingPayload http://localhost:8090/api/v1/trading/analysis
```

Expected for each successful trading request: a visible `data:` frame containing `"subType":"trading_complete"` before the process returns to the prompt. If local configuration cannot start the application, leave Task 4 as `append` and record the environmental blocker in the execution report; do not mark it `pass`.

- [ ] **Step 5: Update document-level status after all tasks pass**

Only when Tasks 1-4 all show `pass`:

- change this plan's top-level `status: append` to `status: pass`;
- change the design document status from “实现计划已生成，待执行” to “实现完成并验收通过”;
- keep the design document linked to
  `docs/superpowers/plans/2026-07-19-sse-terminal-state.md`.

- [ ] **Step 6: Mark Task 4 pass and commit**

After Steps 1-5 and all acceptance checks pass, change only the Task 4 table to `pass`, then run:

```powershell
git add docs/superpowers/test/2026-06-21-frontend-product-polish-test.md docs/superpowers/plans/2026-07-19-sse-terminal-state-design.md docs/superpowers/plans/2026-07-19-sse-terminal-state.md
git commit -m "test: verify SSE terminal state handling"
```
