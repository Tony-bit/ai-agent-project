# 测试方案：Trading Target 请求级 ToolContext

## 1. 测试背景

### 1.1 对应设计
- 设计文档：`docs/superpowers/plans/2026-07-28-trading-target-dynamic-context-design.md`
- 实现计划：`docs/superpowers/plans/2026-07-28-trading-target-dynamic-context-implementation.md`

### 1.2 测试目标
- 验证 `dynamicContext` 中的权威目标能在 LLM 请求创建时被校验并快照到 `ToolContext`。
- 验证工具跨线程执行时不会丢失目标，也不会在并发请求间串标。
- 验证身份边界、ticker 覆盖、监控、搜索工具和 Provider 查询行为保持正确。

### 1.3 测试范围
- `ai-agent-study-trading-api`：共享目标上下文契约。
- `ai-agent-study-trading-domain`：请求注入和 LLM 调用审计。
- `ai-agent-study-trading-infra`：七个 Trading ToolCallback。

### 1.4 不在本次测试范围
- `IStockDataProvider` 外部数据源、缓存策略和返回结构。
- Spring AI 内部 `DefaultToolCallingManager` 的内部实现契约。
- 真实模型、MCP 服务和外部行情服务的在线可用性。

---

## 2. 测试策略

| 测试层级 | 是否覆盖 | 说明 |
|------|------|------|
| 单元测试 | 是 | 验证上下文校验、工具策略、审计和监控 |
| 集成测试 | 是 | 验证 Trading 模块组合及 Spring AI 公开 API 编译契约 |
| 接口测试 | 否 | 本次不改变对外接口 |
| 回归测试 | 是 | 验证 Starter、Prompt 和工具既有行为 |
| 手工验证 | 否 | 外部模型和行情源不纳入自动回归 |

### 2.1 Mock 策略
| 依赖项 | 是否 Mock | Mock 方式 | 说明 |
|------|------|------|------|
| `ChatClient.ChatClientRequestSpec` | 是 | Mockito | 捕获并断言请求级 Tool Context |
| `IStockDataProvider` | 是 | Mockito | 只验证工具层传入的权威 ticker |
| `TradingRolloutMonitor` | 否 | 内存计数器 | 直接断言指标快照 |

---

## 3. 测试场景设计

### 3.1 正常场景
| 编号 | 场景名称 | 前置条件 | 输入 | 预期结果 | status |
|------|------|------|------|------|------|
| TC-001 | 请求注入权威目标 | 两处目标一致 | `TradingChatMemory.apply()` | 请求 Tool Context 只包含同一 `TargetContext` | pass |
| TC-002 | 六个目标工具查询 | Tool Context 包含目标 | 各工具合法输入 | Provider 均使用 `targetId` | pass |
| TC-003 | 搜索工具在 Run 外执行 | Tool Context 为空 | 股票名称 | 保持原搜索结果 | pass |
| TC-004 | 跨线程工具执行 | 请求绑定目标 | `boundedElastic` 调用 | 工具成功读取目标 | pass |

### 3.2 异常场景
| 编号 | 场景名称 | 前置条件 | 输入 | 预期结果 | status |
|------|------|------|------|------|------|
| TC-101 | DynamicContext 缺少目标 | 无目标键 | 发起 LLM 请求 | 请求前抛出 `IDENTITY_BOUNDARY_VIOLATION` | pass |
| TC-102 | DynamicContext 目标类型错误 | 键值为字符串 | 发起 LLM 请求 | 明确身份边界异常 | pass |
| TC-103 | 两处目标不一致 | `runId` 或 `targetId` 不同 | 发起 LLM 请求 | 请求前拒绝 | pass |
| TC-104 | 目标工具缺少 Tool Context | 空上下文 | 单参数或双参数调用 | 拒绝且记录边界指标 | pass |
| TC-105 | 搜索工具在 Run 内执行 | Tool Context 包含目标 | 股票名称 | 以身份边界异常拒绝 | pass |

### 3.3 边界场景
| 编号 | 场景名称 | 前置条件 | 输入 | 预期结果 | status |
|------|------|------|------|------|------|
| TC-201 | ToolContext 参数为 null | 目标工具 | 双参数调用 | 规范化为空上下文并拒绝 | pass |
| TC-202 | 模型传入错误 ticker | Tool Context 有权威目标 | 其他 ticker | 覆盖为权威目标并增加指标 | pass |
| TC-203 | DynamicContext 串行复用 | 已创建请求 A 后写入目标 B | 使用请求 A 快照 | 仍查询目标 A | pass |
| TC-204 | 两个目标并发执行 | 独立 Tool Context A/B | 并发调用 | 查询结果不串标 | pass |

### 3.4 回归场景
| 编号 | 场景名称 | 前置条件 | 输入 | 预期结果 | status |
|------|------|------|------|------|------|
| TC-301 | StockInfo 仍查询 Provider | Tool Context 有目标 | `get_stock_info` | 不读取初始化快照，调用现有 Provider | pass |
| TC-302 | Provider 异常转换 | Provider 抛异常 | 合法工具调用 | 保持“工具执行失败”返回语义 | pass |
| TC-303 | LLM 调用审计 | TradingContext 有目标 | invocation 成功或失败 | 正常返回或透传原异常并保留审计 | pass |
| TC-304 | Starter 写入目标 | Trading Run 初始化 | 合法请求 | 仅初始化逻辑写入目标键 | pass |

---

## 4. 用例与代码映射

| 测试编号 | 测试类 | 目标类 | 覆盖类型 |
|------|------|------|------|
| TC-001、TC-101～TC-103、TC-203 | `TradingChatMemoryTest` | `TradingChatMemory` | 正常/异常/边界 |
| TC-002～TC-004、TC-104～TC-105、TC-201～TC-204、TC-301～TC-302 | `TradingToolCallbacksTest` | `TradingToolCallbacks` | 正常/异常/并发/回归 |
| TC-303 | `TradingLlmCallAuditTest` | `TradingLlmCallAudit` | 回归 |
| TC-304 | `TradingStarterPipelineTest` | `TradingStarter` | 回归 |

---

## 5. 关键校验点

- Tool Context Map 只包含 `target_context`，且目标实例不可变。
- 模型输入 ticker 不参与实际目标选择。
- 身份边界异常继续增加 `identityBoundaryViolations` 指标。
- 错误 ticker 继续增加 `toolTargetOverrides` 指标。
- 全局 ToolCallback 不保存任何请求级字段。
- 代码库不再包含 `TradingTargetScope` 或目标 `ThreadLocal`。

---

## 6. 执行计划

| 步骤 | 内容 | 预期结果 | status |
|------|------|------|------|
| 1 | 编写并执行目标单元测试 | 全部通过 | pass |
| 2 | 执行三个 Trading 子模块测试 | 全部通过 | pass |
| 3 | 执行 Trading 聚合回归 | 无回归问题 | pass |
| 4 | 执行全仓编译 | 编译成功 | pass |

---

## 7. 验收标准

| 编号 | 验收项 | 标准 | status |
|------|------|------|------|
| AC-001 | 请求级上下文生效 | 所有 Trading LLM 请求注入同一权威目标 | pass |
| AC-002 | 身份边界完整 | 缺失、错误类型、不一致和 Run 内搜索均被拒绝 | pass |
| AC-003 | 跨线程与并发隔离 | `boundedElastic` 和双目标并发用例通过 | pass |
| AC-004 | 旧作用域删除 | 全仓无 `TradingTargetScope` 引用 | pass |
| AC-005 | 无关键回归 | Trading 模块测试通过 | pass |
| AC-006 | 编译通过 | `mvn clean compile -q` 成功 | pass |

---

## 8. 风险与说明

| 风险点 | 影响 | 应对措施 |
|------|------|------|
| Spring AI Tool Context API 使用方式变化 | 工具拿不到目标 | 通过公开 API 编译和回调单测锁定契约 |
| `DynamicContext` 使用未检查泛型转换 | 类型错误不明确 | 先按 `Object` 读取并显式校验类型 |
| 全局工具 Bean 意外持有请求状态 | 并发串标 | 仅通过 `call` 参数传递目标并执行并发测试 |

---

## 9. 执行结果记录

| 项目 | 结果 |
|------|------|
| 单元测试 | pass：Trading 三个子模块共 199 个测试，失败 0、错误 0、跳过 0 |
| 集成测试 | pass：根项目 `mvn test -q` 全量回归共 842 个测试，失败 0、错误 0、跳过 0 |
| 编译验证 | pass：`mvn clean compile -q` 退出码 0 |

### 9.1 问题记录
| 编号 | 问题描述 | 影响范围 | 状态 |
|------|------|------|------|
| - | 暂无 | - | pass |

### 9.2 结论
- 是否达到提测/合并条件：是
- 结论说明：身份边界、跨线程、并发隔离、相邻回归和全仓编译均已通过。
