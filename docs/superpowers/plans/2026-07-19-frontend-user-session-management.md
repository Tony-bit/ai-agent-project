# Frontend Login And Session Isolation Implementation Plan

> **For agentic workers:** execute task-by-task. Each task has a `status` table. Keep `append` until the task is implemented and verified, then change only that task to `pass`.

**Goal:** 支持账号密码登录和游客模式，并按当前身份隔离会话；进入后可指定 `sessionId`、删除指定会话，并保留已经同步到 Mem0 的长期记忆。

**Architecture:** 前端增加最小登录界面、游客进入和 token 状态，启动时优先用本地 token 恢复身份，恢复失败再允许创建新游客。所有请求携带 `Authorization: Bearer <token>`。后端增加最小认证服务、游客用户创建、认证过滤器和当前用户上下文。会话列表、历史消息、Agent 执行、股票分析、记忆同步和删除会话都从认证上下文取得当前用户，并复用会话所有权守卫。

**Tech Stack:** Java 17、Spring Boot、Spring MVC、MyBatis、MySQL、Redis、原生 JavaScript、Tailwind CSS、Node.js Test Runner、JUnit、Mockito、Maven。

**Design:** `docs/superpowers/design/2026-07-19-frontend-user-session-management-design.md`

**API Design:** `docs/superpowers/design/2026-07-19-session-management-api-design.md`

**Test Cases:** `docs/superpowers/test/2026-07-19-frontend-user-session-management-test-cases.md`

---

## File Map

- Create minimal authentication domain/service objects for account password login and guest identity creation.
- Create authentication filter and current-user context.
- Create `SessionAccessState` and `SessionOwnershipService` for reusable ownership decisions based on authenticated user.
- Create `SessionOperationRegistry` for atomic `EXECUTING`/`DELETING` leases.
- Create `ChatSessionCommandService` for transactional deletion and post-commit cleanup.
- Extend chat session/message DAOs and MyBatis XML with conditional delete operations.
- Modify session, Agent and Trading controllers to enforce authentication and ownership.
- Modify `agent-ui-core.js` for token/session helpers.
- Modify `index.html` for login, session ID settings and single-session deletion interactions.

### Task 1: Minimal account and guest authentication

| Task | status |
|------|------|
| Task 1: Minimal account and guest authentication | pass |

**Files:**
- Create: `ai-agent-study-domain/src/main/java/denny/ai/agent/domain/auth/AuthUser.java`
- Create: `ai-agent-study-infrastructure/src/main/java/denny/ai/agent/infrastructure/service/AuthService.java`
- Create: `ai-agent-study-infrastructure/src/main/java/denny/ai/agent/infrastructure/dao/IAuthUserDao.java`
- Create/Modify: user table migration or MyBatis mapper for auth users
- Test: `ai-agent-study-app/src/test/java/denny/ai/agent/test/infrastructure/service/AuthServiceTest.java`

- [ ] Write failing tests for successful login, unknown account, wrong password, disabled user, password hash verification and guest creation.
- [ ] Run focused auth service test; expect failure because auth service does not exist.
- [ ] Add SQL-based initial account seed for local development, storing only `password_hash` and active account metadata.
- [ ] Implement minimal user lookup, password hash verification, unified invalid-credentials error and guest user creation.
- [ ] Generate unique backend-owned guest `userId` values with a `guest_` prefix.
- [ ] Allow `account` and `password_hash` to be empty only for `GUEST` users; keep `user_id` required and unique.
- [ ] Ensure no plaintext password is logged or returned.
- [ ] Re-run focused auth service test; expect `BUILD SUCCESS`.
- [ ] Commit with `git commit -m "feat: add password login service"`.

### Task 2: Token authentication boundary

| Task | status |
|------|------|
| Task 2: Token authentication boundary | pass |

**Files:**
- Create: `ai-agent-study-trigger/src/main/java/denny/ai/agent/trigger/http/AuthController.java`
- Create: `ai-agent-study-trigger/src/main/java/denny/ai/agent/trigger/http/AuthenticationFilter.java`
- Create: `ai-agent-study-domain/src/main/java/denny/ai/agent/domain/auth/CurrentUserContext.java`
- Test: `ai-agent-study-app/src/test/java/denny/ai/agent/test/trigger/http/AuthControllerIntegrationTest.java`
- Test: `ai-agent-study-app/src/test/java/denny/ai/agent/test/trigger/http/AuthenticationFilterTest.java`

- [ ] Write failing tests for `POST /api/v1/auth/login`, `POST /api/v1/auth/guest`, `GET /api/v1/auth/me`, missing token, invalid token and disabled user.
- [ ] Run focused auth controller/filter tests; expect failure.
- [ ] Add minimal JWT config binding for `auth.jwt.secret` and `auth.jwt.expires-in-seconds`.
- [ ] Implement login and guest responses with `accessToken`, `tokenType`, `expiresIn` and current user payload.
- [ ] Ensure `/auth/me` restores valid account and guest tokens, and returns `401` for unparseable, expired or missing users.
- [ ] Implement Bearer token filter for protected APIs and skip login/static/health paths.
- [ ] Store authenticated user ID in request context and expose it via `CurrentUserContext`.
- [ ] Re-run focused tests; expect `BUILD SUCCESS`.
- [ ] Commit with `git commit -m "feat: secure APIs with login token"`.

### Task 3: Session ownership contract

| Task | status |
|------|------|
| Task 3: Session ownership contract | pass |

**Files:**
- Create: `ai-agent-study-domain/src/main/java/denny/ai/agent/domain/service/chatsession/SessionAccessState.java`
- Create: `ai-agent-study-infrastructure/src/main/java/denny/ai/agent/infrastructure/service/SessionOwnershipService.java`
- Test: `ai-agent-study-app/src/test/java/denny/ai/agent/test/infrastructure/service/SessionOwnershipServiceTest.java`

- [ ] Write failing tests for `AVAILABLE`, `OWNED`, `UNAVAILABLE`, blank IDs, illegal characters and length greater than 64.
- [ ] Run focused ownership test; expect failure because the types do not exist.
- [ ] Implement ownership resolution using authenticated `currentUserId + sessionId`.
- [ ] Ensure no API path trusts caller-supplied `userId` for ownership.
- [ ] Re-run focused test; expect `BUILD SUCCESS`.
- [ ] Commit with `git commit -m "feat: add authenticated session ownership guard"`.

### Task 4: Atomic session operation registry

| Task | status |
|------|------|
| Task 4: Atomic session operation registry | pass |

**Files:**
- Create: `ai-agent-study-infrastructure/src/main/java/denny/ai/agent/infrastructure/service/SessionOperationRegistry.java`
- Test: `ai-agent-study-infrastructure/src/test/java/denny/ai/agent/infrastructure/service/SessionOperationRegistryTest.java`

- [ ] Write failing tests proving execution blocks deletion, deletion blocks execution, wrong lease types cannot release each other, and release permits the next operation.
- [ ] Run focused registry test; expect failure because the registry does not exist.
- [ ] Implement atomic acquisition and conditional release for execution and deletion leases keyed by `userId + sessionId`.
- [ ] Re-run focused test; expect `BUILD SUCCESS`.
- [ ] Commit with `git commit -m "feat: coordinate session operations"`.

### Task 5: Transactional session deletion

| Task | status |
|------|------|
| Task 5: Transactional session deletion | pass |

**Files:**
- Modify: `ai-agent-study-infrastructure/src/main/java/denny/ai/agent/infrastructure/dao/IChatSessionDao.java`
- Modify: `ai-agent-study-infrastructure/src/main/java/denny/ai/agent/infrastructure/dao/IChatMessageDao.java`
- Modify: `ai-agent-study-app/src/main/resources/mybatis/mapper/chat_session_mapper.xml`
- Modify: `ai-agent-study-app/src/main/resources/mybatis/mapper/chat_message_mapper.xml`
- Create: `ai-agent-study-infrastructure/src/main/java/denny/ai/agent/infrastructure/service/ChatSessionCommandService.java`
- Test: `ai-agent-study-app/src/test/java/denny/ai/agent/test/infrastructure/service/ChatSessionCommandServiceTest.java`

- [ ] Write failing tests for owned deletion, other-user rejection, running-session rejection, delete ordering, rollback and post-commit cleanup without Mem0 calls.
- [ ] Run focused command service test; expect failure.
- [ ] Add DAO operations for deleting messages by `sessionId` and deleting sessions by `userId + sessionId`.
- [ ] Implement one `@Transactional` command that deletes messages before session and requires one affected session row.
- [ ] Clear runtime memory/activity after commit and never call Mem0 deletion.
- [ ] Re-run focused tests; expect `BUILD SUCCESS`.
- [ ] Commit with `git commit -m "feat: delete authenticated user sessions"`.

### Task 6: Secure session HTTP APIs

| Task | status |
|------|------|
| Task 6: Secure session HTTP APIs | pass |

**Files:**
- Modify: `ai-agent-study-infrastructure/src/main/java/denny/ai/agent/infrastructure/service/ChatSessionQueryService.java`
- Modify: `ai-agent-study-trigger/src/main/java/denny/ai/agent/trigger/http/ChatSessionController.java`
- Modify: `ai-agent-study-app/src/test/java/denny/ai/agent/test/infrastructure/service/ChatSessionQueryServiceTest.java`
- Modify: `ai-agent-study-app/src/test/java/denny/ai/agent/test/trigger/http/ChatSessionControllerIntegrationTest.java`

- [ ] Write failing tests proving session list/messages/delete/sync require token and use current authenticated user.
- [ ] Run focused query/controller tests; expect failure because the new contracts are missing.
- [ ] Remove trusted `userId` query handling from protected session APIs.
- [ ] Add `DELETE /api/v1/session/{sessionId}` using `CurrentUserContext`.
- [ ] Add `RequestMethod.DELETE` and allow `Authorization` header in CORS/preflight handling.
- [ ] Re-run focused tests; expect `BUILD SUCCESS`.
- [ ] Commit with `git commit -m "feat: expose authenticated session APIs"`.

### Task 7: Guard Agent and Trading execution

| Task | status |
|------|------|
| Task 7: Guard Agent and Trading execution | pass |

**Files:**
- Modify: `ai-agent-study-trigger/src/main/java/denny/ai/agent/trigger/http/AiAgentController.java`
- Modify: `ai-agent-study-trigger/src/main/java/denny/ai/agent/trading/trigger/http/TradingAnalysisController.java`
- Modify: `ai-agent-study-trigger/src/main/java/denny/ai/agent/trading/trigger/http/TradingAnalysisRequestDTO.java`
- Test: `ai-agent-study-trigger/src/test/java/denny/ai/agent/trigger/http/AiAgentControllerSessionGuardTest.java`
- Test: `ai-agent-study-trigger/src/test/java/denny/ai/agent/trading/trigger/http/TradingAnalysisControllerSessionGuardTest.java`

- [ ] Write failing tests proving unauthenticated requests, foreign sessions and occupied sessions never submit asynchronous work.
- [ ] Run focused trigger tests; expect failure.
- [ ] Resolve current user from `CurrentUserContext` before task submission in all Agent and Trading entry points.
- [ ] Stop treating request-body `userId` as trusted; remove it from DTOs if safe, or ignore it during authorization.
- [ ] Ensure POST SSE endpoints require `Authorization: Bearer <token>` before creating emitters or submitting async work.
- [ ] Return `401` for invalid, expired or missing tokens without starting Agent or Trading execution.
- [ ] Release execution in every completion, exception, timeout and cancellation path.
- [ ] Re-run focused tests; expect `BUILD SUCCESS`.
- [ ] Commit with `git commit -m "feat: guard authenticated session execution"`.

### Task 8: Frontend login, guest and token state

| Task | status |
|------|------|
| Task 8: Frontend login, guest and token state | pass |

**Files:**
- Modify: `docs/dev-ops/nginx/html/js/agent-ui-core.js`
- Modify: `docs/dev-ops/nginx/html/test/agent-ui-core.test.js`
- Modify: `docs/dev-ops/nginx/html/index.html`

- [ ] Write failing Node tests for token storage, auth header generation, auth clearing, guest restore fallback and session ID validation.
- [ ] Run `node --test docs/dev-ops/nginx/html/test/agent-ui-core.test.js`; expect failure because new helpers are absent.
- [ ] Implement token helpers and current-user state helpers.
- [ ] On page start, call `/api/v1/auth/me` when a local token exists; restore identity on success and clear token on failure.
- [ ] Add login view with account/password inputs, guest entry button and login failure handling.
- [ ] Before switching from any existing identity to a newly logged-in account or guest, stop active streams and clear old token, current user, sessions, messages, cursors and transient errors.
- [ ] On login success, store token/current user, load current user if needed, then load session list.
- [ ] On guest entry success, store token/current guest user and load that guest session list.
- [ ] Add `Authorization: Bearer <token>` to all protected requests.
- [ ] Keep streaming requests on `fetch` rather than native `EventSource`, so POST SSE calls can send the `Authorization` header.
- [ ] Handle SSE `401` by stopping the stream, clearing token/current user and returning to the login view.
- [ ] Do not place long-lived access tokens in SSE URL query parameters.
- [ ] Remove editable `userId` settings from the main UI; keep user display read-only.
- [ ] Re-run Node tests; expect all tests pass.
- [ ] Commit with `git commit -m "feat: add frontend login state"`.

### Task 9: Frontend session ID settings and deletion

| Task | status |
|------|------|
| Task 9: Frontend session ID settings and deletion | pass |

**Files:**
- Modify: `docs/dev-ops/nginx/html/index.html`
- Modify: `docs/dev-ops/nginx/html/test/agent-ui-security-smoke.html`

- [ ] Add failing browser assertions for session ID apply, DELETE URL/method, auth header, stopped click propagation, confirmation cancellation, current/non-current state transitions and request failure recovery.
- [ ] Add session ID settings popover with Apply/Cancel, focus restoration, Escape handling and Toast validation.
- [ ] Add accessible delete icon to every session list item and confirmation text stating synchronized long-term memory is retained.
- [ ] Call `DELETE /api/v1/session/{sessionId}` with `Authorization` header and no `userId` query parameter.
- [ ] Reject deletion of the currently running session; reset to a generated session after deleting the current session; otherwise remove only the target item.
- [ ] Re-run browser security smoke checks; expect all assertions pass and no console error.
- [ ] Commit with `git commit -m "feat: manage authenticated chat sessions"`.

### Task 10: Regression and acceptance

| Task | status |
|------|------|
| Task 10: Regression and acceptance | pass |

**Files:**
- Modify after verification: `docs/superpowers/test/2026-06-21-frontend-product-polish-test.md`
- Review: all files changed by Tasks 1-9

- [ ] Run `node --test docs/dev-ops/nginx/html/test/agent-ui-core.test.js`; expect all tests pass.
- [ ] Run focused backend tests for auth, ownership, registry, deletion, session controller and execution guards; expect `BUILD SUCCESS`.
- [ ] Run `mvn clean compile -DskipTests`; expect `BUILD SUCCESS`.
- [ ] Verify desktop `1280x720` and mobile `390x844`: login, guest entry, token restore, logout, owned/new/foreign session selection, current/non-current deletion, retained-Mem0 notice, keyboard focus and no overlapping UI.
- [ ] Update a task status from `append` to `pass` only after every step in that task is executed successfully.
- [ ] Record executed commands, results and remaining external/manual gaps in the test document.
- [ ] Commit with `git commit -m "test: verify authenticated session management"`.
