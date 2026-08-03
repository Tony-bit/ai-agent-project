# A股股票名称补全与二次澄清设计

## 状态

- 初始日期：2026-07-16
- 修订日期：2026-08-03
- 状态：设计已确认，待实施计划
- 对应 Story：`docs/trading-agent/2026-07-16-stock-name-completion-story.md`

## 已确认决策

1. 使用 Tushare `stock_basic(list_status=L)` 全量获取当前上市股票。
2. 权威缓存记录只保存股票名称和标准交易所代码，例如 `北方华创 -> 002371.SZ`。
3. 使用本地持久化快照和 JVM 内存不可变索引；刷新时原子替换，不拆成约 6,000 个独立 Caffeine 条目。
4. 支持精确、别名、前缀、后缀和任意位置连续子串匹配。
5. 精确名称、精确别名或唯一子串候选自动补全；多个普通子串候选必须向用户追问。
6. 每天定时刷新，启动时先加载快照再刷新；刷新失败保留上一版。
7. 第一版不支持拼音、错别字、编辑距离、向量检索或退市股票。
8. 退役 `clientId=6001` 的 Trading 二次意图识别，`clientId=3201` 是唯一意图识别入口。
9. `3201` 只输出用户原文中的 `stockMention`；权威股票身份由固定 Java 工作流解析，LLM 不得生成 ticker。
10. 多候选或无候选时持久化 `PendingStockAction`，下一轮仍由 `IntentRouteNode` 处理选择、替换、取消或新意图。
11. 本期只支持 A 股单股票、单任务，不处理多股票、多任务恢复、港股或美股。

## 架构

```text
Tushare stock_basic(list_status=L)
  -> StockNameRefreshService
  -> validate(name, tsCode, uniqueness, non-empty)
  -> build immutable StockNameIndex
  -> atomically write local snapshot
  -> atomically publish in-memory index

User query + session history + optional PendingStockAction
  -> IntentRouteNode (clientId=3201)
       -> GENERAL_CHAT: continue general conversation
       -> STOCK_ANALYSIS: emit stockMention and current-query slots
       -> pending answer: emit ANSWER / REPLACE / CANCEL / NEW_INTENT
  -> StockAnalysisPreparationPort
  -> StockSymbolResolver (plain Java workflow)
       -> unique: RESOLVED authoritative StockIdentity
       -> multiple: persist WAITING_SELECTION and ask user
       -> empty: persist WAITING_REPLACEMENT and ask user
  -> RESOLVED only
       -> assemble default StockAnalysisRequestVO
       -> create a new runId
       -> start Trading Agent
```

`StockSymbolResolver` 解析的是低频权威身份 `stockName + targetId`，不是包含价格、PE、市值等动态
字段的 `StockInfoVO`。`getStockInfo()` 不参与意图识别和名称补全；Trading run 建立后仍调用一次，
并把结果放入 Trading 上下文供后续节点复用。

## 路由与模块边界

`IntentRouteNode` 不直接依赖 Tushare、`IStockDataProvider` 或 Trading 领域实现。通用 Domain 定义
窄端口 `StockAnalysisPreparationPort` 和中立输入输出契约，Trading Domain 提供实现。单任务
`STOCK_ANALYSIS` 由 `RoutingResultHandler` 路由到该端口，不再路由到
`tradingIntentRoutingNode`。

`clientId=6001` 的节点、Prompt、ChatMemory、Trading Tools、Skills、配置和路由引用直接删除，
不得保留运行时 fallback。版本回退依赖 Git 和发布系统，不在同一版本中保留两条可启动 Trading
的路径。

## 数据与内存

当前 5,529 条真实响应约 353 KiB。Java 对象、规范化名称、Map 和刷新双缓冲预计峰值
10-24 MB，为本能力预留 32 MB 堆内存。缓存实体保持最小化，只持有 `name` 和 `tsCode`；
6 位 ticker 从 `tsCode` 派生。

## 匹配与安全边界

候选召回不依赖 Tushare `name` 参数的模糊行为。标准名称精确匹配优先，其次是受配置约束的
别名，再进行任意位置连续子串扫描。前缀和后缀属于同一 contains 匹配层级。

多候选时不允许服务端直接取第一条。最终 ticker 必须属于索引候选，否则按解析失败处理。LLM
只负责判断回答与话题关系，不负责生成证券代码。

索引的权威输出为：

```text
ResolvedStockIdentity
  targetId: 603259.SH
  stockName: 药明康德
  indexVersion: 20260803T033000Z-5529
```

`StockSlot.stockCode` 在迁移期保留兼容读取；新契约使用 `stockMention` 保存用户原文指代，并用
可选 `explicitTicker` 表示用户明确输入的代码。任何名称或简称都必须经过索引解析，明确 ticker
也必须经过索引反查校验后才能建立 `TargetContext`。

## 二次澄清

### 持久化契约

`PendingStockAction` 使用 Redis 按 `userId + sessionId` 保存，每个会话最多一个活动记录。业务
有效期默认 30 分钟，Redis 记录保留 24 小时用于识别逻辑过期和重复请求。它不能只放在每轮新建
的 `DynamicContext` 中，也不能只依赖聊天历史恢复。

```text
PendingStockAction
  pendingId
  version
  userId
  sessionId
  status: WAITING_SELECTION | WAITING_REPLACEMENT | STARTING | STARTED | CANCELLED | EXPIRED | FAILED
  originalQuery
  stockMention
  analysisType
  analysisDepth
  timeRange
  candidates: [{targetId, stockName, candidateRef}]
  indexVersion
  attemptCount
  createdAt
  expiresAt
  resolvedTargetId
  runId
```

Repository 必须支持基于 `version` 的 CAS 更新。同一 Pending 版本只允许预留一个 runId：候选
解析成功后先以 CAS 写入 `resolvedTargetId + runId` 并转为 `STARTING`，再用该 runId 幂等启动
Trading，成功后转为 `STARTED`。重复请求复用同一 runId；`STARTING` 状态允许恢复启动，不能
生成新 runId。

### 第一轮解析

- 唯一候选：返回 `RESOLVED`，不创建 Pending，使用本次默认配置创建新 run。
- 多候选：保存 `WAITING_SELECTION`，通过 SSE 返回稳定编号、股票全名和 `targetId`，本轮结束。
- 无候选：保存 `WAITING_REPLACEMENT`，要求用户提供完整名称或 6 位代码，本轮结束。
- 索引不可用：返回 `INDEX_UNAVAILABLE`，不得保存成 `NOT_FOUND`，不得误导用户修改股票名。
- 非 A 股：返回 `UNSUPPORTED_MARKET`，不得进入重复澄清。

`AMBIGUOUS` 或 `NOT_FOUND` 通过现有意图澄清 SSE 通道返回前端，并写入 Root 可持久化的最终回复
字段；处理链在本轮明确终止，不创建 `TargetContext` 或 runId。下一轮的可靠恢复以 Redis Pending
为准，主会话文本只用于用户可见的对话连续性。

### 下一轮处理

所有前端 Query 仍先进入 `IntentRouteNode`。节点在调用 3201 前加载 Pending，并先执行确定性的
`PendingStockAnswerMatcher`：

- 候选编号、精确全名、6 位代码或标准 `targetId`：直接匹配候选，输出 `ANSWER`。
- 明确取消表达：输出 `CANCEL`，终结 Pending，继续处理当前新意图。
- 新的股票名称：输出 `REPLACE`，替换 `stockMention` 后重新运行 Resolver。
- 无法确定的自然语言：把脱敏后的 Pending 摘要显式传给 3201，由其输出
  `ANSWER | REPLACE | CANCEL | NEW_INTENT`；任何 ticker 仍需 Java 候选校验。
- 明确的新通用意图：输出 `NEW_INTENT`，终结 Pending，正常进入通用会话。

业务有效期到达后以 CAS 转为 `EXPIRED` 并返回明确提示，要求用户重新发起股票分析。澄清过程中
只继承当前 Pending 保存的显式分析槽位，不继承上一 Trading run 的分析师、轮次、分析深度或
ticker。

现有 `analysisDepth` 澄清策略不在本 Story 中修改。只有 3201 已输出可执行的单任务
`STOCK_ANALYSIS` 后，才进入股票身份补全；若 3201 先要求补充分析深度，则沿用现有流程完成该
澄清，再把合并后的 `stockMention` 和本次显式槽位交给 Preparation。新 Run 始终不继承上一 Run
的深度或分析师配置。

### 执行示例

```text
上一轮药明康德 Trading run 完成
  -> 用户：那帮我分析一下兆易创新呢
  -> 3201：沿用现有 analysisDepth 规则，询问快速了解还是完整投资分析
  -> 用户：完整投资分析
  -> 3201：STOCK_ANALYSIS, stockMention=兆易创新
  -> Resolver：唯一匹配 603986.SH
  -> 使用当前请求明确选择的深度和其余默认配置创建新 runId
  -> Trading 初始化时调用一次 getStockInfo(603986.SH)
  -> 最终结果写回主会话，内部工具和节点消息不写入主会话
```

```text
用户：分析一下平安
  -> Resolver：平安银行、中国平安
  -> 保存 WAITING_SELECTION，返回候选
用户：第二个
  -> IntentRouteNode 加载 Pending
  -> Java matcher 选择中国平安 601318.SH
  -> CAS 消费 Pending，创建新 runId 并启动 Trading
```

## 刷新与失败语义

默认每天 03:30（Asia/Shanghai）刷新。启动先读快照；有快照时立即可查询并后台刷新，无快照时
执行首次加载。只有完整拉取、校验和快照写入全部成功后才发布新索引。任何失败均不清空旧索引。

无索引时返回 `INDEX_UNAVAILABLE`；使用旧索引时结果携带 `indexVersion/refreshedAt/stale`。Pending
保存候选快照和 `indexVersion`，索引刷新后仍按原候选解释“第二个”。

## 测试边界

原索引测试覆盖精确、前缀、后缀、中间子串、别名、唯一候选、多候选、无结果、候选外 ticker
拒绝、快照恢复、定时刷新、非法新数据拒绝、并发读取与原子替换。

新增端到端测试覆盖：

- 药明康德分析完成后输入“那帮我分析一下兆易创新呢”，必须重新执行现有深度澄清；回答后创建新 run，且全程不调用 6001。
- “药明”唯一补全为“药明康德 / 603259.SH”。
- “平安”返回稳定候选，下一轮支持“第二个”、全名、6 位代码和标准 `targetId`。
- 多候选后支持替换股票名、取消、切换到通用会话。
- 无结果后支持提供新名称或代码并重新解析。
- Pending TTL、CAS 冲突、重复提交和同一 Pending 只创建一个 run。
- 索引刷新后仍按 Pending 保存的候选快照解释“第二个”。
- `INDEX_UNAVAILABLE`、`NOT_FOUND` 和 `UNSUPPORTED_MARKET` 返回不同用户语义。
- 主会话只保存用户消息和最终 Trading 结果，不保存内部工具、节点消息或上一 run 配置。
- 6001 停流后，3201 不暴露 Trading 数据工具；直接 Trading API 行为保持不变。

## 详细规格

字段、配置、错误处理、验收标准和实施任务以对应 Story 为准。
