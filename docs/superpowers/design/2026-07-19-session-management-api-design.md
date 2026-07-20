# 登录与用户级会话隔离 API 设计

## 1. 文档目的

本文定义账号密码登录、游客进入、认证上下文、用户级会话隔离和删除指定会话所需的 HTTP 接口及后端约束。

总体交互和页面设计见：

- `docs/superpowers/design/2026-07-19-frontend-user-session-management-design.md`

本设计遵循现有 Spring MVC、MyBatis 和统一 `Response<T>` 响应结构。调用方不再通过 `userId` 参数声明身份，服务端必须从认证 token 中解析当前用户，并以当前用户作为会话归属校验依据。

## 2. 接口变更总览

| 类型 | 方法 | 路径 | 说明 |
|---|---|---|---|
| 新增 | `POST` | `/api/v1/auth/login` | 账号密码登录，返回访问 token |
| 新增 | `POST` | `/api/v1/auth/guest` | 创建或进入游客身份，返回访问 token |
| 新增 | `GET` | `/api/v1/auth/me` | 获取当前认证用户 |
| 修改 | `GET` | `/api/v1/session/list` | 不再接收可信 `userId`，按当前登录用户查询 |
| 修改 | `GET` | `/api/v1/session/{sessionId}/messages` | 不再接收可信 `userId`，按当前登录用户校验所有权 |
| 新增 | `DELETE` | `/api/v1/session/{sessionId}` | 删除当前登录用户拥有的指定会话 |
| 修改 | `POST` | `/api/v1/agent/auto_agent` | 执行前校验会话是否可由当前登录用户续接 |
| 修改 | `POST` | `/api/v1/agent/inspection` | 与通用 Agent 复用同一会话所有权守卫 |
| 修改 | `POST` | `/api/v1/trading/analysis` | 不再接收可信 `userId`，按当前登录用户执行 |
| 加固 | `POST` | `/api/v1/session/{sessionId}/sync-memory` | 按当前登录用户校验会话所有权 |

## 3. 通用认证规则

除登录和游客接口外，所有受保护接口必须携带：

```http
Authorization: Bearer <accessToken>
```

后端认证过滤器负责：

1. 校验 `Authorization` 请求头存在且为 Bearer 格式。
2. 校验 token 签名和过期时间。
3. 从 token 中解析 `userId`。
4. 回库查询用户并确认用户状态可用。
5. 将当前用户放入请求上下文，供 Controller 和 Service 使用。

接口不得把请求参数或请求体中的 `userId` 当作可信身份来源。为兼容旧调用，后端可以短期忽略请求体中的 `userId`，但不能用它参与权限判定。

## 4. 通用字段规则

### 4.1 account

- 登录接口必填，游客接口不传。
- 长度为 1 到 128 个字符。
- 前后空白应被裁剪。
- 账号不存在和密码错误返回相同错误语义。

### 4.2 password

- 登录接口必填，游客接口不传。
- 服务端不记录明文密码。
- 密码校验使用安全哈希结果比对。

### 4.3 userId

- 服务端生成或从用户表读取，调用方不得在受保护接口中声明可信 `userId`。
- 账号用户使用用户表已有 `user_id`。
- 游客用户由后端随机生成唯一 `user_id`，建议格式为 `guest_` 加随机串。
- `user_id` 必须全局唯一，且不能为空。

### 4.4 sessionId

- 受保护会话接口必填。
- 长度为 1 到 64 个字符。
- 仅允许字母、数字、下划线和连字符。
- 正则：`^[a-zA-Z0-9_-]{1,64}$`。

前端校验用于改善体验，后端必须独立执行相同校验。

### 4.5 初始账号创建方式

本阶段选择 SQL 初始化初始账号，不通过应用配置在启动时自动创建账号。

原因是用户身份最终存储在数据库，SQL 初始化更直观、可审计，也避免应用每次启动时根据配置隐式修改账号数据。最小要求：

- 提供一条或少量初始化账号 SQL。
- SQL 中写入 `user_type = 'ACCOUNT'`、唯一 `user_id`、唯一 `account`、安全哈希后的 `password_hash`、`status = 'active'`。
- 不在配置文件中保存明文默认密码。
- 若本地开发需要默认账号，可以在本地初始化 SQL 中约定账号和初始密码，并明确该密码只用于开发环境。

游客账号不通过初始化 SQL 创建；游客用户只由 `POST /api/v1/auth/guest` 在运行时创建。

### 4.6 JWT 配置项

JWT 配置保持最小化，放在应用配置中：

```yaml
auth:
  jwt:
    secret: ${AI_AGENT_JWT_SECRET:dev-only-change-me}
    expires-in-seconds: 86400
```

约束：

- `secret` 用于签名校验，生产环境必须通过环境变量覆盖默认值。
- `expires-in-seconds` 控制访问 token 过期时间，默认可先使用 24 小时。
- 本阶段不设计 refresh token、token 黑名单、设备管理或强制踢下线。
- 登录、游客进入签发的 token 使用同一套过期时间。

## 5. 统一响应与错误语义

继续使用现有响应结构：

```json
{
  "code": "200",
  "info": "success",
  "data": null
}
```

本次接口使用以下业务码：

| code | info | 含义 |
|---|---|---|
| `200` | `success` | 操作成功 |
| `400` | `invalid request` | 请求体、账号、密码或会话 ID 非法 |
| `401` | `unauthorized` | 未登录、token 非法、token 过期或登录失败 |
| `403` | `user disabled` | 当前用户不可用 |
| `404` | `session not found` | 删除目标不存在或不属于当前用户 |
| `409` | `session id unavailable` | 指定 ID 已属于其他用户，不能读取或续接 |
| `409` | `session is running` | 会话存在正在执行的任务，暂时不能删除 |
| `500` | `operation failed` | 服务端执行失败 |

删除接口对“不存在”和“不属于当前用户”统一返回 `404`，不暴露其他用户的会话信息。历史查询和执行入口对“ID 已由其他用户占用”返回 `409`，不返回会话内容、所属用户或其他元数据。

## 6. 新增接口：登录

### 6.1 请求

```http
POST /api/v1/auth/login
Content-Type: application/json
Accept: application/json
```

```json
{
  "account": "demo-user",
  "password": "******"
}
```

### 6.2 成功响应

```json
{
  "code": "200",
  "info": "success",
  "data": {
    "accessToken": "jwt-token",
    "tokenType": "Bearer",
    "expiresIn": 86400,
    "user": {
      "userId": "demo-user",
      "account": "demo-user"
    }
  }
}
```

### 6.3 失败语义

账号不存在、密码错误、用户禁用不返回可区分的账号存在性信息。前端展示统一登录失败提示。若需要区分用户禁用，可在服务端日志中记录，不在普通登录响应中泄露。

## 7. 新增接口：游客进入

### 7.1 请求

```http
POST /api/v1/auth/guest
Accept: application/json
```

请求体为空。

### 7.2 成功响应

```json
{
  "code": "200",
  "info": "success",
  "data": {
    "accessToken": "jwt-token",
    "tokenType": "Bearer",
    "expiresIn": 86400,
    "user": {
      "userId": "guest_8f3a9c2e7b4d",
      "userType": "GUEST",
      "account": null
    }
  }
}
```

后端每次调用游客接口都创建一个新的游客用户并生成新的随机 `userId`。如果前端本地已有 token，应先调用 `/api/v1/auth/me` 尝试恢复；只有恢复失败或用户主动选择重新游客进入时，才调用本接口。

## 8. 新增接口：当前用户

### 8.1 请求

```http
GET /api/v1/auth/me
Authorization: Bearer <accessToken>
Accept: application/json
```

### 8.2 成功响应

```json
{
  "code": "200",
  "info": "success",
  "data": {
    "userId": "demo-user",
    "userType": "ACCOUNT",
    "account": "demo-user"
  }
}
```

前端刷新页面时可通过该接口恢复当前用户展示，并加载该用户的会话列表。若 token 对应游客用户且仍有效，返回原游客 `userId`；若 token 解析失败、过期或用户不存在，返回 `401`，前端清理旧 token 并展示登录界面。

## 9. 修改接口：会话列表

### 9.1 请求

```http
GET /api/v1/session/list?cursorTime={cursorTime}&pageSize={pageSize}
Authorization: Bearer <accessToken>
```

`userId` 不再作为可信参数传递。后端按当前登录用户查询会话列表。

### 9.2 判定规则

- 未认证：返回 `401`。
- 已认证：只返回当前登录用户拥有的会话。
- 查询结果不得混入其他用户会话。

## 10. 修改接口：查询会话消息

### 10.1 请求

```http
GET /api/v1/session/{sessionId}/messages?cursorIndex={cursorIndex}
Authorization: Bearer <accessToken>
```

### 10.2 判定规则

| 会话状态 | 响应 | 前端行为 |
|---|---|---|
| 属于当前登录用户 | `200 + MessageListResult` | 展示历史消息 |
| 数据库中不存在 | `200 + 空 MessageListResult` | 将该 ID 作为新会话使用 |
| 属于其他用户 | `409 session id unavailable` | 拒绝切换并保留原页面状态 |
| 参数非法 | `400` | 展示校验提示 |
| 未认证 | `401` | 回到登录界面 |

不存在的会话返回空结果，是为了支持用户手工指定尚未创建的合法会话 ID。空结果只表示当前用户可以使用该 ID，不代表已经创建数据库记录。

## 11. 新增接口：删除指定会话

### 11.1 请求

```http
DELETE /api/v1/session/{sessionId}
Authorization: Bearer <accessToken>
Accept: application/json
```

### 11.2 成功响应

```json
{
  "code": "200",
  "info": "success",
  "data": null
}
```

### 11.3 处理流程

1. 认证过滤器解析当前登录用户。
2. 校验 `sessionId`。
3. 检查该用户下该会话是否存在正在执行的任务；若存在，返回 `409 session is running`。
4. 按当前登录用户 ID 和 `sessionId` 查询会话；未找到时返回 `404 session not found`。
5. 在同一数据库事务内先删除 `ai_chat_message`，再删除 `ai_chat_session`。
6. 通过会话删除影响行数确认所有权未在操作过程中变化。
7. 事务提交后清理 Redis 会话窗口、本地运行时窗口和滑动窗口活动记录。
8. 返回成功。

删除操作不调用 Mem0 删除接口，已同步的长期记忆继续保留。

### 11.4 幂等语义

首次删除成功返回 `200`。对同一会话再次删除返回 `404`。前端收到首次成功后立即移除列表项，不自动重试已经成功的删除请求。

## 12. 修改接口：通用 Agent 与巡检 Agent

接口路径保持：

```http
POST /api/v1/agent/auto_agent
POST /api/v1/agent/inspection
Authorization: Bearer <accessToken>
Accept: text/event-stream
```

请求体继续包含 `sessionId` 和业务字段，不再要求可信 `userId`：

```json
{
  "sessionId": "session_1784457600000_abcd1234",
  "message": "解释一下向量数据库",
  "maxStep": 5
}
```

在创建异步任务和返回 SSE emitter 前执行会话所有权守卫：

| 会话状态 | 行为 |
|---|---|
| 数据库中不存在 | 允许执行，首次持久化时归属当前登录用户 |
| 属于当前登录用户 | 允许续接 |
| 属于其他用户 | 不启动异步任务，返回 `session id unavailable` 错误事件 |
| 参数非法 | 不启动异步任务，返回参数错误事件 |
| 未认证 | 不启动异步任务，返回认证失败 |

`auto_agent` 和 `inspection` 必须复用同一个守卫，不能在两个 Controller 方法中复制判定逻辑。

### 12.1 SSE 认证规则

通用 Agent 和巡检 Agent 的流式响应仍使用 `POST + text/event-stream`。浏览器前端必须使用 `fetch` 发起请求并读取响应流，以便在请求头中携带 `Authorization: Bearer <accessToken>`。

本阶段不使用浏览器原生 `EventSource` 连接这些接口，因为原生 `EventSource` 不能设置自定义 `Authorization` 请求头。如果后续新增 `GET` 型 SSE 接口，需要单独设计短期一次性 stream token 或 Cookie 认证，不得直接把长期访问 token 放到 URL query 参数中。

认证失败时，后端应在创建 `ResponseBodyEmitter` 和提交异步任务前返回 `401`。如果技术上需要保持 SSE 响应格式，也只能发送认证失败事件后立即关闭连接，但不得启动 Agent 执行任务。

## 13. 修改接口：股票分析

接口路径保持：

```http
POST /api/v1/trading/analysis
Authorization: Bearer <accessToken>
Accept: text/event-stream
```

请求示例：

```json
{
  "sessionId": "session_1784457600000_abcd1234",
  "ticker": "600519",
  "tradeDate": "2026-07-19",
  "selectedAnalysts": ["FUNDAMENTAL", "TECHNICAL"],
  "maxDebateRounds": 2,
  "maxRiskRounds": 1
}
```

股票分析在创建 `TradingSseSession` 和调度异步任务前执行与通用 Agent 相同的所有权守卫。后端使用当前登录用户作为会话归属，不使用请求体中的 `userId`。

### 13.1 SSE 认证规则

股票分析接口同样使用 `POST + text/event-stream`，前端通过 `fetch` 携带 `Authorization` 请求头并读取响应流。认证过滤器必须覆盖该路径；认证失败、token 过期、用户禁用或会话归属不合法时，不创建 `TradingSseSession`，不启动异步分析任务。

SSE 事件内容中可以包含 `sessionId` 便于前端归并展示，但不得包含访问 token、密码、账号校验细节或其他认证敏感信息。

## 14. 加固接口：同步长期记忆

接口调整为：

```http
POST /api/v1/session/{sessionId}/sync-memory
Authorization: Bearer <accessToken>
```

同步前必须校验当前登录用户拥有该 `sessionId`。会话不存在或不属于当前用户时返回统一失败，不能仅依赖“查询不到待同步记录”来隐式成功。

删除会话接口不调用本接口，也不反向删除已经同步到 Mem0 的长期记忆。

## 15. 后端组件设计

### 15.1 AuthService

提供账号密码登录能力：

```java
LoginResult login(String account, String password);
LoginResult createGuest();
CurrentUser getCurrentUser();
```

职责包括账号查询、密码哈希校验、游客用户创建、随机游客 `userId` 生成、用户状态检查、token 签发和统一错误语义。

### 15.2 AuthenticationFilter

拦截受保护接口，校验 Bearer token，回库确认用户状态，并将当前用户写入请求上下文。登录接口、静态资源和必要的健康检查不拦截。

### 15.3 CurrentUserContext

为 Controller 和 Service 提供当前登录用户：

```java
String currentUserId();
```

如果未认证上下文中调用，应抛出认证异常。

### 15.4 SessionOwnershipService

提供统一判定，不直接返回其他用户的会话实体：

```java
public enum SessionAccessState {
    AVAILABLE,
    OWNED,
    UNAVAILABLE
}

SessionAccessState resolve(String currentUserId, String sessionId);
```

查询历史、通用 Agent、巡检 Agent 和股票分析复用该服务。

### 15.5 ChatSessionCommandService

负责删除用例和事务边界：

```java
void deleteOwnedSession(String currentUserId, String sessionId);
```

职责包括参数校验、执行占用检查、所有权确认、数据库事务删除和提交后的缓存清理。DAO 增加：

```java
int deleteBySessionId(String sessionId);
int deleteByUserIdAndSessionId(String userId, String sessionId);
```

消息 DAO 先执行第一个方法，会话 DAO 后执行第二个方法。

### 15.6 SessionOperationRegistry

新增轻量会话操作注册器，将每个会话标记为 `EXECUTING` 或 `DELETING`：

```java
boolean tryAcquireExecution(String userId, String sessionId);
void releaseExecution(String userId, String sessionId);
boolean tryAcquireDeletion(String userId, String sessionId);
void releaseDeletion(String userId, String sessionId);
boolean isRunning(String userId, String sessionId);
```

通用 Agent、巡检 Agent 和股票分析在调度任务前调用 `tryAcquireExecution`，在任务完成、异常或取消的 `finally` 路径释放。删除服务在事务开始前调用 `tryAcquireDeletion`，只有获得删除占用后才能继续，并在删除成功或失败的 `finally` 路径释放。

## 16. 数据一致性与并发

### 16.1 数据库事务

消息删除和会话删除必须处于同一 `@Transactional` 方法中。任一步骤失败都回滚，不能出现会话存在但消息部分删除，或会话删除但消息残留。

### 16.2 执行与删除竞争

执行入口先获得执行占用；删除入口无法获得删除占用时返回 `409`。删除占用释放前，不允许新执行获得同一 `userId + sessionId` 的占用。状态获取必须是原子的，不能用相互分离的“先检查、再写入”实现。

### 16.3 首次创建竞争

数据库已对 `session_id` 建立唯一约束。两个用户同时使用相同的新会话 ID 时，只允许一个创建成功；另一方捕获唯一键冲突后重新执行所有权判定并返回 `409 session id unavailable`，不得向已创建会话追加消息。

### 16.4 缓存清理

数据库提交成功后调用现有运行时缓存清理，并清理活动跟踪。缓存清理失败只记录告警，不回滚已经提交的数据库删除，也不调用 Mem0。

## 17. Controller 与 CORS

`ChatSessionController` 的 CORS 方法列表增加：

```java
RequestMethod.DELETE
```

认证请求使用 `Authorization` 头。如果部署层 Nginx 显式限制请求头或 HTTP 方法，需要允许 `Authorization`、`DELETE` 和对应的 `OPTIONS` 预检请求。

## 18. 测试范围

### 18.1 Controller 测试

- 登录成功返回 token。
- 游客进入返回随机游客 `userId` 和 token。
- 有效 token 调用 `/auth/me` 可恢复账号用户或游客用户。
- token 解析失败时 `/auth/me` 返回 `401`。
- 登录失败不暴露账号存在性。
- 未认证访问受保护接口返回 `401`。
- 删除自己的会话返回 `200`。
- 删除不存在或其他用户的会话返回相同 `404`。
- 删除执行中的会话返回 `409`。
- 消息查询不能返回其他用户的数据。
- SSE 入口在认证或所有权校验失败时不提交异步任务。

### 18.2 Service 测试

- `AuthService` 正确处理成功登录、密码错误、用户禁用。
- `AuthService` 创建游客用户时生成唯一 `guest_` 前缀 `userId`，且 `account/password_hash` 为空。
- `AuthenticationFilter` 正确解析 token 并注入当前用户。
- `SessionOwnershipService` 正确返回三种状态。
- 删除顺序为消息后会话。
- 会话删除影响行数为零时事务回滚。
- 消息删除失败或会话删除失败时全部回滚。
- 删除成功后清理运行时缓存和活动记录。
- 删除过程不调用 Mem0。
- 执行占用与删除互斥，异常路径能够释放占用。

### 18.3 DAO 测试

- 用户账号唯一。
- 游客用户允许 `account` 和 `password_hash` 为空，但 `user_id` 必须唯一。
- `deleteBySessionId` 只删除目标会话消息。
- `deleteByUserIdAndSessionId` 同时匹配两个字段。
- 其他用户使用相同删除请求不会影响数据。

### 18.4 前端契约测试

- 登录后保存 token 并携带 `Authorization` 头。
- 页面启动时优先用本地 token 调 `/auth/me` 恢复身份。
- `/auth/me` 失败后清理旧 token，并允许调用 `/auth/guest` 创建新游客。
- 会话列表、历史消息、同步和删除接口不再拼接 `userId`。
- 通用 Agent 和股票分析均使用当前 token 与当前 `sessionId`。
- 删除成功、`404`、`409`、`401` 和网络失败均恢复正确页面状态。
- 删除确认文案明确说明 Mem0 长期记忆将保留。

## 19. 验收标准

1. 用户可以通过账号密码登录或游客模式进入聊天界面。
2. 游客 `userId` 由后端随机生成并入库，`account` 和 `password_hash` 允许为空。
3. 页面启动时若本地 token 有效，可恢复原账号用户或游客用户；解析失败后重新进入游客模式会生成新 `userId`。
4. 服务端从 token 解析当前用户，所有受保护接口不信任前端传入的 `userId`。
3. 所有已有会话的读取、续接、同步和删除都校验当前登录用户所有权。
4. 不存在的合法会话 ID 可以由当前登录用户首次创建。
5. 其他用户的会话内容不会被读取、追加、同步或删除。
6. 执行中的会话不能删除，异步任务完成后可正常删除。
7. 会话和消息删除具备数据库原子性。
8. 删除会话后 Redis、本地运行时缓存和活动记录被清理。
9. 删除会话不会删除或修改 Mem0 长期记忆。
