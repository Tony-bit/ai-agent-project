# Frontend User And Session Management Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 支持在现有前端修改 `userId`、指定 `sessionId`、安全删除指定会话，并保留已经同步到 Mem0 的长期记忆。

**Architecture:** 在现有原生 HTML/JavaScript 页面中增加身份会话设置弹层和单会话删除交互；后端增加统一会话所有权服务、删除事务和执行互斥控制。所有读取、续接、同步与删除入口统一校验 `userId + sessionId`，数据库删除完成后只清理运行时缓存，不删除 Mem0 长期记忆。

**Tech Stack:** Java 17、Spring Boot、Spring MVC、MyBatis、MySQL、Redis、原生 JavaScript、Tailwind CSS、Node.js Test Runner、JUnit 4/5、Mockito、Maven。

---

## Confirmed Design

## 背景

当前原生 HTML 前端会在启动时从 URL 参数、`localStorage` 和默认值中解析 `userId`，但页面内只能查看、不能修改。会话 ID 由页面自动生成或通过历史列表切换，同样没有手工指定入口。会话列表也没有删除单个会话的能力。

本次改动在现有单页和原生 JavaScript 架构内补齐以下能力：

1. 在页面内修改当前 `userId`。
2. 在页面内修改或指定当前 `sessionId`。
3. 删除当前用户拥有的指定会话。
4. 删除会话时保留已经同步到 Mem0 的长期记忆。

不引入新的前端框架，不增加用户认证体系，不提供批量删除，也不修改 Mem0 中的长期记忆。

## 交互设计

### 身份与会话设置

保留页面底部现有的用户 ID、会话 ID 状态条，在其右侧增加设置图标按钮。点击后打开紧凑弹层，包含：

- 用户 ID 输入框。
- 会话 ID 输入框。
- 取消按钮。
- 应用按钮。

输入值仅允许字母、数字、下划线和连字符，长度为 1 到 64 个字符。校验失败时使用现有非阻塞 Toast 展示错误，不关闭弹层。

点击应用后：

1. 如果存在活动请求，先取消该请求，避免旧身份或旧会话的响应覆盖新状态。
2. 更新页面内的当前用户 ID 和会话 ID。
3. 将用户 ID 持久化到 `localStorage` 的 `agent.userId`；会话 ID 只在当前页面生命周期内生效，不持久化，避免刷新后意外续接手工会话。
4. 用户 ID 变化时重置会话分页状态，并重新加载该用户的会话列表。
5. 会话 ID 变化时携带当前用户 ID 请求对应历史消息；若会话不存在，则按新会话展示空态，后续发送使用该 ID；若会话已属于其他用户，则拒绝切换并保留原状态。

用户 ID 和会话 ID 的当前值继续显示在状态条中。弹层打开时使用当前值初始化输入框。

### 删除指定会话

会话列表项在悬停或键盘聚焦时显示删除图标按钮，并提供可访问名称。点击删除按钮不得触发会话切换，而是打开确认对话框，明确展示将删除的会话 ID。

确认后调用单会话删除接口：

- 成功：从列表移除该项并显示成功 Toast。
- 删除的是当前会话：清空当前展示，生成新的会话 ID，并回到通用对话空态。
- 删除的不是当前会话：保持当前聊天内容和会话 ID 不变。
- 失败：保留列表项和当前页面状态，显示后端错误信息或通用失败提示。

如果当前会话存在活动请求，前端不允许直接删除该会话，提示用户先取消任务。其他非活动会话仍可删除。

## 后端接口

在 `ChatSessionController` 增加：

```http
DELETE /api/v1/session/{sessionId}?userId={userId}
```

成功响应沿用现有统一响应结构：

```json
{
  "code": "200",
  "info": "success",
  "data": null
}
```

接口要求：

1. `userId` 和 `sessionId` 使用与查询接口一致的非空、长度和字符格式校验。
2. 必须按 `userId + sessionId` 查询并校验会话所有权，不能只按 `sessionId` 删除。
3. 会话不存在或不属于该用户时返回业务失败，不泄露该会话是否属于其他用户。
4. Controller 的 CORS 方法声明增加 `DELETE`。

现有历史消息接口补充必填的 `userId` 查询参数：

```http
GET /api/v1/session/{sessionId}/messages?userId={userId}&cursorIndex={cursorIndex}
```

查询消息前必须验证会话所有权。会话不存在时返回带有“会话不存在”语义的空结果，供前端将合法 ID 作为新会话使用；会话属于其他用户时返回统一的无权访问业务失败，不能返回任何消息。

通用 Agent 和股票分析入口也必须在开始执行前复用同一所有权守卫：会话不存在时允许创建，属于当前用户时允许续接，属于其他用户时拒绝执行。股票分析请求 DTO 增加 `userId`，前端同步传递当前值。该校验不能只放在前端，否则手工调用接口仍可向其他用户的会话追加消息。

## 服务与数据边界

新增会话删除服务，负责一个明确的事务边界：

1. 按 `userId + sessionId` 查询并确认会话所有权；不存在或不属于当前用户时终止操作。
2. 删除 `ai_chat_message` 中该会话的全部消息。
3. 删除 `ai_chat_session` 中对应的会话记录。
4. 数据库事务提交后，清理该会话的 Redis/进程内运行时缓存和活动跟踪状态。

数据库删除必须先删除消息、再删除会话，以兼容没有级联删除约束的部署。DAO 删除语句都带 `sessionId`；会话删除语句额外带 `userId`，并通过受影响行数确认所有权未在操作期间变化。

缓存清理属于派生状态清理。数据库删除成功后，即使缓存清理失败，接口也记录警告并按删除成功处理，避免已删除的数据库事实被误报为失败。后续请求不得通过缓存重新持久化已删除会话；前端通过禁止删除活动会话降低该竞争风险，服务端也应在删除入口清理活动跟踪。

## Mem0 策略

删除接口不调用 Mem0 删除能力，也不修改长期记忆同步记录之外的任何 Mem0 数据。

原因是长期记忆可能经过抽取、去重和合并，无法保证与单个会话一一对应。此次“删除会话”仅表示删除本站可见的会话及消息历史，确认对话框中应明确提示“已同步的长期记忆将保留”。

## 状态与数据流

```text
设置弹层 -> 校验输入 -> 取消活动请求 -> 更新当前身份/会话
                                      -> userId 变化 -> 重载会话列表
                                      -> sessionId 变化 -> 加载历史或显示空态

会话删除按钮 -> 二次确认 -> DELETE(userId, sessionId)
                           -> 后端所有权校验
                           -> 事务删除消息与会话
                           -> 清理派生缓存，保留 Mem0
                           -> 前端移除列表项并修正当前会话状态
```

所有通用对话、股票分析、会话列表、历史消息和记忆同步请求继续从同一份可变运行时状态读取当前 `userId` 与 `sessionId`，避免部分接口仍使用页面启动时的常量。后端所有读取、续接和删除已有会话的入口统一执行所有权校验。

## 错误处理

- 非法 ID：前端阻止提交；后端仍独立校验。
- 历史会话不存在：手工指定会话时显示空态，不视为页面错误。
- 会话已属于其他用户：拒绝加载历史和发起任务，保留切换前的页面状态。
- 删除不存在或无权访问的会话：返回统一业务失败，前端不移除列表项。
- 删除请求网络失败：恢复删除按钮并允许重试。
- 活动请求期间切换设置：先取消活动请求，再应用新值。
- 活动请求期间删除当前会话：前端拒绝并提示先取消。
- `localStorage` 不可用：用户 ID 仍在当前页面内生效，不阻断操作。

## 测试设计

### 前端核心测试

- ID 格式校验覆盖合法值、空值、超长值和非法字符。
- 应用新用户后更新运行时状态、持久化用户 ID并重置会话分页。
- 应用新会话后加载历史；空历史进入新会话空态。
- 删除按钮阻止列表项点击冒泡。
- 删除当前会话与删除非当前会话分别更新正确的页面状态。
- 删除失败时保留列表项并恢复交互。
- 活动请求期间不能删除当前会话。

### 后端测试

- 当前用户可删除自己的会话及全部消息。
- 会话不存在时不执行消息删除。
- 用户不能删除其他用户的会话。
- 用户不能读取其他用户的会话消息，也不能通过通用 Agent 或股票分析向其追加内容。
- 不存在的合法会话 ID 可以由当前用户首次使用并创建。
- 数据库删除失败时事务回滚，不留下只删除一部分的数据。
- 删除成功后触发运行时缓存与活动跟踪清理。
- Mem0 客户端或长期记忆接口不会被调用。

### 浏览器回归

- 桌面和窄屏下设置弹层不溢出、不遮挡关键操作。
- 切换用户后列表、当前用户标签和后续请求参数一致。
- 指定已有会话可恢复历史，指定新 ID 可正常开始对话。
- 删除确认文案明确提示保留长期记忆。
- 键盘可以打开设置、聚焦删除按钮、取消或确认操作。

## 验收标准

1. 用户可在页面内修改合法的用户 ID 和会话 ID，所有后续接口使用新值。
2. 切换用户会加载对应用户的会话列表，不混入上一用户的会话。
3. 手工指定当前用户已有的会话会加载历史，指定不存在的合法 ID 可作为新会话使用，其他用户的会话不能读取或续接。
4. 用户只能删除自己拥有的指定会话，消息和会话数据库记录一并删除。
5. 删除当前会话后页面进入新的空会话，删除其他会话不影响当前内容。
6. 已同步到 Mem0 的长期记忆在删除会话后仍然保留。
7. 失败、取消、窄屏和键盘操作均不会导致页面进入不可恢复状态。

---

## File Structure

| File | Responsibility |
|---|---|
| `ai-agent-study-domain/src/main/java/denny/ai/agent/domain/service/chatsession/SessionAccessState.java` | 定义会话可用、当前用户拥有、其他用户占用三种状态 |
| `ai-agent-study-infrastructure/src/main/java/denny/ai/agent/infrastructure/service/SessionOwnershipService.java` | 统一校验 ID 格式与会话所有权 |
| `ai-agent-study-infrastructure/src/main/java/denny/ai/agent/infrastructure/service/SessionOperationRegistry.java` | 原子协调执行和删除占用 |
| `ai-agent-study-infrastructure/src/main/java/denny/ai/agent/infrastructure/service/ChatSessionCommandService.java` | 承担会话删除事务与提交后缓存清理 |
| `ai-agent-study-infrastructure/src/main/java/denny/ai/agent/infrastructure/service/ChatSessionQueryService.java` | 按当前用户安全查询会话消息 |
| `ai-agent-study-infrastructure/src/main/java/denny/ai/agent/infrastructure/dao/IChatSessionDao.java` | 增加带用户条件的会话删除 |
| `ai-agent-study-infrastructure/src/main/java/denny/ai/agent/infrastructure/dao/IChatMessageDao.java` | 增加按会话删除消息 |
| `ai-agent-study-app/src/main/resources/mybatis/mapper/chat_session_mapper.xml` | 实现会话条件删除 SQL |
| `ai-agent-study-app/src/main/resources/mybatis/mapper/chat_message_mapper.xml` | 实现消息删除 SQL |
| `ai-agent-study-trigger/src/main/java/denny/ai/agent/trigger/http/ChatSessionController.java` | 暴露安全历史查询和单会话删除接口 |
| `ai-agent-study-trigger/src/main/java/denny/ai/agent/trigger/http/AiAgentController.java` | 通用/巡检执行前校验所有权并登记执行占用 |
| `ai-agent-study-trigger/src/main/java/denny/ai/agent/trading/trigger/http/TradingAnalysisController.java` | 股票分析执行前校验所有权并登记执行占用 |
| `ai-agent-study-trigger/src/main/java/denny/ai/agent/trading/trigger/http/TradingAnalysisRequestDTO.java` | 增加 `userId` 字段 |
| `docs/dev-ops/nginx/html/js/agent-ui-core.js` | 提供 ID 校验和可测试的运行时配置更新函数 |
| `docs/dev-ops/nginx/html/index.html` | 增加设置弹层、会话删除交互并统一使用可变运行时状态 |

## Implementation Tasks

### Task 1: Session ownership contract

| Task | status |
|------|------|
| Task 1: Session ownership contract | append |

**Files:**
- Create: `ai-agent-study-domain/src/main/java/denny/ai/agent/domain/service/chatsession/SessionAccessState.java`
- Create: `ai-agent-study-infrastructure/src/main/java/denny/ai/agent/infrastructure/service/SessionOwnershipService.java`
- Create: `ai-agent-study-app/src/test/java/denny/ai/agent/test/infrastructure/service/SessionOwnershipServiceTest.java`

- [ ] **Step 1: Write failing ownership tests**

```java
assertEquals(SessionAccessState.AVAILABLE, service.resolve("user-a", "new-session"));
assertEquals(SessionAccessState.OWNED, service.resolve("user-a", "owned-session"));
assertEquals(SessionAccessState.UNAVAILABLE, service.resolve("user-a", "other-session"));
assertThrows(IllegalArgumentException.class, () -> service.resolve("<script>", "session-1"));
```

- [ ] **Step 2: Run the focused test and verify failure**

```powershell
mvn -pl ai-agent-study-app -am -Dtest=SessionOwnershipServiceTest -Dsurefire.failIfNoSpecifiedTests=false test
```

Expected: FAIL because `SessionAccessState` and `SessionOwnershipService` do not exist.

- [ ] **Step 3: Implement the minimal ownership contract**

```java
public enum SessionAccessState { AVAILABLE, OWNED, UNAVAILABLE }

public SessionAccessState resolve(String userId, String sessionId) {
    validateId("userId", userId);
    validateId("sessionId", sessionId);
    ChatSessionPO session = chatSessionDao.queryBySessionId(sessionId);
    if (session == null) return SessionAccessState.AVAILABLE;
    return userId.equals(session.getUserId())
            ? SessionAccessState.OWNED : SessionAccessState.UNAVAILABLE;
}
```

- [ ] **Step 4: Run the focused test and verify pass**

Run the Step 2 command. Expected: `BUILD SUCCESS`.

- [ ] **Step 5: Commit**

```powershell
git add ai-agent-study-domain/src/main/java/denny/ai/agent/domain/service/chatsession/SessionAccessState.java ai-agent-study-infrastructure/src/main/java/denny/ai/agent/infrastructure/service/SessionOwnershipService.java ai-agent-study-app/src/test/java/denny/ai/agent/test/infrastructure/service/SessionOwnershipServiceTest.java
git commit -m "feat: add session ownership guard"
```

### Task 2: Atomic execution and deletion registry

| Task | status |
|------|------|
| Task 2: Atomic execution and deletion registry | append |

**Files:**
- Create: `ai-agent-study-infrastructure/src/main/java/denny/ai/agent/infrastructure/service/SessionOperationRegistry.java`
- Create: `ai-agent-study-infrastructure/src/test/java/denny/ai/agent/infrastructure/service/SessionOperationRegistryTest.java`

- [ ] **Step 1: Write failing mutual-exclusion tests**

```java
assertTrue(registry.tryAcquireExecution("user-a", "session-1"));
assertFalse(registry.tryAcquireDeletion("user-a", "session-1"));
registry.releaseExecution("user-a", "session-1");
assertTrue(registry.tryAcquireDeletion("user-a", "session-1"));
assertFalse(registry.tryAcquireExecution("user-a", "session-1"));
```

- [ ] **Step 2: Run the focused test and verify failure**

```powershell
mvn -pl ai-agent-study-infrastructure -am -Dtest=SessionOperationRegistryTest -Dsurefire.failIfNoSpecifiedTests=false test
```

Expected: FAIL because `SessionOperationRegistry` does not exist.

- [ ] **Step 3: Implement atomic state transitions**

```java
private enum Operation { EXECUTING, DELETING }

public boolean tryAcquireExecution(String userId, String sessionId) {
    return operations.putIfAbsent(key(userId, sessionId), Operation.EXECUTING) == null;
}

public boolean tryAcquireDeletion(String userId, String sessionId) {
    return operations.putIfAbsent(key(userId, sessionId), Operation.DELETING) == null;
}
```

Release methods must use `remove(key, expectedOperation)` so one operation cannot release another operation's lease.

- [ ] **Step 4: Run the focused test and verify pass**

Run the Step 2 command. Expected: `BUILD SUCCESS`.

- [ ] **Step 5: Commit**

```powershell
git add ai-agent-study-infrastructure/src/main/java/denny/ai/agent/infrastructure/service/SessionOperationRegistry.java ai-agent-study-infrastructure/src/test/java/denny/ai/agent/infrastructure/service/SessionOperationRegistryTest.java
git commit -m "feat: coordinate session execution and deletion"
```

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
- Create: `ai-agent-study-app/src/test/java/denny/ai/agent/test/infrastructure/service/ChatSessionCommandServiceTest.java`

- [ ] **Step 1: Write failing deletion service tests**

```java
service.deleteOwnedSession("user-a", "session-1");
InOrder order = inOrder(chatMessageDao, chatSessionDao);
order.verify(chatMessageDao).deleteBySessionId("session-1");
order.verify(chatSessionDao).deleteByUserIdAndSessionId("user-a", "session-1");
verify(conversationMemoryService).clearRuntimeMemory("session-1");
verify(sessionEndDetectionService).removeActivity("user-a", "session-1");
```

Also assert `409`-equivalent failure when deletion occupancy cannot be acquired, rollback when the session delete count is `0`, and that the command service has no Mem0 dependency.

- [ ] **Step 2: Run the focused test and verify failure**

```powershell
mvn -pl ai-agent-study-app -am -Dtest=ChatSessionCommandServiceTest -Dsurefire.failIfNoSpecifiedTests=false test
```

Expected: FAIL because delete DAO methods and command service do not exist.

- [ ] **Step 3: Add DAO methods and exact SQL**

```xml
<delete id="deleteBySessionId">
    DELETE FROM ai_chat_message WHERE session_id = #{sessionId}
</delete>

<delete id="deleteByUserIdAndSessionId">
    DELETE FROM ai_chat_session
    WHERE user_id = #{userId} AND session_id = #{sessionId}
</delete>
```

- [ ] **Step 4: Implement the transaction and post-commit cleanup**

```java
@Transactional
public void deleteOwnedSession(String userId, String sessionId) {
    if (!operationRegistry.tryAcquireDeletion(userId, sessionId)) {
        throw new IllegalStateException("session is running");
    }
    TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
        @Override
        public void afterCommit() {
            conversationMemoryService.clearRuntimeMemory(sessionId);
            sessionEndDetectionService.removeActivity(userId, sessionId);
        }

        @Override
        public void afterCompletion(int status) {
            operationRegistry.releaseDeletion(userId, sessionId);
        }
    });
    ownershipService.requireOwned(userId, sessionId);
    chatMessageDao.deleteBySessionId(sessionId);
    if (chatSessionDao.deleteByUserIdAndSessionId(userId, sessionId) != 1) {
        throw new IllegalStateException("session not found");
    }
}
```

The transaction synchronization calls only runtime cache/activity cleanup, never Mem0 deletion. `afterCompletion` holds the delete lease through commit or rollback, preventing a new execution from entering before the database outcome is final.

- [ ] **Step 5: Run service and DAO integration tests**

```powershell
mvn -pl ai-agent-study-app -am -Dtest=ChatSessionCommandServiceTest,ChatSessionDaoIntegrationTest -Dsurefire.failIfNoSpecifiedTests=false test
```

Expected: `BUILD SUCCESS`.

- [ ] **Step 6: Commit**

```powershell
git add ai-agent-study-infrastructure/src/main/java/denny/ai/agent/infrastructure/dao ai-agent-study-infrastructure/src/main/java/denny/ai/agent/infrastructure/service/ChatSessionCommandService.java ai-agent-study-app/src/main/resources/mybatis/mapper ai-agent-study-app/src/test/java/denny/ai/agent/test/infrastructure/service/ChatSessionCommandServiceTest.java
git commit -m "feat: delete owned chat sessions transactionally"
```

### Task 4: Secure session HTTP APIs

| Task | status |
|------|------|
| Task 4: Secure session HTTP APIs | append |

**Files:**
- Modify: `ai-agent-study-infrastructure/src/main/java/denny/ai/agent/infrastructure/service/ChatSessionQueryService.java`
- Modify: `ai-agent-study-trigger/src/main/java/denny/ai/agent/trigger/http/ChatSessionController.java`
- Modify: `ai-agent-study-app/src/test/java/denny/ai/agent/test/infrastructure/service/ChatSessionQueryServiceTest.java`
- Modify: `ai-agent-study-app/src/test/java/denny/ai/agent/test/trigger/http/ChatSessionControllerIntegrationTest.java`

- [ ] **Step 1: Write failing HTTP and query tests**

```java
mockMvc.perform(get("/api/v1/session/{id}/messages", "session-1")
        .param("userId", "user-a"))
        .andExpect(jsonPath("$.code").value("200"));

mockMvc.perform(delete("/api/v1/session/{id}", "session-1")
        .param("userId", "user-a"))
        .andExpect(jsonPath("$.code").value("200"));
```

Add cases for invalid IDs, unavailable IDs, absent sessions and another user's delete request.

- [ ] **Step 2: Run tests and verify failure**

```powershell
mvn -pl ai-agent-study-app -am -Dtest=ChatSessionQueryServiceTest,ChatSessionControllerIntegrationTest -Dsurefire.failIfNoSpecifiedTests=false test
```

Expected: FAIL because `userId` is not required on message queries and DELETE is not mapped.

- [ ] **Step 3: Update controller signatures**

```java
@GetMapping("/{sessionId}/messages")
public Response<MessageListResult> getSessionMessages(
        @PathVariable String sessionId,
        @RequestParam String userId,
        @RequestParam(required = false) Integer cursorIndex) { ... }

@DeleteMapping("/{sessionId}")
public Response<Void> deleteSession(
        @PathVariable String sessionId,
        @RequestParam String userId) { ... }
```

Add `RequestMethod.DELETE` to the Controller CORS declaration.

- [ ] **Step 4: Run tests and verify pass**

Run the Step 2 command. Expected: `BUILD SUCCESS`.

- [ ] **Step 5: Commit**

```powershell
git add ai-agent-study-infrastructure/src/main/java/denny/ai/agent/infrastructure/service/ChatSessionQueryService.java ai-agent-study-trigger/src/main/java/denny/ai/agent/trigger/http/ChatSessionController.java ai-agent-study-app/src/test/java/denny/ai/agent/test/infrastructure/service/ChatSessionQueryServiceTest.java ai-agent-study-app/src/test/java/denny/ai/agent/test/trigger/http/ChatSessionControllerIntegrationTest.java
git commit -m "feat: expose secure session management APIs"
```

### Task 5: Guard Agent execution ownership

| Task | status |
|------|------|
| Task 5: Guard Agent execution ownership | append |

**Files:**
- Modify: `ai-agent-study-trigger/src/main/java/denny/ai/agent/trigger/http/AiAgentController.java`
- Modify: `ai-agent-study-trigger/src/main/java/denny/ai/agent/trading/trigger/http/TradingAnalysisController.java`
- Modify: `ai-agent-study-trigger/src/main/java/denny/ai/agent/trading/trigger/http/TradingAnalysisRequestDTO.java`
- Create: `ai-agent-study-trigger/src/test/java/denny/ai/agent/trigger/http/AiAgentControllerSessionGuardTest.java`
- Create: `ai-agent-study-trigger/src/test/java/denny/ai/agent/trading/trigger/http/TradingAnalysisControllerSessionGuardTest.java`

- [ ] **Step 1: Write failing guard tests**

```java
when(ownershipService.resolve("user-a", "session-1"))
        .thenReturn(SessionAccessState.UNAVAILABLE);
controller.autoAgent(request, response);
verifyNoInteractions(autoAgentExecuteStrategy);

when(operationRegistry.tryAcquireExecution("user-a", "session-1"))
        .thenReturn(false);
controller.analyze(tradingRequest, response);
verifyNoInteractions(tradingStarter);
```

- [ ] **Step 2: Run focused tests and verify failure**

```powershell
mvn -pl ai-agent-study-trigger -am -Dtest=AiAgentControllerSessionGuardTest,TradingAnalysisControllerSessionGuardTest -Dsurefire.failIfNoSpecifiedTests=false test
```

Expected: FAIL because controllers do not use the ownership service or operation registry.

- [ ] **Step 3: Add `userId` and shared guard behavior**

```java
private String userId;
```

Both execution paths must resolve ownership and acquire execution before submitting asynchronous work. Every completion, exception, timeout and cancellation path must release in `finally`:

```java
finally {
    operationRegistry.releaseExecution(userId, sessionId);
}
```

- [ ] **Step 4: Run focused tests and verify pass**

Run the Step 2 command. Expected: `BUILD SUCCESS`.

- [ ] **Step 5: Commit**

```powershell
git add ai-agent-study-trigger/src/main/java/denny/ai/agent/trigger/http/AiAgentController.java ai-agent-study-trigger/src/main/java/denny/ai/agent/trading/trigger/http ai-agent-study-trigger/src/test/java/denny/ai/agent
git commit -m "feat: guard session execution ownership"
```

### Task 6: Frontend runtime identity and session settings

| Task | status |
|------|------|
| Task 6: Frontend runtime identity and session settings | append |

**Files:**
- Modify: `docs/dev-ops/nginx/html/js/agent-ui-core.js`
- Modify: `docs/dev-ops/nginx/html/test/agent-ui-core.test.js`
- Modify: `docs/dev-ops/nginx/html/index.html`

- [ ] **Step 1: Write failing core tests**

```javascript
assert.equal(validateRuntimeId('demo-user_01'), true);
assert.equal(validateRuntimeId('<script>'), false);
assert.deepEqual(applyRuntimeIdentity(state, 'user-b', 'session-b'), {
    userId: 'user-b', sessionId: 'session-b'
});
```

- [ ] **Step 2: Run Node tests and verify failure**

```powershell
node --test docs/dev-ops/nginx/html/test/agent-ui-core.test.js
```

Expected: FAIL because the new helpers are not exported.

- [ ] **Step 3: Implement helpers and mutable runtime state**

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
const runtimeState = { userId: runtimeConfig.userId, sessionId: generateSessionId() };
```

Replace the startup constant `currentUserId` and standalone `sessionId` reads with `runtimeState.userId` and `runtimeState.sessionId` in every request builder.

- [ ] **Step 4: Add the settings popover**

Add a settings icon button next to the current IDs, two labeled inputs, Cancel and Apply controls, focus management, Escape close and non-blocking validation Toast. Applying a changed user must cancel the active request, persist `agent.userId`, reset cursors and reload the session list. Applying a changed session must load owned history or show a clean new-session state.

- [ ] **Step 5: Run Node tests and verify pass**

Run the Step 2 command. Expected: all tests pass.

- [ ] **Step 6: Commit**

```powershell
git add docs/dev-ops/nginx/html/js/agent-ui-core.js docs/dev-ops/nginx/html/test/agent-ui-core.test.js docs/dev-ops/nginx/html/index.html
git commit -m "feat: edit frontend user and session ids"
```

### Task 7: Frontend single-session deletion

| Task | status |
|------|------|
| Task 7: Frontend single-session deletion | append |

**Files:**
- Modify: `docs/dev-ops/nginx/html/index.html`
- Modify: `docs/dev-ops/nginx/html/test/agent-ui-security-smoke.html`

- [ ] **Step 1: Add failing browser fixture assertions**

```javascript
assert(deleteUrl.includes('/api/v1/session/session-owned?userId=demo-user'));
assert.equal(deleteRequest.method, 'DELETE');
assert.equal(agentDocument.querySelectorAll('[data-session-id="session-owned"]').length, 0);
```

Cover delete button click isolation, confirmation cancellation, current-session reset, non-current deletion and failed request recovery.

- [ ] **Step 2: Implement accessible delete controls**

Each session list item gets an icon button with `type="button"`, `aria-label="删除会话"` and click propagation stopped. The confirmation dialog must display the session ID and the text “已同步的长期记忆将保留”.

- [ ] **Step 3: Implement the request and state transitions**

```javascript
await fetch(buildApiUrl(`/api/v1/session/${encodeURIComponent(targetSessionId)}?userId=${encodeURIComponent(runtimeState.userId)}`), {
    method: 'DELETE',
    headers: { Accept: 'application/json' }
});
```

Reject deletion of the currently running session. On successful current-session deletion, generate a new session ID and reset panels; on non-current deletion, remove only the target list item.

- [ ] **Step 4: Run browser security smoke verification**

Open `docs/dev-ops/nginx/html/test/agent-ui-security-smoke.html` through the existing static test server and expect every assertion to report `PASS` with no console errors.

- [ ] **Step 5: Commit**

```powershell
git add docs/dev-ops/nginx/html/index.html docs/dev-ops/nginx/html/test/agent-ui-security-smoke.html
git commit -m "feat: delete individual chat sessions"
```

### Task 8: Full regression and acceptance

| Task | status |
|------|------|
| Task 8: Full regression and acceptance | append |

**Files:**
- Modify after verification: `docs/superpowers/test/2026-06-21-frontend-product-polish-test.md`
- Review: all files changed by Tasks 1-7

- [ ] **Step 1: Run deterministic frontend tests**

```powershell
node --test docs/dev-ops/nginx/html/test/agent-ui-core.test.js
```

Expected: all tests pass.

- [ ] **Step 2: Run focused backend tests**

```powershell
mvn -pl ai-agent-study-app,ai-agent-study-trigger -am -Dtest=SessionOwnershipServiceTest,SessionOperationRegistryTest,ChatSessionCommandServiceTest,ChatSessionQueryServiceTest,ChatSessionControllerIntegrationTest,AiAgentControllerSessionGuardTest,TradingAnalysisControllerSessionGuardTest -Dsurefire.failIfNoSpecifiedTests=false test
```

Expected: `BUILD SUCCESS`.

- [ ] **Step 3: Run compile regression**

```powershell
mvn clean compile -DskipTests
```

Expected: `BUILD SUCCESS`.

- [ ] **Step 4: Perform browser acceptance**

Verify desktop `1280x720` and mobile `390x844`: change user, load its session list, select an owned session, specify a new session ID, reject another user's ID, delete non-current session, delete current session, retain Mem0 notice, keyboard focus, Escape close and no overlapping UI.

- [ ] **Step 5: Update statuses from evidence only**

Change a task table from `append` to `pass` only after all steps in that task have been executed successfully. Keep unexecuted manual or external checks as `append`.

- [ ] **Step 6: Commit verification evidence**

```powershell
git add docs/superpowers/test/2026-06-21-frontend-product-polish-test.md docs/superpowers/plans/2026-07-19-frontend-user-session-management-design.md
git commit -m "test: verify frontend session management"
```
