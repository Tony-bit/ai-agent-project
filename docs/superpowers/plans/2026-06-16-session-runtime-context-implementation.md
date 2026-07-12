# Session Runtime Context Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a runtime context layer that prepares reusable turn/session context once per request while preserving existing routing and chat behavior.

**Architecture:** `AutoAgentExecuteStrategy` creates the turn-scoped `DynamicContext`, then delegates runtime preparation to `RuntimeContextAssembler`. The assembler loads flow config through `AgentRuntimeConfigCache`, loads conversation history through `SessionRuntimeContextManager`, writes strongly typed context objects plus compatibility keys into `DynamicContext`, and downstream routing nodes reuse those keys with their current DB/Redis fallback.

**Tech Stack:** Java, Spring Boot, Maven, JUnit 4, Mockito.

---

### Task 1: Runtime Context Types

**Files:**
- Create: `ai-agent-study-domain/src/main/java/denny/ai/agent/domain/model/valobj/runtime/SessionRuntimeContext.java`
- Create: `ai-agent-study-domain/src/main/java/denny/ai/agent/domain/model/valobj/runtime/TurnRuntimeContext.java`
- Create: `ai-agent-study-domain/src/main/java/denny/ai/agent/domain/service/runtime/RuntimeContextKeys.java`

- [ ] Add Lombok-backed value objects for session and turn context.
- [ ] Add string constants for `turnRuntimeContext`, `sessionRuntimeContext`, `userRuntimeContext`, `recentHistoryMessages`, `persona`, and `aiAgentClientFlowConfigVOMap`.
- [ ] Compile domain sources.

### Task 2: Runtime Services

**Files:**
- Create: `ai-agent-study-domain/src/main/java/denny/ai/agent/domain/service/runtime/AgentRuntimeConfigCache.java`
- Create: `ai-agent-study-domain/src/main/java/denny/ai/agent/domain/service/runtime/SessionRuntimeContextManager.java`
- Create: `ai-agent-study-domain/src/main/java/denny/ai/agent/domain/service/runtime/RuntimeContextAssembler.java`
- Create: `ai-agent-study-domain/src/main/java/denny/ai/agent/domain/service/runtime/DefaultRuntimeContextAssembler.java`
- Test: `ai-agent-study-domain/src/test/java/denny/ai/agent/domain/service/runtime/SessionRuntimeContextManagerTest.java`
- Test: `ai-agent-study-domain/src/test/java/denny/ai/agent/domain/service/runtime/DefaultRuntimeContextAssemblerTest.java`
- Test: `ai-agent-study-domain/src/test/java/denny/ai/agent/domain/service/runtime/AgentRuntimeConfigCacheTest.java`

- [ ] Implement TTL-based flow config cache using `ConcurrentHashMap`.
- [ ] Implement session context manager that loads `ChatMemoryPersistenceService.getConversationHistory(sessionId)` once and formats `role: content` history lines.
- [ ] Implement assembler that chooses all-routing config for empty `aiAgentId`, agent-specific config for explicit `aiAgentId`, loads session context, and writes compatibility keys.
- [ ] Add tests for cache hit/miss/clear, history formatting/fallback, and assembler key writes.

### Task 3: Execution and Root Wiring

**Files:**
- Modify: `ai-agent-study-domain/src/main/java/denny/ai/agent/domain/service/auto/AutoAgentExecuteStrategy.java`
- Modify: `ai-agent-study-domain/src/main/java/denny/ai/agent/domain/service/auto/step/RootNode.java`
- Test: `ai-agent-study-domain/src/test/java/denny/ai/agent/domain/service/auto/AutoAgentExecuteStrategyTest.java`
- Test: `ai-agent-study-domain/src/test/java/denny/ai/agent/domain/service/auto/step/RootNodeTest.java`

- [ ] Inject `RuntimeContextAssembler` into `AutoAgentExecuteStrategy`.
- [ ] Call `prepare()` after trace id is set and before the handler chain runs.
- [ ] Remove direct repository loading from `initInspectionContext`; assembler supplies explicit-agent config.
- [ ] Update `RootNode` to use existing `dynamicContext.aiAgentClientFlowConfigVOMap` first, and only query repository if config is absent.
- [ ] Add/update tests to verify prepared config is not queried again.

### Task 4: Routing History Reuse

**Files:**
- Modify: `ai-agent-study-domain/src/main/java/denny/ai/agent/domain/service/auto/step/routing/IntentRoutingNode.java`
- Modify: `ai-agent-study-domain/src/main/java/denny/ai/agent/domain/service/auto/step/routing/QueryDecompositionNode.java`
- Modify: `ai-agent-study-domain/src/main/java/denny/ai/agent/domain/service/auto/step/routing/TaskRoutingSlotNode.java`
- Test: `ai-agent-study-domain/src/test/java/denny/ai/agent/domain/service/auto/step/routing/IntentRoutingNodeTest.java`
- Test: `ai-agent-study-domain/src/test/java/denny/ai/agent/domain/service/auto/step/routing/QueryDecompositionNodeTest.java`
- Test: `ai-agent-study-domain/src/test/java/denny/ai/agent/domain/service/auto/step/routing/TaskRoutingSlotNodeTest.java`

- [ ] Read `RuntimeContextKeys.RECENT_HISTORY_MESSAGES` from `DynamicContext`.
- [ ] Fall back to current `ChatMemoryPersistenceService` history loading when the compatibility key is absent.
- [ ] Add tests proving prepared history is passed to routing services and persistence service is not called.

### Task 5: Trading Skills Auto Reload Configuration

**Files:**
- Modify: `ai-agent-study-trading/ai-agent-study-trading-infra/src/main/java/denny/ai/agent/trading/infra/config/TradingSkillsConfig.java`
- Test: `ai-agent-study-trading/ai-agent-study-trading-infra/src/test/java/denny/ai/agent/trading/infra/config/TradingSkillsConfigTest.java`

- [ ] Add `@Value("${spring.ai.trading.skills.auto-reload:false}")`.
- [ ] Pass the property to `SkillsAgentHook.autoReload(...)`.
- [ ] Update tests to verify the config bean still builds.

### Task 6: Verification

**Files:**
- All modified files.

- [ ] Run focused domain tests for runtime and routing.
- [ ] Run focused trading config test.
- [ ] Run `mvn clean compile -q`.
- [ ] Report any failing pre-existing tests or environment blockers.
