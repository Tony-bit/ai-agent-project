# 账号自助注册测试文档

## 测试目标

验证账号注册的输入边界、密码安全、账号唯一性、HTTP 契约、公开路由、前端交互、自动登录和既有认证能力回归。

关联需求：`docs/superpowers/plans/2026-07-21-account-registration-implementation.md`

关联设计：`docs/superpowers/plans/2026-07-21-account-registration-design.md`

## 测试环境

- Java 17
- Maven 多模块工程
- Spring MVC MockMvc
- JUnit 5 / Mockito
- Node.js Test Runner
- 本机 Microsoft Edge 无头浏览器
- 桌面视口：`1280x720`
- 移动视口：`390x844`

## 服务层用例

| ID | 场景 | 输入或前置条件 | 预期结果 |
|---|---|---|---|
| REG-SVC-001 | 合法注册 | `new_user` / `secure-password` | 创建 `ACCOUNT`、`ACTIVE` 用户 |
| REG-SVC-002 | 账号规范化 | 账号首尾有空白 | 入库账号已去除首尾空白 |
| REG-SVC-003 | 用户 ID | 合法注册 | `user_` 前缀且每次生成唯一 ID |
| REG-SVC-004 | BCrypt 入库 | 合法密码 | 入库值不等于明文且可匹配原密码 |
| REG-SVC-005 | 空账号 | `null` 或空字符串 | `INVALID_REGISTRATION`，不写库 |
| REG-SVC-006 | 账号过短 | 少于 3 位 | `INVALID_REGISTRATION`，不写库 |
| REG-SVC-007 | 账号过长 | 超过 32 位 | `INVALID_REGISTRATION`，不写库 |
| REG-SVC-008 | 账号非法字符 | 空格、`@` 等 | `INVALID_REGISTRATION`，不写库 |
| REG-SVC-009 | 密码过短 | 少于 8 位 | `INVALID_REGISTRATION`，不写库 |
| REG-SVC-010 | 密码过长 | 超过 72 位 | `INVALID_REGISTRATION`，不写库 |
| REG-SVC-011 | 已存在账号 | 预查询命中 | `ACCOUNT_ALREADY_EXISTS` |
| REG-SVC-012 | 并发重复注册 | 插入触发唯一键冲突 | `ACCOUNT_ALREADY_EXISTS`，不泄露 SQL |

## HTTP 控制器用例

| ID | 场景 | 预期结果 |
|---|---|---|
| REG-HTTP-001 | 注册成功 | HTTP 200，返回 token、有效期和账号用户 |
| REG-HTTP-002 | 成功响应字段 | 不包含 `password` 和 `passwordHash` |
| REG-HTTP-003 | 非法注册输入 | HTTP 400，`code = 400` |
| REG-HTTP-004 | 账号已存在 | HTTP 409，`code = 409` |
| REG-HTTP-005 | 非预期异常 | HTTP 500，通用 `operation failed` |
| REG-HTTP-006 | 注册请求无 token | 不被认证过滤器拦截 |
| REG-HTTP-007 | `/auth/me` 无 token | 继续返回 401 |

## 前端共享逻辑用例

| ID | 场景 | 预期结果 |
|---|---|---|
| REG-FE-001 | 合法输入 | 返回去空白账号和未修改密码 |
| REG-FE-002 | 非法账号 | 返回 `field = account` 和规则提示 |
| REG-FE-003 | 密码少于 8 位 | 返回 `field = password` |
| REG-FE-004 | 密码超过 72 位 | 返回 `field = password` |
| REG-FE-005 | 确认密码不一致 | 返回 `field = confirmPassword` |

## 浏览器交互用例

| ID | 场景 | 操作 | 预期结果 |
|---|---|---|---|
| REG-UI-001 | 默认模式 | 打开未登录页面 | 默认选中登录，确认密码隐藏 |
| REG-UI-002 | 切换注册 | 点击“注册” | 标题、说明和主按钮切换，确认密码显示 |
| REG-UI-003 | 切回登录 | 输入密码后点击“登录”模式 | 密码、确认密码和错误被清理 |
| REG-UI-004 | 客户端拦截 | 输入非法账号并提交 | 显示规则提示，不发注册请求 |
| REG-UI-005 | 请求载荷 | 输入合法注册信息并提交 | 请求仅含 `account`、`password` |
| REG-UI-006 | 重复提交 | 注册请求未完成时再次操作 | 提交、游客和模式按钮均禁用 |
| REG-UI-007 | 重复账号 | 服务端返回 409 | 显示“账号已存在”，保留账号，清空密码 |
| REG-UI-008 | 非法请求 | 服务端返回 400 | 显示规则错误，清空密码 |
| REG-UI-009 | 网络失败 | 注册请求失败 | 显示通用重试提示并恢复按钮 |
| REG-UI-010 | 注册成功 | 服务端返回 token | 保存身份、加载会话列表、进入应用 |
| REG-UI-011 | 退出后模式 | 注册成功后退出 | 回到默认登录模式 |
| REG-UI-012 | 游客回归 | 点击游客进入 | 既有游客身份流程正常 |

## 视觉与可访问性用例

| ID | 视口 | 预期结果 |
|---|---|---|
| REG-VIS-001 | `1280x720` | 注册卡片完全位于视口内，无横向滚动和重叠 |
| REG-VIS-002 | `390x844` | 注册卡片完全位于视口内，最长提示可换行 |
| REG-VIS-003 | 键盘 | 模式按钮、输入框、提交和游客入口均可聚焦 |
| REG-VIS-004 | 辅助语义 | 模式按钮同步 `aria-selected`，错误区域使用 `role=alert` |

## 安全与回归用例

| ID | 场景 | 预期结果 |
|---|---|---|
| REG-SEC-001 | 密码响应泄露 | 任意注册响应 | 无密码和密码哈希 |
| REG-SEC-002 | 确认密码泄露 | 检查网络请求 | 确认密码不发送 |
| REG-SEC-003 | 数据库异常 | 非唯一键数据库异常 | 不返回 SQL 或内部异常文本 |
| REG-REG-001 | 账号登录 | 原有账号登录成功 |
| REG-REG-002 | 错误登录 | 仍返回统一 401，不暴露账号存在性 |
| REG-REG-003 | 游客创建 | 仍生成独立游客用户和 token |
| REG-REG-004 | token 恢复 | `/auth/me` 恢复账号或游客身份 |
| REG-REG-005 | 会话隔离 | 新注册账号只能访问自己的会话 |

## 自动化命令

后端聚焦测试：

```powershell
mvn -pl ai-agent-study-app -am '-Dtest=AuthServiceTest,AuthControllerIntegrationTest,AuthenticationFilterTest' '-Dsurefire.failIfNoSpecifiedTests=false' test
```

前端共享逻辑测试：

```powershell
node --test docs/dev-ops/nginx/html/test/agent-ui-core.test.js
```

HTML 内联脚本语法检查：

```powershell
node -e "const fs=require('fs'),vm=require('vm'); const html=fs.readFileSync('docs/dev-ops/nginx/html/index.html','utf8'); const scripts=[...html.matchAll(/<script(?:[^>]*)>([\s\S]*?)<\/script>/g)].map(m=>m[1]).filter(Boolean); for(const code of scripts)new vm.Script(code);"
```

## 当前执行记录

| 时间 | 检查项 | 结果 | 说明 |
|---|---|---|---|
| 2026-07-21 | 后端聚焦测试 | 通过 | 19 项，0 失败，0 错误 |
| 2026-07-21 | 前端 Node 测试 | 通过 | 36 项，0 失败 |
| 2026-07-21 | HTML 内联脚本语法 | 通过 | 2 段内联脚本均可解析 |
| 2026-07-21 | 认证、会话与执行守卫回归 | 通过 | 10 个指定套件实际生成 9 个 Jupiter 报告，共 42 项，0 失败；JUnit 4 查询套件单独执行 |
| 2026-07-21 | 会话查询 JUnit 4 回归 | 通过 | `JUnitCore` 实际执行 22 项，全部通过 |
| 2026-07-21 | 应用模块默认 Jupiter 回归 | 通过 | reactor 安装期间实际执行 41 项，0 失败 |
| 2026-07-21 | 桌面浏览器验证 | 通过 | `1280x720`，卡片范围 `top=70`、`bottom=650`，无横向溢出 |
| 2026-07-21 | 移动浏览器验证 | 通过 | `390x844`，卡片范围 `top=132`、`bottom=712`，无横向溢出 |
| 2026-07-21 | 浏览器注册交互矩阵 | 通过 | 客户端拦截、请求中禁用、请求体、200、400、409、网络失败、退出复位和游客回归均通过 |
| 2026-07-21 | 浏览器控制台检查 | 通过 | 桌面和移动注册页面均无控制台错误 |

### 浏览器验证产物

- `account-registration-desktop.png`
- `account-registration-mobile.png`

截图保存在当前 Codex 任务的可视化产物目录中，不写入项目仓库。

### 回归说明

`ChatSessionQueryServiceTest` 使用 JUnit 4，但 `ai-agent-study-app` 的 Surefire 配置固定使用 JUnit Jupiter，常规 Maven 命令会编译该类但不执行。为避免本需求改变全局测试引擎，本次先安装当前 reactor 依赖，再通过以下命令实际运行该类：

```powershell
mvn -q -pl ai-agent-study-app org.codehaus.mojo:exec-maven-plugin:3.5.0:java '-Dexec.mainClass=org.junit.runner.JUnitCore' '-Dexec.args=denny.ai.agent.test.infrastructure.service.ChatSessionQueryServiceTest' '-Dexec.classpathScope=test'
```

后续应单独迁移该类到 JUnit 5，或评估在应用模块启用 Vintage Engine。该既有测试配置问题不影响本次注册运行时代码。

本次没有对配置中的远程 MySQL 执行真实账号写入，避免污染共享环境。数据层通过现有唯一索引定义、DAO 插入路径、BCrypt 服务测试和 `DuplicateKeyException` 并发竞争测试覆盖。

## 通过标准

1. 所有服务层、控制器、过滤器和前端共享逻辑自动化测试通过。
2. 注册成功响应和请求均不泄露密码相关字段。
3. 重复账号在预查询和并发唯一键竞争下均返回 409 语义。
4. 注册成功后自动登录并进入独立会话空间。
5. 登录、游客、token 恢复和会话隔离回归通过。
6. 桌面与移动视口无溢出、重叠或不可操作控件。
7. 所有未执行项在交付前更新为通过或明确记录为剩余风险。
