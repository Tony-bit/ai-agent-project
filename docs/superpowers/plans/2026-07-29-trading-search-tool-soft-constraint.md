# Trading 搜索工具软约束实现计划

> **致智能体工作者：** 必需子技能：使用 `executing-plans` 按任务逐步实施本计划。步骤使用复选框跟踪。

**目标：** 通过工具 description 和 Trading 共享目标上下文降低子节点误调用 `search_stock_by_name` 的概率，同时保留现有执行时身份边界。

**架构：** 工具使用阶段说明继续由 `TradingToolCallbacks` 所有；所有 Trading 角色共用的目标锁定说明由 `TradingPromptRenderer` 所有。不修改 ChatClient 工具注册、Provider 查询或 ToolContext 校验。

**技术栈：** Java 17、Spring AI 1.1.2、JUnit 5、Maven

---

### 任务 1：用测试锁定软约束文案

| 任务 | status |
|------|------|
| 任务 1：用测试锁定软约束文案 | pass |

**文件：**
- 修改：`ai-agent-study-trading/ai-agent-study-trading-infra/src/test/java/denny/ai/agent/trading/infra/tools/TradingToolCallbacksTest.java`
- 修改：`ai-agent-study-trading/ai-agent-study-trading-domain/src/test/java/denny/ai/agent/trading/domain/prompt/TradingPromptRendererTest.java`

- [ ] **步骤 1：添加工具 description 约束测试**

在 `TradingToolCallbacksTest` 添加测试，读取 `searchStockByNameCallback().getToolDefinition().description()`，断言包含“仅用于交易启动前的标的解析”“进入 Trading Run 后禁止调用”，并且不包含“必须调用此工具”。

- [ ] **步骤 2：添加共享目标锁定测试**

在 `TradingPromptRendererTest` 添加测试，调用 `renderer.renderTargetContext(target())`，断言结果包含“当前标的已完成解析并锁定”“禁止调用 `search_stock_by_name`”和“不得重新解析、替换或切换本次分析标的”。

- [ ] **步骤 3：运行两个目标测试并确认失败**

运行：

```powershell
mvn -pl ai-agent-study-trading/ai-agent-study-trading-domain,ai-agent-study-trading/ai-agent-study-trading-infra -am -Dtest=TradingPromptRendererTest,TradingToolCallbacksTest -Dsurefire.failIfNoSpecifiedTests=false test
```

预期：新增断言失败，证明现有文案尚未提供软约束。

### 任务 2：实现工具阶段说明与目标锁定说明

| 任务 | status |
|------|------|
| 任务 2：实现工具阶段说明与目标锁定说明 | pass |

**文件：**
- 修改：`ai-agent-study-trading/ai-agent-study-trading-infra/src/main/java/denny/ai/agent/trading/infra/tools/TradingToolCallbacks.java`
- 修改：`ai-agent-study-trading/ai-agent-study-trading-domain/src/main/java/denny/ai/agent/trading/domain/prompt/TradingPromptRenderer.java`

- [ ] **步骤 1：收紧搜索工具 description**

将 description 改为：仅在 Trading Run 启动前、没有明确股票代码且没有已解析目标时调用；已有股票代码、TS 代码、`TargetContext` 或已进入 Trading Run 时禁止调用；不得重新解析或切换已锁定标的。

- [ ] **步骤 2：扩展共享目标上下文**

在 `renderTargetContext()` 的标的信息之后加入三条规则：标的已经解析并锁定；禁止调用 `search_stock_by_name`；不得重新解析、替换或切换标的。

- [ ] **步骤 3：运行目标测试并确认通过**

运行任务 1 的 Maven 命令。

预期：`TradingPromptRendererTest` 和 `TradingToolCallbacksTest` 全部通过。

### 任务 3：回归验证并提交

| 任务 | status |
|------|------|
| 任务 3：回归验证并提交 | pass |

**文件：**
- 验证：`ai-agent-study-trading/ai-agent-study-trading-domain`
- 验证：`ai-agent-study-trading/ai-agent-study-trading-infra`

- [ ] **步骤 1：运行两个模块的完整测试**

```powershell
mvn -pl ai-agent-study-trading/ai-agent-study-trading-domain,ai-agent-study-trading/ai-agent-study-trading-infra -am -Dsurefire.failIfNoSpecifiedTests=false test
```

预期：构建成功，现有 Run 外搜索和 Run 内拒绝测试继续通过。

- [ ] **步骤 2：检查差异质量**

运行 `git diff --check`，并确认差异中没有 clientId 过滤、Provider 行为修改或异常吞噬。

- [ ] **步骤 3：提交本次实现**

只暂存本计划列出的实现与测试文件以及本计划文档，提交信息使用 `fix: constrain stock search tool usage`。
