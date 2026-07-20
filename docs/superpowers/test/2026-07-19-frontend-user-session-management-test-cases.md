# 登录与用户级会话隔离测试用例

## 文档目的

本文定义账号密码登录、游客模式、token 恢复、SSE 认证、用户级会话隔离、指定会话删除和前端状态切换的测试用例。

设计依据：

- `docs/superpowers/design/2026-07-19-frontend-user-session-management-design.md`

本文只描述测试用例和验收验证，不描述实现任务拆分。

## 状态约定

| 状态 | 含义 |
|---|---|
| `not-run` | 用例已定义，尚未执行 |
| `pass` | 已执行且通过 |
| `fail` | 已执行但未通过 |
| `blocked` | 因环境、数据或依赖问题暂不能执行 |

当前所有用例初始状态均为 `not-run`。

## 测试数据

| 数据 | 说明 |
|---|---|
| `account_user_a` | SQL 初始化账号 A，状态 active |
| `account_user_b` | SQL 初始化账号 B，状态 active |
| `disabled_user` | SQL 初始化禁用账号 |
| `guest_user` | 通过 `POST /api/v1/auth/guest` 动态创建 |
| `session_owned_a` | 属于账号 A 的已有会话 |
| `session_owned_b` | 属于账号 B 的已有会话 |
| `session_new_valid` | 数据库中不存在、格式合法的新会话 ID |
| `session_invalid` | 包含非法字符或长度非法的会话 ID |

账号密码只用于测试环境。初始化 SQL 中必须保存密码哈希，不保存明文密码。

## 认证与 token 用例

| ID | 用例 | 前置条件 | 步骤 | 期望结果 | 状态 |
|---|---|---|---|---|---|
| AUTH-001 | 账号登录成功 | `account_user_a` active | 使用正确账号密码调用 `/api/v1/auth/login` | 返回 token、过期时间和当前用户；不返回密码信息 | not-run |
| AUTH-002 | 账号不存在 | 无对应账号 | 使用不存在账号登录 | 返回统一登录失败，不暴露账号是否存在 | not-run |
| AUTH-003 | 密码错误 | `account_user_a` active | 使用错误密码登录 | 返回统一登录失败，不返回 token | not-run |
| AUTH-004 | 用户禁用 | `disabled_user` disabled | 使用正确密码登录 | 登录失败或返回用户不可用；不得签发可用 token | not-run |
| AUTH-005 | 游客进入 | 无 | 调用 `/api/v1/auth/guest` | 创建 `GUEST` 用户，`account/password_hash` 为空，返回 `guest_` 前缀 userId 和 token | not-run |
| AUTH-006 | 有效 token 恢复账号 | 已登录账号 A | 带 token 调用 `/api/v1/auth/me` | 返回账号 A 当前用户信息 | not-run |
| AUTH-007 | 有效 token 恢复游客 | 已创建游客 token | 带游客 token 调用 `/api/v1/auth/me` | 返回原游客 `userId`，不新建游客 | not-run |
| AUTH-008 | token 解析失败 | 本地存在非法 token | 调用 `/api/v1/auth/me` | 返回 `401`；前端清 token 并展示登录界面 | not-run |
| AUTH-009 | token 过期 | 构造过期 token | 调用受保护接口 | 返回 `401`；前端清理登录态 | not-run |
| AUTH-010 | JWT 配置生效 | 配置 `auth.jwt.expires-in-seconds` | 登录后解析 token 过期时间 | 过期时间符合配置 | not-run |

## 会话隔离用例

| ID | 用例 | 前置条件 | 步骤 | 期望结果 | 状态 |
|---|---|---|---|---|---|
| SESSION-001 | 查询自己的会话列表 | 账号 A 有会话 | A token 调用 `/api/v1/session/list` | 只返回账号 A 的会话 | not-run |
| SESSION-002 | 不携带 token 查询会话列表 | 无 token | 调用 `/api/v1/session/list` | 返回 `401` | not-run |
| SESSION-003 | 查询自己的历史消息 | `session_owned_a` 存在 | A token 查询该会话消息 | 返回该会话消息 | not-run |
| SESSION-004 | 查询他人历史消息 | `session_owned_b` 存在 | A token 查询 B 的会话消息 | 返回不可用错误，不返回消息内容 | not-run |
| SESSION-005 | 查询不存在的新会话 | `session_new_valid` 不存在 | A token 查询该会话消息 | 返回空消息列表，允许后续创建 | not-run |
| SESSION-006 | 非法 sessionId | `session_invalid` | 查询历史或发起任务 | 前端阻止；后端独立返回参数错误 | not-run |
| SESSION-007 | 忽略请求体 userId | A token，请求体伪造 B 的 userId | 发起 Agent 或会话接口 | 后端仍按 A 身份处理，不信任伪造 userId | not-run |

## SSE 认证用例

| ID | 用例 | 前置条件 | 步骤 | 期望结果 | 状态 |
|---|---|---|---|---|---|
| SSE-001 | 通用 Agent 携带 token 建流 | A token 有效 | `fetch` POST `/api/v1/agent/auto_agent`，带 `Authorization` 和 `Accept: text/event-stream` | 返回 SSE 流并按 A 身份执行 | not-run |
| SSE-002 | 巡检 Agent 携带 token 建流 | A token 有效 | `fetch` POST `/api/v1/agent/inspection` | 返回 SSE 流并按 A 身份执行 | not-run |
| SSE-003 | 股票分析携带 token 建流 | A token 有效 | `fetch` POST `/api/v1/trading/analysis` | 返回 SSE 流并按 A 身份执行 | not-run |
| SSE-004 | SSE 缺少 token | 无 token | 调用任一 SSE 入口 | 返回 `401`，不创建 emitter，不提交异步任务 | not-run |
| SSE-005 | SSE token 非法 | 非法 token | 调用任一 SSE 入口 | 返回 `401`，不创建 emitter，不提交异步任务 | not-run |
| SSE-006 | SSE 会话属于他人 | A token，`session_owned_b` | A 发起该 session 的 SSE 请求 | 返回会话不可用，不启动 Agent/Trading 执行 | not-run |
| SSE-007 | 不使用 URL token | 前端发起 SSE | 检查请求 URL | URL 不包含长期 access token；认证只在请求头中 | not-run |

## 删除会话用例

| ID | 用例 | 前置条件 | 步骤 | 期望结果 | 状态 |
|---|---|---|---|---|---|
| DELETE-001 | 删除自己的非当前会话 | A 有两个会话 | 删除非当前会话 | 数据库会话和消息删除；当前页面内容不变 | not-run |
| DELETE-002 | 删除自己的当前会话 | A 当前会话存在 | 删除当前会话 | 删除成功后页面进入新空会话 | not-run |
| DELETE-003 | 删除他人会话 | A token，`session_owned_b` | 删除 B 的会话 | 返回 `404` 或统一失败；不暴露 B 会话信息 | not-run |
| DELETE-004 | 删除不存在会话 | A token，不存在 ID | 调用删除接口 | 返回 `404`；前端不移除列表项 | not-run |
| DELETE-005 | 执行中会话删除 | 会话有活动 SSE 请求 | 删除该会话 | 返回会话运行中；前端提示先取消 | not-run |
| DELETE-006 | 删除不影响 Mem0 | 会话已同步长期记忆 | 删除会话 | 不调用 Mem0 删除；长期记忆保留 | not-run |
| DELETE-007 | 删除事务回滚 | 模拟消息或会话删除失败 | 调用删除接口 | 事务回滚，不出现只删消息或只删会话 | not-run |

## 前端状态用例

| ID | 用例 | 前置条件 | 步骤 | 期望结果 | 状态 |
|---|---|---|---|---|---|
| UI-001 | 启动时恢复账号 token | 本地保存 A token | 打开页面 | 先调用 `/auth/me`，成功后加载 A 会话列表 | not-run |
| UI-002 | 启动时恢复游客 token | 本地保存游客 token | 打开页面 | 恢复原游客身份并加载游客会话列表 | not-run |
| UI-003 | 启动时 token 失效 | 本地保存无效 token | 打开页面 | 清 token，展示登录界面 | not-run |
| UI-004 | 无 token 游客进入 | 本地无 token | 点击游客进入 | 创建新游客，保存 token，进入聊天界面 | not-run |
| UI-005 | 切换账号清旧状态 | A 已登录且有会话列表 | 退出或切换为 B 登录 | 停止旧流，清 A 状态，再加载 B 会话列表 | not-run |
| UI-006 | 游客切换账号清旧状态 | 游客已登录 | 使用账号登录 | 游客消息、列表、分页游标和错误提示不残留 | not-run |
| UI-007 | SSE 401 前端处理 | SSE 请求返回 401 | 发起流式请求 | 停止流式状态，清 token，回登录界面 | not-run |
| UI-008 | 会话设置合法 ID | 已登录 | 输入合法新 sessionId 并应用 | 加载历史或展示空态 | not-run |
| UI-009 | 会话设置非法 ID | 已登录 | 输入非法 sessionId | 前端提示错误，不发起切换 | not-run |
| UI-010 | 删除按钮不触发会话切换 | 会话列表有删除按钮 | 点击删除按钮 | 打开确认或执行删除，不触发列表项切换 | not-run |
| UI-011 | localStorage 不可用 | 模拟存储异常 | 登录或游客进入 | 当前页内可用；刷新后需要重新登录 | not-run |

## 浏览器回归用例

| ID | 用例 | 前置条件 | 步骤 | 期望结果 | 状态 |
|---|---|---|---|---|---|
| BROWSER-001 | 桌面布局 | 视口 1280x720 | 完成登录、会话切换、删除 | 登录框、弹层、列表和聊天区不重叠 | not-run |
| BROWSER-002 | 移动布局 | 视口 390x844 | 完成登录、会话切换、删除 | 页面可操作，不出现关键按钮溢出 | not-run |
| BROWSER-003 | 键盘操作 | 已登录 | Tab/Enter 操作登录、设置、删除确认 | 焦点顺序可用，确认/取消可键盘完成 | not-run |
| BROWSER-004 | 删除确认文案 | 会话可删除 | 打开删除确认 | 明确提示删除会话记录但保留已同步长期记忆 | not-run |

## 验收覆盖关系

| 验收点 | 覆盖用例 |
|---|---|
| 账号密码登录 | AUTH-001 至 AUTH-004 |
| 游客模式与 token 恢复 | AUTH-005 至 AUTH-009、UI-001 至 UI-004 |
| JWT 配置 | AUTH-010 |
| 用户级会话隔离 | SESSION-001 至 SESSION-007 |
| SSE 认证 | SSE-001 至 SSE-007、UI-007 |
| 删除指定会话 | DELETE-001 至 DELETE-007、UI-010 |
| 切换登录用户清状态 | UI-005、UI-006 |
| 浏览器交互 | BROWSER-001 至 BROWSER-004 |
