# Trading SSE Forwarding Bugfix Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Ensure TradingAgent events, especially `trading_complete`, reach the browser before the shared emitter is closed.

**Architecture:** Keep the existing single-stock routing and emitter ownership unchanged. Replace the no-op callback passed by `IntentRoutingNode` with a callback that delegates `AutoAgentExecuteResultEntity` events to the node's existing `sendSseResult` method.

**Tech Stack:** Java 17, Spring MVC `ResponseBodyEmitter`, JUnit 5, Mockito

---

### Task 1: Forward Trading SSE Events

| Task | status |
|------|------|
| Task 1: Forward Trading SSE Events | pass |

**Files:**
- Modify: `ai-agent-study-trading/ai-agent-study-trading-domain/src/main/java/denny/ai/agent/trading/domain/node/IntentRoutingNode.java:115`
- Create: `ai-agent-study-trading/ai-agent-study-trading-domain/src/test/java/denny/ai/agent/trading/domain/node/IntentRoutingNodeSseForwardingTest.java`

- [x] **Step 1: Write the failing forwarding test**

Mock `TradingStarter.start(...)` so it invokes the supplied sender with a `trading_complete` event, then assert that `IntentRoutingNode.sendSseResult(...)` receives that same event.

- [x] **Step 2: Run the focused test and verify it fails**

Run: `mvn -pl ai-agent-study-trading/ai-agent-study-trading-domain -am -Dtest=IntentRoutingNodeSseForwardingTest -Dsurefire.failIfNoSpecifiedTests=false test`

Expected: FAIL because the current callback discards the event.

- [x] **Step 3: Implement the minimal forwarding callback**

```java
starter.start(tradingRequest, dynamicContext, (type, event) -> {
    if (event instanceof AutoAgentExecuteResultEntity result) {
        sendSseResult(dynamicContext, result);
    }
});
```

- [x] **Step 4: Run focused and related tests**

Run the focused test, then `TradingStarterPipelineTest`. Expected: PASS.
