# 账号自助注册实现需求

## 目标

在现有账号密码登录和游客登录能力上增加账号自助注册。用户使用账号和密码注册，成功后由服务端直接签发 JWT，前端复用现有认证状态切换逻辑进入聊天界面。

关联设计：`docs/superpowers/plans/2026-07-21-account-registration-design.md`

关联测试：`docs/superpowers/test/2026-07-21-account-registration-test.md`

## 范围

本次实现包括：

- `POST /api/v1/auth/register` 注册接口。
- 账号格式、密码长度、重复账号校验。
- BCrypt 密码哈希和账号用户入库。
- 注册成功后签发与登录相同结构的 JWT。
- 登录卡片内的“登录 / 注册”模式切换。
- 注册确认密码、错误提示和重复提交防护。
- 服务层、控制器、认证过滤器和前端共享逻辑测试。

本次不包括：

- 邮箱、手机号和验证码。
- 密码找回、密码修改和账号注销。
- 游客升级为正式账号。
- 注册审批、第三方登录和用户资料。
- 新的数据库表或字段迁移。
- 新增应用级限流基础设施。

## 现有能力约束

- 用户数据继续使用 `ai_auth_user`。
- `account` 和 `user_id` 已有唯一索引。
- `ACCOUNT` 用户必须具有非空 `account` 和 `password_hash`。
- 密码继续使用 `BCryptPasswordEncoder`。
- JWT 继续由 `JwtTokenService` 签发。
- 前端继续由 `createAuthState` 保存 token，并由 `acceptAuthentication` 完成身份切换。
- 现有登录账号允许最长 128 个字符；新的自助注册规则不得破坏预置账号登录。

## 接口需求

### 请求

```http
POST /api/v1/auth/register
Content-Type: application/json
```

```json
{
  "account": "new_user",
  "password": "secure-password"
}
```

前端确认密码不得进入请求体。

### 成功响应

返回 HTTP 200，响应结构与 `/api/v1/auth/login` 一致：

```json
{
  "code": "200",
  "info": "success",
  "data": {
    "accessToken": "jwt-token",
    "tokenType": "Bearer",
    "expiresIn": 3600,
    "user": {
      "userId": "user_generated-id",
      "userType": "ACCOUNT",
      "account": "new_user"
    }
  }
}
```

响应中不得包含 `password` 或 `passwordHash`。

### 失败响应

| 场景 | HTTP | code | info |
|---|---:|---|---|
| 账号或密码规则不合法 | 400 | `400` | `invalid registration` |
| 账号已经存在 | 409 | `409` | `account already exists` |
| 非预期服务异常 | 500 | `500` | `operation failed` |

数据库异常信息、SQL、密码和密码哈希不得进入响应。

## 输入规则

### 账号

- 服务端去除首尾空白。
- 长度 3-32。
- 仅允许 ASCII 字母、数字、下划线和连字符。
- 规则：`^[A-Za-z0-9_-]{3,32}$`。
- 唯一性以数据库唯一索引为最终边界。

### 密码

- 长度 8-72。
- 不去除首尾空白。
- 服务端使用 BCrypt 编码后入库。

### 确认密码

- 仅由前端使用。
- 必须与密码完全一致。
- 不得保存到本地存储或发送到服务端。

## 后端实现任务

### 任务 1：注册服务

修改：

- `ai-agent-study-infrastructure/src/main/java/denny/ai/agent/infrastructure/service/AuthService.java`
- `ai-agent-study-app/src/test/java/denny/ai/agent/test/infrastructure/service/AuthServiceTest.java`

要求：

1. 增加 `register(account, password)`。
2. 在写库前完成账号规范化和输入校验。
3. 查询已有账号，为普通重复注册提供明确结果。
4. 生成 `user_` 加无连字符 UUID 的用户 ID。
5. 创建 `ACCOUNT`、`ACTIVE` 用户。
6. 使用 BCrypt 编码密码。
7. 捕获 `DuplicateKeyException`，处理并发重复注册。
8. 增加 `INVALID_REGISTRATION` 和 `ACCOUNT_ALREADY_EXISTS` 失败原因。
9. 返回领域用户时不暴露密码哈希。

### 任务 2：HTTP 注册入口

修改：

- `ai-agent-study-trigger/src/main/java/denny/ai/agent/trigger/http/AuthController.java`
- `ai-agent-study-app/src/test/java/denny/ai/agent/test/trigger/http/AuthControllerIntegrationTest.java`

要求：

1. 增加 `RegisterRequest`。
2. 增加 `POST /api/v1/auth/register`。
3. 成功时复用现有 token 和用户响应构造。
4. 按失败原因映射 400、409 和 500。
5. 未知异常只返回通用 500 响应。

### 任务 3：认证过滤器公开路径

修改：

- `ai-agent-study-trigger/src/main/java/denny/ai/agent/trigger/http/AuthenticationFilter.java`
- `ai-agent-study-app/src/test/java/denny/ai/agent/test/trigger/http/AuthenticationFilterTest.java`

要求：

1. `/api/v1/auth/register` 不要求 Bearer token。
2. `/api/v1/auth/me` 和其他现有受保护接口继续要求 token。
3. 登录、游客、静态资源和 OPTIONS 行为不变。

## 前端实现任务

### 任务 4：共享注册校验

修改：

- `docs/dev-ops/nginx/html/js/agent-ui-core.js`
- `docs/dev-ops/nginx/html/test/agent-ui-core.test.js`

要求：

1. 增加可独立测试的 `validateRegistration`。
2. 返回规范化账号和原始密码。
3. 校验失败时返回字段标识及用户可读提示。
4. 覆盖合法输入、非法账号、密码长度和确认密码不一致。

### 任务 5：注册交互

修改：

- `docs/dev-ops/nginx/html/index.html`

要求：

1. 登录卡片增加“登录 / 注册”分段切换。
2. 注册模式显示确认密码并使用 `new-password` 自动完成语义。
3. 切换模式时清理密码、确认密码和错误提示。
4. 注册期间禁用提交、游客入口和模式切换。
5. 客户端校验失败时不发送请求。
6. 请求体只包含 `account` 和 `password`。
7. HTTP 409 显示“账号已存在”。
8. HTTP 400 显示注册规则错误。
9. 其他失败显示通用重试提示。
10. 注册成功调用现有认证接受流程并进入聊天界面。
11. 退出登录或 token 失效时恢复默认登录模式。

## 实现顺序

1. 先补服务层失败测试，再实现注册服务。
2. 补控制器和过滤器失败测试，再实现 HTTP 入口。
3. 运行后端聚焦测试。
4. 补前端共享校验测试，再实现校验函数。
5. 实现登录卡片注册交互。
6. 运行 Node 测试和内联脚本语法检查。
7. 执行真实浏览器桌面与移动验证。
8. 执行回归测试并更新测试文档执行记录。

## 完成门槛

- 后端聚焦测试全部通过。
- 前端 Node 测试全部通过。
- HTML 内联脚本无语法错误。
- 注册请求不包含确认密码。
- BCrypt 入库和重复账号竞争均有自动化测试。
- 桌面 `1280x720` 与移动 `390x844` 无横向溢出、卡片越界或控件重叠。
- 登录、游客进入、token 恢复和会话隔离没有回归。
- 测试文档记录实际命令、结果和未覆盖项。

## 数据库与发布

本次不修改数据库结构。部署前目标环境必须已经执行认证用户表 DDL，并具有：

- `uk_auth_user_id`
- `uk_auth_account`
- `chk_auth_user_credentials`

发布后注册接口立即可用。若需关闭公网自助注册，应由后续配置开关或网关策略独立设计；本次不增加未定义的隐藏开关。
