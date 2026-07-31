# 通用对话缺陷修复与回归记录

status: pass
owner: Codex
created_at: 2026-07-30
updated_at: 2026-07-31

## 1. 背景

本次缺陷回归覆盖通用对话入口和 `ChatClient` 工具边界两个关联问题：

1. 前端只有 `aiAgentId=3` 的“通用任务助手”，请求会进入 PE 多步骤链路。数据库虽已配置 `agent_id=8` 和 `client_id=3001`，但前端没有入口，`RootNode` 也没有 ID 8 的显式路由。
2. `AiClientNode` 会把所有 Spring `ToolCallback` Bean 注册给每个 `ChatClient`，导致通用聊天客户端 `3001` 可以看到依赖 Trading Run 上下文的交易工具。模型调用交易工具但请求不含 `target_context` 时，会触发 `IDENTITY_BOUNDARY_VIOLATION`。

回归过程中还确认了两个相邻问题：Trading Skills 的轻量工具包装器没有转发双参数调用中的 `ToolContext`；`GeneralChatNode` 发送 error SSE 后仍可能继续走成功完成逻辑，形成冲突终态。

## 2. 修复目标

- 新增“通用对话助手”，通过 `aiAgentId=8` 直接进入 `GeneralChatNode`。
- 保持 ID 3 的 PE 链路、ID 5 的巡检链路、空 ID 的意图路由和其他显式 ID 的既有行为不变。
- 通用聊天和普通意图路由客户端不再暴露依赖 Trading Run 的交易工具。
- 已启用的交易客户端继续保留交易工具能力。
- 轻量工具包装器完整转发 `ToolContext`。
- 流式失败只产生错误终态，不再继续执行成功完成逻辑。

## 3. 最终方案

### 3.1 通用对话显式路由

前端在 `aiAgentSelect` 中增加 `<option value="8">通用对话助手</option>`，继续使用现有 `POST /api/v1/agent/auto_agent` 接口和 `aiAgentId` 字段。

`RootNode` 注入 `GeneralChatNode`，显式路由顺序为：

1. `aiAgentId` 为空：进入统一或拆分意图路由。
2. `aiAgentId="5"`：进入 `IntelligentInspection`。
3. `aiAgentId="8"`：进入 `GeneralChatNode`。
4. 其他非空 `aiAgentId`：进入 `Step1AnalyzerNode` PE 链路。

ID 8 使用 `Objects.equals(..., "8")` 精确匹配，避免 `80` 等相似 ID 被误路由。

### 3.2 数据库结论

2026-07-30 已通过只读连接核对 `ai-agent-station`：

- `ai_agent.agent_id=8` 已存在，名称为“通用智能体”，状态为启用。
- `ai_agent_flow_config.agent_id=8` 已关联 `client_id=3001`、`client_type=DEFAULT`、`sequence=1`。
- 现有迁移 `V2027__agent_flow_advisor_sync.sql` 已包含相同数据。

因此本次没有新增数据库迁移，也没有执行 DDL 或 DML。

### 3.3 交易工具隔离

`AiClientNode` 维护稳定的交易工具名集合，并通过 `spring.ai.trading.tools.enabled-client-ids` 显式控制可注册交易工具的客户端：

- 非交易工具继续注册给所有客户端。
- 交易工具仅注册给显式启用的客户端，不通过 `600x` 前缀推断身份。
- 基础配置保留 `6001` 至 `6013` 的交易工具能力。
- `3001`、`3201` 等普通客户端不再获得交易专用工具。

该隔离只改变工具可见范围，不改变路由、Prompt、Advisor、记忆或普通工具注册行为。

### 3.4 `ToolContext` 转发

Trading Skills 轻量工具包装器实现两个 `call` 重载。双参数重载直接调用 `delegate.call(functionInput, toolContext)`，不创建、复制或修改上下文，保证原始工具收到同一个 `ToolContext` 实例。

### 3.5 流式错误终态

`GeneralChatNode#streamToEmitter` 记录异步流错误，并在订阅结束后向调用方重新抛出。失败后不再写入成功响应、标记成功或发送 complete SSE；正常流式调用的事件顺序和响应内容保持不变。

## 4. 兼容性边界

- 不修改 `AutoAgentRequestDTO`、Controller、鉴权、会话执行锁或 SSE 协议。
- 不新增 HTTP 接口、请求字段或 `agentType`。
- `RoutingResultHandler` 及其意图到执行节点的映射保持不变，`GENERAL_CHAT` 仍进入 `GeneralChatNode`。
- `6001` 仍可在 Trading Run 建立前调用 `search_stock_by_name`。
- `6002` 至 `6013` 仍由 `TradingChatMemory.apply` 注入不可变的 `target_context`。
- 股票请求若被错误路由到通用聊天，不再绕过 Trading Run 直接调用交易数据工具。

## 5. 变更范围

| 类型 | 文件或组件 | 变更摘要 |
|------|------|------|
| 前端 | `docs/dev-ops/nginx/html/index.html` | 新增 ID 8 的“通用对话助手”选项 |
| 前端测试 | `docs/dev-ops/nginx/html/test/agent-ui-security-smoke.html` | 校验 ID 8 和 ID 3 的请求体 |
| 路由 | `RootNode.java` | 增加 ID 8 到 `GeneralChatNode` 的精确分支 |
| 路由测试 | `RootNodeTest.java` | 覆盖 ID 8、相似 ID 和既有路由回归 |
| 工具装配 | `AiClientNode.java` | 增加交易工具白名单过滤并转发 `ToolContext` |
| 工具测试 | `AiClientNodeToolIsolationTest.java` | 覆盖普通客户端、交易客户端和包装器行为 |
| 流式处理 | `GeneralChatNode.java` | 失败后抛出流异常，阻止成功终态 |
| 流式测试 | `GeneralChatNodeTest.java` | 覆盖流式失败传播和 SSE 错误终态 |
| 配置 | `application.yml` | 配置允许使用交易工具的客户端 ID |

## 6. 回归场景

测试遵循“只验证本层逻辑，中间件和下游节点统一 Mock”的原则，不调用真实模型，不写数据库。

### 6.1 正常场景

| 编号 | 场景 | 预期结果 | status |
|------|------|------|------|
| TC-001 | 选择“通用对话助手”发送消息 | 请求体携带 `aiAgentId="8"` | pass |
| TC-002 | 后端收到 ID 8 | `RootNode` 返回 `GeneralChatNode` | pass |
| TC-003 | 已启用的交易客户端装配工具 | 保留交易工具能力 | pass |
| TC-004 | 轻量包装器双参数调用 | 原始工具收到同一个 `ToolContext` | pass |

### 6.2 异常场景

| 编号 | 场景 | 预期结果 | status |
|------|------|------|------|
| TC-101 | `GeneralChatNode` 流式 Publisher 报错 | 发送 error SSE 后抛出异常，不产生成功终态 | pass |
| TC-102 | 普通客户端尝试装配交易工具 | 交易工具不注册，普通工具仍正常注册 | pass |

### 6.3 边界场景

| 编号 | 场景 | 预期结果 | status |
|------|------|------|------|
| TC-201 | `aiAgentId="80"` | 不误匹配 ID 8，继续进入 PE 链路 | pass |
| TC-202 | `aiAgentId` 为 `null`、空串或空白串 | 继续进入意图路由 | pass |

### 6.4 回归场景

| 编号 | 场景 | 预期结果 | status |
|------|------|------|------|
| TC-301 | 选择 ID 3 的“通用任务助手” | 请求携带 ID 3，后端进入 PE 链路 | pass |
| TC-302 | 使用 ID 5 的巡检助手 | 进入 `IntelligentInspection` | pass |
| TC-303 | 不选择智能体 | 进入配置指定的意图路由模式 | pass |
| TC-304 | `GENERAL_CHAT` 意图路由 | 仍进入 `GeneralChatNode` | pass |
| TC-305 | Trading 上下文和交易工具调用 | role 仍收到目标上下文 | pass |

## 7. 用例与代码映射

| 场景 | 测试类或用例 |
|------|------|
| ID 8 精确路由 | `RootNodeTest#shouldRouteGeneralChatAgentToGeneralChatNode()` |
| 相似 ID 不误匹配 | `RootNodeTest#shouldNotTreatSimilarAgentIdAsGeneralChat()` |
| 通用客户端隐藏交易工具 | `AiClientNodeToolIsolationTest#should_hide_trading_tools_from_general_chat_client()` |
| 交易客户端保留交易工具 | `AiClientNodeToolIsolationTest#should_keep_trading_tools_for_enabled_trading_client()` |
| `ToolContext` 透明转发 | `AiClientNodeToolIsolationTest#lightweight_wrapper_should_forward_tool_context_to_delegate()` |
| 流式失败终态 | `GeneralChatNodeTest#should_propagate_stream_failure_after_sending_error_event()` |
| ID 8 与 ID 3 前端契约 | 浏览器安全烟测请求体断言 |

## 8. 验收与执行结果

| 项目 | 结果 | 说明 |
|------|------|------|
| 数据库只读核对 | pass | Agent 8 已启用并关联客户端 3001 |
| 后端路由测试 | pass | `RootNodeTest` 记录为 15 项通过 |
| 工具隔离与上下文测试 | pass | 普通客户端、交易客户端和包装器场景通过 |
| 流式错误终态测试 | pass | 正常流式与错误传播场景通过 |
| 前端 Node 测试 | pass | 记录为 36 项通过 |
| 浏览器烟测 | pass | 记录为 19 项通过，包含 ID 8 与 ID 3 请求体断言 |
| Trading 链路回归 | pass | 路由、上下文和交易工具聚焦测试通过 |
| 编译验证 | pass | `ai-agent-study-domain` 编译成功，HTML 内联脚本可解析 |

## 9. 结论

通用对话入口、显式路由、交易工具隔离、`ToolContext` 转发和流式错误终态均已完成修复并通过回归。既有 PE、巡检、意图路由和 Trading 链路未发现回归，本次缺陷达到归档条件。
