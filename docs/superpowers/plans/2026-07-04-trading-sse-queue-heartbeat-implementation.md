# TradingAgent SSE Queue Heartbeat Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Convert TradingAgent SSE output to a request-scoped bounded queue with a single writer and heartbeat, while keeping the existing frontend business payload contract unchanged.

**Architecture:** Domain code will depend on a small `SseEventSink` abstraction, not on `ResponseBodyEmitter`. The trigger layer will own `TradingSseSession`, which serializes structured outbound events through one writer loop and sends heartbeat comment frames. Trading pipeline code will use `shouldContinue()` to stop launching expensive work after disconnect or backpressure closure.

**Tech Stack:** Java 17, Spring Boot 3.5, `ResponseBodyEmitter`, `ThreadPoolExecutor`, `ScheduledExecutorService`, JUnit 5/JUnit 4 existing test mix, FastJSON.

---

## File Map

- Create `ai-agent-study-domain/src/main/java/denny/ai/agent/domain/service/sse/SseEventSink.java`: transport abstraction exposed to domain code.
- Create `ai-agent-study-domain/src/main/java/denny/ai/agent/domain/service/sse/SseSessionState.java`: session lifecycle states.
- Modify `ai-agent-study-domain/src/main/java/denny/ai/agent/domain/service/auto/step/AbstractExecuteSupport.java`: send business events through `SseEventSink` when present; keep legacy emitter fallback for non-trading paths.
- Create `ai-agent-study-trigger/src/main/java/denny/ai/agent/trading/trigger/http/SseOutboundType.java`: outbound event kind enum.
- Create `ai-agent-study-trigger/src/main/java/denny/ai/agent/trading/trigger/http/SseOutboundEvent.java`: structured queued event record.
- Create `ai-agent-study-trigger/src/main/java/denny/ai/agent/trading/trigger/http/TradingSseSession.java`: bounded queue, writer loop, heartbeat scheduling, state transitions, cleanup.
- Modify `ai-agent-study-trigger/src/main/java/denny/ai/agent/trading/trigger/http/TradingAnalysisController.java`: create `TradingSseSession`, inject `SseEventSink`, remove direct TradingAgent emitter writes.
- Modify `ai-agent-study-trading/ai-agent-study-trading-domain/src/main/java/denny/ai/agent/trading/domain/config/TradingExecutorConfig.java`: add a dedicated SSE writer executor and keep heartbeat executor lightweight.
- Modify `ai-agent-study-trading/ai-agent-study-trading-domain/src/main/java/denny/ai/agent/trading/domain/config/TradingStarter.java`: complete via `SseEventSink`, not directly through `ResponseBodyEmitter`.
- Modify `ai-agent-study-trading/ai-agent-study-trading-domain/src/main/java/denny/ai/agent/trading/domain/config/TradingStateContext.java`: use `SseEventSink.shouldContinue()` for terminal/late event behavior.
- Modify `ai-agent-study-trading/ai-agent-study-trading-domain/src/main/java/denny/ai/agent/trading/domain/pipeline/TradingPipeline.java`: stop before the next stage when the sink says not to continue.
- Modify trading node classes under `ai-agent-study-trading/.../domain/node`: check `shouldContinue()` before blocking LLM calls.
- Modify `docs/dev-ops/nginx/html/index.html`: harden parser comment-frame behavior only if existing parser does not already ignore comments cleanly.
- Test `ai-agent-study-trigger/src/test/java/denny/ai/agent/trading/trigger/http/TradingSseSessionTest.java`: queue, heartbeat, complete, writer error behavior.
- Test `ai-agent-study-domain/src/test/java/denny/ai/agent/domain/service/auto/step/AbstractExecuteSupportTest.java`: sink path and legacy fallback.
- Test existing trading pipeline/starter tests: update assertions from direct emitter completion to sink completion.

## Tasks

### Task 1: Domain SSE Contract

**Files:**
- Create `ai-agent-study-domain/src/main/java/denny/ai/agent/domain/service/sse/SseEventSink.java`
- Create `ai-agent-study-domain/src/main/java/denny/ai/agent/domain/service/sse/SseSessionState.java`

- [ ] **Step 1: Add the lifecycle enum**

```java
package denny.ai.agent.domain.service.sse;

public enum SseSessionState {
    OPEN,
    CLOSING,
    CLOSED,
    DISCONNECTED,
    FAILED
}
```

- [ ] **Step 2: Add the sink interface**

```java
package denny.ai.agent.domain.service.sse;

public interface SseEventSink {
    boolean sendBusiness(String eventName, Object payload);
    boolean trySendHeartbeat();
    void complete();
    void markDisconnected(Throwable cause);
    boolean isDisconnected();
    boolean shouldContinue();
    SseSessionState state();
}
```

- [ ] **Step 3: Compile domain module**

Run: `mvn -pl ai-agent-study-domain -DskipTests compile`

Expected: compile passes.

### Task 2: Trigger-Side Session Transport

**Files:**
- Create `ai-agent-study-trigger/src/main/java/denny/ai/agent/trading/trigger/http/SseOutboundType.java`
- Create `ai-agent-study-trigger/src/main/java/denny/ai/agent/trading/trigger/http/SseOutboundEvent.java`
- Create `ai-agent-study-trigger/src/main/java/denny/ai/agent/trading/trigger/http/TradingSseSession.java`
- Test `ai-agent-study-trigger/src/test/java/denny/ai/agent/trading/trigger/http/TradingSseSessionTest.java`

- [ ] **Step 1: Add outbound event model**

Use an enum with `BUSINESS`, `HEARTBEAT`, and `COMPLETE`; use a Java record that carries type, event name, payload, request/session metadata, event id, timestamp, and comment.

- [ ] **Step 2: Implement session queue and writer**

`TradingSseSession` must implement `SseEventSink`, use a bounded `BlockingQueue<SseOutboundEvent>`, generate event ids, send only from `runWriterLoop`, and serialize first-phase business payloads as the original JSON object in `data: ...\n\n`.

- [ ] **Step 3: Implement heartbeat and cleanup**

Heartbeat uses `offer` only and sends `: heartbeat\n\n`; `complete()` stops heartbeat, moves `OPEN -> CLOSING`, enqueues COMPLETE with a short timeout, and force-completes if the queue cannot accept the terminal event.

- [ ] **Step 4: Add unit tests**

Cover multi-producer single-writer behavior, heartbeat skip when queue is full, idempotent complete, `shouldContinue()` state behavior, and writer send exceptions.

- [ ] **Step 5: Run trigger tests**

Run: `mvn -pl ai-agent-study-trigger -Dtest=TradingSseSessionTest test`

Expected: tests pass.

### Task 3: Controller Migration

**Files:**
- Modify `ai-agent-study-trigger/src/main/java/denny/ai/agent/trading/trigger/http/TradingAnalysisController.java`
- Modify `ai-agent-study-trading/ai-agent-study-trading-domain/src/main/java/denny/ai/agent/trading/domain/config/TradingExecutorConfig.java`

- [ ] **Step 1: Add `tradingSseWriterExecutor` bean**

Use a dedicated `ThreadPoolExecutor` with a bounded queue and `AbortPolicy`; do not use `CallerRunsPolicy` for the writer loop.

- [ ] **Step 2: Inject writer and heartbeat executors**

Controller constructor should receive `ExecutorService tradingSseWriterExecutor` and `ScheduledExecutorService tradingSseHeartbeatExecutor`.

- [ ] **Step 3: Replace inner `SseSession` with `TradingSseSession`**

Create the session after `ResponseBodyEmitter`, register emitter callbacks to `markDisconnected`, call `startWriter`, call `startHeartbeat`, and store `sseEventSink` in `DynamicContext`.

- [ ] **Step 4: Preserve response compatibility**

Keep `Content-Type: text/event-stream`, add `Cache-Control: no-cache, no-transform`, keep `Connection: keep-alive`, and keep `X-Accel-Buffering: no`.

- [ ] **Step 5: Run compile**

Run: `mvn -pl ai-agent-study-trigger,ai-agent-study-trading/ai-agent-study-trading-domain -am -DskipTests compile`

Expected: compile passes.

### Task 4: Domain Send Path Migration

**Files:**
- Modify `ai-agent-study-domain/src/main/java/denny/ai/agent/domain/service/auto/step/AbstractExecuteSupport.java`
- Modify `ai-agent-study-trading/ai-agent-study-trading-domain/src/main/java/denny/ai/agent/trading/domain/config/TradingStarter.java`
- Modify `ai-agent-study-trading/ai-agent-study-trading-domain/src/main/java/denny/ai/agent/trading/domain/config/TradingStateContext.java`

- [ ] **Step 1: Route `sendSseResult` through sink**

When `dynamicContext.getValue("sseEventSink")` is an `SseEventSink`, call `sendBusiness(result.getType(), result)` and return. Preserve old emitter logic only when no sink exists.

- [ ] **Step 2: Complete via sink**

In `TradingStarter`, replace TradingAgent direct emitter completion with `SseEventSink.complete()` when present; keep legacy emitter fallback for non-sink contexts and older async path compatibility.

- [ ] **Step 3: Propagate disconnected state**

In `TradingStateContext`, `isSseDisconnected()` should prefer `sink.shouldContinue() == false`; mark should call `sink.markDisconnected(cause)` when possible.

- [ ] **Step 4: Update tests**

Adjust existing tests so pipeline path verifies sink completion instead of direct emitter completion.

- [ ] **Step 5: Run tests**

Run: `mvn -pl ai-agent-study-domain,ai-agent-study-trading/ai-agent-study-trading-domain -Dtest=AbstractExecuteSupportTest,TradingStarterPipelineTest,TradingStateContextTerminalTest test`

Expected: tests pass.

### Task 5: Pipeline Abort Checks

**Files:**
- Modify `ai-agent-study-trading/ai-agent-study-trading-domain/src/main/java/denny/ai/agent/trading/domain/pipeline/TradingPipeline.java`
- Modify node classes in `ai-agent-study-trading/ai-agent-study-trading-domain/src/main/java/denny/ai/agent/trading/domain/node`

- [ ] **Step 1: Stop between stages**

Before each stage executes, check the sink from `context.getDynamicContext()`. If `shouldContinue()` is false, stop the loop without treating it as a business error.

- [ ] **Step 2: Stop before LLM calls**

In analyst, researcher, manager, risk, recommendation, and portfolio nodes, check the sink before each blocking `chatClient.prompt().user(prompt).call().content()` call.

- [ ] **Step 3: Run trading tests**

Run: `mvn -pl ai-agent-study-trading/ai-agent-study-trading-domain test`

Expected: tests pass.

### Task 6: Frontend Parser Compatibility

**Files:**
- Modify `docs/dev-ops/nginx/html/index.html` only if needed.

- [ ] **Step 1: Verify parser ignores comments**

The current parser filters only `data:` lines and returns when data is empty. This already ignores `: heartbeat\n\n`; keep behavior unchanged unless testing reveals a gap.

- [ ] **Step 2: Add minimal parser hardening if needed**

If changing parser code, ensure comment frames and empty blocks return before `JSON.parse`.

### Task 7: Final Verification

**Files:**
- All modified files.

- [ ] **Step 1: Search for direct TradingAgent emitter writes**

Run: `rg -n "emitter\\.send\\(|ResponseBodyEmitter|sseSendLock|sseDisconnected" ai-agent-study-trigger/src/main/java/denny/ai/agent/trading ai-agent-study-trading/ai-agent-study-trading-domain/src/main/java ai-agent-study-domain/src/main/java/denny/ai/agent/domain/service/auto/step/AbstractExecuteSupport.java`

Expected: TradingAgent main path writes through `TradingSseSession`; legacy non-trading fallbacks are isolated.

- [ ] **Step 2: Run targeted test suite**

Run: `mvn -pl ai-agent-study-domain,ai-agent-study-trigger,ai-agent-study-trading/ai-agent-study-trading-domain -am test`

Expected: tests pass or only pre-existing unrelated failures are documented.

- [ ] **Step 3: Manual SSE smoke command**

Run after app starts: `curl -N -H "Accept: text/event-stream" http://localhost:8090/api/v1/trading/analysis`

Expected: business `data:` payload shape remains unchanged; heartbeat appears as `: heartbeat`.

## Self-Review

- Spec coverage: covered sink abstraction, single writer, bounded queue, heartbeat, strong complete, old path收口, `shouldContinue()`, headers, frontend parser compatibility, and tests.
- Placeholder scan: no open-ended placeholders are required for execution; task details refer to exact files and behavior.
- Type consistency: `SseEventSink`, `SseSessionState`, `SseOutboundEvent`, `SseOutboundType`, and `TradingSseSession` are consistently named across tasks.
