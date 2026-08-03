# Story: A股股票名称补全与定时缓存

| 字段 | 内容 |
|------|------|
| 创建日期 | 2026-07-16 |
| 修订日期 | 2026-08-03 |
| 状态 | pending |
| 优先级 | P1 |
| 数据源 | Tushare `stock_basic` |
| 关联设计 | `docs/superpowers/plans/2026-07-16-stock-name-completion-design.md` |

---

## 1. 背景与问题

用户询问股票时经常使用不完整名称或市场简称，例如“华创怎么样”。当前
`search_stock_by_name` 直接把用户名称传给 Tushare `stock_basic.name`，但该参数只可靠支持
精确名称查询，不提供可依赖的模糊匹配契约。真实验证中，“药明康德”能够返回结果，
“贵州”返回空结果。

当前实现还存在两个风险：

- `TradingIntentRoutingService.searchTickerByName()` 会取搜索结果第一项，可能在多候选时分析错股票。
- 现有模糊匹配集成测试允许空结果通过，不能验证模糊匹配能力。

Tushare `stock_basic(list_status=L)` 可以一次返回全部当前上市 A 股。2026-07-16 实测返回
5,529 条，包含名称和标准交易所代码的响应约 353 KiB，适合在本地维护完整索引。

## 2. 用户故事

作为 Trading Agent 用户，我希望使用股票全名、名称前缀、名称后缀或连续名称片段提问时，
Agent 能补全对应股票；当多个候选难以区分时，Agent 应列出候选让我确认，而不是静默选择第一项。

作为系统维护者，我希望股票名称索引按计划从 Tushare 全量刷新，并在 Tushare 临时不可用或
服务重启时继续使用最近一次成功快照。

## 3. 目标

- 全量拉取当前上市股票的 `name` 和 `ts_code`。
- 在内存中维护不可变的股票名称索引，并原子替换完整索引。
- 将最近一次成功数据保存为本地持久化快照。
- 支持精确名称、前缀、后缀和任意位置连续子串召回。
- 前缀和后缀具有相同基础权重，不假设用户更偏好其中一种表达。
- 精确名称、精确别名或唯一子串候选允许自动补全；多个普通子串候选默认要求用户确认。
- Agent 只能使用本地索引返回的 `ts_code`，不得凭记忆生成股票代码。
- 每日定时刷新，并支持应用启动时加载快照和后台刷新。
- 退役 `clientId=6001` 的二次 LLM 意图识别，由 `clientId=3201` 统一识别意图和提取 `stockMention`。
- 使用固定 Java 工作流把 `stockMention` 解析为权威股票身份。
- 多候选或无候选时持久化 Pending，并支持下一轮选择、替换、取消或切换新意图。
- 每次解析成功都创建新的 Trading run，不继承上一 run 的分析配置。

## 4. 非目标

- 第一版不支持拼音首字母、编辑距离、错别字纠正或语义向量检索。
- 股票名称索引不引入 Elasticsearch、Redis、数据库表或分布式缓存；二次澄清 Pending 复用现有 Redis。
- 不缓存行情、财务或新闻数据；本 Story 只处理股票名称和标准交易所代码。
- 不索引退市和暂停上市股票，默认只查询 `list_status=L`。
- 不使用市值或模型常识作为股票代码来源。
- 本期不支持多股票或多任务中的名称澄清与恢复。
- 本期不改变独立 `/trading/analysis` API 的代码型输入契约。
- 本期不把价格、PE、市值等动态 `StockInfoVO` 字段放入名称索引。

## 5. 数据契约

缓存中的权威记录只包含：

```json
{
  "name": "北方华创",
  "tsCode": "002371.SZ"
}
```

约束：

- `name` 为 Tushare 当前股票名称，去除首尾空白后不能为空。
- `tsCode` 必须匹配 `^[0-9]{6}\\.(SH|SZ|BJ)$`。
- 交易流程需要 6 位 ticker 时，从 `tsCode` 的前 6 位派生，不维护第二份权威代码。
- 同一 `tsCode` 只保留一条记录；刷新数据存在重复或非法记录时拒绝发布新索引。

## 6. 缓存与刷新设计

### 6.1 内存索引

使用单个不可变 `StockNameIndex`，通过 `AtomicReference` 或等价原子引用发布：

```text
StockNameIndex
  records: List<StockNameRecord>
  exactNameMap: Map<normalizedName, StockNameRecord>
  aliasMap: Map<normalizedAlias, tsCode>  (可配置的常用简称)
  refreshedAt: Instant
```

不把每只股票作为独立 Caffeine 条目。一次查询最多扫描约 6,000 个标准化名称，内存和延迟均可控。
单份索引预计占用 5-12 MB；刷新期间新旧索引并存，给该能力预留 32 MB JVM 堆预算。

### 6.2 本地快照

- 默认路径：`./data/trading/stock-name-index.json`，允许通过配置覆盖。
- 启动时优先读取并校验快照，成功后立即发布内存索引。
- 快照写入采用临时文件加原子移动，避免进程中断留下半文件。
- Tushare 拉取或校验失败时，不覆盖现有内存索引和快照。

### 6.3 定时刷新

- 应用已启用 `@EnableScheduling`，新增独立刷新任务。
- 默认每天 `03:30`，时区 `Asia/Shanghai`，cron 可配置。
- 应用启动完成后触发一次后台刷新；没有可用快照时允许执行一次同步首次加载，失败则保持
  `NOT_READY` 并返回明确错误。
- 刷新流程为：全量拉取 -> 规范化 -> 完整校验 -> 构建新索引 -> 写快照 -> 原子发布。
- 多实例部署时允许每个实例独立刷新，本 Story 不引入分布式锁。

## 7. 模糊匹配设计

### 7.1 规范化

- Unicode NFKC 规范化。
- 去除首尾及名称内部空白。
- 拉丁字母统一大写。
- 不删除 `ST`、`*ST` 或股票名称中的有效中文字符。

### 7.2 候选召回

按以下顺序召回并去重：

1. 标准名称精确匹配。
2. 可配置别名精确匹配。
3. 标准名称包含查询字符串，包含前缀、后缀和中间连续子串。

例如查询“华创”至少召回：

- `北方华创 -> 002371.SZ`（后缀匹配）
- `华创云信 -> 600155.SH`（前缀匹配）

前缀和后缀不因位置获得不同基础权重。本地索引只按匹配证据等级、查询覆盖率、名称和
`ts_code` 进行稳定排序；会话上下文由后续 Agent 消歧阶段使用，不进入缓存索引的排序逻辑。

### 7.3 自动补全与消歧

- 标准名称精确匹配：直接解析。
- 唯一别名或唯一子串候选：直接解析。
- 精确别名同时存在其他普通子串候选：别名候选可直接解析。
- 多个普通子串候选：只有用户原文或会话历史包含能够唯一指向某个候选的额外信息时，Agent
  才能在候选集合内选择；否则返回候选并使用 `ASK_DISAMBIGUATION`。
- 3201 对澄清回答的判断必须能映射到本次候选集合，否则降级为继续澄清，不启动分析。
- 删除任何“直接取第一项”的兜底行为。

## 8. 单任务二次澄清

### 8.1 主链路

```text
Query + sessionId
  -> IntentRouteNode / clientId=3201
  -> STOCK_ANALYSIS + stockMention
  -> StockAnalysisPreparationPort
  -> StockSymbolResolver
       -> RESOLVED: assemble request and create runId
       -> AMBIGUOUS: persist WAITING_SELECTION and stop
       -> NOT_FOUND: persist WAITING_REPLACEMENT and stop
       -> INDEX_UNAVAILABLE / UNSUPPORTED_MARKET: return explicit failure and stop
```

`tradingIntentRoutingNode/clientId=6001` 不再参与主链路。名称补全阶段只生成
`ResolvedStockIdentity(targetId, stockName, indexVersion)`；Trading run 建立后再调用一次
`getStockInfo()` 获取动态分析数据。6001 对应节点、Prompt、ChatMemory、Tools、Skills、配置和
路由引用在本次实现中直接删除，不保留运行时 fallback。

### 8.2 PendingStockAction

Redis 中每个 `userId + sessionId` 最多保存一个活动 Pending。业务有效期默认 30 分钟，记录物理
保留 24 小时，用于识别逻辑过期和重复请求：

```text
pendingId, version, userId, sessionId, status,
originalQuery, stockMention, analysisType, analysisDepth, timeRange,
candidates[{candidateRef,targetId,stockName}], indexVersion,
attemptCount, createdAt, expiresAt, resolvedTargetId, runId
```

状态包括 `WAITING_SELECTION / WAITING_REPLACEMENT / STARTING / STARTED / CANCELLED / EXPIRED /
FAILED`。Repository 使用版本 CAS。解析成功后先预留唯一 runId 并转为 `STARTING`，再以该
runId 幂等启动 Trading；成功后转为 `STARTED`。前端重试和启动恢复必须复用同一 runId。

`AMBIGUOUS` 或 `NOT_FOUND` 使用现有意图澄清 SSE 通道返回前端，并写入 Root 可持久化的最终回复
字段。本轮随即终止，不创建 `TargetContext` 或 runId；下一轮恢复以 Redis Pending 为准。

### 8.3 下一轮回答

下一轮仍首先进入 `IntentRouteNode`。节点加载 Pending 后按以下顺序处理：

1. Java 精确匹配候选编号、全名、6 位代码或 `targetId`。
2. Java 识别明确取消表达。
3. 新股票名作为 `REPLACE` 重新运行 Resolver。
4. 其余表达把 Pending 摘要交给 3201，输出 `ANSWER / REPLACE / CANCEL / NEW_INTENT`。
5. 所有 LLM 选择都必须经过候选集合校验，禁止生成集合外 ticker。

明确的新通用意图终结 Pending 并正常路由。Pending 到期要求重新发起股票分析，不从普通聊天
历史猜测过期候选。澄清只继承当前 Pending 保存的显式槽位，不继承上一 Trading run 配置。

现有 `analysisDepth` 澄清策略保持不变。只有 3201 输出可执行的单任务 `STOCK_ANALYSIS` 后才进入
股票身份补全；如需先澄清分析深度，则完成现有流程后再把合并的 `stockMention` 交给 Resolver。

## 9. 配置建议

```yaml
spring:
  ai:
    trading:
      stock-name-index:
        enabled: true
        refresh-cron: "0 30 3 * * ?"
        refresh-zone: "Asia/Shanghai"
        snapshot-path: "./data/trading/stock-name-index.json"
        max-candidates: 10
        pending-validity: 30m
        pending-retention: 24h
        aliases:
          华创: "002371.SZ"
```

别名值必须在当前股票索引中存在，否则启动或刷新时记录配置错误并忽略该别名。

## 10. 主要代码改造

| 范围 | 改造内容 |
|------|----------|
| API | 新增股票名称记录和候选结果值对象，候选统一携带标准 `tsCode` |
| Tushare | 新增 `stock_basic(list_status=L)` 全量查询方法 |
| 缓存 | 新增不可变 `StockNameIndex`、快照读写和原子发布服务 |
| 调度 | 新增启动刷新和每日 cron 刷新任务 |
| 工具 | `search_stock_by_name` 改为查询本地索引并返回全部有效候选 |
| 路由 | 校验 Agent 返回代码属于候选集合，删除直接取第一候选逻辑 |
| Prompt | 明确候选内选择、高置信度自动补全和多候选追问规则 |
| 测试 | 增加本地匹配、刷新、快照、失败保留旧索引和路由消歧测试 |
| 通用契约 | 新增 `StockAnalysisPreparationPort`、`stockMention` 和 Pending disposition；迁移期兼容旧 `stockCode` |
| Trading 准备层 | 新增固定 Java `TradingRequestPreparationNode` 和 `StockSymbolResolver` |
| Pending | 新增 Redis Repository、TTL、CAS、确定性回答匹配和幂等启动 |
| 6001 删除 | 删除节点、Prompt、服务、ChatMemory、Tools/Skills 配置和全部路由引用 |

## 11. 错误处理与可观测性

- 无快照且首次拉取失败：索引状态为 `NOT_READY`，查询返回明确的暂不可用信息。
- 已有索引且刷新失败：继续使用旧索引，记录失败原因和旧索引年龄。
- Tushare 返回空列表、非法代码、重复代码或数量异常：拒绝发布新索引。
- 日志记录刷新来源、记录数、耗时、快照路径、成功时间和失败阶段，不记录 Token。
- 暴露当前记录数、最后成功刷新时间、快照加载状态和刷新失败次数等指标。
- 区分 `NOT_FOUND`、`AMBIGUOUS`、`INDEX_UNAVAILABLE` 和 `UNSUPPORTED_MARKET`，不得统一降级为 UNKNOWN。
- 记录 Pending 创建、解决、替换、取消、过期、CAS 冲突和重复请求复用 runId 指标。
- 日志关联 `pendingId/indexVersion/runId/targetId`，但不记录完整用户隐私文本。

## 12. 验收标准

| 编号 | 验收项 | 标准 |
|------|--------|------|
| AC-001 | 全量拉取 | 一次 `stock_basic(list_status=L)` 可构建全部上市股票索引 |
| AC-002 | 数据最小化 | 每条权威缓存记录只保存 `name` 和 `tsCode` |
| AC-003 | 前缀匹配 | 查询名称开头片段可返回正确候选 |
| AC-004 | 后缀匹配 | 查询名称结尾片段可返回正确候选 |
| AC-005 | 中间匹配 | 查询连续中间片段可返回正确候选 |
| AC-006 | 唯一候选 | 唯一候选自动解析为对应 6 位 ticker |
| AC-007 | 多候选 | 多个普通子串候选且无明确上下文时不取第一项，返回澄清问题和候选列表 |
| AC-008 | 候选约束 | Agent 生成候选集合外代码时禁止启动交易分析 |
| AC-009 | 定时刷新 | 配置的 cron 到期后完成全量刷新和原子替换 |
| AC-010 | 重启恢复 | Tushare 不可用时可从有效快照恢复查询能力 |
| AC-011 | 失败保护 | 刷新失败或新数据非法时继续使用上一版索引 |
| AC-012 | 内存预算 | 刷新峰值不超过为该能力预留的 32 MB 预算 |
| AC-013 | 唯一路由 | 单任务股票请求只调用 3201，不调用 6001 |
| AC-014 | 连续股票分析 | 药明康德完成后分析兆易创新，重新执行现有深度澄清；回答后创建新 run 且不继承上一 run 配置 |
| AC-015 | 二次选择 | 多候选后支持序号、全名、代码和 `targetId` 选择 |
| AC-016 | 替换与取消 | Pending 支持替换股票名、取消和切换通用意图 |
| AC-017 | 无结果重试 | NOT_FOUND 后可用新名称或代码重新解析 |
| AC-018 | 跨请求恢复 | Pending 不依赖 `DynamicContext` 或自然语言历史，可跨请求恢复 |
| AC-019 | 幂等消费 | 同一 Pending 版本在重复请求下最多创建一个 Trading run |
| AC-020 | 数据职责 | 名称索引不承载动态行情，Trading 启动后只初始化一次 `StockInfoVO` |

## 13. 测试场景

- 精确名称：`北方华创`。
- 后缀片段：`华创` 命中 `北方华创`。
- 前缀片段：`华创` 命中 `华创云信`。
- 中间片段：选择一个名称中间连续片段验证。
- 唯一片段：只有一个候选时自动解析。
- 多候选：`平安`、`华创` 在无上下文时触发消歧。
- 别名优势：配置别名后允许在候选集合内自动解析。
- 无结果：返回 `NOT_FOUND`，不生成 ticker。
- 快照启动、定时刷新、Tushare 超时、空结果、非法记录、原子替换和并发查询。
- 修正现有集成测试，空结果不得再冒充模糊搜索成功。
- 连续分析：药明康德完成后输入“那帮我分析一下兆易创新呢”，先验证现有深度澄清，再验证名称补全和新 run。
- Pending 选择：“第二个”、候选全名、6 位代码和标准 `targetId`。
- Pending 替换：“不是，我说的是平安好医生”。
- Pending 取消或新意图：“算了，讲个笑话”。
- Pending 过期、CAS 冲突、前端重复提交和索引刷新后的候选稳定性。
- 索引不可用与真正无结果返回不同提示。
- 验证 6001 未调用、3201 未暴露 Trading 数据工具、上一 run 配置未继承。

## 14. 实施任务

| Task | 内容 | 状态 |
|------|------|------|
| Task 1 | 定义记录、索引、候选和配置契约 | pending |
| Task 2 | 实现 Tushare 全量股票列表查询 | pending |
| Task 3 | 实现快照加载、校验、写入和原子索引替换 | pending |
| Task 4 | 实现精确、别名和任意位置子串匹配 | pending |
| Task 5 | 实现启动刷新与每日定时刷新 | pending |
| Task 6 | 接入 Java `StockSymbolResolver` 与路由候选校验 | pending |
| Task 7 | 补齐单元测试、集成测试和回归测试 | pending |
| Task 8 | 验证内存、刷新失败降级和真实 Tushare 数据 | pending |
| Task 9 | 定义 Preparation Port、强类型 ResolutionResult 和 `stockMention` 兼容迁移 | pending |
| Task 10 | 实现 Redis Pending Repository、TTL、CAS 和幂等消费 | pending |
| Task 11 | 实现单任务二次澄清的选择、替换、取消和新意图流程 | pending |
| Task 12 | 切换单任务主链路并直接删除 6001 代码、配置和路由资产 | pending |

## 15. 范围约束与后续事项

本 Story 只验收 A 股单股票、单任务。多股票、多任务中的准备屏障、部分执行恢复和 task graph
持久化另立 Story，不在本次实现中顺带处理。现有独立 Trading API 保持只接收明确 ticker，避免
本次变更扩大外部接口行为。
