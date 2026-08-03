# 测试方案：Trading 意图路由职责合并

## 1. 测试背景

### 1.1 对应 Story 与设计

- Story：`docs/trading-agent/2026-08-03-trading-intent-routing-consolidation-story.md`
- 设计：`docs/superpowers/plans/2026-08-03-trading-intent-routing-consolidation-design.md`

### 1.2 测试目标

- 验证 `clientId=6001` 被安全删除，单任务股票分析只经过 3201 一次意图识别。
- 验证 `TradingRequestNode` 在进入 Trading 前完成请求构造、权威身份校验和失败分类。
- 验证 Trading Tool 白名单、分析类型映射、SSE 所有权及多任务门禁符合 Story 契约。
- 验证连续 Trading run 相互隔离，同时主会话历史和共享原始数据缓存继续复用。
- 验证 `V2030` 只删除 Trading Client 6001，不破坏 `prompt_id=6001` 及其它既有功能。

### 1.3 测试范围

- `ai-agent-study-domain`：3201 输出解析、工具装配、统一路由、SSE 终止响应、Root 持久化。
- `ai-agent-study-trading-domain`：类型映射、`TradingRequestNode`、`TargetContextFactory`、
  `TradingStarter` 和 run 隔离。
- `ai-agent-study-app`：Flyway DML 静态安全校验和跨模块集成。
- `ai-agent-study-trigger`：直接 `/trading/analysis` API 兼容性。

重点组件：

- `IntentRoutingService`
- `AiClientNode`
- `RoutingResultHandler`
- `IntentRoutingNode`
- `RootNode`
- `AnalysisTypeMapper`
- `TradingRequestNode`
- `TargetContextFactory`
- `TradingStarter`

### 1.4 不在本次测试范围

- `SPLIT` 模式股票分析。
- 股票简称、模糊匹配、多候选消歧、Pending 和跨请求二次澄清。
- 多股票或包含股票分析的多任务执行能力；本次只验证门禁拒绝。
- 请求幂等键、历史结论缓存和前端重复提交去重。
- 股票信息快照表、缓存刷新定时任务和缓存过期策略变更。

---

## 2. 测试策略

### 2.1 测试分层

| 测试层级 | 是否覆盖 | 说明 |
|------|------|------|
| 单元测试 | 是 | 覆盖结构化输出、映射、校验、异常分类和路由分支 |
| 集成测试 | 是 | 覆盖 3201 到 `TradingStarter` 的协作及连续 run 隔离 |
| 接口测试 | 是 | 覆盖直接 Trading API 的原入口兼容性 |
| 回归测试 | 是 | 覆盖 GENERAL_CHAT、PE、巡检、工具集合、缓存 Key 和数据库 Prompt |
| 手工验证 | 是 | 在 MySQL 测试库执行 Flyway，并观察前端 SSE 终止协议 |

### 2.2 测试原则

- 所有股票分析验收在 `intent.routing.mode=UNIFIED` 下执行。
- 中间件和外部服务统一 mock，只验证当前层业务逻辑。
- 每个自动化用例必须以明确的 `assert` 或 Mockito `verify` 结束。
- 对未找到、Provider 失败、数据完整性失败、SSE 断连和迁移误删分别覆盖。
- 禁止依赖真实 Tushare、真实 LLM、生产 Redis 或生产 MySQL 完成自动化测试。

### 2.3 Mock 策略

| 依赖项 | 是否 Mock | Mock 方式 | 说明 |
|------|------|------|------|
| 3201 ChatClient | 是 | Stub/Mockito | 返回固定结构化路由结果和工具调用结果 |
| `IStockDataProvider` | 是 | Mockito/Stub | 控制唯一、空、多条、非法和异常结果 |
| Trading ToolCallbacks | 是 | 测试 ToolCallback | 验证白名单后的最终工具名集合 |
| Trading pipeline | 是 | Spy/Stub | 统计执行次数并捕获 `TargetContext` |
| SSE sink/emitter | 是 | 内存 Sink/Mockito | 断言事件类型、顺序、次数和关闭所有者 |
| 会话历史存储 | 是 | 内存仓储/Mockito | 断言持久化文本和下一轮读取 |
| 股票原始数据缓存 | 是 | Spy/内存缓存 | 断言 Key 不包含 runId，允许跨 run 命中 |
| MySQL/Flyway | 自动化中是 | SQL 静态校验与内存数据模型 | 不用 H2 模拟 MySQL 方言 |

---

## 3. 测试场景设计

### 3.1 正常场景

| 编号 | 场景名称 | 前置条件 | 输入 | 预期结果 | status |
|------|------|------|------|------|------|
| TC-001 | 完整名称启动分析 | 3201 可调用名称搜索 | 对药明康德进行完整投资分析 | 唯一解析为 `603259.SH`，创建一个新 run 并执行 pipeline | append |
| TC-002 | 六位代码启动分析 | Provider 返回唯一身份 | `603259` | 不调用名称搜索，权威校验后启动 Trading | append |
| TC-003 | 标准代码启动分析 | Provider 返回唯一身份 | `603259.SH` | ticker 保持为权威 `603259.SH` | append |
| TC-004 | 分析类型单选 | 类型 Mapper 可用 | `TECHNICAL` | 只选择 `TECHNICAL` 分析师 | append |
| TC-005 | 分析类型组合 | 类型 Mapper 可用 | `FUNDAMENTAL,NEWS` | 选择对应两个分析师 | append |
| TC-006 | 默认全部分析师 | 类型为空或 `ALL` | 空值、`ALL` | 使用 Trading 当前默认全部分析师 | append |
| TC-007 | 已验证目标启动 | 身份预检成功 | 已验证 `TargetContext` | `TradingStarter` 使用同一对象且不重复查询身份 | append |
| TC-008 | 连续换股分析 | 同一 session | 药明康德后分析兆易创新 | 两次 runId 不同，第二次标的和 Trading 上下文不含第一只股票 | append |
| TC-009 | 连续同股分析 | 同一 session | 连续两次相同 Query | 两次 pipeline 均执行，runId 不同 | append |
| TC-010 | 通用追问复用历史 | 前一轮 Trading 已持久化 | 刚才为什么建议持有 | 3201/GENERAL_CHAT 能读取主会话历史，不启动旧 Trading run | append |
| TC-011 | 普通多任务执行 | 任务列表不含股票分析 | PE 与通用问答组合 | 沿用现有多任务执行和汇总 | append |

### 3.2 异常场景

| 编号 | 场景名称 | 前置条件 | 输入 | 预期结果 | status |
|------|------|------|------|------|------|
| TC-101 | 股票身份不存在 | Provider 返回空列表 | 合法六位代码 | 登记 `CLARIFICATION`，不创建 runId，不调用 `TradingStarter` | append |
| TC-102 | 身份 Provider 超时 | Provider 抛超时异常 | 合法代码 | 登记 `ERROR`，保留 cause，不调用 `TradingStarter` | append |
| TC-103 | 身份 Provider 鉴权失败 | Provider 抛鉴权异常 | 合法代码 | 返回数据服务错误，不伪装成澄清 | append |
| TC-104 | 权威身份返回多条 | Provider 返回两条 | 合法代码 | 抛 `StockIdentityValidationException` 并停止 | append |
| TC-105 | 权威记录非法 | targetId 非法或名称为空 | 合法代码 | 返回身份校验错误并记录告警 | append |
| TC-106 | 请求名称与权威名称不一致 | Provider 返回另一名称 | ticker 与名称冲突 | 不进入 Trading pipeline | append |
| TC-107 | 股票槽位为空 | 路由为股票分析 | 无 `StockSlot` | 发送一次澄清和一次完成事件 | append |
| TC-108 | ticker 非法 | 路由为股票分析 | 五位、七位、字母代码 | 发送路由澄清，不查询 Provider | append |
| TC-109 | 非 A 股后缀 | 路由为股票分析 | `.HK`、`.US` | 拒绝并停止，不创建 run | append |
| TC-110 | 股票多任务门禁 | `multiTask=true` | 股票分析加通用任务 | 整轮拒绝，所有子任务均未执行 | append |
| TC-111 | Provider 错误 SSE 协议 | 已登记 `ERROR` | Provider 失败 | 只发送一个已完成 `error`，不追加澄清或完成事件 | append |
| TC-112 | 澄清 SSE 协议 | 已登记 `CLARIFICATION` | 股票不存在 | 依次发送一次 `summary/clarification` 和一次 `complete` | append |
| TC-113 | SSE 发送失败 | Sink 拒绝或客户端断开 | 任一路由终止结果 | 不重发，控制流正常返回并由外层清理 | append |
| TC-114 | 未知 Trading Tool | 白名单包含不存在名称 | 应用装配 | 启动失败并指出未知工具名 | append |
| TC-115 | 3201 非 JSON 输出 | 统一路由解析失败 | Markdown 报告 | 沿用重试/降级，不进入 Trading | append |

### 3.3 边界场景

| 编号 | 场景名称 | 前置条件 | 输入 | 预期结果 | status |
|------|------|------|------|------|------|
| TC-201 | ticker 空格与小写后缀 | Provider 返回唯一身份 | ` 603259.sh ` | 规范化为 `603259.SH` | append |
| TC-202 | 直接代码无名称 | Provider 返回唯一身份 | `stockName=null` | 使用权威名称创建 `TargetContext` | append |
| TC-203 | 废弃 exchange 冲突 | 旧请求可反序列化 | `603259` 加 `exchange=SZ` | 忽略 exchange，以权威 `603259.SH` 为准 | append |
| TC-204 | 代码自身后缀错误 | Provider 无对应记录 | `603259.SZ` | 按身份未找到停止，不使用 exchange 修正 | append |
| TC-205 | 类型大小写和空格 | Mapper 可用 | ` technical ` | 映射为 `TECHNICAL` | append |
| TC-206 | 类型组合含非法项 | Mapper 可用 | `TECHNICAL,UNKNOWN` | 忽略非法项，保留 `TECHNICAL` | append |
| TC-207 | 类型全部非法 | Mapper 可用 | `UNKNOWN` | 降级为默认全部并记录警告 | append |
| TC-208 | Tool 白名单缺少 Client | Client 不在 Map | 任意 Client | 不装配任何 Trading Tool | append |
| TC-209 | Tool 白名单为空 | Client 显式空列表 | 任意 Client | 不装配任何 Trading Tool | append |
| TC-210 | Tool 白名单重复项 | 配置包含重复工具名 | 3201 配置 | 去重后工具集合稳定 | append |

### 3.4 回归场景

| 编号 | 场景名称 | 前置条件 | 输入 | 预期结果 | status |
|------|------|------|------|------|------|
| TC-301 | 6001 不再装配 | 完整 Spring 测试上下文 | 应用启动 | 6001 Bean、Client 配置和 ChatMemory Key 均不存在 | append |
| TC-302 | 3201 工具集合 | 白名单生效 | 3201 | Trading Tool 集合严格等于 `read_skill`、`search_stock_by_name` | append |
| TC-303 | 分析节点工具兼容 | 白名单生效 | 6002-6013 | 每个 Client 的 Trading Tool 集合与改造前一致 | append |
| TC-304 | 非 Trading 工具兼容 | 白名单生效 | MCP、记忆和通用工具 | 装配集合不受 Trading 白名单影响 | append |
| TC-305 | GENERAL_CHAT 回归 | `UNIFIED` 模式 | 普通聊天 | 路由和历史上下文行为不变 | append |
| TC-306 | PE 与巡检回归 | `UNIFIED` 模式 | PE、巡检请求 | 原节点正常执行 | append |
| TC-307 | analysisDepth 回归 | 前一轮询问分析深度 | 完整投资分析 | 恢复股票对象并进入新 Trading run | append |
| TC-308 | 直接 Trading API 回归 | Controller 使用原入口 | 显式/默认分析师请求 | API 输入输出和默认值保持不变 | append |
| TC-309 | StockInfo 来源回归 | Trading 已启动 | 有效目标 | `populateStockInfo()` 调用一次并写入 `TradingContext` | append |
| TC-310 | Trading ChatMemory 隔离 | 同一 session 两个 run | 相同或不同股票 | Memory Key 包含各自 runId 和 targetId，不交叉读取 | append |
| TC-311 | 原始数据缓存兼容 | 同股票同参数两个 run | 获取原始数据 | 缓存 Key 不含 runId，仍具备跨 run 复用能力 | append |
| TC-312 | 交易所展示字段兼容 | Provider 返回交易所 | 生成报告/导出 | `StockInfoVO.exchange` 和搜索结果展示不变 | append |
| TC-313 | V2030 精确删除 | SQL 资源可读 | 静态扫描 | 只含类型化 Client 删除，不删除系统 Prompt | append |
| TC-314 | Prompt 6001 保留 | 测试数据含 ID 碰撞 | 应用 V2030 数据模型 | Prompt 数量和 3001 绑定不变，共享资源不变 | append |
| TC-315 | 新旧库最终状态一致 | 旧库升级与空库全迁移数据集 | 执行迁移模型 | 两类路径最终配置一致，3201 与 6002-6013 不变 | append |

---

## 4. 用例与代码映射

| 测试编号 | 对应用例方法 | 目标类/方法 | 覆盖类型 | 说明 |
|------|------|------|------|------|
| TC-001~003、115 | `should_route_stock_with_authoritative_slots()` 等 | `IntentRoutingServiceTest`、`IntentRoutingNodeTest` | 正常/异常 | 3201 输出与降级 |
| TC-004~006、205~207 | `should_map_analysis_type_when_input_is_supported()` 等 | 新增 `AnalysisTypeMapperTest` | 正常/边界 | 迁移 6001 映射语义 |
| TC-007、101~109、201~204 | `should_stop_before_trading_when_identity_resolution_fails()` 等 | 新增 `TradingRequestNodeTest`、`TargetContextFactoryTest` | 正常/异常/边界 | 请求构造与身份预检 |
| TC-010、110~113、305~307 | `should_send_one_terminal_protocol_when_route_stops()` 等 | `RoutingResultHandlerTest`、`IntentRoutingNodeTest`、`RootNodeTest` | 异常/回归 | 路由终止与持久化 |
| TC-114、208~210、302~304 | `should_apply_trading_tool_allowlist_by_client()` 等 | `AiClientNodeToolIsolationTest` | 异常/边界/回归 | 构建期工具隔离 |
| TC-008、009、309~311 | `should_create_isolated_run_for_each_request()` 等 | `TradingStarterPipelineTest`、`TradingChatMemoryTest`、`TradingNamespaceKeyFactoryTest` | 正常/回归 | run 隔离与缓存复用 |
| TC-301、313~315 | `should_remove_only_trading_client_6001()` 等 | 新增 `TradingClientRemovalMigrationTest` | 回归 | 6001 代码与数据迁移 |
| TC-308 | `should_keep_direct_trading_api_contract()` | 新增或补充 `TradingAnalysisControllerTest` | 回归 | 直接入口兼容 |
| TC-312 | `should_preserve_exchange_in_trading_output()` | Provider/导出既有测试 | 回归 | 展示字段兼容 |
| TC-001、008~010、305~311 | `should_execute_consolidated_trading_route_end_to_end()` | 新增 `TradingRoutingConsolidationIntegrationTest` | 集成 | 关键跨模块契约 |

---

## 5. Story 验收覆盖矩阵

| Story 验收项 | 覆盖测试 | status |
|------|------|------|
| AC-001~AC-010 | TC-001~TC-009、TC-115、TC-301~TC-302、TC-309 | append |
| AC-011~AC-017 | TC-114、TC-208~TC-210、TC-303~TC-308 | append |
| AC-018~AC-022 | TC-010~TC-011、TC-107、TC-110~TC-113 | append |
| AC-023~AC-025 | TC-004~TC-006、TC-205~TC-207 | append |
| AC-026~AC-029 | TC-007、TC-101~TC-106、TC-202、TC-308 | append |
| AC-030~AC-033 | TC-301、TC-313~TC-315 | append |
| AC-034 | 全部股票用例固定 `UNIFIED`；`SPLIT` 明确排除 | append |
| AC-035~AC-037 | TC-003、TC-201、TC-203~TC-204、TC-312 | append |
| AC-038~AC-041 | TC-008~TC-010、TC-310~TC-311 | append |

---

## 6. 关键校验点

### 6.1 数据正确性

- `StockAnalysisRequestVO.ticker` 在预检后等于 `TargetContext.targetId`。
- `stockName` 与权威名称一致；代码输入无名称时由权威记录补齐。
- `selectedAnalysts` 与 Mapper 结果一致。
- `StockInfoVO` 仍由 `populateStockInfo()` 获取并写入 Trading 上下文。

### 6.2 状态流转正确性

- 身份预检失败前不创建 runId，不调用 `TradingStarter`。
- 多任务门禁在任何子任务执行前终止整轮。
- 每个有效前端请求创建不同 runId；Trading 状态不跨 run。
- 主会话历史仍按 sessionId 提供给 3201 和 GENERAL_CHAT。

### 6.3 异常处理正确性

- 未找到、Provider 失败和身份完整性失败使用不同领域异常。
- `CLARIFICATION` 与 `ERROR` 终止协议互斥。
- Provider cause 被保留；SSE 断连不触发重复发送。

### 6.4 日志、监控与告警

- 是否需要校验日志输出：是。
- 关键日志点：
  - 每个 Client 最终 Trading Tool 集合。
  - Provider 失败的异常类型、ticker 和 cause。
  - 身份多条、非法或不一致的高优先级告警。
  - 多任务股票门禁命中。
  - 连续 run 的 runId、targetId 和 sessionId。

---

## 7. 执行计划

### 7.1 自动化测试执行

| 步骤 | 内容 | 预期结果 | status |
|------|------|------|------|
| 1 | 补充 domain 路由、工具白名单和 Root 单元测试 | 正常、异常和边界分支断言完成 | append |
| 2 | 补充 trading-domain 类型映射、身份预检和 Starter 测试 | 请求构造、异常分类和 run 隔离通过 | append |
| 3 | 补充 app 迁移静态测试与跨模块集成测试 | 6001 精确删除及主链路通过 | append |
| 4 | 补充 trigger 直接 Trading API 回归 | 原接口契约通过 | append |
| 5 | 执行 `mvn -pl ai-agent-study-domain -am test` | domain 测试通过 | append |
| 6 | 执行 `mvn -pl ai-agent-study-trading/ai-agent-study-trading-domain -am test` | trading-domain 测试通过 | append |
| 7 | 执行 `mvn -pl ai-agent-study-app,ai-agent-study-trigger -am test` | 集成与接口回归通过 | append |
| 8 | 执行 `mvn test` | 全仓测试通过 | append |

### 7.2 手工验证步骤

| 步骤 | 操作 | 预期结果 | status |
|------|------|------|------|
| 1 | 在包含 Client 6001 和 Prompt 6001 的 MySQL 测试库执行 Flyway 到 `V2030` | Client 关系清除，Prompt 及 3001 绑定保留 | append |
| 2 | 在空 MySQL 测试库执行全部 Flyway 迁移 | 最终状态与升级库一致 | append |
| 3 | 前端连续分析药明康德和兆易创新 | 两次 runId 不同，第二次无第一只股票的 Trading 内容 | append |
| 4 | 前端连续两次分析药明康德 | 两次完整执行，runId 不同，主会话历史连续 | append |
| 5 | 模拟无股票和 Provider 故障 | 分别显示澄清与错误，SSE 正常结束 | append |
| 6 | Trading 后输入“刚才为什么建议持有” | 通用对话能够结合上一轮主会话历史回答 | append |

---

## 8. 验收标准

| 编号 | 验收项 | 标准 | status |
|------|------|------|------|
| AC-T01 | 正常主流程 | TC-001~TC-011 全部通过 | append |
| AC-T02 | 异常处理 | TC-101~TC-115 全部通过 | append |
| AC-T03 | 边界行为 | TC-201~TC-210 全部通过 | append |
| AC-T04 | 核心回归 | TC-301~TC-315 全部通过 | append |
| AC-T05 | Story 覆盖 | Story AC-001~AC-041 均有测试映射且通过 | append |
| AC-T06 | 数据库兼容 | 新旧库到 `V2030` 状态一致且 Prompt 6001 保留 | append |
| AC-T07 | 编译与全仓测试 | `mvn test` 成功，无既有测试回归 | append |
| AC-T08 | SSE 生命周期 | 终止协议、发送次数和 emitter 所有权符合设计 | append |

---

## 9. 风险与说明

| 风险点 | 影响 | 应对措施 |
|------|------|------|
| 静态 SQL 测试不能完全替代 MySQL | 无法发现方言或真实数据差异 | 保留两条 MySQL 测试库手工验收 |
| LLM 工具调用具有随机性 | 自动化测试不稳定 | 使用固定 ChatClient 和 ToolCallback 测试替身 |
| SSE 异步时序导致偶发测试 | 事件次数或顺序断言波动 | 使用内存同步 Sink，不使用固定 sleep |
| 直接 API 与 AutoAgent 新入口并存 | 一个入口改动影响另一个 | 分别测试原入口和已验证目标入口 |
| 6002-6013 工具集合较多 | 白名单迁移漏项 | 保存改造前基线并逐 Client 精确比较集合 |

---

## 10. 执行结果记录

### 10.1 执行结果

| 项目 | 结果 | status |
|------|------|------|
| 单元测试 | 待执行 | append |
| 集成测试 | 待执行 | append |
| 接口回归 | 待执行 | append |
| MySQL 手工验收 | 待执行 | append |
| 编译与全仓测试 | 待执行 | append |

### 10.2 问题记录

| 编号 | 问题描述 | 影响范围 | status |
|------|------|------|------|
| BUG-001 | 暂无，执行后补充 | 待确认 | append |

### 10.3 结论

- 是否达到提测/合并条件：否。
- 当前结论：测试设计已完成，所有执行项初始状态为 `append`；实现完成并全部转为 `pass` 后方可提测。
