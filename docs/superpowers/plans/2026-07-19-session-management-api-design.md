# 用户与会话管理 API 设计

## 1. 文档目的

本文定义前端支持修改 `userId`、指定 `sessionId` 和删除指定会话所需的 HTTP 接口及后端约束。

总体交互和页面设计见：

- `docs/superpowers/plans/2026-07-19-frontend-user-session-management-design.md`

本设计遵循现有 Spring MVC、MyBatis 和统一 `Response<T>` 响应结构，不引入独立鉴权系统。`userId` 仍由调用方传入，但所有会话读取、续接和删除操作必须校验 `userId + sessionId` 的所有权关系。

## 2. 接口变更总览

| 类型 | 方法 | 路径 | 说明 |
|---|---|---|---|
| 新增 | `DELETE` | `/api/v1/session/{sessionId}` | 删除当前用户拥有的指定会话 |
| 修改 | `GET` | `/api/v1/session/{sessionId}/messages` | 增加 `userId` 并校验会话所有权 |
| 修改 | `POST` | `/api/v1/agent/auto_agent` | 执行前校验会话是否可由当前用户续接 |
| 修改 | `POST` | `/api/v1/agent/inspection` | 与通用 Agent 复用同一会话所有权守卫 |
| 修改 | `POST` | `/api/v1/trading/analysis` | 请求增加 `userId` 并校验会话所有权 |
| 加固 | `POST` | `/api/v1/session/{sessionId}/sync-memory` | 明确复用所有权校验，不新增参数 |

前端修改用户 ID 和会话 ID 本身不需要保存接口。页面更新运行时状态后，调用会话列表、历史消息或业务执行接口即可。

## 3. 通用字段规则

### 3.1 userId

- 必填。
- 长度为 1 到 64 个字符。
- 仅允许字母、数字、下划线和连字符。
- 正则：`^[a-zA-Z0-9_-]{1,64}$`。

### 3.2 sessionId

- 必填。
- 长度为 1 到 64 个字符。
- 仅允许字母、数字、下划线和连字符。
- 正则：`^[a-zA-Z0-9_-]{1,64}$`。

前端校验用于改善体验，后端必须独立执行相同校验。

## 4. 统一响应与错误语义

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
| `400` | `invalid userId or sessionId` | 参数为空、超长或格式非法 |
| `404` | `session not found` | 删除目标不存在或不属于当前用户 |
| `409` | `session id unavailable` | 指定 ID 已属于其他用户，不能读取或续接 |
| `409` | `session is running` | 会话存在正在执行的任务，暂时不能删除 |
| `500` | `session operation failed` | 服务端执行失败 |

删除接口对“不存在”和“不属于当前用户”统一返回 `404`，不暴露其他用户的会话信息。

手工指定会话时必须判断该 ID 能否由当前用户使用，因此历史查询和执行入口对“ID 已由其他用户占用”返回 `409`。该响应只说明 ID 不可用，不返回会话内容、所属用户或其他元数据。

## 5. 新增接口：删除指定会话

### 5.1 请求

```http
DELETE /api/v1/session/{sessionId}?userId={userId}
Accept: application/json
```

示例：

```http
DELETE /api/v1/session/session_1784457600000_abcd1234?userId=demo-user
```

### 5.2 成功响应

```json
{
  "code": "200",
  "info": "success",
  "data": null
}
```

### 5.3 处理流程

1. 校验 `userId` 和 `sessionId`。
2. 检查该会话是否存在正在执行的任务；若存在，返回 `409 session is running`。
3. 按 `userId + sessionId` 查询会话；未找到时返回 `404 session not found`。
4. 在同一数据库事务内先删除 `ai_chat_message`，再删除 `ai_chat_session`。
5. 通过会话删除影响行数确认所有权未在操作过程中变化。
6. 事务提交后清理 Redis 会话窗口、本地运行时窗口和滑动窗口活动记录。
7. 返回成功。

删除操作不调用 Mem0 删除接口，已同步的长期记忆继续保留。

### 5.4 幂等语义

首次删除成功返回 `200`。对同一会话再次删除返回 `404`。前端收到首次成功后立即移除列表项，不自动重试已经成功的删除请求。

## 6. 修改接口：查询会话消息

### 6.1 请求

原接口：

```http
GET /api/v1/session/{sessionId}/messages?cursorIndex={cursorIndex}
```

修改后：

```http
GET /api/v1/session/{sessionId}/messages?userId={userId}&cursorIndex={cursorIndex}
```

`cursorIndex` 保持可选，`userId` 调整为必填。

### 6.2 判定规则

| 会话状态 | 响应 | 前端行为 |
|---|---|---|
| 属于当前用户 | `200 + MessageListResult` | 展示历史消息 |
| 数据库中不存在 | `200 + 空 MessageListResult` | 将该 ID 作为新会话使用 |
| 属于其他用户 | `409 session id unavailable` | 拒绝切换并保留原页面状态 |
| 参数非法 | `400` | 展示校验提示 |

不存在的会话返回空结果，是为了支持用户手工指定尚未创建的合法会话 ID。空结果只表示当前用户可以使用该 ID，不代表已经创建数据库记录。

### 6.3 兼容性

前端页面的所有消息查询必须补充 `userId`。缺少 `userId` 的旧调用返回 `400`，不再允许无所有权上下文地读取消息。

## 7. 修改接口：通用 Agent 与巡检 Agent

接口路径和请求 DTO 字段不变：

```http
POST /api/v1/agent/auto_agent
POST /api/v1/agent/inspection
```

请求体继续包含：

```json
{
  "userId": "demo-user",
  "sessionId": "session_1784457600000_abcd1234",
  "message": "解释一下向量数据库",
  "maxStep": 5
}
```

在创建异步任务和返回 SSE emitter 前执行会话所有权守卫：

| 会话状态 | 行为 |
|---|---|
| 数据库中不存在 | 允许执行，首次持久化时归属当前用户 |
| 属于当前用户 | 允许续接 |
| 属于其他用户 | 不启动异步任务，返回 `session id unavailable` 错误事件 |
| 参数非法 | 不启动异步任务，返回参数错误事件 |

`auto_agent` 和 `inspection` 必须复用同一个守卫，不能在两个 Controller 方法中复制判定逻辑。

## 8. 修改接口：股票分析

接口路径不变：

```http
POST /api/v1/trading/analysis
```

`TradingAnalysisRequestDTO` 新增必填字段：

```java
private String userId;
```

修改后的请求示例：

```json
{
  "userId": "demo-user",
  "sessionId": "session_1784457600000_abcd1234",
  "ticker": "600519",
  "tradeDate": "2026-07-19",
  "selectedAnalysts": ["FUNDAMENTAL", "TECHNICAL"],
  "maxDebateRounds": 2,
  "maxRiskRounds": 1
}
```

股票分析在创建 `TradingSseSession` 和调度异步任务前执行与通用 Agent 相同的所有权守卫。前端从可变运行时配置读取当前 `userId`，不能继续只发送 `sessionId`。

## 9. 加固接口：同步长期记忆

接口保持不变：

```http
POST /api/v1/session/{sessionId}/sync-memory?userId={userId}
```

同步前必须明确校验 `userId + sessionId` 所有权。会话不存在或不属于当前用户时返回统一失败，不能仅依赖“查询不到待同步记录”来隐式成功。

删除会话接口不调用本接口，也不反向删除已经同步到 Mem0 的长期记忆。

## 10. 后端组件设计

### 10.1 SessionOwnershipService

提供统一判定，不直接返回其他用户的会话实体：

```java
public enum SessionAccessState {
    AVAILABLE,
    OWNED,
    UNAVAILABLE
}

SessionAccessState resolve(String userId, String sessionId);
```

- `AVAILABLE`：会话不存在，当前用户可以创建。
- `OWNED`：会话属于当前用户。
- `UNAVAILABLE`：会话存在但属于其他用户。

查询历史、通用 Agent、巡检 Agent 和股票分析复用该服务。

### 10.2 SessionDeletionService

负责删除用例和事务边界：

```java
void deleteOwnedSession(String userId, String sessionId);
```

职责包括参数校验、执行占用检查、所有权确认、数据库事务删除和提交后的缓存清理。DAO 增加：

```java
int deleteBySessionId(String sessionId);
int deleteByUserIdAndSessionId(String userId, String sessionId);
```

消息 DAO 先执行第一个方法，会话 DAO 后执行第二个方法。

### 10.3 SessionOperationRegistry

现有 `SessionActivityTracker` 表示会话在滑动时间窗口内有过活动，不能准确表示异步任务仍在执行。新增轻量会话操作注册器，将每个会话标记为 `EXECUTING` 或 `DELETING`：

```java
boolean tryAcquireExecution(String userId, String sessionId);
void releaseExecution(String userId, String sessionId);
boolean tryAcquireDeletion(String userId, String sessionId);
void releaseDeletion(String userId, String sessionId);
boolean isRunning(String userId, String sessionId);
```

通用 Agent、巡检 Agent 和股票分析在调度任务前调用 `tryAcquireExecution`，在任务完成、异常或取消的 `finally` 路径释放。删除服务在事务开始前调用 `tryAcquireDeletion`，只有获得删除占用后才能继续，并在删除成功或失败的 `finally` 路径释放。执行占用与删除占用互斥。

单实例部署使用进程内实现即可；如果同一服务存在多个实例，则必须使用 Redis 原子键或分布式锁实现，不能依赖本地 Map。

## 11. 数据一致性与并发

### 11.1 数据库事务

消息删除和会话删除必须处于同一 `@Transactional` 方法中。任一步骤失败都回滚，不能出现会话存在但消息部分删除，或会话删除但消息残留。

### 11.2 执行与删除竞争

执行入口先获得执行占用；删除入口无法获得删除占用时返回 `409`。删除占用释放前，不允许新执行获得同一 `userId + sessionId` 的占用。状态获取必须是原子的，不能用相互分离的“先检查、再写入”实现，否则仍可能发生检查后竞争。

### 11.3 首次创建竞争

数据库已对 `session_id` 建立唯一约束。两个用户同时使用相同的新会话 ID 时，只允许一个创建成功；另一方捕获唯一键冲突后重新执行所有权判定并返回 `409 session id unavailable`，不得向已创建会话追加消息。

### 11.4 缓存清理

数据库提交成功后调用现有 `ConversationMemoryService.clearRuntimeMemory(sessionId)`，并清理活动跟踪。缓存清理失败只记录告警，不回滚已经提交的数据库删除，也不调用 Mem0。

## 12. Controller 与 CORS

`ChatSessionController` 的 `@CrossOrigin` 方法列表增加：

```java
RequestMethod.DELETE
```

如果部署层 Nginx 显式限制 HTTP 方法，也需要允许 `DELETE` 和对应的 `OPTIONS` 预检请求。

## 13. 测试范围

### 13.1 Controller 测试

- 删除自己的会话返回 `200`。
- 删除不存在或其他用户的会话返回相同 `404`。
- 删除执行中的会话返回 `409`。
- 消息查询缺少 `userId` 返回 `400`。
- 消息查询不能返回其他用户的数据。
- SSE 入口在所有权校验失败时不提交异步任务。

### 13.2 Service 测试

- `SessionOwnershipService` 正确返回三种状态。
- 删除顺序为消息后会话。
- 会话删除影响行数为零时事务回滚。
- 消息删除失败或会话删除失败时全部回滚。
- 删除成功后清理运行时缓存和活动记录。
- 删除过程不调用 Mem0。
- 执行占用与删除互斥，异常路径能够释放占用。

### 13.3 DAO 测试

- `deleteBySessionId` 只删除目标会话消息。
- `deleteByUserIdAndSessionId` 同时匹配两个字段。
- 其他用户使用相同删除请求不会影响数据。

### 13.4 前端契约测试

- 所有消息查询携带当前 `userId`。
- 通用 Agent 和股票分析均使用修改后的当前用户与会话 ID。
- 删除成功、`404`、`409` 和网络失败均恢复正确页面状态。
- 删除确认文案明确说明 Mem0 长期记忆将保留。

## 14. 验收标准

1. 仅新增一个 HTTP 路径：删除指定会话。
2. 所有已有会话的读取、续接、同步和删除都校验所有权。
3. 不存在的合法会话 ID 可以由当前用户首次创建。
4. 其他用户的会话内容不会被读取、追加或删除。
5. 执行中的会话不能删除，异步任务完成后可正常删除。
6. 会话和消息删除具备数据库原子性。
7. 删除会话后 Redis、本地运行时缓存和活动记录被清理。
8. 删除会话不会删除或修改 Mem0 长期记忆。
