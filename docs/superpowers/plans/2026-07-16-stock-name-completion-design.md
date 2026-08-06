# Story 2：A股股票名称补全与定时缓存

| 字段 | 内容 |
|------|------|
| 创建日期 | 2026-07-16 |
| 状态 | pass |
| 优先级 | P1 |
| 数据源 | Tushare `stock_basic` |
| 文档类型 | Story 与设计合并文档 |
| 关联测试 | `docs/superpowers/test/2026-07-16-stock-name-completion-test.md` |

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

作为系统维护者，我希望应用启动时从 Tushare 加载全量股票名称目录，并按计划刷新 JVM 内存索引。

## 3. 目标

- 全量拉取当前上市股票的 `name` 和 `ts_code`，转换为目录使用的 `stockName` 和六位 `stockCode`。
- 在内存中维护不可变的股票名称索引，并原子替换完整索引。
- 支持标准名称精确匹配和任意位置连续子串匹配。
- 唯一候选自动补全；多个候选进入二次澄清，不得默认选择第一项。
- 3201 LLM 只提取用户原始名称片段；Java 优先使用本地索引补齐 `stockName` 和 `stockCode`，索引
  不可用时复用原有远端精确名称查询能力兜底。
- 应用启动时从远端完成首次加载，并每日定时全量刷新。

## 4. 非目标

- 第一版不支持拼音首字母、编辑距离、错别字纠正或语义向量检索。
- 股票名称目录不写数据库、Redis 或本地 JSON 文件，不提供跨重启快照恢复。
- 不为股票名称查询新增 Skill 或暴露 LLM Tool。
- 第一版不支持额外别名配置，只匹配 Tushare 返回的标准股票名称。
- 不缓存行情、财务或新闻数据；本 Story 只处理股票名称和六位 A 股代码。
- 不索引退市和暂停上市股票，默认只查询 `list_status=L`。
- 不使用市值或模型常识作为股票代码来源。

## 5. 数据契约

股票目录记录只包含：

```json
{
  "stockName": "北方华创",
  "stockCode": "002371"
}
```

约束：

- `stockName` 来自 Tushare 当前股票名称，去除首尾空白后不能为空。
- 刷新源数据的 `ts_code` 必须匹配 `^[0-9]{6}\\.(SH|SZ|BJ)$`，写入内存目录前取前六位形成 `stockCode`。
- `stockCode` 必须匹配 `^[0-9]{6}$`，其格式与 `StockSlot.stockCode` 保持一致。
- 同一 `stockCode` 只保留一条记录；刷新数据存在重复或非法记录时拒绝发布新索引。
- 目录不保存 `exchange`。进入 Trading 前由 `TradingRequestNode` 调用 `TargetContextFactory` 完成权威
  身份预检，并以带交易所后缀的 `TargetContext.targetId` 作为最终股票身份。

3201 股票槽位区分用户原始名称片段和 Java 解析后的规范身份：

```text
StockSlot
  stockNameQuery: 华创     # LLM 提取的用户原始连续名称片段
  stockName: null          # Java 解析成功后填充为规范股票名称
  stockCode: null          # Java 解析成功后填充为六位股票代码
```

用户明确输入六位代码时，`stockNameQuery` 和 `stockName` 可以为空，`stockCode` 直接保存六位代码。
`StockRequestResolver` 完成名称解析后必须同时填充规范 `stockName` 和 `stockCode`，
后续节点不得再使用 `stockNameQuery` 推导股票身份。

当 3201 已识别为股票请求，但 `stockNameQuery` 和 `stockCode` 都为空时，股票标的保持
`UNRESOLVED`。Java 不调用本地索引或远端接口；没有现有 Pending 时创建 Pending，有现有 Pending
时保留当前状态，并询问用户提供股票名称或六位代码。已识别的 `analysisMode` 必须保留。

## 6. 架构、缓存与刷新设计

### 6.1 目标架构

```text
Tushare stock_basic(list_status=L)
  -> StockNameRefreshService
  -> 校验源数据 name / ts_code / 六位代码唯一性
  -> 转换为 StockNameRecord(stockName, stockCode)
  -> 构建不可变 StockNameIndex
  -> 原子发布 JVM 内存索引

用户请求 + sessionId 主会话历史
  -> IntentRouteNode / clientId=3201
       -> LLM 识别 STOCK_ANALYSIS 或 FINANCIAL_GENERAL
       -> LLM 只提取 stockNameQuery、明确 stockCode 和 analysisMode
  -> IntentRoutingNode 内的 StockRequestResolver（Java，执行节点选择之前）
       -> session 存在 Pending：先按原始回复和槽位确定性解析候选选择、分析模式或新股票名称/代码
            -> 可解析：由 Java 推进/覆盖 Pending，不受 3201 本轮非股票误判影响
            -> 不可解析且 3201 明确为非股票意图：取消 Pending，再执行非股票路由
       -> 明确六位 stockCode：跳过名称检索
       -> stockNameQuery 和 stockCode 都为空：保存或保留 UNRESOLVED Pending，先澄清股票
       -> stockNameQuery：调用 StockNameResolutionService
            -> 索引 READY：精确 Map 查询；未命中再做 List 连续子串扫描
            -> 索引 NOT_READY/EXPIRED：Java 调用远端精确名称查询，不触发索引刷新
            -> 0 个候选：返回 NOT_FOUND（股票不存在），不创建 Pending
            -> 1 个候选：Java 补齐 stockName + stockCode
            -> 2 到 max-candidates 个候选：创建股票候选 Pending
       -> 合并 Pending 中已确认的股票标的和分析模式
       -> 股票多候选：先澄清股票，不创建 runId
       -> 股票已确认但 analysisMode 未确认：再澄清快速了解或完整投资分析
       -> stockTarget=RESOLVED + analysisMode=QUICK：GeneralChatNode
       -> stockTarget=RESOLVED + analysisMode=FULL：TradingRequestNode
  -> TradingRequestNode（仅 FULL）
       -> TargetContextFactory 权威身份预检
       -> 创建 TargetContext(runId, targetId, stockName, ...)
  -> TradingStarter
       -> Java 显式补齐 StockInfoVO
       -> Trading pipeline
```

3201 LLM 不读取缓存、不调用 `read_skill` 或 `search_stock_by_name`，也不得生成股票代码。本地索引
和远端兜底均由 Java 显式调用，只负责候选召回和槽位补全，不保存动态 `StockInfoVO`。名称解析是
QUICK 和 FULL 的公共前置阶段，不能收拢在 `TradingRequestNode` 内。只有 FULL 分支需要
`TargetContextFactory` 权威身份预检并创建新
`runId`；QUICK 进入 `GeneralChatNode`，多候选和任一澄清阶段均不创建 `runId`。

QUICK 分支不要求 `GeneralChatNode` 读取 `StockSlot`。股票和模式均解析完成后，Java 使用当前请求或
Pending 已保存的 `originalQuery`、规范 `stockName`、六位 `stockCode` 按固定模板组装
`executionQuery`，再通过 `GeneralChatNode` 现有的文本输入契约执行：

```text
用户原始问题：
{originalQuery}

系统已确认的股票：
{stockName}（{stockCode}）

请基于上述已确认股票回答原始问题，不要重新识别股票。
```

这是 Java 确定性字符串组装，不调用 LLM 做 Query Rewrite，也不在原文中执行名称替换。首次唯一
匹配使用本轮原始请求；二次澄清使用 Pending 中的 `originalQuery`，因此用户只回复“1”时仍能恢复
最初的“昨天收盘价”等业务要求。`GeneralChatNode` 仍只接收普通字符串，不新增对 `StockSlot`、
Pending Repository 或名称索引的依赖。原始请求继续保留在路由输入和审计上下文中，
`executionQuery` 仅作为本轮内部执行文本。FULL 分支不生成该文本，继续向 `TradingRequestNode`
传递结构化规范股票身份。

### 6.2 内存索引

使用单个不可变 `StockNameIndex`，通过 `AtomicReference` 或等价原子引用发布：

```text
StockNameIndex
  records: List<StockNameRecord>
  exactNameMap: Map<normalizedName, List<StockNameRecord>>
  refreshedAt: Instant
  expiresAt: Instant
```

`exactNameMap` 和 `records` 引用同一批 `StockNameRecord`，不复制业务记录。Map 负责精确查询；精确
未命中时顺序扫描 List，并直接收集命中的记录，不再回 Map 做第二次查询。标准化名称属于运行时
派生索引元数据，不增加股票目录的业务字段。

不把每只股票作为独立 Caffeine 条目。一次查询最多扫描约 6,000 个标准化名称，内存和延迟均可控。
单份索引预计占用 5-12 MB；刷新期间新旧索引并存，给该能力预留 32 MB JVM 堆预算。

索引有效期固定为最近一次成功加载后的 7 天：

- `READY`：存在完整索引且 `now < expiresAt`，允许名称查询。
- `NOT_READY`：本实例启动后尚无任何成功索引，禁止读取本地索引；名称请求改走 Java 远端精确名称
  查询兜底。
- `EXPIRED`：索引年龄达到 7 天，禁止继续用于名称解析；名称请求改走同一远端兜底。
- 刷新执行中仍可读取当前未过期索引；刷新只在全量数据校验成功后原子发布新索引并重置 7 天有效期。
- 任意一次全量刷新成功都会发布新索引并进入 `READY`：允许 `NOT_READY -> READY`、
  `READY -> READY` 和 `EXPIRED -> READY`；成功发布时统一重置 `loadedAt` 与 `expiresAt`。
- 不在用户查询线程触发刷新，避免并发请求造成刷新风暴。

`NOT_READY/EXPIRED` 的请求级兜底只查询当前名称，不构建、更新或延长本地索引：

- 复用原有 Tushare `stock_basic.name` 精确名称查询契约，不承诺连续子串模糊匹配。
- 唯一结果按正常流程补齐槽位；多个结果进入候选 Pending；空结果返回股票不存在。
- API、传输或协议错误返回系统错误，不伪装成空结果。
- 索引为 `READY` 但本地查询无候选时，直接返回股票不存在，不重复调用远端名称查询。

### 6.3 启动与定时刷新

- 应用已启用 `@EnableScheduling`，新增独立刷新任务。
- 默认每天 `03:30`，时区 `Asia/Shanghai`，cron 可配置。
- 使用 `ApplicationRunner` 在 `ApplicationReadyEvent` 之前同步执行首次热加载，使 3201 等运行资产完成
  装配前尽量具备名称解析能力。首次加载失败不阻止整个应用启动，索引保持 `NOT_READY`，六位代码
  请求仍可沿用 `TargetContextFactory` 权威预检链路。
- 首次热加载每次启动只尝试一次；失败后不安排短期或周期重试，也不由用户查询触发刷新，只等待
  下一次每日 `03:30` 定时任务。
- 严格按官方接口协议单次调用 `stock_basic(list_status=L)` 拉取全部当前上市 A 股，不分页、不按
  交易所拆分，也不传入协议未声明的 `limit/offset`。
- 刷新流程为：全量拉取 -> 规范化 -> 必要字段与唯一性校验 -> 构建新索引 -> 原子发布。
- 定时刷新失败时继续使用当前内存索引，不发布半成品或空索引。
- 同一 JVM 使用单次刷新门禁，启动加载、定时刷新或后续运维触发重叠时只允许一个刷新任务执行。
- 本 Story 按单 JVM 部署设计，不考虑多实例刷新、分布式锁或跨版本滚动发布。

发布前只执行构建索引所需的契约校验：响应非空、名称非空、源 `ts_code` 合法、六位 `stockCode`
合法且不重复。标准名称重复可以保留为多条候选，不得用 Map 静默覆盖。不增加记录数阈值、历史
数量跌幅、市场覆盖率或分市场数量等防御性校验。

## 7. 模糊匹配设计

### 7.1 规范化

- Unicode NFKC 规范化。
- 去除首尾及名称内部空白。
- 拉丁字母统一大写。
- 不删除 `ST`、`*ST` 或股票名称中的有效中文字符。

### 7.2 候选召回

按以下顺序召回：

1. 标准名称精确匹配。
2. 精确匹配未命中时，扫描标准名称是否包含查询字符串，包括前缀、后缀和中间连续子串。

例如查询“华创”至少召回：

- `北方华创 -> 002371`（后缀匹配）
- `华创云信 -> 600155`（前缀匹配）

前缀和后缀不因位置获得不同权重。模糊候选按 `stockName` 和 `stockCode` 稳定排序；会话上下文
不进入缓存索引的匹配和排序逻辑。

模糊查询始终扫描完整索引以计算 `totalMatches`。命中数为 2 到 `max-candidates` 时返回全部候选并
创建 Pending；超过上限时只返回前 `max-candidates` 条作为提示，同时说明总数并要求用户输入更
完整的名称，此时不创建 Pending，避免未展示候选和可选候选集合不一致。

### 7.3 自动补全与消歧

- 标准名称精确匹配且只有一条记录：Java 直接解析；同名多条仍进入澄清，不静默覆盖。
- 唯一连续子串候选：Java 直接解析。
- 0 个候选：返回 `NOT_FOUND` 和“股票不存在，请检查股票名称或输入六位代码”，不创建 Pending，
  不继续询问分析模式。
- 2 到 `max-candidates` 个候选：创建短期 Pending，返回全部候选并使用 `ASK_DISAMBIGUATION`。
- 候选超过 `max-candidates`：返回缩小名称范围的澄清，不创建 Pending。
- 二次回复支持候选序号（如 `1`、`第一个`）、完整候选名称和六位候选代码。
- 序号和候选选择代码必须属于 Pending 候选集合；名称与候选完整名称一致时选择该候选。
- 3201 提取出的候选外 `stockNameQuery` 视为切换到新股票：覆盖旧股票候选并生成新 `version`，保留
  Pending 中的 `originalQuery` 和已经确认的 `analysisMode`，再按本地索引或远端兜底执行同一套
  0/1/多候选解析规则。切换只替换股票目标，不丢失原始业务问题。
- 候选外合法六位代码同样视为切换到新股票：覆盖旧股票候选并生成新 `version`，保留已确认的
  `analysisMode`，再进入明确代码的权威身份预检。
- 无法识别为候选选择、分析模式、新股票名称或合法六位代码的输入才是无效选择；继续澄清且不选择执行节点。
- 股票选择成功后只更新 `stockTarget`；若 `analysisMode` 未确认，继续询问快速了解或完整投资分析。
- 两个维度都确认后先原子 Claim，再选择 `GeneralChatNode` 或 `TradingRequestNode`；节点接管成功后删除。
- 删除任何“直接取第一项”的兜底行为。

Pending 使用现有 Redis 能力保存短期结构化状态，不写 MySQL：

```text
key: trading:stock-resolution:{sessionId}
value:
  version: UUID
  status: PENDING / CLAIMED
  claimId
  claimExpiresAt
  originalQuery
  stockNameQuery
  targetStatus: UNRESOLVED / AMBIGUOUS / RESOLVED
  orderedCandidates: List<StockNameRecord>
  resolvedStockName
  resolvedStockCode
  analysisMode: UNRESOLVED / QUICK / FULL
  createdAt
  expiresAt
TTL: 10 分钟
```

每个 `sessionId` 同时只保留一个 Pending。候选外新股票名称或合法六位代码覆盖旧股票候选，保留
`originalQuery` 和已确认的分析模式并生成新 `version`。新名称重新执行统一名称解析：0 候选返回股票不存在且不保留
旧股票 Pending，1 候选补齐新股票，多候选发布新的候选 Pending。两个维度全部确认后 Claim；无法
识别的输入保留 Pending 并再次澄清；Pending 缺失或过期时提示重新输入股票名称。
3201 仍是 AutoAgent 自然语言路径每轮的唯一意图入口：它结合主会话历史输出本轮意图和槽位，但
3201 的意图分类不直接决定是否删除活跃 Pending。3201 返回后、执行节点选择前，Java
`StockRequestResolver` 必须先读取当前 session 的 Pending，并按以下优先级处理：

1. 原始回复或槽位可确定性解析为 Pending 候选序号、完整候选名称、候选代码、分析模式，或者
   3201 提取出候选外新股票名称/合法六位代码时，由 Java 推进或覆盖 Pending；即使 3201 本轮意图被判为
   `GENERAL_CHAT`，也不得清除 Pending 或改走普通聊天。
2. 输入无法按上述规则解析，且 3201 明确判定为非股票意图时，才清除当前股票 Pending 并执行新意图，
   避免稍后的序号回复误选旧候选。
3. 输入无法解析但 3201 仍判定为股票请求时，按当前 Pending 的无效选择或空槽位规则继续澄清。

该优先级只允许 Java 使用结构化 Pending 和确定性语法接管，不允许 Java 重新做开放式意图识别。
Redis 不可用属于系统错误，不降级为选择第一项。

第一版不支持同一 `sessionId` 的并行澄清流程，也不在前端请求或 SSE 中新增 `pendingId/version`。
多个标签页共享同一 `sessionId` 时采用 last-write-wins：最新股票请求覆盖旧 Pending，之后到达的序号、
名称或代码回复一律按 Redis 中当前 Pending 解释，不保证仍对应旧标签页展示的候选。不同 `sessionId`
的 Pending 完全隔离。`version + CAS` 只保证服务端并发写入一致性，不承诺跨标签页的旧候选关联。

Pending 的创建、状态推进、领取、完成和释放必须通过 Redis Lua 或等价原子操作实现：

- 创建或覆盖时生成新的随机 `version`。
- 股票选择或分析模式选择等部分推进执行 compare-and-set：只有 Redis 当前 `version` 等于读取版本时
  才写入新状态和新 `version`。
- 有效状态推进成功后刷新 10 分钟 TTL；无效选择不刷新 TTL。
- 两个维度都确认后，执行节点接管前必须将 `PENDING` 原子更新为 `CLAIMED`，写入随机 `claimId`
  和 60 秒 `claimExpiresAt`。只有领取成功的实例可以继续分流。
- Claim 只覆盖“Pending 消费到 GeneralChat/Trading 接管”的短窗口，不覆盖完整分析执行时间。
- 执行节点成功接管后按 `version + claimId` 比较并删除；接管前发生可重试系统错误时按相同条件释放回
  `PENDING` 并生成新版本。
- Claim 超时后允许同一 JVM 的后续请求原子重新领取；CAS 或 Claim 失败时重新读取最新状态，不启动
  执行节点。
- 旧请求完成时若 `version` 或 `claimId` 已变化，不得删除或覆盖新的 Pending。

股票请求包含两个正交维度：

```text
stockTarget: UNRESOLVED / AMBIGUOUS / RESOLVED
analysisMode: UNRESOLVED / QUICK / FULL
```

只有两类业务歧义进入二次澄清：名称命中多个可选股票，或者股票已经唯一确认但 `analysisMode`
仍不明确。0 个名称候选直接返回股票不存在；索引、Redis 或远端故障返回系统错误，二者都不得
伪装成业务歧义。当两个维度同时未确认时，固定先澄清股票标的，再澄清分析模式；每轮最多提出
一个澄清问题，不把候选选择和快速/完整选择合并成复合问题。状态机如下：

股票请求缺少名称和代码时属于 `stockTarget=UNRESOLVED`，不是 `NOT_FOUND`。首次进入时创建 Pending；
后续无法识别的空槽位回复保留 Pending 并重复当前澄清，无效推进不刷新 10 分钟 TTL。

```text
NONE
  -> 任一维度未确认：PENDING

PENDING
  -> 有效序号/完整名称/候选代码：stockTarget=RESOLVED
       -> analysisMode=UNRESOLVED：保留 Pending -> 询问快速了解/完整投资分析
       -> analysisMode=QUICK：原子 CLAIMED -> GeneralChatNode 接管 -> 比较并删除
       -> analysisMode=FULL：原子 CLAIMED -> TradingRequestNode 接管 -> 比较并删除
  -> 股票已确认 + 快速了解：原子 CLAIMED -> GeneralChatNode
  -> 股票已确认 + 完整投资分析：原子 CLAIMED -> TradingRequestNode
  -> 无效选择：PENDING -> 再次澄清
  -> 新股票名称查询：REPLACED -> 删除旧 Pending -> 重新检索
  -> 候选外合法六位代码：REPLACED(newVersion) -> 权威身份预检 -> 保留 analysisMode
  -> 输入不能确定性推进/覆盖 Pending + 3201 明确为非股票意图：CANCELLED -> 删除 Pending -> 执行新意图
  -> 10 分钟到期：EXPIRED -> Redis 自动删除 -> 提示重新输入名称
  -> Redis 失败：ERROR -> 不启动 Trading

CLAIMED
  -> 执行节点接管成功：COMPLETED -> 按 version + claimId 删除
  -> 接管前可重试错误：RELEASED -> PENDING(newVersion)
  -> claimExpiresAt 到期：允许后续请求重新 CLAIMED
```

### 7.4 SSE 与主会话交互

多候选复用 Story 1 已确定的路由终止协议，不新增共享 SSE 实体字段：

```text
type=summary
subType=clarification
content=找到多只名称包含“华创”的股票：\n1. 北方华创（002371）\n2. 华创云信（600155）\n请回复序号、完整候选名称或候选代码；也可以输入其他股票名称或六位代码切换股票。

type=complete
```

`StockRequestResolver` 只登记 `routingTerminalResponse`、
`routingTerminalKind=CLARIFICATION` 和现有 `clarificationPrompt`；`IntentRoutingNode` 发送业务事件，
`AutoAgentExecuteStrategy` 负责物理关闭
emitter。Root 继续将候选文本持久化到 `sessionId` 主会话历史。第一版不要求候选按钮或新增前端
请求字段，现有前端能够按普通澄清文本展示；结构化候选 UI 留给独立后续 Story。

## 8. 配置建议

```yaml
spring:
  ai:
    trading:
      stock-name-index:
        refresh-cron: "0 30 3 * * ?"
        refresh-zone: "Asia/Shanghai"
        max-age: 7d
        max-candidates: 10
      stock-resolution-pending:
        ttl: 10m
        claim-timeout: 60s
```

## 9. 代码改造与复用边界

### 9.1 新增能力

| 范围 | 新增内容 |
|------|----------|
| 路由领域模型 | `StockNameRecord`、`StockNameCandidate`、`StockNameResolution` 和 `PendingStockRequest` |
| Tushare | 新增 `callGenericStrict()` 和 `stock_basic(list_status=L)` 全量方法；错误不得伪装成空结果 |
| JVM 索引 | `StockNameIndex`、原子索引持有者、状态和 7 天有效期判断 |
| 刷新 | `StockNameRefreshService`、启动热加载 Runner、每日 Scheduler 和 JVM 单次刷新门禁 |
| 名称解析 | Java `StockNameResolutionService`，集中处理精确 Map、模糊 List、远端精确名称兜底和二次选择校验 |
| 请求解析 | `IntentRoutingNode` 同路由包下新增 Java `StockRequestResolver`，合并股票标的和分析模式 |
| Pending | 结构化 `PendingStockRequest`、领域接口和 Redis 实现，按 `sessionId` 保存 10 分钟 |

### 9.2 修改能力

| 范围 | 修改内容 |
|------|----------|
| `StockSlot` | 增加 `stockNameQuery`；`stockName`、`stockCode` 只在 Java 解析后作为规范身份使用 |
| 3201 Schema/Prompt | 提取名称原文或明确代码；禁止生成代码、调用名称搜索 Skill/Tool 或执行候选裁决 |
| 3201 工具装配 | Story 2 生效后不再为 3201 装配 `read_skill` 和 `search_stock_by_name` |
| `AnalysisDepthFollowUpResolver` | 复用快速/完整选项词表，将历史文本推断升级为结构化 Pending 维度合并 |
| `TradingRequestNode` | 只接收已确认 FULL 和规范股票标的，继续执行请求构造与身份预检 |
| `RoutingResultHandler` | 调用 Resolver 后再选择执行节点；多候选时生成带稳定序号的澄清文本；仅当 Resolver 未确定性接管且 3201 明确为非股票意图时，通过 Pending 生命周期接口清除状态 |
| 配置 | 增加刷新 cron、时区、7 天有效期、候选上限和 Pending TTL |

### 9.3 直接复用

| 既有能力 | 复用方式 |
|------|----------|
| Story 1 的 AutoAgent 唯一意图入口 | AutoAgent 自然语言请求每轮仍先经过 3201；不恢复 6001；直接 Trading API 不经过 3201 |
| `TushareApiClient` | 复用现有 HTTP 请求和 DTO 映射；保留 `callGeneric()` 降级语义以兼容旧调用方 |
| `@EnableScheduling` | 复用应用现有调度开关，只新增股票目录 Scheduler |
| `StringRedisTemplate` | 复用现有 Redis 连接与序列化基础设施，Pending 使用独立 Key 前缀和 TTL |
| `TradingRequestNode` | 复用 Story 1 的请求构造、分析师映射、终止响应登记和 Trading 调用职责 |
| `TargetContextFactory` | 继续进行最终权威身份预检并生成带后缀 `targetId` |
| `TradingStarter.populateStockInfo()` | 继续由 Java 按当前目标补齐动态 `StockInfoVO`，不全量预热 |
| SSE 所有权 | 复用 `IntentRoutingNode` 发送业务事件、外层关闭 emitter 的协议 |
| 主会话历史 | 继续按 `sessionId` 持久化和复用候选澄清文本及用户回复 |
| Trading 隔离 | 每次解析成功后创建新 `runId`，Trading ChatMemory 和中间状态继续按 run 隔离 |
| 原始数据缓存 | 不修改行情、财务、新闻等缓存 Key 和 TTL，也不向 Key 增加 runId；当前 Provider 是否实际接入缓存不属于本 Story 验收 |

### 9.4 明确不改

- 不修改直接 `/trading/analysis` API；该入口仍要求明确代码并沿用原有启动方式。
- 不修改 `TradingStarter` 的正式 `StockInfoVO` 初始化位置和 Trading pipeline。
- 不修改 `TargetContext.targetId`、路由 `exchange` 废弃规则及展示层 `exchange` 字段。
- 不修改 6002-6013 分析节点及其既有工具集合；现有 `search_stock_by_name` Tool 可为其他兼容调用方
  保留，但不装配给 3201。索引不可用时由 Java `StockNameSource` 直接复用同一远端接口契约。
- 不改变 `analysisDepth` 的产品二选一语义；需要修改其状态保存和与股票候选的先后顺序。普通多任务
  执行和包含股票分析的多任务门禁保持不变。
- 不支持多股票同时分析；本 Story 的 Pending 只对应一个股票请求的标的和分析模式。

### 9.5 模块依赖约束

`StockRequestResolver` 与 `IntentRoutingNode` 同属 `ai-agent-study-domain`，但保持独立类以便单元测试。
核心 domain 同时定义股票目录源和 Pending 仓储接口，具体实现由外层模块提供：

```text
ai-agent-study-domain
  IntentRoutingNode
  StockRequestResolver
  StockNameIndex / StockNameResolutionService
  StockNameSource 接口（全量加载 + 请求级精确名称兜底）
  StockResolutionPendingRepository 接口

ai-agent-study-trading-infra -> ai-agent-study-domain
  TushareStockNameSource 实现

ai-agent-study-infrastructure -> ai-agent-study-domain
  RedisStockResolutionPendingRepository 实现

ai-agent-study-trading-domain -> ai-agent-study-domain
  TradingRequestNode

ai-agent-study-app
  组合以上 Bean
```

`ai-agent-study-domain` 不依赖 trading-domain、trading-infra 或 infrastructure，禁止形成反向 Maven
依赖。`StockRequestResolver` 返回包含路由决定和已解析结果的对象；决定包括 `CLARIFY_TARGET`、
`CLARIFY_ANALYSIS_MODE`、`ROUTE_GENERAL_CHAT` 或 `ROUTE_TRADING`。`ROUTE_GENERAL_CHAT` 同时携带由
Java 固定模板生成的 `executionQuery`，`ROUTE_TRADING` 携带结构化规范股票身份。Resolver 不直接调用 `GeneralChatNode`、
`TradingRequestNode` 或 `IntentRoutingNode`，避免 Spring Bean 循环依赖。最终节点仍由
`RoutingResultHandler` 选择。

## 10. 错误处理与可观测性

- 首次拉取失败：索引状态为 `NOT_READY`，名称查询走请求级远端精确名称兜底；兜底也失败时才返回
  暂不可用信息。
- 已有索引且刷新失败：继续使用当前索引，记录失败原因和索引年龄。
- 当前索引达到 7 天且刷新仍失败：状态转为 `EXPIRED`，停止使用本地索引；名称查询走远端精确名称
  兜底，明确六位代码仍走权威预检。
- `NOT_READY` 或 `EXPIRED` 后续刷新成功：原子发布完整新索引，恢复为 `READY` 并重新计算 7 天有效期，
  不要求重启应用。
- Tushare 返回空列表、非法代码或重复代码：无法构建有效索引，拒绝发布新索引；不根据记录总数
  或相对变化额外判断数据是否完整。
- 名称无候选的业务结果为 `NOT_FOUND`，响应文本明确为股票不存在；SSE 仍复用
  `CLARIFICATION` 终止协议，但不创建 Pending。Pending 缺失/过期、候选选择非法同样复用
  `CLARIFICATION` 协议。
- 索引 `NOT_READY/EXPIRED` 本身不是请求错误；此时远端名称兜底的 API、传输或协议失败，以及 Redis
  Pending 读写失败属于 `ERROR`。
- `TargetContextFactory` 的未找到、Provider 和身份完整性失败继续复用 Story 1 的失败分类。
- 股票目录刷新、请求级远端名称兜底和 `findStockIdentities()` 使用 `callGenericStrict()`：`code=0`
  且 `items=[]` 正常返回空列表；`code!=0` 抛 `TushareApiException`；SSL、连接和超时抛
  `TushareTransportException`；非法
  JSON、`data/fields/items` 缺失或行列结构异常抛 `TushareProtocolException`。
- 只有单只股票查询的正常空列表映射为未找到；全量股票目录正常返回空列表仍属于刷新数据异常。
- 日志记录刷新来源、记录数、耗时、成功时间和失败阶段，不记录 Token。
- 暴露当前记录数、最后成功刷新时间、索引年龄、索引状态、刷新失败次数、查询耗时、候选数量和
  Pending 创建/命中/过期次数等指标。

## 11. 验收标准

| 编号 | 验收项 | 标准 |
|------|--------|------|
| AC-001 | 全量拉取 | 严格按协议只调用一次 `stock_basic(list_status=L)` 构建全部上市股票索引，不分页或分市场拉取 |
| AC-002 | 数据最小化 | 每条股票目录记录只保存 `stockName` 和六位 `stockCode` |
| AC-003 | 前缀匹配 | 查询名称开头片段可返回正确候选 |
| AC-004 | 后缀匹配 | 查询名称结尾片段可返回正确候选 |
| AC-005 | 中间匹配 | 查询连续中间片段可返回正确候选 |
| AC-006 | 唯一候选 | 唯一候选由 Java 自动解析为对应六位 `stockCode` |
| AC-007 | 多候选 | 多个普通子串候选且无明确上下文时不取第一项，返回澄清问题和候选列表 |
| AC-008 | 候选约束 | 二次选择不属于 Pending 候选集合时禁止启动交易分析 |
| AC-009 | 定时刷新 | 配置的 cron 到期后完成全量刷新和原子替换 |
| AC-010 | 重启加载 | 应用重启后重新从 Tushare 全量构建 JVM 索引 |
| AC-011 | 失败保护 | 刷新失败或新数据非法时继续使用上一版索引 |
| AC-012 | 内存预算 | 刷新峰值不超过为该能力预留的 32 MB 预算 |
| AC-013 | Java 边界 | 3201 只提取名称片段，名称检索和候选判断不由 LLM 执行 |
| AC-014 | 二次选择 | 序号、完整候选名称和候选内六位代码均可选择 Pending 候选 |
| AC-015 | 启动热加载 | 首次加载在 `ApplicationReadyEvent` 前执行，成功后索引为 `READY` |
| AC-016 | 一周有效期 | 索引最近成功加载未满 7 天可用，达到 7 天后停止名称解析 |
| AC-017 | 每日刷新 | 每天 03:30 按上海时区刷新，成功后原子替换并重置有效期 |
| AC-018 | 刷新失败 | 未过期旧索引继续可用，失败数据不覆盖当前索引 |
| AC-019 | 无持久化 | 股票目录不写 MySQL、Redis 或本地 JSON，重启后重新全量加载 |
| AC-020 | Pending 生命周期 | Pending 按 `sessionId` 保存 10 分钟，成功、覆盖，以及输入无法确定性解析且 3201 明确为非股票转向时清除 |
| AC-021 | SSE 兼容 | 多候选复用 `summary/clarification + complete`，不要求前端新增协议 |
| AC-022 | 代码输入兼容 | 索引不可用时，明确六位代码仍可进入 `TargetContextFactory` 预检 |
| AC-023 | 性能 | 约 6,000 条目录下，预热后名称解析 P95 小于 5 ms、P99 小于 10 ms |
| AC-024 | 双重澄清顺序 | 股票和分析模式同时未确认时，先确认股票，再确认快速了解或完整投资分析 |
| AC-025 | QUICK 分支 | 股票确认且选择快速了解时进入 `GeneralChatNode`，不创建 Trading run |
| AC-026 | FULL 分支 | 股票确认且选择完整投资分析时才进入 `TradingRequestNode` |
| AC-027 | 模块依赖 | 核心 domain 只定义接口和路由逻辑，基础设施单向依赖并实现接口，无 Maven 或 Bean 循环 |
| AC-028 | Pending 原子消费 | 同一 JVM 的并发请求通过 version CAS 和 CLAIMED 状态保证同一 Pending 只有一个请求接管执行 |
| AC-029 | Tushare 错误区分 | 严格调用区分正常空数据、API 错误、传输错误和响应协议错误 |
| AC-030 | Tushare 兼容 | 现有 `callGeneric()` 保持异常降级为空列表，未迁移调用方行为不变 |
| AC-031 | 零候选语义 | 本地名称目录无候选时返回股票不存在，不创建 Pending，不询问分析模式 |
| AC-032 | 澄清边界 | 只有股票多候选或分析模式不明确进入二次澄清；两者同时不明确时先股票后模式 |
| AC-033 | 状态恢复 | `NOT_READY` 或 `EXPIRED` 后刷新成功均恢复为 `READY`，并重置加载时间和 7 天有效期 |
| AC-034 | 启动失败策略 | 首次热加载失败后不额外重试，只等待下一次每日 `03:30` 定时刷新 |
| AC-035 | 索引不可用兜底 | `NOT_READY/EXPIRED` 时由 Java 调用原有远端精确名称查询；唯一、多条、空和异常结果按统一分类处理 |
| AC-036 | 兜底隔离 | 请求级远端结果不写入本地索引；`READY` 状态零候选不重复查询远端 |
| AC-037 | 新股票切换 | Pending 候选外股票名称或合法六位代码覆盖旧股票候选、生成新版本并保留原始业务问题和已确认分析模式；新名称复用统一 0/1/多候选规则 |
| AC-038 | 多标签页边界 | 同一 session 只保留最新 Pending，不支持并行澄清；不同 session 的 Pending 相互隔离 |
| AC-039 | 空股票槽位 | 股票请求缺少名称和代码时保存 `UNRESOLVED` Pending，保留已确认模式并先询问股票；不查询数据或创建 run |
| AC-040 | Pending 接管优先级 | 活跃 Pending 的有效候选选择、模式选择或候选外新股票名称/代码由 Java 确定性优先接管；3201 本轮误判为非股票意图时不得清除状态或改变分支 |
| AC-041 | QUICK 执行 Query | Java 使用 `originalQuery + stockName + stockCode` 固定模板组装 `executionQuery`；不调用 LLM 改写，`GeneralChatNode` 不读取股票槽位或 Pending |

## 12. 测试场景

- 精确名称：`北方华创`。
- 后缀片段：`华创` 命中 `北方华创`。
- 前缀片段：`华创` 命中 `华创云信`。
- 中间片段：选择一个名称中间连续片段验证。
- 唯一片段：只有一个候选时自动解析。
- 多候选：`平安`、`华创` 在无上下文时触发消歧。
- 无结果：返回 `NOT_FOUND`，不生成 ticker。
- 多候选后分别使用序号、完整名称和六位代码完成二次选择。
- “分析华创”先选择股票，再选择快速/完整；每轮只出现一个澄清问题。
- “完整分析华创”只澄清股票，选择后进入 Trading。
- “简单看看华创”只澄清股票，选择后进入 GeneralChat。
- QUICK 唯一命中或完成候选选择后，Java 以原始问题和规范股票身份拼接执行 Query；二次回复只有
  `1` 时也必须保留最初问题的业务要求，且不调用 LLM 做改写。
- 活跃 Pending 下回复 `1`、`第一个`、有效分析模式或候选外新股票名称/代码时，即使 3201 本轮误判为
  `GENERAL_CHAT`，也由 Java 确定性推进或覆盖 Pending；只有无法解析的真正非股票输入才清除 Pending。
- 唯一股票但分析模式不明确时只询问快速/完整。
- 候选外股票名称或合法六位代码切换为新股票并保留已确认模式；无法识别的文本、非法代码、
  无效序号、Pending 缺失或过期时不启动 Trading。
- 启动加载、定时刷新、Tushare 超时、空结果、非法记录、原子替换和并发查询。
- 连续 7 天刷新失败后索引过期，名称请求走远端精确查询兜底，六位代码请求继续执行。
- 同一 JVM 启动加载与定时刷新重叠时只执行一个刷新任务。
- 同一 JVM 的并发刷新只执行一个任务，并发 Pending 回复通过 CAS/Claim 保证只有一个请求接管。
- 多候选 SSE 保持现有澄清与完成事件顺序，Root 持久化候选文本。
- GENERAL_CHAT、PE、巡检、`analysisDepth`、直接 Trading API、6002-6013 和 Trading run 隔离回归。
- 修正现有集成测试，空结果不得再冒充模糊搜索成功。

## 13. 实施任务

任务状态只使用：

- `appending`：尚未完成，或尚未通过该任务定义的全部验收。
- `pass`：实现、自动化测试及该任务要求的验证全部完成。

| Task | 要实现的功能 | 主要文件或模块 | 前置依赖 | 完成标准 | status |
|------|--------------|----------------|----------|----------|--------|
| Task 1 | 定义股票名称解析领域契约：`StockNameRecord`、索引状态、候选结果、双维度 Pending、Resolver 决策对象；扩展 `StockSlot.stockNameQuery/stockName/stockCode/analysisMode`，保留废弃 `exchange` 的反序列化兼容 | `ai-agent-study-domain/.../model/valobj/StockSlot.java`；新增股票名称解析值对象与端口 | Story 1 已在 master 完成 | 槽位与设计第 5 节一致；核心 domain 不引用 trading/infrastructure 实现；覆盖 AC-002、AC-013、AC-027 | pass |
| Task 2 | 为 Tushare 增加严格调用入口和三类异常，同时保留 `callGeneric()` 的空列表兼容语义 | `ai-agent-study-trading-infra/.../provider/TushareApiClient.java`；新增 `TushareApiException`、`TushareTransportException`、`TushareProtocolException`；对应测试 | Task 1 | `code=0/items=[]`、API 错误、传输错误和协议错误可区分；旧调用方行为不变；TC-121~TC-125 通过 | pass |
| Task 3 | 实现 `stock_basic(list_status=L)` 单次全量名称源，只转换并输出 `stockName + 六位 stockCode`；同时提供索引不可用时的请求级精确名称兜底 | `ai-agent-study-trading-infra` 中新增 `TushareStockNameSource`；复用 Task 2 严格入口 | Task 1、Task 2 | 不分页、不分市场、不传 `limit/offset`；空目录和非法记录不发布；TC-018~TC-020、TC-103~TC-106、TC-126 通过 | pass |
| Task 4 | 实现 JVM 不可变名称索引：精确 Map 优先，未命中后扫描 List 做连续子串匹配，稳定排序并计算完整 `totalMatches` | `ai-agent-study-domain` 中新增 `StockNameIndex`、`StockNameResolutionService` 及测试 | Task 1 | 支持精确、前缀、后缀和中间连续子串；同名不覆盖；候选上限语义正确；TC-002~TC-005、TC-107、TC-201~TC-207、TC-214 通过 | pass |
| Task 5 | 实现索引生命周期和原子发布：`NOT_READY/READY/EXPIRED`、7 天有效期、刷新失败保留旧索引、过期后恢复 | 新增 `StockNameIndexHolder`、`StockNameRefreshService`、配置属性及测试 | Task 3、Task 4 | 状态转换和 `loadedAt/expiresAt` 正确；构建失败不暴露半成品；TC-101~TC-106、TC-208~TC-220 通过 | pass |
| Task 6 | 接入启动热加载和每日 03:30 上海时区刷新；同 JVM 刷新门禁保证启动与定时任务不重入 | `ai-agent-study-app` 启动装配；新增 `ApplicationRunner`、`@Scheduled` 刷新任务；更新 `application.yml` | Task 5 | 启动只尝试一次，失败后等待每日任务；无 enabled 开关；TC-001、TC-011~TC-012、TC-212~TC-213 通过 | pass |
| Task 7 | 定义 Pending Repository 端口和 Redis 数据契约，包含 `version/status/claimId/claimExpiresAt/originalQuery/候选/分析模式` | `ai-agent-study-domain` 中新增 Pending 模型和 Repository 接口 | Task 1 | Redis Key、10 分钟 TTL、60 秒 Claim 窗口和单 session 单 Pending 契约固定；核心 domain 不依赖 Redis | pass |
| Task 8 | 实现 Redis Pending 原子操作：创建/覆盖、CAS 推进、Claim、释放、比较删除和超时重领 | `ai-agent-study-infrastructure` 中新增 `RedisStockResolutionPendingRepository`、Lua 脚本或等价原子实现及测试 | Task 7 | 并发回复只能一个请求接管；旧请求不得覆盖或删除新状态；TC-108~TC-120、TC-210~TC-218、TC-225~TC-227 通过 | pass |
| Task 9 | 调整 3201 契约：只抽取 `stockNameQuery`、明确 `stockCode` 和 `analysisMode`；Story 2 最终态移除 3201 的 `read_skill/search_stock_by_name` 名称工具 | `IntentRoutingPrompt.java`、`IntentRoutingService.java`、结构化 Validator、`AiClientNode` 工具配置及测试 | Task 1 | 3201 不查缓存、不生成股票代码；Story 1 独立基线与 Story 2 最终基线区分清楚；TC-301~TC-302 通过 | pass |
| Task 10 | 实现 `StockRequestResolver` 状态机：Pending 确定性接管优先、空股票、0/1/多候选、分析模式澄清、候选外名称/代码切换，并保留 `originalQuery` 与已确认模式 | `ai-agent-study-domain` 中新增 `StockRequestResolver`、`AnalysisDepthFollowUpResolver`、Resolver 决策对象及测试 | Task 4、Task 7、Task 8、Task 9 | 每轮只问一个问题；0 候选不创建 Pending；候选外股票切换规则正确；TC-006~TC-009、TC-013~TC-024、TC-107~TC-113、TC-221~TC-224 通过 | pass |
| Task 11 | 将 Resolver 接入 `IntentRoutingNode/RoutingResultHandler`，统一澄清 SSE 和 Pending 清理；QUICK 使用 Java 固定模板组装 `executionQuery` 后继续调用 `GeneralChatNode` 的文本契约 | `IntentRoutingNode.java`、`RoutingResultHandler.java`、`GeneralChatNode.java` 现有调用边界及测试 | Task 10 | 有效 Pending 回复优先于 3201 误判；GeneralChat 不读取 `StockSlot`；只发送 `summary/clarification + complete`；TC-014、TC-023~TC-024、TC-305、TC-310、TC-314 通过 | pass |
| Task 12 | 接通 FULL 分支：只把已解析的规范股票身份交给现有 `TradingRequestNode`，继续执行 `TargetContextFactory` 权威预检；直接 Trading API 绕过名称索引 | `TradingRequestNode.java`、`TargetContextFactory.java`、Trading 集成测试 | Task 10、Task 11 | 只有 FULL 创建 run；QUICK 不创建 run；每次请求新 runId；直接 API 和 6002-6013 不回归；TC-010、TC-114~TC-115、TC-303~TC-313 通过 | pass |
| Task 13 | 完成跨模块装配、配置绑定、指标与日志；记录索引状态、刷新结果、查询耗时、候选数和 Pending 生命周期，不记录 Token 或完整 Redis 值 | `ai-agent-study-app` Bean 装配与配置；现有 observability/metrics 组件 | Task 5、Task 6、Task 8、Task 11、Task 12 | Spring 上下文无循环依赖；配置可启动；日志与指标满足第 10 节；TC-315 通过 | pass |
| Task 14 | 执行 Story 2 全部单元、集成、真实 Tushare、性能和全仓回归验证，并回填任务、AC、TC 状态 | `docs/superpowers/test/2026-07-16-stock-name-completion-test.md`；相关模块测试 | Task 1~Task 13 | TC-001~TC-024、TC-101~TC-126、TC-201~TC-227、TC-301~TC-315 全部通过；约 6,000 条查询达到 P95/P99 指标；通过后将对应状态改为 `pass` | pass |
