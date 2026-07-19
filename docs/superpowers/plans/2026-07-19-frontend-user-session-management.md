# Frontend User And Session Management Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 支持在现有前端修改 `userId`、指定 `sessionId`、安全删除指定会话，并保留已经同步到 Mem0 的长期记忆。

**Architecture:** 前端维护一份可变的运行时身份状态，并提供设置弹层和单会话删除入口。后端增加统一所有权判定、删除事务和执行/删除互斥，所有读取、续接、同步与删除入口都校验 `userId + sessionId`。

**Tech Stack:** Java 17、Spring Boot、Spring MVC、MyBatis、MySQL、Redis、原生 JavaScript、Tailwind CSS、Node.js Test Runner、JUnit、Mockito、Maven。

**Design:** `docs/superpowers/plans/2026-07-19-frontend-user-session-management-design.md`

**API Design:** `docs/superpowers/plans/2026-07-19-session-management-api-design.md`

---

## File Map

- Create `SessionAccessState` and `SessionOwnershipService` for reusable ownership decisions.
- Create `SessionOperationRegistry` for atomic `EXECUTING`/`DELETING` leases.
- Create `ChatSessionCommandService` for transactional deletion and post-commit cleanup.
- Extend chat session/message DAOs and MyBatis XML with conditional delete operations.
- Modify session, Agent and Trading controllers to enforce ownership.
- Modify `agent-ui-core.js` for testable ID validation/state updates.
- Modify `index.html` for settings and single-session deletion interactions.

### Task 1: Session ownership contract

| Task | status |
|------|------|
| Task 1: Session ownership contract | append |

**Files:**
- Create: `ai-agent-study-domain/src/main/java/denny/ai/agent/domain/service/chatsession/SessionAccessState.java`
- Create: `ai-agent-study-infrastructure/src/main/java/denny/ai/agent/infrastructure/service/SessionOwnershipService.java`
- Test: `ai-agent-study-app/src/test/java/denny/ai/agent/test/infrastructure/service/SessionOwnershipServiceTest.java`

- [ ] Write failing tests for `AVAILABLE`, `OWNED`, `UNAVAILABLE`, blank IDs, illegal characters and length greater than 64.
- [ ] Run `mvn -pl ai-agent-study-app -am -Dtest=SessionOwnershipServiceTest -Dsurefire.failIfNoSpecifiedTests=false test`; expect failure because the types do not exist.
- [ ] Implement:

```java
public enum SessionAccessState { AVAILABLE, OWNED, UNAVAILABLE }

public SessionAccessState resolve(String userId, String sessionId) {
    validateId(userId);
    validateId(sessionId);
    ChatSessionPO session = chatSessionDao.queryBySessionId(sessionId);
    if (session == null) return SessionAccessState.AVAILABLE;
    return userId.equals(session.getUserId()) ? OWNED : UNAVAILABLE;
}
```

- [ ] Re-run the focused test; expect `BUILD SUCCESS`.
- [ ] Commit with `git commit -m "feat: add session ownership guard"`.

### Task 2: Atomic session operation registry

| Task | status |
|------|------|
| Task 2: Atomic session operation registry | append |

**Files:**
- Create: `ai-agent-study-infrastructure/src/main/java/denny/ai/agent/infrastructure/service/SessionOperationRegistry.java`
- Test: `ai-agent-study-infrastructure/src/test/java/denny/ai/agent/infrastructure/service/SessionOperationRegistryTest.java`

- [ ] Write failing tests proving execution blocks deletion, deletion blocks execution, wrong lease types cannot release each other, and release permits the next operation.
- [ ] Run `mvn -pl ai-agent-study-infrastructure -am -Dtest=SessionOperationRegistryTest -Dsurefire.failIfNoSpecifiedTests=false test`; expect failure because the registry does not exist.
- [ ] Implement atomic `putIfAbsent` acquisition and conditional `remove(key, expectedOperation)` release:

```java
boolean tryAcquireExecution(String userId, String sessionId);
void releaseExecution(String userId, String sessionId);
boolean tryAcquireDeletion(String userId, String sessionId);
void releaseDeletion(String userId, String sessionId);
```

- [ ] Re-run the focused test; expect `BUILD SUCCESS`.
- [ ] Commit with `git commit -m "feat: coordinate session operations"`.

### Task 3: Transactional session deletion

| Task | status |
|------|------|
| Task 3: Transactional session deletion | append |

**Files:**
- Modify: `ai-agent-study-infrastructure/src/main/java/denny/ai/agent/infrastructure/dao/IChatSessionDao.java`
- Modify: `ai-agent-study-infrastructure/src/main/java/denny/ai/agent/infrastructure/dao/IChatMessageDao.java`
- Modify: `ai-agent-study-app/src/main/resources/mybatis/mapper/chat_session_mapper.xml`
- Modify: `ai-agent-study-app/src/main/resources/mybatis/mapper/chat_message_mapper.xml`
- Create: `ai-agent-study-infrastructure/src/main/java/denny/ai/agent/infrastructure/service/ChatSessionCommandService.java`
- Test: `ai-agent-study-app/src/test/java/denny/ai/agent/test/infrastructure/service/ChatSessionCommandServiceTest.java`

- [ ] Write failing tests for owned deletion, other-user rejection, running-session rejection, delete ordering, rollback and post-commit cleanup without Mem0 calls.

```java
service.deleteOwnedSession("user-a", "session-1");
InOrder order = inOrder(chatMessageDao, chatSessionDao);
order.verify(chatMessageDao).deleteBySessionId("session-1");
order.verify(chatSessionDao).deleteByUserIdAndSessionId("user-a", "session-1");
verify(conversationMemoryService).clearRuntimeMemory("session-1");
verify(sessionEndDetectionService).removeActivity("user-a", "session-1");
```
- [ ] Run `mvn -pl ai-agent-study-app -am -Dtest=ChatSessionCommandServiceTest -Dsurefire.failIfNoSpecifiedTests=false test`; expect failure.
- [ ] Add exact DAO operations:

```java
int deleteBySessionId(String sessionId);
int deleteByUserIdAndSessionId(String userId, String sessionId);
```

```sql
DELETE FROM ai_chat_message WHERE session_id = #{sessionId};
DELETE FROM ai_chat_session WHERE user_id = #{userId} AND session_id = #{sessionId};
```

- [ ] Implement one `@Transactional` command: acquire deletion lease, require ownership, delete messages before session, require one affected session row, clear runtime memory/activity after commit, release the lease after transaction completion.
- [ ] Run `ChatSessionCommandServiceTest` and `ChatSessionDaoIntegrationTest`; expect `BUILD SUCCESS`.
- [ ] Commit with `git commit -m "feat: delete owned chat sessions"`.

### Task 4: Secure session HTTP APIs

| Task | status |
|------|------|
| Task 4: Secure session HTTP APIs | append |

**Files:**
- Modify: `ai-agent-study-infrastructure/src/main/java/denny/ai/agent/infrastructure/service/ChatSessionQueryService.java`
- Modify: `ai-agent-study-trigger/src/main/java/denny/ai/agent/trigger/http/ChatSessionController.java`
- Modify: `ai-agent-study-app/src/test/java/denny/ai/agent/test/infrastructure/service/ChatSessionQueryServiceTest.java`
- Modify: `ai-agent-study-app/src/test/java/denny/ai/agent/test/trigger/http/ChatSessionControllerIntegrationTest.java`

- [ ] Write failing tests for required message-query `userId`, unavailable IDs, absent IDs, owned deletion, foreign deletion and invalid parameters.

```java
mockMvc.perform(get("/api/v1/session/{id}/messages", "session-1")
        .param("userId", "user-a"))
        .andExpect(jsonPath("$.code").value("200"));
mockMvc.perform(delete("/api/v1/session/{id}", "session-1")
        .param("userId", "user-a"))
        .andExpect(jsonPath("$.code").value("200"));
```
- [ ] Run focused query/controller tests; expect failure because the new contracts are missing.
- [ ] Change the message endpoint and add deletion:

```java
@GetMapping("/{sessionId}/messages")
Response<MessageListResult> getSessionMessages(
        @PathVariable String sessionId,
        @RequestParam String userId,
        @RequestParam(required = false) Integer cursorIndex);

@DeleteMapping("/{sessionId}")
Response<Void> deleteSession(
        @PathVariable String sessionId,
        @RequestParam String userId);
```

- [ ] Add `RequestMethod.DELETE` to CORS and preserve the documented `200/400/404/409/500` response codes.
- [ ] Re-run focused tests; expect `BUILD SUCCESS`.
- [ ] Commit with `git commit -m "feat: expose secure session APIs"`.

### Task 5: Guard Agent and Trading execution

| Task | status |
|------|------|
| Task 5: Guard Agent and Trading execution | append |

**Files:**
- Modify: `ai-agent-study-trigger/src/main/java/denny/ai/agent/trigger/http/AiAgentController.java`
- Modify: `ai-agent-study-trigger/src/main/java/denny/ai/agent/trading/trigger/http/TradingAnalysisController.java`
- Modify: `ai-agent-study-trigger/src/main/java/denny/ai/agent/trading/trigger/http/TradingAnalysisRequestDTO.java`
- Test: `ai-agent-study-trigger/src/test/java/denny/ai/agent/trigger/http/AiAgentControllerSessionGuardTest.java`
- Test: `ai-agent-study-trigger/src/test/java/denny/ai/agent/trading/trigger/http/TradingAnalysisControllerSessionGuardTest.java`

- [ ] Write failing tests proving foreign sessions and occupied sessions never submit asynchronous work.

```java
when(ownershipService.resolve("user-a", "session-1"))
        .thenReturn(SessionAccessState.UNAVAILABLE);
controller.autoAgent(request, response);
verifyNoInteractions(autoAgentExecuteStrategy);
```
- [ ] Run the focused trigger tests; expect failure.
- [ ] Add `userId` to `TradingAnalysisRequestDTO`; resolve ownership and acquire execution before task submission in all three entry points.
- [ ] Release execution in every completion, exception, timeout and cancellation path:

```java
finally {
    operationRegistry.releaseExecution(userId, sessionId);
}
```

- [ ] Re-run focused tests; expect `BUILD SUCCESS`.
- [ ] Commit with `git commit -m "feat: guard session execution ownership"`.

### Task 6: Frontend runtime identity settings

| Task | status |
|------|------|
| Task 6: Frontend runtime identity settings | append |

**Files:**
- Modify: `docs/dev-ops/nginx/html/js/agent-ui-core.js`
- Modify: `docs/dev-ops/nginx/html/test/agent-ui-core.test.js`
- Modify: `docs/dev-ops/nginx/html/index.html`

- [ ] Write failing Node tests for valid/invalid IDs, state updates and localStorage fallback.

```javascript
assert.equal(validateRuntimeId('demo-user_01'), true);
assert.equal(validateRuntimeId('<script>'), false);
assert.equal(applyRuntimeIdentity(state, 'user-b', 'session-b').sessionId, 'session-b');
```
- [ ] Run `node --test docs/dev-ops/nginx/html/test/agent-ui-core.test.js`; expect failure because new helpers are absent.
- [ ] Implement and export:

```javascript
const RUNTIME_ID_PATTERN = /^[a-zA-Z0-9_-]{1,64}$/;
function validateRuntimeId(value) {
    return RUNTIME_ID_PATTERN.test(value || '');
}
function applyRuntimeIdentity(state, userId, sessionId) {
    if (!validateRuntimeId(userId) || !validateRuntimeId(sessionId)) {
        throw new TypeError('Invalid runtime identity');
    }
    return { ...state, userId, sessionId };
}
```

- [ ] Replace startup constants with `runtimeState.userId` and `runtimeState.sessionId` in all general, Trading, history and memory requests.
- [ ] Add the settings icon/popover, two inputs, Apply/Cancel, focus restoration, Escape handling, Toast validation, request cancellation, user persistence, cursor reset and history reload.
- [ ] Re-run Node tests; expect all tests pass.
- [ ] Commit with `git commit -m "feat: edit frontend user and session ids"`.

### Task 7: Frontend single-session deletion

| Task | status |
|------|------|
| Task 7: Frontend single-session deletion | append |

**Files:**
- Modify: `docs/dev-ops/nginx/html/index.html`
- Modify: `docs/dev-ops/nginx/html/test/agent-ui-security-smoke.html`

- [ ] Add failing browser assertions for DELETE URL/method, stopped click propagation, confirmation cancellation, current/non-current state transitions and request failure recovery.

```javascript
assert(deleteUrl.includes('/api/v1/session/session-owned?userId=demo-user'));
assert.equal(deleteRequest.method, 'DELETE');
assert.equal(agentDocument.querySelectorAll('[data-session-id="session-owned"]').length, 0);
```
- [ ] Add an accessible delete icon to every session list item and confirmation text stating that synchronized long-term memory is retained.
- [ ] Call:

```javascript
fetch(buildApiUrl(`/api/v1/session/${encodeURIComponent(targetSessionId)}?userId=${encodeURIComponent(runtimeState.userId)}`), {
    method: 'DELETE',
    headers: { Accept: 'application/json' }
});
```

- [ ] Reject deletion of the currently running session; reset to a generated session after deleting the current session; otherwise remove only the target item.
- [ ] Run the browser security smoke page; expect all assertions `PASS` and no console error.
- [ ] Commit with `git commit -m "feat: delete individual chat sessions"`.

### Task 8: Regression and acceptance

| Task | status |
|------|------|
| Task 8: Regression and acceptance | append |

**Files:**
- Modify after verification: `docs/superpowers/test/2026-06-21-frontend-product-polish-test.md`
- Review: all files changed by Tasks 1-7

- [ ] Run `node --test docs/dev-ops/nginx/html/test/agent-ui-core.test.js`; expect all tests pass.
- [ ] Run focused backend tests for ownership, registry, deletion, session controller and execution guards; expect `BUILD SUCCESS`.
- [ ] Run `mvn clean compile -DskipTests`; expect `BUILD SUCCESS`.
- [ ] Verify desktop `1280x720` and mobile `390x844`: user switch, owned/new/foreign session selection, current/non-current deletion, retained-Mem0 notice, keyboard focus and no overlapping UI.
- [ ] Update a task status from `append` to `pass` only after every step in that task is executed successfully.
- [ ] Record executed commands, results and remaining external/manual gaps in the test document.
- [ ] Commit with `git commit -m "test: verify frontend session management"`.
