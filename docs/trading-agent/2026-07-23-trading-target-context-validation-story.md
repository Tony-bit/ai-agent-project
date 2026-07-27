# Story: Trading Agent 标的身份锚定与节点结果硬校验

| 字段 | 内容 |
|------|------|
| 创建日期 | 2026-07-23 |
| 状态 | pending |
| 优先级 | P0 |
| 适用模块 | `ai-agent-study-trading` |
| 数据源 | Tushare、现有新闻数据源 |
| Prompt 来源 | `ai_client_system_prompt`（`prompt_type=STEP`） |
| 参考项目 | TauricResearch/TradingAgents、openai/codex |

---

## 1. 背景与问题

在分析 `601318 中国平安` 时，路由节点正确识别了股票代码，技术指标输入也属于
`601318`，但后续多空辩论和研究主管输出混入了 `001309 德明利` 的公司名称、ROE、
业绩增长、历史价格和跌停叙事。错误内容随后被下游节点重复引用，形成了错误的“多节点一致性”。

当前系统已经具备共享上下文：每次股票分析创建独立 `TradingStateContext`，并通过
`DynamicContext["trading_context"]` 向各 role 暴露同一个 `TradingContextVO`。问题不在于
缺少共享状态，而在于：

1. 股票身份只是普通字段，没有形成初始化后不可修改的运行契约。
2. Java 上下文中存在 `StockInfoVO`，但实际传给 LLM 的 Prompt 不一定包含完整身份。
3. 节点输出以自由文本或宽松 JSON 为主，无法稳定执行 Schema 和语义校验。
4. 节点结果写入 `TradingContextVO` 前没有标的身份硬校验。
5. Research Manager 主要读取 Bull/Bear 历史，错误观点重复后容易被当作交叉验证。
6. 缓存、Chat Memory、运行结果缺少统一的标的和运行命名空间规则。

本 Story 在现有 `DynamicContext + TradingContextVO + TradingPipeline` 架构上增加可信身份和
结果提交边界，不重写状态机，也不引入新的 Agent 框架。

## 2. 用户故事

作为 Trading Agent 用户，我希望系统从路由到最终推荐始终分析同一只股票；当任一节点输出
其他股票的身份或无法由当前输入支撑的关键数据时，系统应拒绝该结果进入下游，而不是继续生成
仓位、价格和推荐结论。

作为系统维护者，我希望一次分析运行拥有可追踪的 `runId` 和唯一 `targetId`，所有节点输入、
输出、日志和运行态都能按这两个标识关联，并能通过自动化测试复现和阻断串股问题。

## 3. 目标

- 路由 LLM 只产生候选股票，Java 根据 Tushare 权威数据创建不可变 `TargetContext`。
- 每只股票分析流水线生成独立 `runId`，整条流水线内保持不变。
- 现有 Java V1 Prompt 完整持久化到数据库，作为可审计、可回滚的历史版本。
- 每个 run 启动时冻结本轮 Prompt 快照，所有 Trading role 使用同一时点的数据库配置。
- 所有 Trading Agent role 的 V2 Prompt 均注入同一份标的身份上下文。
- 所有 LLM 节点输出改为可解析、可校验的结构化 payload。
- Java 为节点 payload 包装可信 `NodeResultEnvelope`，身份字段不由 LLM 生成。
- 节点结果提交到 `TradingContextVO` 前执行 Schema、身份、实体和关键数据校验。
- 明确串股或数据污染时 fail-closed，不进入后续辩论、风控和推荐阶段。
- 建立运行态、Chat Memory 和数据缓存的隔离规则。
- 增加针对 `601318/001309` 的污染注入回归测试。

## 4. 非目标

- 第一阶段不实现逐条事实的持久化 `EvidenceStore` 和完整 `evidenceId` 血缘系统。
- 第一阶段不引入第二个 LLM 作为事实审核器。
- 不引入 Apollo、Nacos 或新的 Prompt 配置中心。
- 不通过联网搜索对每个节点输出做二次事实核查。
- 不重构现有 Trading Pipeline、状态机阶段和 SSE 协议。
- 不禁止新闻报告提及同行、交易对手或行业相关公司；相关实体必须来自当前节点输入。
- 不把行业阈值作为股票身份的主要判断依据。
- 不因 `runId` 不同而禁用同一股票、同一日期原始数据的安全缓存复用。

## 5. 核心概念与边界

| 对象 | 生成方 | 生命周期 | 职责 |
|------|--------|----------|------|
| `sessionId` | 会话层 | 用户会话 | 多轮对话关联 |
| `runId` | Java | 单只股票的一次完整分析 | 隔离本次运行的状态、报告和日志 |
| `targetId` | Java/Tushare | 股票身份有效期 | 标准股票标识，A 股使用 `ts_code` |
| `traceId` | 观测层 | 一次调用链 | Langfuse、日志链路追踪 |
| `TargetContext` | Java | 单次运行，只读 | 本次分析标的是谁 |
| `TradingContextVO` | Java Pipeline | 单次运行，可演进 | 保存各阶段报告和决策 |
| `NodeResultEnvelope` | Java | 单节点结果 | 节点 payload 的可信身份外壳 |

约束：

- `runId` 在每次 `TradingStarter.start()` 或每个股票型 `startForSubTask()` 启动时生成。
- `targetId` 对 A 股统一使用 Tushare `ts_code`，例如 `601318.SH`。
- 不同时保存可独立修改的 `targetId` 和 `tsCode`；`targetId` 即权威 `ts_code`。
- 6 位 `stockCode` 从 `targetId` 派生，避免两份代码发生漂移。
- `TargetContext` 创建后不可修改，也不提供 setter。
- LLM 返回的股票代码、名称和 `runId` 只能作为待校验信号，不能覆盖 Java 身份。

## 6. 数据契约

### 6.1 路由候选

路由节点继续输出结构化候选，但该结果不是最终可信身份：

```json
{
  "entityMention": "中国平安",
  "ticker": "601318",
  "resolutionStatus": "RESOLVED"
}
```

Java 必须使用候选 ticker 查询股票基础信息，并确认返回记录唯一且有效。

### 6.2 TargetContext

建议在 trading API 或 domain 的稳定值对象层新增不可变记录：

```java
public record TargetContext(
        String runId,
        String targetId,
        String stockName,
        String industry,
        LocalDate asOfDate
) {
    public String stockCode() {
        return targetId == null || targetId.length() < 6
                ? null : targetId.substring(0, 6);
    }
}
```

字段规则：

- `runId`：Java 生成的 UUID，不能为空。
- `targetId`：标准 `ts_code`，匹配 `^[0-9]{6}\.(SH|SZ|BJ)$`。
- `stockName`：Tushare `stock_basic.name`，不能为空。
- `industry`：Tushare 行业分类，允许为空，只用于辅助判断，不参与唯一身份判定。
- `asOfDate`：本次分析的数据截止日期，不默认等同于系统当前日期。

当前工程只支持 A 股，因此不在 `TargetContext` 中保存 `market`、`currency` 和 `exchange`：

- 市场固定为 A 股。
- 货币固定为人民币。
- 交易所可由 `targetId` 的 `.SH/.SZ/.BJ` 后缀派生。

### 6.3 NodeResultEnvelope

LLM 只生成各节点自己的业务 payload，Java 编排层添加可信外壳：

```java
public record NodeResultEnvelope<T>(
        String runId,
        String targetId,
        String nodeName,
        Instant generatedAt,
        T payload
) {}
```

`runId`、`targetId`、`nodeName`、`generatedAt` 均由 Java 注入。LLM 不负责生成完整
`StockInfoVO`，也不能通过 payload 修改目标身份。

## 7. 标的解析与初始化流程

### 7.1 可信身份生成

新增 `TargetContextFactory` 或等价领域服务：

```text
路由候选 ticker/name
  -> ticker 格式标准化
  -> Tushare stock_basic 查询
  -> 校验返回 ts_code/name
  -> Java 生成 runId
  -> 创建 TargetContext
  -> 写入 TradingContextVO
```

规则：

- 路由 ticker 与 Tushare 返回代码不一致：拒绝启动分析。
- 路由名称与 Tushare 名称不一致：如果路由名称只是用户 mention，可记录解析结果；如果路由明确
  声称已解析为另一个标准名称，则拒绝或重新澄清。
- Tushare 返回空、多条或非法 `ts_code`：拒绝启动分析。
- `StockInfoVO` 保留行情等可变数据；身份权威字段来自 `TargetContext`。

### 7.2 runId 生成位置

- `TradingStarter.start()`：每次调用生成一个新 `runId`。
- `TradingStarter.startForSubTask()`：每只股票子任务生成独立 `runId`。
- 同一流水线内所有分析师、辩论者、风控和推荐节点使用同一个 `runId`。
- 多股票请求可以保留外层 `parentRunId`，但第一阶段不要求新增该字段。

## 8. Prompt 数据库版本管理与身份锚定

### 8.1 复用现有 Prompt 版本设施

不新增 YAML 版本开关，不引入 Apollo，也不在 Java 中继续扩展 `*_PROMPT_V2` 常量。复用现有
`ai_client_system_prompt` 表及以下字段：

```text
prompt_id
prompt_type
version
status
change_desc
prompt_content
```

Trading role 使用 `prompt_type=STEP`。`prompt_id` 统一采用 role 的 `clientId`：

| Role | clientId / promptId |
|------|---------------------|
| Fundamental Analyst | `6002` |
| Technical Analyst | `6003` |
| Sentiment Analyst | `6004` |
| News Analyst | `6005` |
| Bull Researcher | `6006` |
| Bear Researcher | `6007` |
| Research Manager | `6008` |
| Portfolio Manager | `6009` |
| Neutral Risk Analyst | `6010` |
| Conservative Risk Analyst | `6011` |
| Aggressive Risk Analyst | `6012` |
| Recommendation Node | `6013` |

`IntentRoutingNode/6001` 在 `TargetContext` 创建之前执行，不属于本次身份 Prompt 快照；它继续负责
输出股票候选。外层多任务汇总节点只消费已经校验的 Trading 结果，不在本次 `6002~6013` Prompt
集合内。

同一个数字可以同时存在 SYSTEM Prompt 和 STEP Prompt，通过 `prompt_type` 精确区分。禁止继续混用
`fundamental_analyst` 等符号型 promptId 和 `6002` 等 clientId，否则 Repository 按 clientId 组装
Flow Config 时无法命中版本记录。

### 8.2 V1 归档与 V2 初始化

新增 Flyway 数据迁移脚本，包含两类内容：

1. 表结构或索引缺失时通过 DDL 补齐；现有环境已经具备时不得重复修改。
2. 通过 DML 将当前 Java Prompt 常量的完整内容持久化为 `version=1`，再插入带身份锚定和结构化
   输出契约的 `version=2`。

严格来说，建表和字段变更属于 DDL，Prompt 正文存档属于 DML。二者可以放在同一个受版本控制的
Flyway migration 中，但必须在注释和任务中明确区分。

迁移后的目标状态示例：

```text
prompt_id=6002, prompt_type=STEP, version=1, status=0  // 当前代码中的 V1，历史存档
prompt_id=6002, prompt_type=STEP, version=2, status=1  // 本 Story 的 V2，当前生效
```

要求：

- V1 内容必须与迁移前 Java 常量逐字一致，保证可审计和可回滚。
- `6002~6013` 每个 role 都必须保存 V1 和 V2，不允许只迁移部分节点。
- 同一 `prompt_id + prompt_type` 同时只能有一个 `status=1`。
- 单个 role 激活可复用现有 `activateVersion`；整套 Trading Prompt 上线必须在一个数据库事务中
  完成 `6002~6013` 的批量激活，避免新 run 读取到 V1/V2 混合集合。
- 批量激活前必须校验 12 个 role 的目标版本全部存在、模板合法且输出 Schema 兼容；任一失败则
  整个事务回滚。
- Java Prompt 常量在数据库迁移和回归验证完成前保留；节点切换到数据库后只作为迁移来源和短期
  对照，不再作为运行时静默 fallback。
- 数据库缺少生效 Prompt 时本次 Trading run 直接失败，不能退回缺少身份锚定的 V1 常量。

### 8.3 run 级 Prompt 快照

不能让每个节点执行时单独查询当前生效版本。否则运行过程中切换数据库版本，可能出现 Analyst
使用 V1、Research Manager 使用 V2 的混合运行。

在 `TradingStarter` 创建 `runId` 和 `TargetContext` 后，一次性加载 `6002~6013` 当前生效的
STEP Prompt，形成不可变快照：

```java
public record TradingPromptSnapshot(
        String runId,
        Map<String, PromptVersion> prompts
) {}

public record PromptVersion(
        String promptId,
        Integer version,
        String content,
        String contentHash
) {}
```

约束：

- 快照属于当前 run，创建后不可修改。
- 数据库版本切换只影响下一次 run，不能改变正在执行的 run。
- 任一必需 role 缺少生效 Prompt、存在多个生效版本或模板校验失败时，拒绝启动 Pipeline。
- 日志和最终报告元数据记录每个 role 的 `promptId/version/contentHash`。
- `TradingPromptSnapshotFactory` 在 run 启动时通过一次批量查询直接读取数据库生效 STEP Prompt，
  不复用默认 5 分钟 TTL 的 `AgentRuntimeConfigCache`，保证事务激活成功后的下一次 run 立即生效。
- 批量查询结果必须携带 `promptId/version/content`，不能只返回丢失版本信息的旧 Flow Config VO。
- 每个 run 只执行一次 Prompt 批量查询，后续节点只读快照，不再访问数据库或运行配置缓存。

### 8.4 命名占位符

V2 不继续使用位置型 `%s`。当前 Fundamental Prompt 增加 industry 参数后发生字段整体错位，说明
位置参数不适合数据库动态模板。V2 使用受控的命名占位符，例如：

```text
{{targetContext}}
{{stockData}}
{{analystReports}}
{{debateHistory}}
{{riskReports}}
{{outputContract}}
```

新增统一 `TradingPromptRenderer`：

- 每个 role 维护允许的占位符白名单。
- 渲染前检查缺失占位符、未知占位符和未替换占位符。
- 业务数据只能填入指定变量，不能通过字符串位置顺序绑定。
- 模板激活前执行同一套静态校验，非法模板不得变为 `status=1`。

`targetContext` 渲染内容统一为：

```text
本次唯一分析标的：
- 股票代码：601318
- TS代码：601318.SH
- 股票名称：中国平安
- 行业：保险
- 数据截止日期：2026-07-22

所有分析、工具调用、报告和投资判断必须只针对该标的。
不得把其他公司作为本次分析主体；若输入资料出现其他公司，只能按资料中的关联关系引用。
不得使用模型记忆补充输入资料中不存在的公司事实或数值。
```

所有 Trading role 的 V2 Prompt 必须包含 `{{targetContext}}`。工具参数由 Java 从
`TargetContext.targetId` 派生，不能让 LLM 自由选择 ticker。Prompt 锚定是降低错误概率的软约束，
不能替代结果校验。

## 9. 节点结构化输出

### 9.1 输出原则

- 优先使用当前 Spring AI 版本支持的 native structured output 或类型映射。
- 每个节点定义明确 DTO、枚举、必填字段和数值范围。
- 不再通过“截取第一个 `{` 到最后一个 `}`”作为正常解析路径。
- 不允许结构化解析失败后静默退化成可提交的自由文本。
- 需要保留自然语言时，放在 `summary`、`rationale` 或 `content` 字段中。

### 9.2 节点最小公共语义

每类 payload 至少能表达：

- 分析结论或立场。
- 关键依据列表。
- 风险或数据质量问题。
- 自然语言总结。
- 可选的 `targetEcho`，用于检测模型是否理解目标；该字段不作为权威身份。

Bull/Bear 当前的 Markdown 输出需要定义结构化 `ResearchArgumentPayload`；Research Manager、三个风险
角色、Portfolio Manager 和 Recommendation 分别定义对应 payload，避免下游继续解析自由文本。

## 10. 提交前硬校验

新增 `NodeResultValidator`，并接入 `NodeResultCommitter` 之前。校验成功才能修改
`TradingContextVO`。

### 10.1 校验层级

| 层级 | 校验内容 | 失败处理 |
|------|----------|----------|
| Schema | JSON 可解析、字段完整、枚举和数值范围合法 | 可带错误反馈重试一次 |
| Envelope | `runId/targetId/nodeName` 与当前执行作用域一致 | 直接拒绝 |
| Target Echo | 模型回显的代码和名称与 `TargetContext` 一致 | 直接拒绝 |
| Foreign Entity | 输出中的其他股票代码、公司名称是否来自允许输入 | 未授权实体直接拒绝 |
| Input Consistency | 价格、ROE、技术指标等关键数值是否与当前节点输入一致 | 明显冲突直接拒绝 |
| Data Quality | 日期、人民币计价、百分比单位和缺失状态是否一致 | 拒绝或降级，按规则分类 |

### 10.2 外部实体规则

不能简单禁止所有其他公司名称，因为新闻和行业比较可能合法出现。每个节点调用前构造
`AllowedEntitySet`：

- 当前 `TargetContext` 中的股票代码和名称。
- 当前节点输入资料明确出现的相关公司和代码。
- 配置允许的指数、行业和宏观实体。

输出中出现不在允许集合内的股票代码或公司名称时，判定为模型自行引入的未授权实体。对于
`601318` 任务，若输入未出现 `德明利/001309`，节点输出出现该实体必须拒绝。

### 10.3 关键数据一致性

第一阶段不建设完整 EvidenceStore，但必须复用节点原始输入做基础比对：

- 技术节点输出的当前价、均线、RSI、MACD 等不得与输入值明显冲突。
- 基本面节点输出的 ROE、利润率、增长率不得创造输入中不存在的精确值。
- 新闻节点不得把未出现在新闻输入中的事件写成事实。
- 下游辩论节点不得引入上游报告和允许实体集合之外的新精确公司事实。

第二阶段再将这些规则升级为逐条 `evidenceId` 引用。

### 10.4 失败策略

- Schema 格式问题：最多重试一次，向模型提供精简的校验错误。
- 明确 target mismatch、未授权股票实体或跨股票关键数据：不重试或仅允许一次清空式重试；仍失败则
  将运行置为 `INVALID_DATA`/`ERROR`。
- 身份污染不得只记录 warning 后继续进入投资计划、风险和最终推荐。
- 非身份类的单个分析师普通失败，可沿用现有“至少一个分析师成功”的策略，但最终结论必须明确
  数据缺失。

## 11. Research Manager 输入改造

Research Manager 不能只读取 Bull/Bear 文本。输入至少包含：

```text
TargetContext
经过校验的分析师结构化报告
Bull/Bear 结构化历史
各节点校验状态与数据质量警告
当前轮次
```

Manager 必须明确区分：

- 多个节点独立引用同一原始输入，不等于多源交叉验证。
- 上游被标记为无效或未提交的结果不得进入辩论历史。
- 数据不足时可以输出 `INSUFFICIENT_DATA`，不得用模型常识补齐事实。

## 12. 上下文与缓存隔离

### 12.1 运行态隔离

- 每只股票、每次分析创建独立 `TradingStateContext` 和 `TradingContextVO`。
- `DynamicContext["trading_context"]` 只作为兼容入口，节点执行以当前
  `TradingStateContext.getTradingContext()` 引用为准。
- 多股票子任务不得共用一个可写 `TradingContextVO`。
- 运行报告、临时结果和节点状态使用 `runId + targetId` 关联。

### 12.2 Chat Memory 隔离

Trading role 的 Chat Memory conversation key 使用：

```text
trading:{sessionId}:{runId}:{targetId}:{nodeName}
```

除非有明确需求，分析节点不读取同一 session 下其他股票的自由对话历史。跨股票经验应进入独立、
经过脱敏和抽象的记忆机制，不能直接复用原始报告文本。

### 12.3 数据缓存隔离

原始行情和财务数据允许跨 run 复用，缓存键不包含随机 `runId`，而应包含：

```text
provider + apiName + targetId + asOfDate/dateRange + normalizedParams + schemaVersion
```

示例：

```text
tushare:daily:601318.SH:20260701-20260722:v1
tushare:fina_indicator:601318.SH:20251231:v1
```

节点生成报告、辩论历史和最终决策属于运行产物，必须使用 `runId + targetId`，不能放入仅按股票
代码命名的共享缓存。

## 13. Tushare 与单位契约修正

当前实验性改动在正式实现前必须完成以下修正：

1. `stock_basic.industry` 是合法字段，但返回中文行业分类，不能只用英文关键词匹配。
2. Tushare `fina_indicator.roe`、`grossprofit_margin`、`debt_to_assets` 使用百分数值，例如
   `12.5` 表示 `12.5%`；不得按 `0.125` 处理。
3. 当前 `debtToEquity` 实际装入 `debt_to_assets`，需要改名或修正数据来源，不能混用语义。
4. Fundamental Prompt 增加行业字段时必须同步增加模板占位符，避免参数整体错位。
5. Bull/Bear 数据警告文本必须使用正确 UTF-8 内容，不能提交乱码。
6. 数据 guard 必须接入同步 `AnalystCollectionStage` 的提交后、辩论前路径；仅注入 Bean 不算接入。
7. 行业阈值只产生辅助 warning，不作为 target mismatch 的唯一依据。

## 14. Phase 2 扩展：EvidenceStore

第一阶段稳定后增加：

```java
public record Evidence(
        String evidenceId,
        String targetId,
        String source,
        String metric,
        Object value,
        String unit,
        LocalDate asOfDate
) {}
```

节点的关键 claim 引用 `evidenceIds`，Validator 校验证据存在、属于当前 targetId、单位一致且数值匹配。
该扩展解决“报告外层身份正确，但正文引用了其他股票数字”的更细粒度问题，不阻塞第一阶段交付。

## 15. 主要代码改造

| 范围 | 改造内容 |
|------|----------|
| API/Domain | 新增不可变 `TargetContext`、节点结构化 payload 和 `NodeResultEnvelope` |
| Routing | 路由只输出候选；Java 通过 Tushare 确认最终身份 |
| Starter | 每只股票流水线生成 `runId`，初始化 `TargetContext` |
| Context | `TradingContextVO` 持有只读 `TargetContext`，身份不可被节点覆盖 |
| Prompt DB | 将代码中的 V1 存档为 version=1，新增 V2 并按 clientId 管理 STEP Prompt |
| Prompt Activation | 事务内校验并激活完整的 `6002~6013` Prompt 版本集合 |
| Prompt Snapshot | run 启动时批量直读数据库并冻结生效版本和内容哈希 |
| Prompt Renderer | 使用命名占位符渲染数据库模板，覆盖所有 Trading role |
| Analyst | 四类分析师使用结构化输出，保留自然语言 summary 字段 |
| Debate | Bull/Bear 和 Research Manager 改为结构化 payload |
| Risk | 三类风险角色、Portfolio Manager、Recommendation 改为结构化 payload |
| Validation | 新增 Schema、身份、允许实体、关键数值和单位校验 |
| Pipeline | Validator 接入每个节点提交前；身份污染终止运行 |
| Cache/Memory | 定义并接入 target/run 命名空间规则 |
| Observability | 日志和 SSE 携带 `runId/targetId/nodeName/validationStatus` |
| Tests | 单元、Pipeline 集成、污染注入和多股票隔离测试 |

## 16. 可观测性

每次节点调用至少记录：

```text
runId
targetId
nodeName
promptVersion
inputSnapshotHash
outputSchemaVersion
validationStatus
validationErrors
latencyMs
```

约束：

- 不记录 Tushare Token、模型 API Key 或完整敏感配置。
- 身份校验失败使用独立错误码，例如 `TARGET_MISMATCH`、`FOREIGN_ENTITY`、
  `INPUT_DATA_CONFLICT`、`INVALID_SCHEMA`。
- SSE 可以向用户展示“节点数据校验失败，本次分析已停止”，不直接暴露内部 Prompt。

## 17. 验收标准

| 编号 | 验收项 | 标准 |
|------|--------|------|
| AC-001 | runId 创建 | 每次单股票分析生成新 runId，同一流水线所有节点一致 |
| AC-002 | 子任务隔离 | 多股票请求中每只股票拥有独立 runId、TargetContext 和 TradingContextVO |
| AC-003 | 身份权威 | TargetContext 由 Java 和 Tushare 创建，LLM 不能覆盖 |
| AC-004 | V1 存档 | 6002~6013 的当前 Java Prompt 以 version=1 完整存入数据库 |
| AC-005 | 原子激活 | 6002~6013 在同一事务切换版本，失败时全部回滚 |
| AC-006 | Prompt 快照 | 同一 run 的所有节点只使用启动时批量直读并冻结的数据库 Prompt 版本 |
| AC-007 | Prompt 覆盖 | 所有 Trading V2 Prompt 均包含命名占位符 `targetContext` |
| AC-008 | 结构化输出 | 所有 LLM 节点使用明确 DTO/Schema，不以自由文本解析作为正常路径 |
| AC-009 | 提交边界 | 节点结果未经 Validator 成功不得写入 TradingContextVO |
| AC-010 | 串股阻断 | 601318 输入下输出未授权的德明利/001309 时结果被拒绝 |
| AC-011 | 错误终止 | 明确身份污染后不执行仓位、价格和最终推荐节点 |
| AC-012 | 合法关联实体 | 新闻输入中真实出现的相关公司可以在限定字段中被引用 |
| AC-013 | 数值冲突 | 当前价 52.89 的输入不得生成并提交“当前价 482” |
| AC-014 | 单位一致 | ROE、利润率、负债率统一按 percent 契约处理并通过测试 |
| AC-015 | Manager 完整输入 | Research Manager 同时读取 TargetContext、有效分析报告和辩论历史 |
| AC-016 | 缓存隔离 | 不同 targetId 的缓存键不可碰撞，同 targetId 同参数可跨 run 复用原始数据 |
| AC-017 | Memory 隔离 | 不同 runId/targetId 的 Trading role 不共享原始 Chat Memory |
| AC-018 | 回归兼容 | 正常 601318 和其他 A 股完整分析可生成最终结果，SSE 生命周期不退化 |

## 18. 测试场景

### 18.1 单元测试

- `TargetContextFactory` 正确创建 `601318.SH/中国平安`。
- 非法、空或多条 Tushare 身份结果被拒绝。
- `runId` 非空且不同运行不重复。
- 数据库 V1 内容与迁移前对应 Java Prompt 常量一致。
- `6002~6013` 可按 `prompt_type=STEP` 查询唯一生效版本。
- 12 个 role 批量激活任一失败时事务回滚，旧版本集合继续完整生效。
- Prompt 快照在 run 内固定，数据库切换版本后仅新 run 使用新版本。
- 新 run 直接读取激活后的数据库版本，不受 Runtime Config Cache TTL 影响。
- Prompt renderer 在所有 role 中包含相同 `targetId/stockName/asOfDate`。
- 缺失、未知和未替换的命名占位符导致模板校验失败。
- Envelope 身份由 Java 注入，payload 无法覆盖。
- Schema 缺字段、枚举非法、数值越界时拒绝。
- ROE `12.5` 按 `12.5%` 处理，不乘以 100。

### 18.2 污染注入测试

- Technical Analyst 输出 `001309` 或“德明利”时拒绝提交。
- Fundamental Analyst 输入 ROE 不含 `67.65`，输出该精确值时拒绝。
- Bull/Bear 从模型记忆引入“49倍净利增长、五连跌停、¥980 到 ¥482”时拒绝。
- Research Manager 即使 Bull/Bear 同时包含同一错误，也不能把重复错误视作交叉验证。
- 最终节点收到无效上游状态时不得输出 BUY/SELL、仓位或目标价。

### 18.3 合法实体测试

- 新闻输入明确包含中国太保、新华保险时，新闻报告允许引用这些公司。
- 输入不包含德明利时，任何节点不得自行引入德明利。
- 行业指数、上证指数等配置允许实体不被误判为串股。

### 18.4 隔离测试

- 同一 session 先分析 `001309.SZ`，再分析 `601318.SH`，第二次不出现第一次报告内容。
- 多股票子任务串行执行时，每个结果保留自己的 runId 和 targetId。
- 为未来并行执行增加测试：两个 TradingStateContext 不共享可写 TradingContextVO。
- 原始行情缓存对相同 target/date 命中，对不同 target 不命中。

## 19. 实施任务

| Task | 内容 | 状态 |
|------|------|------|
| Task 0 | 清理并修正当前实验性 guard、Prompt 参数和单位契约问题 | pass |
| Task 1 | 定义 `TargetContext`、`NodeResultEnvelope` 和校验错误模型 | pass |
| Task 2 | 实现路由候选到 Tushare 权威身份的 `TargetContextFactory` | pass |
| Task 3 | 在 `TradingStarter` 和股票子任务入口生成并传播 runId | pass |
| Task 4 | 新增数据库迁移：将 6002~6013 的现有 Java V1 Prompt 完整存档 | pass |
| Task 5 | 新增数据库 V2 Prompt，并实现完整 role 集合的事务激活和回滚 | pass |
| Task 6 | 实现 `TradingPromptSnapshot`，在 run 启动时冻结生效版本 | pass |
| Task 7 | 实现命名占位符 `TradingPromptRenderer` 和模板静态校验 | pass |
| Task 8 | 将四类 Analyst 输出迁移到严格结构化 DTO 和数据库 V2 Prompt | pass |
| Task 9 | 将 Bull/Bear/Research Manager 输出迁移到结构化 DTO 和数据库 V2 Prompt | pass |
| Task 10 | 将风险、Portfolio Manager、Recommendation 输出迁移到结构化 DTO 和数据库 V2 Prompt | pass |
| Task 11 | 实现 `NodeResultValidator` 和允许实体集合 | pass |
| Task 12 | 在每个节点结果提交前接入 Validator 和 fail-closed 流程 | pass |
| Task 13 | 改造 Research Manager 输入，纳入有效原始报告和校验状态 | pass |
| Task 14 | 落实运行态、Chat Memory 和数据缓存命名空间 | pass |
| Task 15 | 增加 Prompt 版本、内容哈希、错误码和用户可见失败事件 | pass |
| Task 16 | 完成单元、集成、污染注入和隔离回归测试 | pass |

## 20. 实施顺序与发布策略

建议按以下顺序发布，避免在结构化迁移未完成时同时修改全部节点：

1. 修正现有数据单位、Prompt 参数和 guard 接入问题，建立可信基线。
2. 将代码中的 V1 Prompt 原样写入数据库 `version=1`，完成逐 role 文本一致性测试。
3. 引入 `TargetContext/runId` 和 `TradingPromptSnapshot`，只读传播但暂不改变节点输出。
4. 写入并校验数据库 V2 Prompt，先在测试环境激活完整的 `6002~6013` 版本集合。
5. 按 Analyst -> Debate -> Risk/Final 顺序迁移结构化输出和数据库 Prompt 渲染。
6. 每迁移一组节点，同时接入对应 Validator，禁止出现“已结构化但未校验”的中间状态。
7. 所有 role 完成后激活 V2；版本切换通过数据库 `activateVersion` 完成，不增加 YAML 开关。
8. 最后启用全链路 fail-closed、缓存/Memory 隔离和污染回归集。

`TARGET_MISMATCH` 和未授权 `FOREIGN_ENTITY` 从 V2 启用第一天起必须硬拒绝，不能降级为
warning。数据库版本切换失败、版本集合不完整或模板非法时，不得启动新的 Trading run；已经持有
Prompt 快照的 run 不受影响并继续执行。

## 21. 完成定义

本 Story 完成时应满足：

- `601318 中国平安` 污染复现样本成为自动化测试并稳定通过。
- 当前 Java V1 Prompt 已按 `6002~6013 + STEP + version=1` 完整存档到数据库。
- 全部 Trading LLM role 从 run 级数据库 Prompt 快照读取模板，不再直接依赖 Java Prompt 常量。
- 全部 V2 Prompt 使用命名占位符并注入统一 TargetContext。
- 全部节点输出拥有明确 Schema，并经过统一提交校验。
- 任何明确跨股票身份污染不会进入 `DynamicContext/TradingContextVO`。
- Research Manager 和最终决策节点只能消费已校验结果。
- 多股票、多运行和 Chat Memory 不发生原始上下文串用。
- 当前实验性补丁中的行业、单位、字段语义、Prompt 参数和 guard 接入问题全部关闭。
- 文档、配置、错误码、日志和测试与实现保持一致。
