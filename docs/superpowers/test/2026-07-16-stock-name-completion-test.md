# 测试方案：A股股票名称补全与二次澄清

## 1. 测试背景

### 1.1 对应 Story

- Story 与设计：`docs/superpowers/plans/2026-07-16-stock-name-completion-design.md`
- 前置 Story：`docs/superpowers/plans/2026-08-03-trading-intent-routing-consolidation-story.md`

### 1.2 测试目标

- 验证应用启动时从 Tushare 全量热加载股票名称目录，并每日原子刷新 JVM 索引。
- 验证索引 7 天有效期、刷新失败保护和 `NOT_READY/READY/EXPIRED` 状态符合 Story 契约。
- 验证精确 Map、连续子串 List、唯一候选和多候选二次澄清行为正确。
- 验证 3201 只提取 `stockNameQuery`，Java 完成候选解析和 Pending 选择校验。
- 验证 Story 1 的身份预检、run 隔离、SSE 所有权和直接 Trading API 不发生回归。

### 1.3 测试范围

- `ai-agent-study-domain`：`StockSlot`、3201 Schema/Prompt、`StockRequestResolver`、股票名称索引、
  双维度 Pending 领域契约、统一路由和 SSE 终止协议。
- `ai-agent-study-trading-api`：股票目录、候选和解析结果契约。
- `ai-agent-study-trading-domain`：已解析 FULL 请求与 `TradingRequestNode` 协作。
- `ai-agent-study-trading-infra`：Tushare 股票目录源适配器。
- `ai-agent-study-infrastructure`：Redis Pending 仓储实现。
- `ai-agent-study-app`：启动热加载、调度装配和跨模块集成。

重点组件：

- `StockNameIndex`
- `StockNameRefreshService`
- `StockNameResolutionService`
- `StockRequestResolver`
- `StockResolutionPendingRepository`
- `TradingRequestNode`
- `IntentRoutingNode`
- `TargetContextFactory`
- `TradingStarter`

### 1.4 不在本次测试范围

- 拼音、错别字、编辑距离、语义向量和额外别名检索。
- 多股票同时分析和包含股票分析的多任务执行。
- 候选按钮、结构化前端卡片或新的前端请求字段。
- 行情、财务、新闻、情绪等动态数据缓存策略调整。
- 真实生产 Redis、生产 MySQL 或生产 Tushare 作为自动化测试依赖。

---

## 2. 测试策略

### 2.1 测试分层

| 测试层级 | 是否覆盖 | 说明 |
|------|------|------|
| 单元测试 | 是 | 验证索引、匹配、状态机、过期和选择分支 |
| 集成测试 | 是 | 验证 3201 到 Trading 启动前的模块协作 |
| 接口测试 | 是 | 验证 AutoAgent SSE 和直接 Trading API 兼容性 |
| 回归测试 | 是 | 验证 Story 1、普通意图和 Trading pipeline 行为 |
| 性能测试 | 是 | 验证约 6,000 条目录的本地查询延迟 |
| 手工验证 | 是 | 验证真实 Tushare 数据、定时刷新和同 JVM 并发 Pending |

### 2.2 测试原则

- 自动化测试固定使用 `UNIFIED` 路由模式。
- Tushare、Redis、时钟、调度器、3201 ChatClient 和 Trading pipeline 统一使用 mock 或测试替身。
- 每个测试用例必须有明确断言，不依赖固定 `sleep` 验证异步行为。
- 时间相关测试注入 `Clock`，精确覆盖 7 天和 10 分钟边界。
- 原子替换测试使用并发屏障或可控执行器，不依赖偶然线程时序。

### 2.3 Mock 策略

| 依赖项 | 是否 Mock | Mock 方式 | 说明 |
|------|------|------|------|
| Tushare 全量接口 | 是 | Stub/Mockito | 控制正常、空、少量、非法、重复和异常响应 |
| Tushare 精确名称兜底 | 是 | Stub/Mockito | 控制唯一、多条、空和严格调用异常，不回写索引 |
| 3201 ChatClient | 是 | 固定结构化输出 | 只验证 `stockNameQuery` 和意图槽位 |
| Redis | 是 | 内存 Pending 仓储/Mockito | 验证 Key、TTL、删除和异常分类 |
| `Clock` | 是 | 固定时钟 | 验证索引及 Pending 精确过期边界 |
| 调度器 | 是 | 直接调用刷新入口 | 不等待真实 cron |
| `TargetContextFactory` | 是 | Spy/Stub | 验证调用次数、参数和失败传播 |
| Trading pipeline | 是 | Spy/Stub | 验证是否创建 run 并进入 Trading |
| SSE sender | 是 | 内存同步 sender | 精确断言事件类型、顺序和次数 |

---

## 3. 测试场景设计

### 3.1 正常场景

| 编号 | 场景名称 | 前置条件 | 输入 | 预期结果 | status |
|------|------|------|------|------|------|
| TC-001 | 启动全量热加载 | Tushare 返回非空合法记录 | 应用启动 Runner | 构建 `READY` 索引，记录数、刷新时间和过期时间正确 | append |
| TC-002 | 标准名称精确匹配 | 索引包含北方华创 | `北方华创` | Map 精确命中并补齐 `002371` | append |
| TC-003 | 后缀连续子串匹配 | 索引包含北方华创 | `华创` | List 返回北方华创候选 | append |
| TC-004 | 前缀连续子串匹配 | 索引包含华创云信 | `华创` | List 返回华创云信候选 | append |
| TC-005 | 中间连续子串唯一匹配 | 只有一个名称包含目标片段 | 名称中间片段 | Java 自动补齐规范名称和代码 | append |
| TC-006 | 股票和模式同时不明确 | `华创` 命中两只股票 | `分析华创` | 保存双维度 Pending，只发送股票候选澄清，不创建 run | append |
| TC-007 | 序号选择后继续澄清模式 | Pending 的模式未确认 | `1`、`第一个` | 确认第一候选，保留 Pending，再询问快速/完整 | append |
| TC-008 | 完整名称选择进入 QUICK | Pending 已记录 `QUICK` | `北方华创` | 确认股票、清除 Pending、进入 `GeneralChatNode` | append |
| TC-009 | 六位代码选择进入 FULL | Pending 已记录 `FULL` | `002371` | 确认股票、清除 Pending、进入 `TradingRequestNode` | append |
| TC-010 | 明确代码跳过名称索引 | 股票索引不可用，身份 Provider 可用 | `002371` | 不查询名称索引，直接进入 `TargetContextFactory` | append |
| TC-011 | 每日刷新成功 | 当前索引可用 | 触发刷新入口 | 原子发布新索引并重置 7 天有效期 | append |
| TC-012 | 刷新失败保留旧索引 | 当前索引未过期 | Tushare 抛异常 | 旧索引继续服务，失败计数增加 | append |
| TC-013 | 候选外名称切换股票 | session 已有“查华创昨天收盘价”的 Pending 且已确认分析模式 | `贵州茅台` | 将候选外 `stockNameQuery` 作为新股票查询，覆盖旧候选、生成新 version，保留原始业务问题和已确认模式，并按贵州茅台的新查询结果继续路由 | append |
| TC-014 | 非股票意图清除 Pending | session 已有股票 Pending | 无法按 Pending 规则确定性解析且 3201 明确判为 `GENERAL_CHAT` 的请求 | 普通路由执行，股票 Pending 被删除 | append |
| TC-015 | 唯一股票但模式不明确 | 北方华创唯一命中 | `分析北方华创` | 保存已解析股票，只询问快速/完整 | append |
| TC-016 | 多候选且模式已为 QUICK | `华创` 命中两只股票 | `简单看看华创` | 只询问股票，选择后进入 GeneralChat | append |
| TC-017 | 多候选且模式已为 FULL | `华创` 命中两只股票 | `完整分析华创` | 只询问股票，选择后进入 TradingRequestNode | append |
| TC-018 | 未就绪索引远端唯一兜底 | 索引为 `NOT_READY`，远端精确名称返回唯一结果 | 完整股票名称 | Java 补齐名称和代码，按 analysisMode 继续路由，不刷新本地索引 | append |
| TC-019 | 过期索引远端多候选兜底 | 索引为 `EXPIRED`，远端精确名称返回多条 | 完整股票名称 | 创建候选 Pending 并澄清，不使用过期索引 | append |
| TC-020 | 未就绪索引远端空结果 | 索引为 `NOT_READY`，远端精确名称返回空 | 不存在的完整名称 | 返回股票不存在，不创建 Pending 或 run | append |
| TC-021 | 空股票槽位保留完整分析模式 | 无 Pending，3201 识别 `FULL` 但未提取名称或代码 | `完整分析一只股票` | 创建 `stockTarget=UNRESOLVED, analysisMode=FULL` Pending，只询问股票名称或六位代码 | append |
| TC-022 | 股票和模式都为空 | 无 Pending，两个维度均未解析 | 股票相关但无标的的请求 | 创建双维度未解析 Pending，先询问股票，不询问模式 | append |
| TC-023 | Pending 有效回复优先于 3201 意图误判 | session 存在股票或模式 Pending，3201 本轮返回 `GENERAL_CHAT` | `1`、`第一个`、有效模式或候选外新股票名称/代码 | Java 先确定性推进或覆盖 Pending，不清除状态、不按普通聊天分支执行；两个维度确认后进入对应节点 | append |
| TC-024 | QUICK 确定性执行 Query 组装 | Pending 保存“我想查华创昨天的收盘价”，Java 已解析北方华创 `002371` 且模式为 `QUICK` | 二次回复 `1` | 不调用 LLM 改写；按固定模板生成包含原始问题、规范名称和代码的 `executionQuery`；`GeneralChatNode` 只接收该普通文本且不读取 `StockSlot` | append |

### 3.2 异常场景

| 编号 | 场景名称 | 前置条件 | 输入 | 预期结果 | status |
|------|------|------|------|------|------|
| TC-101 | 首次加载调用失败 | 尚无可用索引 | Tushare 超时 | 状态为 `NOT_READY`，应用可启动；不安排额外刷新重试，只等待每日 `03:30` 任务；后续名称请求使用请求级远端兜底 | append |
| TC-102 | 首次加载返回空列表 | 尚无可用索引 | 空数据 | 拒绝发布，状态为 `NOT_READY` | append |
| TC-103 | 单次全量接口调用 | 触发启动或定时刷新 | 拉取上市股票目录 | 只调用一次 `stock_basic(list_status=L)`，不传 `limit/offset`，不按交易所拆分 | append |
| TC-104 | 源代码格式非法 | 刷新数据包含非法 `ts_code` | 非法批次 | 拒绝发布整个批次 | append |
| TC-105 | 股票代码重复 | 刷新数据含重复六位代码 | 重复批次 | 拒绝发布，不静默覆盖 | append |
| TC-106 | 股票名称为空 | 刷新数据含空名称 | 非法批次 | 拒绝发布整个批次 | append |
| TC-107 | 名称无候选 | 索引 `READY` | 不存在的名称片段 | 业务结果为 `NOT_FOUND`，通过 `CLARIFICATION` 协议明确回复股票不存在；不创建 Pending、run，也不追问分析模式 | append |
| TC-108 | Pending 序号越界 | Pending 只有两个候选 | `3` | 保留 Pending 并再次澄清 | append |
| TC-109 | Pending 候选外代码切换目标 | Pending 不含指定代码且已记录 `FULL` | 其他合法六位代码 | 原子覆盖旧候选并生成新 version，保留 `FULL`，权威预检新代码后进入 `TradingRequestNode` | append |
| TC-110 | Pending 已过期 | Redis Key 已过期 | `第一个` | 提示重新输入股票名称，不创建 run | append |
| TC-111 | Redis 写入失败 | 多候选需要创建 Pending | `分析华创` | 返回 `ERROR`，不发送可继续选择的假澄清 | append |
| TC-112 | Redis 读取失败 | session 可能存在 Pending | 二次回复 | 返回 `ERROR`，不猜测候选 | append |
| TC-113 | 索引过期 | 最近成功刷新已满 7 天 | 名称查询 | 不使用过期索引，改走 Java 远端精确名称兜底 | append |
| TC-114 | 身份预检未找到 | 名称唯一解析成功 | 权威查询返回空 | 复用 Story 1 `CLARIFICATION`，不启动 Trading | append |
| TC-115 | 身份 Provider 失败 | 名称唯一解析成功 | Provider 抛异常 | 复用 Story 1 `ERROR`，保留 cause | append |
| TC-116 | 同 JVM 并发选择 | 两个请求线程读取同一 version | 同时回复同一有效选择 | 只有一个 Claim 成功并进入执行节点 | append |
| TC-117 | 部分推进 CAS 冲突 | Pending 已被另一请求更新 | 使用旧 version 更新 | CAS 失败，重新读取，不覆盖新状态 | append |
| TC-118 | 旧请求完成删除新 Pending | 旧请求已 Claim，随后新查询覆盖 | 旧请求完成回调 | version/claimId 不匹配，不删除新 Pending | append |
| TC-119 | Claim 写入失败 | 两个维度已确认 | Redis Lua 失败 | 返回 `ERROR`，不进入任何执行节点 | append |
| TC-120 | 接管前失败释放 | Claim 成功但执行节点未接管 | 可重试系统错误 | 按 claimId 恢复 PENDING 并生成新 version | append |
| TC-121 | Tushare API 业务错误 | 严格调用收到 `code=40101` | 错误 Token 响应 | 抛 `TushareApiException` 并保留 code、msg、apiName | append |
| TC-122 | Tushare 传输错误 | 严格调用执行 HTTP | SSL、连接或超时异常 | 抛 `TushareTransportException` 并保留 cause | append |
| TC-123 | Tushare 协议错误 | HTTP 成功 | 非法 JSON 或必要结构缺失 | 抛 `TushareProtocolException` | append |
| TC-124 | 全量接口正常空数据 | `code=0, items=[]` | 启动或刷新 | Client 返回空列表，刷新服务拒绝发布并分类为数据异常 | append |
| TC-125 | 旧调用兼容 | 使用原 `callGeneric()` | API、网络或解析错误 | 维持日志加空列表降级语义 | append |
| TC-126 | 请求级远端兜底失败 | 索引为 `NOT_READY/EXPIRED` | 远端 API、传输或协议异常 | 返回 `ERROR` 并保留异常分类，不伪装成股票不存在 | append |

### 3.3 边界场景

| 编号 | 场景名称 | 前置条件 | 输入 | 预期结果 | status |
|------|------|------|------|------|------|
| TC-201 | 重复标准名称 | 两个代码拥有相同标准名称 | 完整名称 | Map 返回两条并进入澄清，不覆盖记录 | append |
| TC-202 | 查询包含空白 | 索引可用 | ` 华 创 ` | 规范化后按 `华创` 查询 | append |
| TC-203 | 拉丁字母大小写 | 包含 ST 名称 | 大小写不同输入 | NFKC 和大写规范化后正确匹配 | append |
| TC-204 | ST 有效字符保留 | 索引含 `*ST` 名称 | `*ST` 片段 | 星号和 ST 不被删除 | append |
| TC-205 | 单字符大量候选 | 大量名称包含同一字符 | 单字符 | 扫描完整集合，只返回候选上限并报告总数，不创建 Pending | append |
| TC-206 | 精确匹配优先 | 完整名称同时是其他名称子串 | 完整标准名称 | 只返回精确 Map 结果，不进入模糊扫描 | append |
| TC-207 | 候选稳定排序 | 多候选输入顺序随机 | 相同查询重复执行 | 候选顺序始终按名称和代码稳定 | append |
| TC-208 | 索引刚好未满 7 天 | 固定时钟为过期前 1 ms | 名称查询 | 索引仍可用 | append |
| TC-209 | 索引刚好达到 7 天 | 固定时钟等于 `expiresAt` | 名称查询 | 状态为 `EXPIRED` | append |
| TC-210 | Pending 刚好未满 10 分钟 | 固定时钟为过期前 1 ms | 有效选择 | 选择成功 | append |
| TC-211 | Pending 刚好达到 10 分钟 | 固定时钟等于过期时间 | 有效选择文本 | 按过期处理 | append |
| TC-212 | JVM 刷新重叠 | 启动、定时入口并发触发 | 两个刷新调用 | 只有一个远端全量调用执行 | append |
| TC-213 | 刷新期间并发读取 | 旧索引可用，新索引构建中 | 连续查询 | 每次只观察完整旧版或完整新版 | append |
| TC-214 | 约 6,000 条查询性能 | JVM 完成预热 | 10,000 次混合查询 | P95 小于 5 ms，P99 小于 10 ms | append |
| TC-215 | Claim 超时重新领取 | Claim 已超过 60 秒 | 同一 JVM 的后续请求重试 | 原子替换 claimId，只有新请求获得执行权 | append |
| TC-216 | 有效推进刷新 TTL | Pending 接近过期 | 有效股票或模式选择 | CAS 成功并把 TTL 刷新为 10 分钟 | append |
| TC-217 | 无效选择不刷新 TTL | Pending 接近过期 | 非法序号 | 继续澄清但 TTL 不延长 | append |
| TC-218 | 重复完成幂等 | Claim 已完成删除 | 再次 complete | 不报错、不影响其他 Pending | append |
| TC-219 | 首次失败后恢复 | 索引为 `NOT_READY` | 后续全量刷新成功 | 原子发布新索引，状态变为 `READY`，设置新的 `loadedAt/expiresAt` | append |
| TC-220 | 过期后恢复 | 索引为 `EXPIRED` | 后续全量刷新成功 | 不要求重启，原子发布新索引并恢复 `READY`，有效期重新计算为 7 天 | append |
| TC-221 | 可用索引零候选不远端重查 | 索引为 `READY` | 本地名称查询零候选 | 返回股票不存在，远端名称查询调用次数为 0 | append |
| TC-222 | 请求级兜底不回写索引 | 索引为 `NOT_READY/EXPIRED` | 远端名称查询成功 | 只返回当前请求结果，索引内容、状态和时间戳均不变化 | append |
| TC-223 | 候选外代码切换后继续澄清模式 | Pending 股票多候选且模式未确认 | 候选外合法六位代码 | 覆盖股票候选并保留 `analysisMode=UNRESOLVED`，确认新股票后只询问快速/完整 | append |
| TC-224 | 候选外名称重新解析 | Pending 存在多个股票候选且已记录 `FULL` | 候选列表外的股票名称 | 覆盖旧候选并生成新 version，保留 `originalQuery` 和 `FULL`；新名称唯一时解析为新股票，多候选时发布新 Pending，0 候选时返回股票不存在且不恢复旧候选 | append |
| TC-225 | 同 session 多标签页覆盖 | 标签页 A 已创建华创 Pending | 同 session 的标签页 B 创建平安 Pending，随后 A 回复 `1` | B 的新 version 覆盖 A；`1` 只按当前平安 Pending 解释，旧 version 不得修改或删除当前状态 | append |
| TC-226 | 不同 session 多标签页隔离 | 两个标签页使用不同 session | 分别创建并选择不同股票 Pending | 两份 Pending 独立推进，互不覆盖或消费 | append |
| TC-227 | Pending 阶段空槽位回复 | 已有股票或模式澄清 Pending，TTL 接近过期 | 3201 未提取名称、代码或有效模式 | 保留当前 Pending 并重复当前澄清，不查询本地或远端数据，不刷新 TTL | append |

### 3.4 回归场景

| 编号 | 场景名称 | 前置条件 | 输入 | 预期结果 | status |
|------|------|------|------|------|------|
| TC-301 | AutoAgent 3201 唯一意图入口 | `UNIFIED` 模式 | AutoAgent 名称股票请求 | 只执行一次 3201 意图识别，不恢复 6001；不影响直接 Trading API | append |
| TC-302 | 3201 不装配名称工具 | Story 2 配置生效 | 构建 3201 | 不含 `read_skill` 和 `search_stock_by_name` | append |
| TC-303 | TradingRequestNode 身份预检 | 股票已确认且模式为 FULL | 股票请求 | Coordinator 分派后调用 `TargetContextFactory` 一次 | append |
| TC-304 | 每次请求新 run | 同一 session 连续两次选择股票 | 两次有效请求 | 两次 `runId` 不同，Trading 状态不交叉 | append |
| TC-305 | 主会话历史复用 | 前一轮发送候选澄清 | 二次回复 | 3201 可读取历史，Root 持久化文本不变 | append |
| TC-306 | StockInfo 初始化兼容 | 身份预检成功 | 启动 Trading | `populateStockInfo()` 仍调用一次并写入上下文 | append |
| TC-307 | 原始数据缓存契约兼容 | 同股票不同 run | 检查缓存 Key 工厂与配置 | 既有 Key 和 TTL 不增加 runId；不要求证明当前 Provider 已产生缓存命中 | append |
| TC-308 | 直接 Trading API 兼容 | 直接接口可用 | 明确代码请求 | 不经过名称索引，原输入输出不变 | append |
| TC-309 | exchange 边界兼容 | Provider 返回交易所 | 报告和导出 | `TargetContext.targetId` 权威，展示字段仍保留 | append |
| TC-310 | analysisDepth 追问兼容 | 股票已确认、分析模式缺失 | 后续补充深度 | QUICK 进入 GeneralChat，FULL 进入 TradingRequestNode | append |
| TC-311 | 股票多任务门禁 | 请求含股票分析和其他任务 | 多任务请求 | 整轮仍按 Story 1 拒绝，不创建 Pending 或 run | append |
| TC-312 | 普通意图回归 | 无股票意图 | GENERAL_CHAT、PE、巡检 | 原路由和执行行为不变 | append |
| TC-313 | 分析节点工具兼容 | 6002-6013 已装配 | 应用启动 | 既有工具集合保持不变 | append |
| TC-314 | SSE 所有权兼容 | 多候选终止路由 | AutoAgent 请求 | 一次 clarification、一次 complete，外层只关闭一次 emitter | append |
| TC-315 | 模块依赖方向 | 读取模块 POM 和 Spring Bean 依赖 | 编译与上下文启动 | domain 不反向依赖实现模块，Resolver 不直接依赖执行节点 | append |

---

## 4. 用例与代码映射

| 测试编号 | 对应用例方法 | 目标类/方法 | 覆盖类型 | 说明 |
|------|------|------|------|------|
| TC-001、101~106、208~213、219~220 | `should_publish_only_valid_complete_index_when_refresh_finishes()` 等 | `StockNameRefreshServiceTest`、`StockNameIndexHolderTest` | 正常/异常/边界 | 热加载、刷新、有效期、恢复和原子性 |
| TC-002~005、107、201~207、214 | `should_scan_fuzzy_records_when_exact_name_is_absent()` 等 | `StockNameIndexTest`、`StockNameResolutionServiceTest` | 正常/异常/边界 | 精确和连续子串匹配 |
| TC-006~009、015~024、108~113、126、210~211、221~227 | `should_build_quick_execution_query_without_llm_rewrite()` 等 | `StockRequestResolverTest`、`StockNameResolutionServiceTest`、`StockResolutionPendingRepositoryTest` | 正常/异常/边界 | Pending 确定性接管、QUICK 执行 Query、空槽位、双维度 Pending、候选外名称/代码切换、多标签页边界、远端兜底和执行节点选择 |
| TC-010、114~115、303~304、306 | `should_start_trading_only_after_name_and_identity_resolution()` 等 | `TradingRequestNodeTest`、`TargetContextFactoryTest` | 正常/异常/回归 | Trading 前置身份边界 |
| TC-014、301~302、305、310~314 | `should_keep_single_routing_and_terminal_sse_ownership()` 等 | `IntentRoutingNodeTest`、`RoutingResultHandlerTest`、`AiClientNodeToolIsolationTest` | 回归 | Story 1 路由和 SSE 兼容 |
| TC-307~309 | `should_preserve_existing_trading_data_and_direct_api_contracts()` 等 | `TradingStarterPipelineTest`、`TradingAnalysisControllerTest` | 回归 | Trading 内部与直接入口兼容 |
| TC-001、006~010、018~024、301~306、314 | `should_complete_stock_name_resolution_before_new_trading_run()` | `StockNameCompletionIntegrationTest` | 集成 | 端到端核心链路、Pending 接管优先级、QUICK Query 组装、空槽位与索引不可用兜底 |
| TC-315 | `should_keep_stock_resolution_module_dependencies_acyclic()` | 模块 POM 静态测试、Spring 上下文测试 | 回归 | Maven 与 Bean 依赖方向 |
| TC-116~120、215~218 | `should_allow_only_one_claim_for_the_same_pending_version()` 等 | `RedisStockResolutionPendingRepositoryTest` | 异常/边界 | version CAS、Claim、释放和幂等完成 |
| TC-121~126 | `should_distinguish_empty_data_from_api_transport_and_protocol_errors()` 等 | `TushareApiClientTest`、`StockNameRefreshServiceTest`、`StockNameResolutionServiceTest` | 异常/回归 | 刷新、远端兜底的严格调用与旧入口兼容 |

---

## 5. 关键校验点

### 5.1 数据正确性

- 目录业务记录只包含 `stockName` 和六位 `stockCode`。
- 精确 Map 和模糊 List 引用相同记录，不静默覆盖重复名称。
- `StockSlot.stockNameQuery` 不作为最终股票身份，解析后必须同时存在规范名称和代码。
- 最终 Trading 身份始终来自 `TargetContext.targetId`。
- QUICK 和 FULL 共用同一份规范股票解析结果，只有 FULL 创建 `TargetContext`。
- QUICK 由 Java 固定模板生成 `executionQuery`，保留 `originalQuery` 的业务要求，不调用 LLM 改写；
  `GeneralChatNode` 不读取 `StockSlot` 或 Pending。

### 5.2 状态流转正确性

- 索引允许 `NOT_READY -> READY`、`READY -> READY`、`READY -> EXPIRED` 和
  `EXPIRED -> READY`；所有成功发布都重置 `loadedAt/expiresAt`。
- 多候选和无候选阶段不创建 `runId`。
- 股票选择成功但分析模式未确认时继续保留 Pending，不创建 `runId`。
- 两个维度全部确认后先 Claim，并在节点接管后删除；覆盖、过期和非股票转向按契约清理 Pending。
- 活跃 Pending 的有效结构化回复由 Java 在执行节点选择前优先接管；只有无法确定性解析且 3201
  明确为非股票意图时才清除 Pending。
- 每次有效 Trading 请求创建新的 `runId`。

### 5.3 异常处理正确性

- 无候选返回 `NOT_FOUND` 业务结果，并通过 `CLARIFICATION` 协议明确回复股票不存在；非法选择和
  Pending 过期返回可继续输入的 `CLARIFICATION`。
- 索引不可用时先走请求级远端精确名称兜底；只有兜底失败和 Redis 故障返回 `ERROR`。
- 刷新失败不能清空未过期旧索引。
- 身份预检失败继续使用 Story 1 的三类领域异常。

### 5.4 日志、监控与告警

- 是否需要校验日志输出：是。
- 关键日志和指标：索引状态、记录数、刷新耗时、索引年龄、刷新失败次数、查询耗时、候选数、
  Pending 创建/命中/过期次数、sessionId、runId 和 targetId。
- 日志不得记录 Tushare Token 或完整 Redis 值。

### 5.5 Story 验收覆盖矩阵

| Story 验收项 | 覆盖测试 | status |
|------|------|------|
| AC-001~AC-005 | TC-001~TC-005、TC-103、TC-201~TC-207 | append |
| AC-006~AC-008 | TC-005~TC-009、TC-107~TC-110 | append |
| AC-009~AC-012 | TC-011~TC-012、TC-101~TC-106、TC-212~TC-213 | append |
| AC-013~AC-014 | TC-006~TC-009、TC-108~TC-112、TC-301~TC-303 | append |
| AC-015~AC-019 | TC-001、TC-011~TC-012、TC-101~TC-106、TC-113、TC-208~TC-209 | append |
| AC-020~AC-022 | TC-006~TC-010、TC-013~TC-014、TC-108~TC-112、TC-210~TC-211、TC-314 | append |
| AC-023 | TC-214 | append |
| AC-024~AC-026 | TC-006~TC-009、TC-015~TC-017、TC-303、TC-310 | append |
| AC-027 | TC-315 | append |
| AC-028 | TC-116~TC-120、TC-215~TC-218 | append |
| AC-029~AC-030 | TC-121~TC-125 | append |
| AC-031 | TC-107 | append |
| AC-032 | TC-006~TC-009、TC-015~TC-017 | append |
| AC-033 | TC-219~TC-220 | append |
| AC-034 | TC-101 | append |
| AC-035 | TC-018~TC-020、TC-113、TC-126 | append |
| AC-036 | TC-221~TC-222 | append |
| AC-037 | TC-109、TC-118、TC-223~TC-224 | append |
| AC-038 | TC-225~TC-226 | append |
| AC-039 | TC-021~TC-022、TC-217、TC-227 | append |
| AC-040 | TC-023 | append |
| AC-041 | TC-024 | append |

---

## 6. 执行计划

### 6.1 自动化测试执行

| 步骤 | 内容 | 预期结果 | status |
|------|------|------|------|
| 1 | 补充 trading-api 契约和 trading-infra 索引/刷新测试 | 数据与生命周期分支通过 | append |
| 2 | 补充 trading-domain 解析、Pending 和请求节点测试 | 唯一、多候选和异常分支通过 | append |
| 3 | 补充 domain 路由、工具隔离和 SSE 回归测试 | Story 1 边界无回归 | append |
| 4 | 补充 app 启动热加载与跨模块集成测试 | 核心链路通过 | append |
| 5 | 执行 `mvn -pl ai-agent-study-trading/ai-agent-study-trading-infra -am test` | 索引和刷新测试通过 | append |
| 6 | 执行 `mvn -pl ai-agent-study-trading/ai-agent-study-trading-domain -am test` | 解析和 Trading 测试通过 | append |
| 7 | 执行 `mvn -pl ai-agent-study-domain,ai-agent-study-app -am test` | 路由和集成回归通过 | append |
| 8 | 执行全仓 `mvn test` | 编译及全仓测试通过 | append |

### 6.2 手工验证步骤

| 步骤 | 操作 | 预期结果 | status |
|------|------|------|------|
| 1 | 使用真实 Tushare 启动应用 | 启动阶段加载约 6,000 条并进入 `READY` | append |
| 2 | 输入“分析华创” | 返回稳定编号的多个候选，不创建 Trading run | append |
| 3 | 分别回复序号、完整候选名称、候选内代码、候选外名称和候选外代码，再选择快速或完整 | 前三种选中候选；候选外名称或六位代码切换目标并保留模式；QUICK 不创建 run，FULL 创建新 run | append |
| 4 | 模拟 Tushare 连续刷新失败 | 7 天内旧索引可用，到期后名称请求改走远端精确查询兜底 | append |
| 5 | 同一 JVM 并发处理同一 session | version CAS 和 Claim 保证只有一个请求接管 | append |
| 6 | 输入六位代码并调用直接 Trading API | 两类明确代码入口均不受名称索引状态影响 | append |
| 7 | 同 session 和不同 session 分别打开两个标签页并交叉输入 | 同 session 仅最新 Pending 生效；不同 session 相互隔离 | append |
| 8 | 输入“完整分析一只股票”，再回复无法识别的内容 | 首轮保留 FULL 并只问股票；无效回复重复股票澄清且不刷新 TTL | append |

---

## 7. 验收标准

| 编号 | 验收项 | 标准 | status |
|------|------|------|------|
| AC-T01 | 正常主流程 | TC-001~TC-024 全部通过 | append |
| AC-T02 | 异常处理 | TC-101~TC-126 全部通过 | append |
| AC-T03 | 边界行为 | TC-201~TC-227 全部通过 | append |
| AC-T04 | 核心回归 | TC-301~TC-315 全部通过 | append |
| AC-T05 | 启动与刷新 | 单次热加载、失败不重试、每日刷新、7 天过期和原子替换通过 | append |
| AC-T06 | 二次澄清 | 候选选择、候选外名称/代码切换、TTL、同 JVM 并发、多标签页边界和非法选择通过 | append |
| AC-T07 | 性能 | 约 6,000 条索引查询 P95/P99 达到 Story 指标 | append |
| AC-T08 | 编译与全仓测试 | `mvn test` 成功，无既有测试回归 | append |

---

## 8. 风险与说明

| 风险点 | 影响 | 应对措施 |
|------|------|------|
| 自动化测试不调用真实 Tushare | 无法覆盖真实权限和数据规模变化 | 保留真实环境手工加载与数量核对 |
| Redis 测试替身不能覆盖真实网络故障 | Pending 读写仍有环境风险 | 在测试环境执行 Redis 断连和恢复验证 |
| 7 天场景不适合真实等待 | 时间测试可能不稳定 | 注入 `Clock`，使用固定时间推进 |
| 性能结果受 CI 机器影响 | 绝对延迟可能波动 | 先验证算法基线，性能门禁使用固定数据和预热 |
| Story 1 尚未实施完成 | Story 2 集成测试类可能暂时无法落地 | 先完成 Story 1 前置契约，再执行跨 Story 用例 |

---

## 9. 执行结果记录

### 9.1 执行结果

| 项目 | 结果 | status |
|------|------|------|
| 单元测试 | 待执行 | append |
| 集成测试 | 待执行 | append |
| 接口回归 | 待执行 | append |
| 性能测试 | 待执行 | append |
| 手工验证 | 待执行 | append |
| 全仓编译测试 | 待执行 | append |

### 9.2 结论

- 是否达到提测或合并条件：否。
- 当前结论：测试设计已完成，所有执行项初始状态为 `append`；实现完成并全部转为 `pass` 后方可提测。
