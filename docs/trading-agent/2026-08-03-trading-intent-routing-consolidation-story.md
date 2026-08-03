# Story: Trading 意图路由职责合并

| 字段 | 内容 |
|------|------|
| 创建日期 | 2026-08-03 |
| 状态 | pending |
| 优先级 | P0 |
| 关联设计 | `docs/superpowers/plans/2026-08-03-trading-intent-routing-consolidation-design.md` |
| 关联测试 | `docs/superpowers/test/2026-08-03-trading-intent-routing-consolidation-test.md` |

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
- 不兼容实验性 `SPLIT` 路由的股票分析；本 Story 仅支持并验收 `UNIFIED` 模式。
- 不实现请求去重或幂等键；重复请求创建新 Trading run，但保留现有通用会话与共享数据复用。

## 5. 验收流程

```text
用户：对药明康德进行完整投资分析
  -> 3201 识别 STOCK_ANALYSIS
  -> search_stock_by_name("药明康德")
  -> 唯一结果 603259
  -> StockSlot(stockName=药明康德, stockCode=603259)
  -> TradingRequestNode 校验并构造 StockAnalysisRequestVO
  -> TargetContextFactory 校验权威 stockCode + stockName
  -> 创建 TargetContext/runId
  -> TradingStarter 接收已验证 TargetContext 并执行 populateStockInfo()
  -> Trading pipeline
```

```text
用户：分析药明康德的投资价值，同时总结今天的科技新闻
  -> 3201 输出 multiTask=true，任务列表包含 STOCK_ANALYSIS
  -> RoutingResultHandler 拒绝整轮任务
  -> 登记 routingTerminalResponse、routingTerminalKind=CLARIFICATION 和 clarificationPrompt
  -> IntentRoutingNode 发送 clarification + complete 业务事件
  -> 返回“股票分析暂不支持与其他任务同时执行，请单独发起股票分析”
  -> 不执行任何子任务，不创建 runId
```

## 6. 数据契约

`StockSlot` 新增 `stockName`，保留已有字段：

```json
{
  "stockCode": "603259",
  "stockName": "药明康德",
  "stockQueryType": "ALL",
  "timeRange": null
}
```

名称输入要求 ticker 来自唯一工具结果。`TradingRequestNode` 将 `stockName` 透传到
`StockAnalysisRequestVO`，`TargetContextFactory` 对请求中的 ticker 和 `stockName` 执行权威校验。
代码输入允许 `stockName` 为空，只要求通过 A 股代码格式和后续权威 ticker 校验。

路由身份只使用 `stockCode`。3201 不再输出 `exchange`；Java `StockSlot.exchange` 为兼容旧请求暂时
保留并标记废弃，但 `TradingRequestNode` 始终忽略该字段。预检成功后将请求 ticker 改写为权威
`TargetContext.targetId`。`StockInfoVO.exchange` 等行情展示字段继续保留。

## 7. 代码改造

| 范围 | 改造内容 |
|------|----------|
| 3201 Prompt | 合并股票名称解析规则，禁止凭记忆生成 ticker 和执行完整分析 |
| 3201 Schema | 增加 `stockName` 并更新 Validator、Few-Shot 和评测契约 |
| 代码规范化 | `stockCode` 是唯一候选代码；废弃并忽略路由槽位 `exchange` |
| 工具边界 | 新增 `Map<String, List<String>> allowedToolsByClient`，只对白名单内 Client 装配指定 Trading Tools |
| 类型映射 | 从 6001 提取无状态 `AnalysisTypeMapper`，保留现有分析类型映射语义 |
| Java 下游 | `TradingRequestNode` 校验槽位并写入 `stockName` 和映射后的 `selectedAnalysts` |
| 路由 | `RoutingResultHandler` 从 `tradingIntentRoutingNode` 改为 `tradingRequestNode` |
| 多任务门禁 | 多任务包含 `STOCK_ANALYSIS` 时整轮拒绝；普通多任务保持原行为 |
| SSE 所有权 | 下游登记终止响应，`IntentRoutingNode` 发送业务事件，`AutoAgentExecuteStrategy` 关闭 emitter |
| 6001 | 删除节点、Java Prompt、Service、ChatMemory、Client 配置、数据库关系和专属测试；保留数据库 `prompt_id=6001` |
| 数据库 DDL | 无结构变更；不新增一次性字段、索引或数据表 |
| 数据库 DML | 新增 `V2030` 类型化删除 Client 6001，保留同值 Prompt 和共享配置 |
| 身份预检 | `TradingRequestNode` 调用 `TargetContextFactory`，失败时不进入 Trading |
| Trading | 新增接收已验证 `TargetContext` 的入口；保持 `populateStockInfo()` 与 pipeline 行为 |
| SSE | 槽位非法时返回现有澄清事件并结束本轮 |
| 路由模式 | 仅改造并验收 `UNIFIED`；`SPLIT` 保留为非生产对比实验 |
| 重复执行 | 每个前端请求创建独立 Trading run；通用会话历史和共享原始数据缓存继续复用 |

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
| AC-020 | 终止响应 | 下游登记 `routingTerminalResponse` 和 `routingTerminalKind` |
| AC-021 | SSE 单一所有者 | `IntentRoutingNode` 按 kind 发送一套终止协议，外层只关闭一次 emitter |
| AC-022 | 会话持久化 | 路由终止文本作为本轮助手回复持久化，可供下一轮会话读取 |
| AC-023 | 类型映射迁移 | `ALL`、四个单类型及逗号组合与原 6001 映射结果一致 |
| AC-024 | 类型默认值 | 空值或全部非法的类型使用 Trading 当前默认全部分析师 |
| AC-025 | 类型契约 | 3201 只输出标准类型码，直接 Trading API 行为不变 |
| AC-026 | 身份预检 | 股票不存在、Provider 失败或身份异常时均不调用 `TradingStarter` |
| AC-027 | 失败分类 | 未找到返回澄清；Provider 和数据完整性失败返回错误，且保留原始 cause |
| AC-028 | 单次查询 | AutoAgent 成功路径只查询一次权威身份并传递同一 `TargetContext` |
| AC-029 | 直接入口兼容 | 直接 Trading API 仍通过原入口在 `TradingStarter` 内创建 `TargetContext` |
| AC-030 | Flyway 兼容 | 不修改已发布迁移；新旧数据库执行到 `V2030` 后状态一致 |
| AC-031 | 精确删除 | 仅删除 Client 6001 的 Flow、类型化关系和 Client 主记录 |
| AC-032 | Prompt 兼容 | `prompt_id=6001` 及 `client 3001 -> prompt 6001` 关系保持不变 |
| AC-033 | 共享资源 | 6001 引用过的 model、advisor、prompt、tool 和历史记录不被删除 |
| AC-034 | 路由模式 | `UNIFIED` 通过全部验收；`SPLIT` 股票分析不作为兼容或发布门禁 |
| AC-035 | 唯一代码来源 | `stockCode` 冲突时忽略 `StockSlot.exchange`，最终以权威 `targetId` 为准 |
| AC-036 | 旧槽位兼容 | 旧请求携带 `exchange` 时可正常反序列化，但该值不参与执行 |
| AC-037 | 展示字段兼容 | `StockInfoVO.exchange` 和 `StockSearchResultVO.exchange` 行为不变 |
| AC-038 | 重复分析 | 相同 session、Query 和股票连续请求两次时完整执行两次并产生不同 runId |
| AC-039 | Trading 隔离 | 新 run 不注入上一 run 的 Trading ChatMemory、中间结果或最终决策 |
| AC-040 | 会话复用 | 3201 和 GENERAL_CHAT 继续读取同一 session 的主会话历史 |
| AC-041 | 数据缓存兼容 | 现有与 run 无关的股票原始数据缓存 Key 和复用行为不变 |

## 9. 测试场景

- 完整名称、明确代码、名称带首尾空格。
- 零结果、多结果、工具异常。
- LLM 返回候选外 ticker、名称与权威记录不一致或非法代码。
- 直接代码输入未携带名称时，使用权威身份成功创建 `TargetContext`。
- 六位代码、标准 `ts_code`、小写后缀及首尾空格正确规范化。
- `exchange` 缺失、合法或与 `stockCode` 冲突时均以 `stockCode` 为准；结果 ticker 为权威
  `TargetContext.targetId`。
- 五位/七位代码、非数字代码、`HK/US` 后缀及代码自身错误交易所后缀被拒绝。
- 权威身份查询返回空列表时发送澄清，不创建 runId，不调用 `TradingStarter`。
- 权威身份 Provider 超时、鉴权和网络异常时发送错误，不创建 runId，并保留异常 cause。
- 权威身份返回多条、非法代码、空名称或请求身份不一致时发送错误并触发告警。
- Provider 或数据完整性错误只发送一个已完成的 `error` 事件，不追加 `clarification` 或 `complete`。
- 成功预检后 `TradingStarter` 使用传入的同一 `TargetContext`，不重复调用身份 Provider。
- 药明康德完成后，经过现有 analysisDepth 澄清再分析兆易创新。
- 同一 session 连续两次提交完全相同的药明康德分析请求，两次 pipeline 均执行且 runId 不同。
- 前端重复提交被视为两个独立请求，不增加请求 ID、幂等锁或历史结论缓存。
- 第二次 Trading run 不读取第一次 run 的角色 ChatMemory、中间报告和最终决策。
- Trading 完成后发起“刚才为什么建议持有”等通用追问，3201/GENERAL_CHAT 可从主会话历史理解上文。
- 两次 run 对相同股票数据的读取仍可命中现有共享原始数据缓存，不把 runId 加入该缓存 Key。
- 股票分析与通用问答、PE 或巡检组成多任务时整轮拒绝，所有子任务均未执行。
- 两个及以上股票分析组成多任务时整轮拒绝，不创建任何 runId。
- 不包含股票分析的现有多任务正常执行和汇总。
- 非法或空股票槽位发送一次 `summary/clarification` 和一次 `complete`，不调用 `TradingStarter`。
- 股票多任务门禁发送一次相同协议的终止事件，不执行任何子任务。
- SSE 发送失败或客户端断开时不重发，外层清理流程正常结束。
- 路由终止响应写入会话记录，下一轮能够从历史读取。
- `ALL`、空值分别使用全部默认分析师。
- `FUNDAMENTAL`、`TECHNICAL`、`SENTIMENT`、`NEWS` 分别只启用对应分析师。
- 合法逗号组合、大小写和首尾空格正确归一化；混合非法项时保留合法子集。
- 全部非法的分析类型降级为默认全部分析师并记录警告。
- 直接 Trading API 显式指定分析师和不指定分析师的行为均保持不变。
- 在包含 Trading Client 6001 和提示词 6001 的既有库执行 `V2030`，只清理 Client 关系。
- 从空库依次执行全部 Flyway 迁移，最终不存在 Trading Client 6001，但提示词优化功能仍可用。
- 重复执行等价 DML 不报错、不误删其它记录；3201 和 6002-6013 配置数量保持不变。
- 迁移 SQL 静态测试禁止出现 `DELETE FROM ai_client_system_prompt` 和无类型的 ID 删除谓词。
- 6001 Bean、Client 配置、Skills 白名单和 ChatMemory Key 不再存在。
- 验证缺失 Client、空列表、重复工具名和未知工具名配置。
- 验证 3201 无法调用行情、新闻、技术指标、基本面和 `get_stock_info`。
- 验证 6002-6013 工具集合兼容，MCP 与通用工具集合不变。
- Trading 初始化仍获取并复用 `StockInfoVO`。
- 非股票路由和直接入口完整回归。
- 所有股票分析验收在 `intent.routing.mode=UNIFIED` 下执行，不增加 `SPLIT` 专属股票回归用例。

## 10. 实施任务

| Task | 内容 | 状态 |
|------|------|------|
| Task 1 | 更新 3201 Prompt、Schema、槽位模型和 Validator | pending |
| Task 2 | 实现仅管理 Trading Tools 的 `allowedToolsByClient` 构建期白名单及配置校验 | pending |
| Task 3 | 提取类型映射，实现 `TradingRequestNode` 身份预检、异常分类及请求构造 | pending |
| Task 4 | 切换下游 Bean，增加股票多任务门禁及统一路由终止响应 | pending |
| Task 5 | 删除 6001 代码与配置，新增 `V2030` 精确 DML 迁移及数据库兼容测试 | pending |
| Task 6 | 补齐单元、集成和连续两次 Trading 回归测试 | pending |
| Task 7 | 验证 GENERAL_CHAT、PE、巡检、直接 Trading API 和分析节点回归 | pending |

## 11. 后续 Story

不完整股票名称、模糊匹配、多候选、二次澄清和股票信息快照缓存统一进入
`docs/trading-agent/2026-07-16-stock-name-completion-story.md`，不在本 Story 中顺带实现。
