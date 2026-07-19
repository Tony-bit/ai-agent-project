# Trading Agent LLM Stream Aggregation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Migrate the 12 Trading Agent report nodes to upstream streaming with server-side aggregation, bounded timeout/cancellation semantics, and guarded two-phase result commits without changing the frontend SSE or report JSON contracts.

**Architecture:** `RetryChatModel` owns per-attempt first-content/idle timeouts and the logical-call deadline. A domain-level collector aggregates request-local chunks and reacts to request cancellation. Trading roles prepare typed results; a trading-level execution scope and committer are the only path that writes real context, after which stages enqueue existing SSE events and advance phases.

**Tech Stack:** Java 17, Spring Boot, Spring AI `ChatClient`, Project Reactor, JUnit 4/5, Mockito, Maven.

---

### Task 1: Streaming timeout configuration

| Task | status |
|------|------|
| Task 1: Streaming timeout configuration | pass |

**Files:**
- Create: `ai-agent-study-domain/src/main/java/denny/ai/agent/domain/service/armory/AiStreamingProperties.java`
- Modify: `ai-agent-study-domain/src/main/java/denny/ai/agent/domain/model/valobj/AiClientModelVO.java`
- Modify: `ai-agent-study-domain/src/main/java/denny/ai/agent/domain/service/armory/AiClientModelNode.java`
- Modify: `ai-agent-study-app/src/main/resources/application.yml`
- Test: `ai-agent-study-domain/src/test/java/denny/ai/agent/domain/service/armory/AiStreamingPropertiesTest.java`

- [ ] Add validated positive defaults for connect `10s`, first content `45s`, idle `30s`, and logical total `150s`.
- [ ] Add optional model `extParam.streamingTimeout` overrides and merge them in `AiClientModelNode`.
- [ ] Write tests for defaults, partial override, non-positive values, and total-timeout validation.
- [ ] Run `mvn -pl ai-agent-study-domain -am -Dtest=AiStreamingPropertiesTest -Dsurefire.failIfNoSpecifiedTests=false test` and expect `BUILD SUCCESS`.

### Task 2: RetryChatModel streaming state machine

| Task | status |
|------|------|
| Task 2: RetryChatModel streaming state machine | pass |

**Files:**
- Modify: `ai-agent-study-domain/src/main/java/denny/ai/agent/domain/service/armory/factory/element/RetryChatModel.java`
- Modify: `ai-agent-study-domain/src/test/java/denny/ai/agent/domain/service/armory/factory/element/RetryChatModelStreamTest.java`

- [ ] Replace attempt-local `AtomicBoolean emitted` with `AWAITING_RESPONSE`, `RESPONSE_OBSERVED`, and `CONTENT_OBSERVED` phases.
- [ ] Detect effective text from `ChatResponse` output text; role, usage, tool data, and blank deltas lock retries without satisfying first-content timing.
- [ ] Apply first-content timeout per attempt, idle timeout after effective content, and one total deadline across attempts, compression, and backoff.
- [ ] Preserve current 1261 compression and retry budgets; never retry after response/content observation.
- [ ] Add virtual-time tests for timeout boundaries, retry locking, call-budget sharing, and concurrent call isolation.
- [ ] Run `mvn -pl ai-agent-study-domain -am -Dtest=RetryChatModelStreamTest -Dsurefire.failIfNoSpecifiedTests=false test` and expect `BUILD SUCCESS`.

### Task 3: Domain collector and request cancellation

| Task | status |
|------|------|
| Task 3: Domain collector and request cancellation | pass |

**Files:**
- Create: `ai-agent-study-domain/src/main/java/denny/ai/agent/domain/service/auto/step/StreamingChatResponseCollector.java`
- Create: `ai-agent-study-domain/src/main/java/denny/ai/agent/domain/service/auto/step/ClientDisconnectedException.java`
- Modify: `ai-agent-study-domain/src/main/java/denny/ai/agent/domain/service/sse/SseEventSink.java`
- Modify: `ai-agent-study-domain/src/main/java/denny/ai/agent/domain/service/auto/step/AbstractExecuteSupport.java`
- Modify: `ai-agent-study-trigger/src/main/java/denny/ai/agent/trading/trigger/http/TradingSseSession.java`
- Test: `ai-agent-study-domain/src/test/java/denny/ai/agent/domain/service/auto/step/StreamingChatResponseCollectorTest.java`
- Test: `ai-agent-study-trigger/src/test/java/denny/ai/agent/trading/trigger/http/TradingSseSessionTest.java`

- [ ] Add a default never-cancelling signal to `SseEventSink` so existing non-Trading implementations remain source-compatible.
- [ ] Aggregate non-null chunks in order with a request-local `StringBuilder`; return only on normal completion.
- [ ] Bind cancellation and thread interruption to upstream subscription disposal and throw `ClientDisconnectedException` without returning partial text.
- [ ] Expose `collectStreamingResponse(requestSpec, operationName, sink)` from `AbstractExecuteSupport`.
- [ ] Emit the Trading session cancellation signal exactly once for disconnect, writer failure, timeout, or terminal failure.
- [ ] Run the collector, abstract support, and trigger session tests and expect `BUILD SUCCESS`.

### Task 4: Trading execution scope and commit gate

| Task | status |
|------|------|
| Task 4: Trading execution scope and commit gate | pass |

**Files:**
- Create: `ai-agent-study-trading/ai-agent-study-trading-domain/src/main/java/denny/ai/agent/trading/domain/execution/NodeExecutionStatus.java`
- Create: `ai-agent-study-trading/ai-agent-study-trading-domain/src/main/java/denny/ai/agent/trading/domain/execution/NodeExecutionState.java`
- Create: `ai-agent-study-trading/ai-agent-study-trading-domain/src/main/java/denny/ai/agent/trading/domain/execution/NodeExecutionScope.java`
- Create: `ai-agent-study-trading/ai-agent-study-trading-domain/src/main/java/denny/ai/agent/trading/domain/execution/NodeExecutionResult.java`
- Create: `ai-agent-study-trading/ai-agent-study-trading-domain/src/main/java/denny/ai/agent/trading/domain/execution/NodeResultCommitter.java`
- Test: `ai-agent-study-trading/ai-agent-study-trading-domain/src/test/java/denny/ai/agent/trading/domain/execution/NodeResultCommitterTest.java`

- [ ] Implement one-way `RUNNING -> COMMITTING -> COMMITTED` and `RUNNING -> FAILED|TIMED_OUT|CANCELLED` transitions.
- [ ] Require success result, live deadline, uncancelled request, expected phase, and successful CAS before invoking the short context-writer callback.
- [ ] Test failed/late/cancelled rejection, commit-timeout races, deadline checks before scheduled timeout callbacks, and absence of SSE work in the commit section.
- [ ] Run `mvn -pl ai-agent-study-trading/ai-agent-study-trading-domain -am -Dtest=NodeResultCommitterTest -Dsurefire.failIfNoSpecifiedTests=false test` and expect `BUILD SUCCESS`.

### Task 5: Configurable node deadlines and invoker cancellation

| Task | status |
|------|------|
| Task 5: Configurable node deadlines and invoker cancellation | pass |

**Files:**
- Modify: `ai-agent-study-trading/ai-agent-study-trading-domain/src/main/java/denny/ai/agent/trading/domain/config/TradingAgentProperties.java`
- Modify: `ai-agent-study-trading/ai-agent-study-trading-domain/src/main/java/denny/ai/agent/trading/domain/pipeline/TradingNodeInvoker.java`
- Modify: `ai-agent-study-trading/ai-agent-study-trading-domain/src/main/java/denny/ai/agent/trading/domain/config/TradingDispatcher.java`
- Test: `ai-agent-study-trading/ai-agent-study-trading-domain/src/test/java/denny/ai/agent/trading/domain/pipeline/TradingNodeInvokerTest.java`

- [ ] Add positive `node-timeout=180s` and validate it is greater than the effective model total timeout.
- [ ] Create an independent scope/deadline per invocation; on timeout transition scope before `future.cancel(true)`.
- [ ] Make interruption and request cancellation terminal and reject late returned values.
- [ ] Remove analyst-count timeout multiplication from both pipeline and legacy dispatcher paths.
- [ ] Run invoker and dispatcher tests and expect `BUILD SUCCESS`.

### Task 6: Migrate the 12 Trading roles to prepare-only results

| Task | status |
|------|------|
| Task 6: Migrate the 12 Trading roles to prepare-only results | pass |

**Files:**
- Modify: all 12 classes under `ai-agent-study-trading/ai-agent-study-trading-domain/src/main/java/denny/ai/agent/trading/domain/node/` listed in the Story.
- Test: representative analyst, debate, risk, decision, and recommendation node tests under `ai-agent-study-trading/ai-agent-study-trading-domain/src/test/java`.

- [ ] Introduce typed prepare methods that accept read-only inputs plus `SseEventSink` and return `NodeExecutionResult<T>`.
- [ ] Replace each target `.call().content()` with the common collector and preserve client IDs, prompts, parsing, fallback rules, and output types.
- [ ] Remove real context mutation, final report SSE, and phase-driving calls from prepare methods; retain guarded progress events only.
- [ ] Add representative parsing/fallback tests and verify prepare leaves real context unchanged.
- [ ] Run `rg -n "\.call\(\)\.content\(\)"` over the 12 target files and expect no matches.

### Task 7: Stage commit, SSE, and success policies

| Task | status |
|------|------|
| Task 7: Stage commit, SSE, and success policies | pass |

**Files:**
- Modify: all Trading Stage classes in `ai-agent-study-trading/ai-agent-study-trading-domain/src/main/java/denny/ai/agent/trading/domain/pipeline/`.
- Modify: `ai-agent-study-trading/ai-agent-study-trading-domain/src/main/java/denny/ai/agent/trading/domain/pipeline/AnalystCollectionStage.java`
- Modify: `ai-agent-study-trading/ai-agent-study-trading-domain/src/main/java/denny/ai/agent/trading/domain/pipeline/TradingPipeline.java`
- Test: corresponding pipeline/stage tests.

- [ ] Have each Stage invoke prepare, commit typed values, then enqueue the existing final SSE and transition phase outside the commit section.
- [ ] Give each parallel analyst an independent deadline and finish when all outcomes are terminal without multiplying wait time.
- [ ] Advance with at least one committed analyst; enter `ERROR` if all analysts fail or a required serial node fails.
- [ ] On post-commit SSE failure keep context committed, cancel the request, and prevent subsequent stages.
- [ ] Run trading-domain stage and pipeline tests and expect `BUILD SUCCESS`.

### Task 8: Regression and configuration verification

| Task | status |
|------|------|
| Task 8: Regression and configuration verification | pass |

**Files:**
- Modify: focused regression tests in domain, trading-domain, and trigger modules.
- Modify: `docs/superpowers/test/2026-07-16-trading-llm-stream-aggregation-test.md` statuses only after verification.

- [ ] Verify GeneralChat still forwards chunks and non-target synchronous callers still use RestClient behavior.
- [ ] Verify no raw model chunks appear in Trading SSE and existing event/report JSON shapes remain unchanged.
- [ ] Run domain tests, trading-domain tests, trigger tests, and `mvn clean compile -DskipTests`.
- [ ] Update only actually executed test and acceptance statuses from `append` to `pass`; leave cloud/manual items as `append` unless performed.

### Task 9: Final review

| Task | status |
|------|------|
| Task 9: Final review | pass |

**Files:**
- Review all files changed by Tasks 1-8.

- [ ] Search for leaked prompts/responses/API keys in new logs and ensure metrics contain only lengths, timing, operation, completion state, and root cause type.
- [ ] Review dependency direction so shared domain code does not import Trading classes.
- [ ] Review `git diff` without reverting pre-existing user changes.
- [ ] Record commands, pass/fail results, manual verification gaps, and residual risks in the final handoff.
