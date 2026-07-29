# Trading Target 请求级 ToolContext 实现计划

> **致智能体工作者：** 必需子技能：使用 superpowers:subagent-driven-development（推荐）或 superpowers:executing-plans 来按任务逐步实现本计划。步骤使用复选框（`- [ ]`）语法进行跟踪。

**目标：** 删除基于 `ThreadLocal` 的 Trading 目标作用域，将 `dynamicContext` 中的权威 `TargetContext` 通过 Spring AI 请求级 `ToolContext` 安全传递给股票工具。

**架构：** `TradingStarter` 仍是目标唯一写入者，`TradingChatMemory.apply()` 在创建请求时完成类型和身份一致性校验，并捕获不可变目标快照。全局无状态 ToolCallback 仅从调用参数 `ToolContext` 读取目标，六个数据工具强制要求目标，搜索工具在 Trading Run 内拒绝执行。

**技术栈：** Java 17、Spring AI 1.1.2、Reactor、JUnit 5、Mockito、Maven

---

### 任务 1：建立请求级目标上下文契约

| 任务 | status |
|------|------|
| 任务 1：建立请求级目标上下文契约 | pass |

**文件：**
- 创建：`ai-agent-study-trading/ai-agent-study-trading-api/src/main/java/denny/ai/agent/trading/api/context/TradingTargetContextKeys.java`
- 修改：`ai-agent-study-trading/ai-agent-study-trading-domain/src/main/java/denny/ai/agent/trading/domain/config/TradingStarter.java`
- 修改：`ai-agent-study-trading/ai-agent-study-trading-domain/src/main/java/denny/ai/agent/trading/domain/execution/TradingChatMemory.java`
- 创建：`ai-agent-study-trading/ai-agent-study-trading-domain/src/test/java/denny/ai/agent/trading/domain/execution/TradingChatMemoryTest.java`

- [x] 编写 `TradingChatMemoryTest`，覆盖正确注入、缺失目标、错误类型、`runId` 不一致、`targetId` 不一致和请求快照不受后续 `DynamicContext` 覆盖影响。
- [x] 运行目标测试并确认新断言失败。
- [x] 新增共享键契约，将 `TradingStarter` 的硬编码键替换为常量，并在 `TradingChatMemory.apply()` 中校验和调用 `request.toolContext(Map.of(...))`。
- [x] 运行 `TradingChatMemoryTest` 和 `TradingStarterPipelineTest`，确认通过。
- [x] 将任务状态更新为 `pass`。

### 任务 2：改造无状态 Trading ToolCallback

| 任务 | status |
|------|------|
| 任务 2：改造无状态 Trading ToolCallback | pass |

**文件：**
- 修改：`ai-agent-study-trading/ai-agent-study-trading-infra/src/main/java/denny/ai/agent/trading/infra/tools/TradingToolCallbacks.java`
- 修改：`ai-agent-study-trading/ai-agent-study-trading-infra/src/test/java/denny/ai/agent/trading/infra/tools/TradingToolCallbacksTest.java`

- [x] 先扩充测试，覆盖双参数 `call`、单参数拒绝、六个目标工具、错误 ticker 覆盖指标、搜索边界、空/错误类型上下文和 `boundedElastic` 跨线程调用。
- [x] 运行 infra 目标测试并确认失败。
- [x] 将基类统一为 `call(String)` 委托空上下文、`call(String, ToolContext)` 解析执行，具体工具在 `doExecute(Map, ToolContext)` 中选择强制目标或可选目标策略。
- [x] 运行 `TradingToolCallbacksTest`，确认所有工具使用 `target.targetId()` 且监控行为保持不变。
- [x] 将任务状态更新为 `pass`。

### 任务 3：删除 ThreadLocal 目标作用域

| 任务 | status |
|------|------|
| 任务 3：删除 ThreadLocal 目标作用域 | pass |

**文件：**
- 删除：`ai-agent-study-trading/ai-agent-study-trading-api/src/main/java/denny/ai/agent/trading/api/provider/TradingTargetScope.java`
- 修改：`ai-agent-study-trading/ai-agent-study-trading-domain/src/main/java/denny/ai/agent/trading/domain/execution/TradingLlmCallAudit.java`
- 修改：`ai-agent-study-trading/ai-agent-study-trading-domain/src/test/java/denny/ai/agent/trading/domain/execution/TradingLlmCallAuditTest.java`

- [x] 补充审计测试，覆盖目标缺失和原始异常透传。
- [x] 将 `TradingLlmCallAudit` 改为校验后直接执行 invocation，并删除 `TradingTargetScope`。
- [x] 全仓搜索 `TradingTargetScope`、`ThreadLocalAccessor` 和目标传播残留，结果必须为空。
- [x] 运行 trading-api、trading-domain 和 trading-infra 测试，确认通过。
- [x] 将任务状态更新为 `pass`。

### 任务 4：回归验证与提测材料

| 任务 | status |
|------|------|
| 任务 4：回归验证与提测材料 | pass |

**文件：**
- 创建：`docs/superpowers/test/2026-07-28-trading-target-dynamic-context-test.md`
- 修改：`docs/superpowers/plans/2026-07-28-trading-target-dynamic-context-implementation.md`

- [x] 执行目标单元测试和并发测试，记录结果。
- [x] 执行 Trading 聚合模块测试，确认严格/宽松 Prompt 相邻链路无回归。
- [x] 执行 `mvn clean compile -q`，确认全仓编译通过。
- [x] 更新测试方案中的执行项、验收项和结论为实际结果。
- [x] 将任务状态更新为 `pass`。
