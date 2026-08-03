# Story: Trading 意图路由职责合并

| 字段 | 内容 |
|------|------|
| 创建日期 | 2026-08-03 |
| 状态 | pending |
| 优先级 | P0 |
| 关联设计 | `docs/superpowers/plans/2026-08-03-trading-intent-routing-consolidation-design.md` |

## 1. 用户故事

作为 AutoAgent 用户，我希望股票分析只执行一次意图识别，避免旧股票分析上下文污染下一次请求，
并能够使用完整 A 股名称或明确代码启动 TradingAgent。

作为系统维护者，我希望 3201 统一负责意图和股票槽位识别，6001 被删除，Trading 下游只接收已
校验的结构化请求，同时保持现有 `StockInfoVO` 初始化和 Trading pipeline 不变。

## 2. 当前问题

当前单任务股票请求先经过 3201，再进入 6001。6001 拥有独立 ChatMemory 和完整 Trading Tools。
连续分析药明康德和兆易创新时，6001 将第二次请求直接执行成完整分析报告，`parseResponse()` 将
Markdown 当 JSON 解析并抛出 `illegal input, char -`，随后错误降级为 `UNKNOWN`。

## 3. 目标

- 删除 6001 节点和所有运行资产。
- 3201 成为唯一意图路由 Client。
- A 股名称通过现有 Skills/`search_stock_by_name` 解析 ticker。
- 3201 输出可由 Java 校验的完整股票槽位。
- 新增无状态 `TradingRequestNode` 构造 `StockAnalysisRequestVO` 并调用 `TradingStarter`。
- 保持 `TradingStarter.populateStockInfo()` 为正式 `StockInfoVO` 来源。

## 4. 非目标

- 不专门实现股票简称或模糊名称，也不额外拦截工具偶然成功解析的简称。
- 不支持候选消歧、Pending 或二次澄清。
- 不改股票缓存、数据表或定时任务。
- 不改 `getStockInfo()` 调用位置。
- 不实现股票分析多任务执行；包含 `STOCK_ANALYSIS` 的多任务整轮拒绝，后续 Story 再兼容。
- 不改普通多任务、直接 Trading API 或其他 Agent 路由。

## 5. 验收流程

```text
用户：对药明康德进行完整投资分析
  -> 3201 识别 STOCK_ANALYSIS
  -> search_stock_by_name("药明康德")
  -> 唯一结果 603259
  -> StockSlot(stockName=药明康德, stockCode=603259)
  -> TradingRequestNode 校验并构造 StockAnalysisRequestVO
  -> TradingStarter 调用 TargetContextFactory 校验权威 stockCode + stockName
  -> 创建 TargetContext/runId
  -> TradingStarter.populateStockInfo()
  -> Trading pipeline
```

```text
用户：分析药明康德的投资价值，同时总结今天的科技新闻
  -> 3201 输出 multiTask=true，任务列表包含 STOCK_ANALYSIS
  -> RoutingResultHandler 拒绝整轮任务
  -> 返回“股票分析暂不支持与其他任务同时执行，请单独发起股票分析”
  -> 不执行任何子任务，不创建 runId
```

## 6. 数据契约

`StockSlot` 新增 `stockName`，保留已有字段：

```json
{
  "stockCode": "603259",
  "stockName": "药明康德",
  "stockQueryType": "综合分析",
  "timeRange": null,
  "exchange": "SH"
}
```

名称输入要求 ticker 来自唯一工具结果。`TradingRequestNode` 将 `stockName` 透传到
`StockAnalysisRequestVO`，`TargetContextFactory` 对请求中的 ticker 和 `stockName` 执行权威校验。
代码输入允许 `stockName` 为空，只要求通过 A 股代码格式和后续权威 ticker 校验。

## 7. 代码改造

| 范围 | 改造内容 |
|------|----------|
| 3201 Prompt | 合并股票名称解析规则，禁止凭记忆生成 ticker 和执行完整分析 |
| 3201 Schema | 增加 `stockName` 并更新 Validator、Few-Shot 和评测契约 |
| 工具边界 | 新增 `Map<String, List<String>> allowedToolsByClient`，只对白名单内 Client 装配指定 Trading Tools |
| Java 下游 | 新增无状态 `TradingRequestNode`，校验槽位并将 `stockName` 写入 `StockAnalysisRequestVO` |
| 路由 | `RoutingResultHandler` 从 `tradingIntentRoutingNode` 改为 `tradingRequestNode` |
| 多任务门禁 | 多任务包含 `STOCK_ANALYSIS` 时整轮拒绝；普通多任务保持原行为 |
| 6001 | 删除节点、Prompt、Service、ChatMemory、配置、数据库关系和专属测试 |
| Trading | `TargetContextFactory` 增加权威名称校验；保持 `populateStockInfo()` 与 pipeline 行为 |
| SSE | 槽位非法时返回现有澄清事件并结束本轮 |

## 8. 验收标准

| 编号 | 验收项 | 标准 |
|------|--------|------|
| AC-001 | 唯一路由 | 单任务股票请求只调用 3201，不调用 6001 |
| AC-002 | 完整名称 | “药明康德”精确解析为 `603259` |
| AC-003 | 明确代码 | 6 位 A 股代码无需名称搜索即可通过 |
| AC-004 | Story 边界 | 不新增简称匹配规则；工具偶然解析成功的简称不额外拦截 |
| AC-005 | ticker 来源 | ticker 只能来自用户代码或唯一工具结果 |
| AC-006 | 权威校验 | 名称输入同时校验 ticker 和 `stockName`；代码输入校验 ticker |
| AC-007 | 请求构造 | `TradingRequestNode` 正确构造并提交 `StockAnalysisRequestVO` |
| AC-008 | StockInfo 来源 | `StockInfoVO` 仍由 `TradingStarter.populateStockInfo()` 生成并写入上下文 |
| AC-009 | 连续分析 | 连续分析两只完整名称股票不会读取 6001 历史，第二次创建新 run |
| AC-010 | 工具隔离 | 3201 路由期间不调用 `get_stock_info` 或分析类工具 |
| AC-011 | 其他路由 | GENERAL_CHAT、PE、巡检和 analysisDepth 行为不变 |
| AC-012 | 外部兼容 | 直接 Trading API 和 6002-6013 行为不变 |
| AC-013 | 默认拒绝 | Map 中缺少 Client 或配置空列表时不装配任何 Trading Tool |
| AC-014 | 配置校验 | 配置不存在的 Trading Tool 名称时应用启动失败 |
| AC-015 | 3201 工具集合 | 3201 最终 Trading Tool 集合严格等于 `read_skill` 和 `search_stock_by_name` |
| AC-016 | 分析节点兼容 | 6002-6013 的 Trading Tool 集合与改造前一致 |
| AC-017 | 非 Trading 能力 | MCP、会话记忆和通用工具装配不受白名单影响 |
| AC-018 | 多任务门禁 | 包含 `STOCK_ANALYSIS` 的多任务不执行任何子任务，也不创建 Trading run |
| AC-019 | 普通多任务 | 不包含 `STOCK_ANALYSIS` 的多任务行为保持不变 |

## 9. 测试场景

- 完整名称、明确代码、名称带首尾空格。
- 零结果、多结果、工具异常。
- LLM 返回候选外 ticker、名称与权威记录不一致或非法代码。
- 直接代码输入未携带名称时，使用权威身份成功创建 `TargetContext`。
- 药明康德完成后，经过现有 analysisDepth 澄清再分析兆易创新。
- 股票分析与通用问答、PE 或巡检组成多任务时整轮拒绝，所有子任务均未执行。
- 两个及以上股票分析组成多任务时整轮拒绝，不创建任何 runId。
- 不包含股票分析的现有多任务正常执行和汇总。
- 6001 Bean、Client 配置、Skills 白名单和 ChatMemory Key 不再存在。
- 验证缺失 Client、空列表、重复工具名和未知工具名配置。
- 验证 3201 无法调用行情、新闻、技术指标、基本面和 `get_stock_info`。
- 验证 6002-6013 工具集合兼容，MCP 与通用工具集合不变。
- Trading 初始化仍获取并复用 `StockInfoVO`。
- 非股票路由和直接入口完整回归。

## 10. 实施任务

| Task | 内容 | 状态 |
|------|------|------|
| Task 1 | 更新 3201 Prompt、Schema、槽位模型和 Validator | pending |
| Task 2 | 实现仅管理 Trading Tools 的 `allowedToolsByClient` 构建期白名单及配置校验 | pending |
| Task 3 | 实现 `TradingRequestNode`、请求构造映射及 `TargetContextFactory` 名称校验 | pending |
| Task 4 | 切换 `RoutingResultHandler` 下游 Bean，并增加股票分析多任务门禁 | pending |
| Task 5 | 删除 6001 代码、配置、数据库关系和测试资产 | pending |
| Task 6 | 补齐单元、集成和连续两次 Trading 回归测试 | pending |
| Task 7 | 验证 GENERAL_CHAT、PE、巡检、直接 Trading API 和分析节点回归 | pending |

## 11. 后续 Story

不完整股票名称、模糊匹配、多候选、二次澄清和股票信息快照缓存统一进入
`docs/trading-agent/2026-07-16-stock-name-completion-story.md`，不在本 Story 中顺带实现。
