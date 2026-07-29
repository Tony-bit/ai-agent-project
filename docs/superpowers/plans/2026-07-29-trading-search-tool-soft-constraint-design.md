# Trading 搜索工具软约束设计

## 背景

`search_stock_by_name` 被注册为 ChatClient 默认工具，因此 Trading 子节点也能看到该工具。现有工具描述包含“必须调用”的强指令，模型可能仅因 Trading Prompt 中出现股票名称就发起搜索。回调随后因请求已经绑定 `TargetContext` 而拒绝执行，导致流式调用失败。

## 目标

- 保持现有工具注册方式，不增加 clientId 白名单或请求级工具过滤。
- 明确 `search_stock_by_name` 只用于 Trading Run 启动前的标的解析。
- 告知所有 Trading 子节点当前标的已经解析并锁定，不得重新搜索或切换标的。
- 保留 Run 内搜索的身份边界异常，作为执行时兜底。

## 非目标

- 不保证模型绝对不会误调用工具；提示约束只能降低概率。
- 不修改 `IntentRoutingPrompt` 中路由阶段必须解析股票名称的规则。
- 不改变 Provider、ToolContext 或 Trading Pipeline 的数据流。

## 方案

### 工具描述

修改 `TradingToolCallbacks.searchStockByNameCallback()` 的 description：

- 删除对所有场景都生效的“必须调用”表达。
- 明确仅在交易启动前、尚未得到明确股票代码且没有已解析目标时调用。
- 明确存在股票代码、TS 代码、`TargetContext` 或已进入 Trading Run 时禁止调用。
- 明确不得通过该工具重新解析或切换已锁定标的。

### Trading 目标提示

在 `TradingPromptRenderer.renderTargetContext()` 生成的共享目标上下文中增加约束：

- 当前股票代码和名称已经完成解析并锁定。
- 禁止调用 `search_stock_by_name`。
- 禁止重新解析、替换或切换本次分析标的。

该目标上下文由 Trading 角色 Prompt 共用，避免逐个修改分析师节点。

### 错误处理

`search_stock_by_name` 在 ToolContext 包含 `TargetContext` 时继续抛出 `IDENTITY_BOUNDARY_VIOLATION`。软约束负责减少误调用，执行校验继续防止越界搜索。

## 测试

- 断言搜索工具 description 包含启动前限定和 Run 内禁止规则，不再包含原来的全局“必须调用此工具”。
- 断言共享目标上下文包含标的锁定和禁止调用 `search_stock_by_name` 的规则。
- 保留现有 Run 外搜索成功、Run 内搜索拒绝测试。

## 风险

模型工具选择具有非确定性。因为工具仍暴露给 Trading 子节点，软约束不能提供与工具过滤相同的确定性隔离；若线上仍出现误调用，需要升级为工具暴露白名单方案。
