# Test: 意图路由 Structured Output 稳定性改造

> **文档版本:** v1.0  
> **创建日期:** 2026-06-14  
> **关联需求:** `docs/superpowers/plans/2026-06-14-routing-structured-output-validation-design.md`  
> **状态:** draft  
> **用例规模:** 核心 65 条，覆盖正常、异常、边界、回归四类场景

---

## 1. 测试背景

### 1.1 对应 Story / Change

- 设计来源：`docs/superpowers/plans/2026-06-14-routing-structured-output-validation-design.md`
- 关联历史测试：
  - `docs/superpowers/test/2026-06-05-intent-routing-fewshot-mainline-test.md`
  - `docs/superpowers/test/2026-06-13-split-intent-routing-experiment-test.md`

### 1.2 测试目标

- 不重复设计文档第 11 章“测试设计”和第 12 章“验收标准”的说明性内容。
- 将设计文档中已有的验证方向落成可执行用例、测试方法映射、执行命令和 `status` 记录。
- 补齐设计文档缺少的独立回归场景集，形成 `TC-301 ~ TC-315` 的可执行回归清单。

### 1.3 测试范围

- 本次需求涉及的模块：
  - 模型重试层：`RetryChatModel`、`RetryStrategy`、`RetryableExceptionTypes`
  - 响应校验上下文：`ChatResponseValidator`、`ResponseValidationContext`
  - 响应校验异常：`ResponseValidationException`、`ResponseValidationFailureType`
  - 路由结构校验：`RoutingStructuredOutputValidator`
  - 路由输出 DTO：`UnifiedRoutingOutput`、`QueryDecompositionOutput`、`TaskIntentRoutingOutput`
  - 路由服务与 Prompt：`IntentRoutingService`、`IntentRoutingPrompt`
  - 任务图校验：`TaskGraphValidator`
  - 指标与评测：`RoutingStageMetric`、`RoutingExecutionMetrics`、`IntentRoutingOnlineEvaluator`、`IntentRoutingOnlineEvalReportWriter`
  - 依赖声明：`ai-agent-study-domain/pom.xml`
- 本次重点验证的类/组件：
  - `RetryChatModel#call`
  - `RetryStrategy#execute`
  - `ResponseValidationContext#withValidator`
  - `ResponseValidationContext#currentValidator`
  - `RoutingStructuredOutputValidator`
  - `IntentRoutingService#callRoutingModel`
  - `IntentRoutingService#routeUnified`
  - `IntentRoutingService#decomposeQueryWithMetric`
  - `IntentRoutingService#routeTaskIntentSlotsWithMetric`
  - `IntentRoutingService#parseUnifiedResponse`
  - `IntentRoutingService#parseQueryDecompositionResponse`
  - `IntentRoutingService#parseResponse`

### 1.4 不在本次测试范围

- 不测试模型语义准确率是否因本次改造提升，只验证结构稳定性和失败归因。
- 不验证 Spring AI 或 Spring AI Alibaba 升级行为，本需求明确不升级版本。
- 不验证 Spring AI `StructuredOutputValidationAdvisor` 自带重复调用能力，本需求明确不使用。
- 不统计所有 attempts 的真实 token 成本，只保留当前最终响应 usage 口径。
- 不要求每次供应商调用形成独立 Langfuse observation。
- 不覆盖 `stream()`、异步调用、线程切换和响应式上下文传播。
- 不覆盖路由模型返回 `tool_calls` 的特殊场景。
- 不扩展 SPLIT 的特殊澄清、部分成功或子任务失败编排语义。

---

## 2. 测试策略

### 2.1 测试分层

| 测试层级 | 是否覆盖 | 说明 |
|------|------|------|
| 单元测试 | 是 | 覆盖重试判定、上下文隔离、Schema 校验、DTO 转换、业务规则、Prompt 字段所有权 |
| 集成测试 | 是 | 覆盖 `IntentRoutingService` 与 `RetryChatModel` 的协作、三阶段 validator 选择和独立重试预算 |
| 接口测试 | 部分 | 通过路由服务入口验证 JSON Mode options、Advisor 上下文和 metrics 输出，不连接真实中间件 |
| 回归测试 | 是 | 覆盖旧网络重试、不可重试异常、压缩触发、普通非路由请求、UNIFIED/SPLIT 主链路 |
| 手工验证 | 是 | 对真实 GLM 兼容接口执行低成本 smoke 和 challenge 在线评测 |

### 2.2 测试原则

- 中间件、真实 LLM、Langfuse 和外部接口统一 mock，只验证当前层业务逻辑。
- 每个自动化用例必须有明确断言，最终以 `assert` / `verify` / `assertThrows` 比对结果结束。
- 重试相关用例必须断言调用次数、Prompt 是否保持原样、异常类型和最终 fallback 边界。
- Schema 相关用例必须断言失败分类，不只断言“抛异常”。
- 路由集成用例必须同时断言业务结果、metrics、失败类型和是否提前 fallback。
- 在线评测只作为验收补充，不能替代可重复的单元与集成测试。

### 2.3 Mock 策略

| 依赖项 | 是否 Mock | Mock 方式 | 说明 |
|------|------|------|------|
| `ChatModel` | 是 | Mockito Stub | 控制成功响应、网络异常、限流、空响应、非法 JSON、Schema 错误后的重试序列 |
| `ChatClient` | 是 | Mockito Deep Stub / 测试替身 | 验证 `.options(jsonObjectOptions)`、advisor 参数和 `chatResponse()` 调用 |
| `ChatResponse` | 是 | Builder / Stub | 构造文本、空响应、usage metadata、异常响应 |
| `ChatResponseValidator` | 是 | Lambda / Mockito Mock | 控制校验成功、校验异常、嵌套调用和上下文隔离 |
| `RoutingStructuredOutputValidator` | 部分 | 真实实例 + Stub Schema | 真实覆盖 JSON、Schema、DTO 和业务规则；复杂 Schema 依赖可最小 stub |
| `TaskGraphValidator` | 部分 | 真实实例 / Mockito Mock | 规则测试用真实实例，路由集成可 mock 指定失败 |
| `IntentFewshotService` | 是 | Mockito Stub | 返回空样本或固定样本，避免向量检索干扰 |
| `IntentRoutingOnlineEvaluator` 依赖 | 是 | Stub Runner / 固定结果 | 验证失败分类、format error 统计和报告输出 |
| Langfuse / Advisor | 是 | Advisor context 捕获 | 验证参数不丢失，不连接真实观测系统 |

---

## 3. 测试场景设计

### 3.1 正常场景

| 编号 | 场景名称 | 前置条件 | 输入 | 预期结果 | status |
|------|------|------|------|------|------|
| TC-001 | 未注册 validator 保持原有调用行为 | `RetryChatModel` 开启 retry，未注册 `ResponseValidationContext` | delegate 首次返回合法 `ChatResponse` | delegate 调用 1 次，返回原响应，无额外校验和异常 | pending |
| TC-002 | validator 校验成功时只调用一次 | 注册 validator，delegate 首次返回合法结构 | 合法 `ChatResponse` | validator 执行 1 次，delegate 调用 1 次，结果成功返回 | pending |
| TC-003 | 第一次 JSON 错误第二次成功 | retry maxAttempts=3，validator 第一次抛 `JSON_PARSE_ERROR`，第二次通过 | 同一 Prompt | delegate 调用 2 次，第二次成功返回，重试使用原始 Prompt | pending |
| TC-004 | 网络错误后 Schema 错误再成功 | retry maxAttempts=3 | 第一次 delegate 抛网络异常，第二次 validator 抛 `SCHEMA_VALIDATION_ERROR`，第三次合法 | delegate 调用 3 次，最终成功，网络和结构错误共享同一预算 | pending |
| TC-005 | 未配置 maxAttempts 默认 3 次 | 构造未显式设置 `maxAttempts` 的 retry 配置 | 连续两次校验失败，第三次成功 | 最多调用 3 次并成功，默认值符合需求 | pending |
| TC-006 | 显式 maxAttempts=N 生效 | retry `maxAttempts=2` | 第一次 `DTO_CONVERSION_ERROR`，第二次成功 | delegate 调用 2 次，未额外调用第三次 | pending |
| TC-007 | UNIFIED 路由启用 JSON_OBJECT | 路由模式 UNIFIED，ChatClient 可捕获 options | 普通 PE 查询 | `OpenAiChatOptions.responseFormat.type=JSON_OBJECT`，使用 Unified validator | pending |
| TC-008 | SPLIT Round1 启用 JSON_OBJECT | 路由模式 SPLIT，执行任务拆分 | 多任务 Query | Round1 请求包含 JSON_OBJECT，使用 Decomposition validator | pending |
| TC-009 | SPLIT Round2 启用 JSON_OBJECT | Round1 返回两个合法任务 | 两个子任务 | 每个 Round2 请求包含 JSON_OBJECT，使用 Intent validator | pending |
| TC-010 | UNIFIED 合法结构映射为业务结果 | Unified validator 通过 | 包含 1 个合法任务的 JSON | parser 返回 `MultiIntentRoutingResult`，`executorNode` 由服务端按 intent 生成 | pending |
| TC-011 | SPLIT Round1 合法结构映射为拆解结果 | Decomposition validator 通过 | 合法 `taskList` | 返回 `QueryDecompositionResult`，`multiTask` 按 taskList 数量归一 | pending |
| TC-012 | SPLIT Round2 合法结构映射为意图和槽位 | Intent validator 通过 | `STOCK_ANALYSIS` 与合法 slots | 返回 `IntentRoutingResult`，股票槽位结构标准化 | pending |
| TC-013 | Round1 与 Round2 重试预算独立 | Round1 第三次成功，Round2 第一子任务也需重试 | 多任务 Query | Round1 消耗自己的 3 次预算后，Round2 每个子任务仍拥有完整预算 | pending |
| TC-014 | 多个 Round2 子任务预算互不影响 | Round1 返回 3 个任务 | 子任务 1 重试后成功，子任务 2 首次成功，子任务 3 重试耗尽 | 每个子任务调用次数按自身预算计算，metrics 能区分 taskId | pending |
| TC-015 | 合法澄清结构通过 UNIFIED 校验 | `needsClarification=true` | `missingInfo` 非空且 `taskList` 可为空 | 返回澄清结果，不强制生成执行节点，不误报结构失败 | pending |

### 3.2 异常场景

| 编号 | 场景名称 | 前置条件 | 输入 | 预期结果 | status |
|------|------|------|------|------|------|
| TC-101 | 空 ChatResponse 可重试 | validator 执行空响应检查 | `ChatResponse=null` 或 result/output/text 为空 | 抛 `ResponseValidationException(EMPTY_RESPONSE)` 并进入重试 | pending |
| TC-102 | 非法 JSON 可重试 | delegate 返回未闭合 JSON 字符串 | `{"intent":"GENERAL_CHAT"` | 抛 `JSON_PARSE_ERROR`，不由 parser 提前 fallback，耗尽后才降级 | pending |
| TC-103 | 缺少必填字段被拒绝 | Schema validator 启用 | Unified JSON 缺少 `taskList` 或 `needsClarification` | 抛 `SCHEMA_VALIDATION_ERROR`，失败类型可被 metrics/评测读取 | pending |
| TC-104 | 字段类型错误被拒绝 | Schema validator 启用 | `multiTask:"yes"`、`taskIndex:"1"` | 抛 `SCHEMA_VALIDATION_ERROR`，不会进入业务 VO 映射成功分支 | pending |
| TC-105 | 非法 intent 枚举被拒绝 | Intent Schema 仅允许当前可执行意图 | `intent:"TECHNICAL_CONSULTING"` | 抛 `SCHEMA_VALIDATION_ERROR` 或业务分类错误，不生成 `GENERAL_CHAT` 成功路由 | pending |
| TC-106 | `UNKNOWN` 不允许由模型输出 | Intent Schema 不放行 `UNKNOWN` | `intent:"UNKNOWN"` | 校验失败并可重试，不能被视为正常低置信路由 | pending |
| TC-107 | 非法 confidence 枚举被拒绝 | Schema validator 启用 | `confidence:"VERY_HIGH"` | 抛 `SCHEMA_VALIDATION_ERROR`，不进入 parser 成功分支 | pending |
| TC-108 | 多余运行期字段被拒绝 | Schema `additionalProperties=false` | 模型输出 `executorNode`、`taskType`、`status`、`metrics` | 抛 `SCHEMA_VALIDATION_ERROR`，服务端字段所有权不被模型接管 | pending |
| TC-109 | DTO 转换失败可重试 | JSON Schema 粗校验通过但 DTO 转换失败 | 嵌套对象类型无法转换 | 抛 `DTO_CONVERSION_ERROR` 并进入同一预算重试 | pending |
| TC-110 | UNIFIED 业务规则失败可重试 | Schema 通过 | `multiTask=true` 但 `taskList.size()==1` | 抛 `BUSINESS_VALIDATION_ERROR`，如果可修复则进入重试 | pending |
| TC-111 | UNIFIED 无需澄清但 taskList 为空 | Schema 通过，业务规则失败 | `needsClarification=false` 且 `taskList=[]` | 抛 `BUSINESS_VALIDATION_ERROR`，耗尽后 fallback，失败类型保留 | pending |
| TC-112 | UNIFIED 澄清缺少 missingInfo | Schema 通过，业务规则失败 | `needsClarification=true` 且 `missingInfo=[]` | 抛 `BUSINESS_VALIDATION_ERROR`，不生成含空澄清信息的成功结果 | pending |
| TC-113 | Round1 任务图非法可重试 | Round1 Schema 通过 | 重复 taskId、索引不连续、totalTasks 不一致 | 由业务校验抛 `BUSINESS_VALIDATION_ERROR`，进入 Round1 当前预算 | pending |
| TC-114 | Round1 依赖关系非法可重试 | Round1 Schema 通过 | dependsOn 指向不存在任务、自依赖或循环依赖 | 抛 `BUSINESS_VALIDATION_ERROR`，不提前 fallback 到原始 Query | pending |
| TC-115 | Round2 槽位结构非法被拒绝 | Intent Schema 启用 | `intentSpecificSlots` 类型错误或嵌套字段类型错误 | 抛 `SCHEMA_VALIDATION_ERROR`，当前子任务按自身预算重试 | pending |
| TC-116 | 401/403 不可重试 | retry 配置包含不可重试错误码 | delegate 抛 401 或 403 | delegate 只调用 1 次，直接失败，不被校验异常逻辑影响 | pending |
| TC-117 | 本地 Schema 定义非法不重试 | validator 初始化或 Schema 编译失败 | 非法 Schema | 直接失败并标记配置/代码问题，不重复调用供应商 | pending |
| TC-118 | 未开启 retry 不满足验收 | 路由使用模型 retry disabled | 首次结构校验失败 | 可执行校验但不自动发起下一次供应商调用，验收标记不通过 | pending |
| TC-119 | 重试耗尽后统一 fallback | maxAttempts=3，三次均 `SCHEMA_VALIDATION_ERROR` | UNIFIED / Round1 / Round2 任一阶段 | 第三次后才 fallback，结果携带明确 finalFailureType | pending |
| TC-120 | 未知业务异常不被误判为可重试 | validator 或 parser 抛普通 `IllegalStateException` | 代码缺陷类异常 | delegate 不重复调用，异常按不可重试处理 | pending |

### 3.3 边界场景

| 编号 | 场景名称 | 前置条件 | 输入 | 预期结果 | status |
|------|------|------|------|------|------|
| TC-201 | ResponseValidationContext 空上下文 | 未注册 validator | 调用 `currentValidator()` | 返回空上下文，普通模型请求不受影响 | pending |
| TC-202 | 上下文成功后清理 | `withValidator` 包裹一次成功调用 | validator 成功 | 调用结束后 `currentValidator()` 为空 | pending |
| TC-203 | 供应商异常后上下文清理 | `withValidator` 内 delegate 抛网络异常 | 网络异常 | 异常传播后 `currentValidator()` 为空 | pending |
| TC-204 | validator 异常后上下文清理 | `withValidator` 内 validator 抛校验异常 | `SCHEMA_VALIDATION_ERROR` | 异常传播后 `currentValidator()` 为空 | pending |
| TC-205 | 嵌套上下文按栈恢复 | 外层 validator A，内层 validator B | 内层调用完成 | 内层期间读取 B，退出内层恢复 A，最外层退出为空 | pending |
| TC-206 | 并发线程 validator 不泄漏 | 两个线程各注册不同 validator | 并发调用 | 每个线程只读取自己的 validator，互不串用 | pending |
| TC-207 | stream 不使用响应校验上下文 | 注册 validator 后调用 `RetryChatModel#stream` | 任意 Prompt | validator 不被执行，stream 保持原有行为 | pending |
| TC-208 | maxAttempts=1 边界 | retry enabled，maxAttempts=1 | 首次校验失败 | 只调用 1 次后失败，finalFailureType 正确 | pending |
| TC-209 | maxAttempts 超过安全上限 | maxAttempts 大于 `RetryStrategy` 安全上限 | 连续失败 | 调用次数不超过安全上限，不出现无限重试 | pending |
| TC-210 | 空集合与单元素任务合法性 | Round1 / UNIFIED validator | Round1 空 taskList、单任务 taskList | Round1 空列表拒绝；单任务合法且 `multiTask=false` | pending |
| TC-211 | nullable 字段表达明确 | Schema 标明 nullable 字段 | `missingInfo=null`、`baseSlot=null`、`intentSpecificSlots={}` | 允许的 nullable 通过，不允许的 null 被拒绝 | pending |
| TC-212 | 超长 reasoning 不破坏 DTO 转换 | Schema 允许字符串 | reasoning 超长但 JSON 合法 | 校验和转换成功，metrics token 估算不为负 | pending |
| TC-213 | 模型输出根节点不是对象 | JSON Mode 返回数组或字符串 | `[]` 或 `"text"` | 抛 `SCHEMA_VALIDATION_ERROR`，不进入 parser | pending |
| TC-214 | 结构失败重试不追加错误反馈 | retry 第二次调用可捕获 Prompt | 第一次结构校验失败 | 第二次 Prompt 与第一次完全一致，不包含上次错误提示 | pending |
| TC-215 | usage 缺失时指标口径保持 | 最终响应无 usage metadata | 合法结构 | 使用估算 token，`estimatedTokens=true`，不误认为所有 attempts 总成本 | pending |

### 3.4 回归场景

| 编号 | 场景名称 | 前置条件 | 输入 | 预期结果 | status |
|------|------|------|------|------|------|
| TC-301 | 原有网络重试行为不回归 | 未注册 validator | 500 / 503 后成功 | 调用次数和既有 `RetryChatModelTest` 预期一致 | pending |
| TC-302 | 原有限流重试行为不回归 | retryable error 包含 429 | 429 后成功 | 按原退避逻辑重试，不受结构校验改造影响 | pending |
| TC-303 | 原有不可重试黑名单不回归 | nonRetryable 包含 401/403 | 401/403 | 只调用一次并抛出原异常 | pending |
| TC-304 | 原有压缩触发逻辑不回归 | compression enabled | 超长 Prompt 或上下文溢出错误 | 主动/被动压缩仍按原路径触发，不被 validator 吞掉 | pending |
| TC-305 | 普通非路由 ChatClient 请求不受影响 | 不注册 validator，不设置 JSON_OBJECT | 普通聊天调用 | 不做 Schema 校验，不强制 JSON Mode，结果与原行为一致 | pending |
| TC-306 | ChatClient Advisor 上下文不丢失 | 路由调用传入 observationContext | 任意合法路由 | `client_id` 和自定义 advisor 参数仍传递 | pending |
| TC-307 | Langfuse Trace 不丢失 | 使用现有 ChatClient Advisor | 一次成功路由 | trace 链路仍可创建，结构校验不绕开 ChatClient | pending |
| TC-308 | 最终响应 token 和总延迟统计不丢失 | 路由返回合法响应 | UNIFIED 与 SPLIT | `RoutingExecutionMetrics` 包含 stage token、totalLatencyMs 和 estimated 标记 | pending |
| TC-309 | UNIFIED 澄清能力保持 | 合法澄清响应通过 validator | 缺少必要信息的 Query | Handler 仍写入 `clarificationPrompt` 和 `missingInfo` | pending |
| TC-310 | SPLIT 不新增澄清语义 | SPLIT Round2 返回无法满足的结构 | 缺槽位或低置信响应 | 按现有 SPLIT 行为处理，不引入部分成功/特殊澄清编排 | pending |
| TC-311 | 服务端 executorNode 映射保持 | parser 输出 intent，无模型 executorNode | PE、股票、通用对话、巡检意图 | `executorNode` 由服务端映射为既有节点 | pending |
| TC-312 | 评测格式错误不算成功路由 | evaluator 读取 finalFailureType | 最终 fallback 为 `GENERAL_CHAT`，failureType 为 `JSON_PARSE_ERROR` | 评测记为格式错误，不记为语义成功 | pending |
| TC-313 | UNIFIED 与 SPLIT 同等级保障 | 两种模式使用同批 smoke cases | 合法与非法结构混合 | 两种模式均启用 JSON_OBJECT、Schema 和 validator，无新差异变量 | pending |
| TC-314 | 依赖显式声明不靠传递依赖 | 检查 `ai-agent-study-domain/pom.xml` | JSON Schema validator 依赖 | 所需依赖显式存在，未升级 Spring AI 版本 | pending |
| TC-315 | 旧路由回归测试集通过 | 执行现有意图路由相关测试 | 既有测试命令 | 原有 few-shot、split、节点、handler 测试均通过 | pending |

---

## 4. 用例与代码映射

| 测试编号 | 对应用例方法 | 目标类/方法 | 覆盖类型 | 说明 |
|------|------|------|------|------|
| TC-001 | `should_keep_original_call_behavior_when_validator_is_absent()` | `RetryChatModel#call` | 正常 | 普通模型调用不受影响 |
| TC-002 | `should_call_delegate_once_when_response_validation_passes()` | `RetryChatModel#call` | 正常 | validator 成功路径 |
| TC-003 | `should_retry_when_json_parse_validation_fails_then_succeeds()` | `RetryChatModel#call` | 正常 | JSON 错误进入重试 |
| TC-004 | `should_share_retry_budget_between_network_and_schema_errors()` | `RetryChatModel#call` | 正常 | 网络和校验失败共享预算 |
| TC-005 | `should_default_max_attempts_to_three_when_not_configured()` | `RetryStrategy#execute` / `RetryConfig` | 正常 | 默认重试次数 |
| TC-006 | `should_honor_explicit_max_attempts_for_validation_errors()` | `RetryStrategy#execute` | 正常 | 显式配置生效 |
| TC-007 | `should_enable_json_object_for_unified_routing()` | `IntentRoutingService#callRoutingModel` | 正常 | UNIFIED JSON Mode |
| TC-008 | `should_enable_json_object_for_query_decomposition()` | `IntentRoutingService#decomposeQueryWithMetric` | 正常 | Round1 JSON Mode |
| TC-009 | `should_enable_json_object_for_each_task_routing_call()` | `IntentRoutingService#routeTaskIntentSlotsWithMetric` | 正常 | Round2 JSON Mode |
| TC-010 | `should_map_valid_unified_output_and_generate_executor_node_server_side()` | `IntentRoutingService#parseUnifiedResponse` | 正常 | 服务端生成执行节点 |
| TC-011 | `should_map_valid_decomposition_output()` | `IntentRoutingService#parseQueryDecompositionResponse` | 正常 | 拆解 DTO 到业务对象 |
| TC-012 | `should_map_valid_task_intent_output_and_normalize_slots()` | `IntentRoutingService#parseResponse` | 正常 | 单任务意图与槽位 |
| TC-013 | `should_keep_round2_budget_after_round1_retries()` | `IntentRoutingService#routeSplit` | 正常 | 阶段预算独立 |
| TC-014 | `should_isolate_retry_budget_between_round2_subtasks()` | `IntentRoutingService#routeSplit` | 正常 | 子任务预算独立 |
| TC-015 | `should_accept_valid_unified_clarification_output()` | `RoutingStructuredOutputValidator` | 正常 | 澄清结构合法 |
| TC-101 | `should_throw_empty_response_failure_when_chat_response_is_blank()` | `RoutingStructuredOutputValidator` | 异常 | 空响应分类 |
| TC-102 | `should_throw_json_parse_failure_for_unclosed_json()` | `RoutingStructuredOutputValidator` | 异常 | 非法 JSON 分类 |
| TC-103 | `should_reject_missing_required_fields()` | `RoutingStructuredOutputValidator` | 异常 | 必填字段 |
| TC-104 | `should_reject_wrong_field_types()` | `RoutingStructuredOutputValidator` | 异常 | 字段类型 |
| TC-105 | `should_reject_invalid_intent_enum()` | `RoutingStructuredOutputValidator` | 异常 | 意图枚举 |
| TC-106 | `should_reject_unknown_intent_from_model_output()` | `RoutingStructuredOutputValidator` | 异常 | UNKNOWN 禁止输出 |
| TC-107 | `should_reject_invalid_confidence_enum()` | `RoutingStructuredOutputValidator` | 异常 | 置信度枚举 |
| TC-108 | `should_reject_runtime_fields_from_model_output()` | `RoutingStructuredOutputValidator` / `IntentRoutingPrompt` | 异常 | 字段所有权 |
| TC-109 | `should_wrap_dto_conversion_error_as_response_validation_exception()` | `RoutingStructuredOutputValidator` | 异常 | DTO 转换失败 |
| TC-110 | `should_reject_unified_multi_task_flag_inconsistent_with_task_count()` | `RoutingStructuredOutputValidator` | 异常 | UNIFIED 业务规则 |
| TC-111 | `should_reject_unified_no_clarification_with_empty_task_list()` | `RoutingStructuredOutputValidator` | 异常 | 空 taskList |
| TC-112 | `should_reject_clarification_without_missing_info()` | `RoutingStructuredOutputValidator` | 异常 | 澄清业务规则 |
| TC-113 | `should_reject_invalid_decomposition_task_index_and_total()` | `TaskGraphValidator` / `RoutingStructuredOutputValidator` | 异常 | 任务图规则 |
| TC-114 | `should_reject_invalid_or_cyclic_dependencies()` | `TaskGraphValidator` / `RoutingStructuredOutputValidator` | 异常 | 依赖规则 |
| TC-115 | `should_reject_invalid_slot_structure_for_task_routing()` | `RoutingStructuredOutputValidator` | 异常 | 槽位结构 |
| TC-116 | `should_not_retry_non_retryable_auth_errors()` | `RetryChatModel#call` | 异常 | 401/403 |
| TC-117 | `should_fail_fast_when_local_schema_is_invalid()` | `RoutingStructuredOutputValidator` | 异常 | 本地配置错误 |
| TC-118 | `should_mark_routing_retry_disabled_as_not_meeting_acceptance()` | `IntentRoutingService` / 配置测试 | 异常 | retry 未开启 |
| TC-119 | `should_fallback_only_after_validation_retries_exhausted()` | `IntentRoutingService#callRoutingModel` | 异常 | fallback 边界 |
| TC-120 | `should_not_treat_unknown_business_exception_as_retryable()` | `RetryableExceptionTypes#isRetryable` | 异常 | 未知异常不可重试 |
| TC-201 | `should_return_empty_validator_when_context_is_absent()` | `ResponseValidationContext#currentValidator` | 边界 | 空上下文 |
| TC-202 | `should_clear_context_after_success()` | `ResponseValidationContext#withValidator` | 边界 | 成功清理 |
| TC-203 | `should_clear_context_after_supplier_exception()` | `ResponseValidationContext#withValidator` | 边界 | 供应商异常清理 |
| TC-204 | `should_clear_context_after_validator_exception()` | `ResponseValidationContext#withValidator` | 边界 | 校验异常清理 |
| TC-205 | `should_restore_outer_validator_after_nested_call()` | `ResponseValidationContext#withValidator` | 边界 | 栈式恢复 |
| TC-206 | `should_isolate_validators_between_threads()` | `ResponseValidationContext` | 边界 | 并发隔离 |
| TC-207 | `should_not_apply_response_validator_to_stream()` | `RetryChatModel#stream` | 边界 | stream 非目标 |
| TC-208 | `should_call_once_when_max_attempts_is_one()` | `RetryStrategy#execute` | 边界 | 一次尝试 |
| TC-209 | `should_cap_validation_retries_by_safe_max_attempts()` | `RetryStrategy#execute` | 边界 | 安全上限 |
| TC-210 | `should_handle_empty_and_single_task_collections_by_stage_rule()` | `RoutingStructuredOutputValidator` | 边界 | 空集合/单元素 |
| TC-211 | `should_apply_nullable_rules_explicitly()` | `RoutingStructuredOutputValidator` | 边界 | nullable |
| TC-212 | `should_accept_long_reasoning_without_negative_metrics()` | `IntentRoutingService#buildMetric` | 边界 | 超长文本 |
| TC-213 | `should_reject_non_object_json_root()` | `RoutingStructuredOutputValidator` | 边界 | 根节点类型 |
| TC-214 | `should_retry_with_original_prompt_without_validation_feedback()` | `RetryChatModel#call` | 边界 | 原始 Prompt |
| TC-215 | `should_keep_final_usage_token_metric_semantics()` | `IntentRoutingService#buildMetric` | 边界 | token 口径 |
| TC-301 | `should_keep_existing_network_retry_behavior()` | `RetryChatModelTest` | 回归 | 网络重试 |
| TC-302 | `should_keep_existing_rate_limit_retry_behavior()` | `RetryChatModelTest` | 回归 | 限流重试 |
| TC-303 | `should_keep_existing_non_retryable_blacklist_behavior()` | `RetryChatModelTest` | 回归 | 黑名单 |
| TC-304 | `should_keep_compression_trigger_behavior()` | `RetryChatModelCompressionTest` | 回归 | 压缩 |
| TC-305 | `should_not_affect_normal_chat_requests_without_validator()` | `RetryChatModel#call` | 回归 | 普通请求 |
| TC-306 | `should_keep_chat_client_advisor_context()` | `IntentRoutingService#callRoutingModel` | 回归 | Advisor |
| TC-307 | `should_keep_langfuse_trace_through_chat_client()` | `IntentRoutingService#callRoutingModel` | 回归 | Trace |
| TC-308 | `should_keep_routing_metrics_after_validation_changes()` | `RoutingExecutionMetrics` | 回归 | 指标 |
| TC-309 | `should_keep_unified_clarification_behavior()` | `IntentRoutingService#routeUnified` | 回归 | 澄清 |
| TC-310 | `should_not_add_split_partial_success_or_clarification_semantics()` | `IntentRoutingService#routeSplit` | 回归 | SPLIT 非目标 |
| TC-311 | `should_keep_server_side_executor_node_mapping()` | `IntentRoutingService#resolveExecutorNode` | 回归 | 执行节点 |
| TC-312 | `should_count_schema_failure_as_format_error_not_successful_general_chat()` | `IntentRoutingOnlineEvaluator` | 回归 | 评测归因 |
| TC-313 | `should_apply_same_structured_output_guarantee_to_unified_and_split()` | 路由集成测试 | 回归 | 同等级保障 |
| TC-314 | `should_declare_json_schema_dependency_explicitly_without_version_upgrade()` | `ai-agent-study-domain/pom.xml` | 回归 | 依赖 |
| TC-315 | `should_pass_existing_intent_routing_regression_suite()` | 既有测试集 | 回归 | 全链路回归 |

---

## 5. 关键校验点

本章不重复设计文档第 6、7、8、10、11、12 章已经定义的结构校验、失败分类、业务规则、可观测性和验收口径，仅保留测试执行时需要落到断言的检查方式。

### 5.1 数据正确性

- 对每类非法结构都要断言具体 `ResponseValidationFailureType`，不能只断言抛异常。
- 对运行期字段所有权用反例断言：模型输出相关字段时必须失败，服务端映射后的业务对象必须成功补齐。
- 对 DTO 字段范围用 Schema 生成结果或 validator 行为断言，避免只检查 Prompt 文案。

### 5.2 状态流转正确性

- 重试类用例必须断言 delegate 调用次数，确保结构失败发生在 `RetryChatModel` attempt 内。
- parser/fallback 类用例必须断言第一次结构失败后不会提前返回业务成功对象。
- SPLIT 预算类用例必须分别捕获 Round1 与每个 Round2 子任务的调用次数。

### 5.3 异常处理正确性

- 可重试与不可重试错误必须分别断言调用次数和最终异常类型。
- 上下文隔离必须覆盖成功、供应商异常、validator 异常、嵌套和并发线程。
- 普通非路由请求必须单独断言未触发 validator，防止全局副作用。

### 5.4 日志/监控/告警

- 是否需要校验日志输出：是。
- 关键日志点：
  - 断言指标对象包含设计文档要求的关键字段，不要求精确匹配日志全文。
  - 对 token 指标只断言“最终响应 usage 口径”标识，不把所有 attempts 成本作为本阶段测试目标。

---

## 6. 执行计划

### 6.1 自动化测试执行

| 步骤 | 内容 | 预期结果 | status |
|------|------|------|------|
| 1 | 新增 `ResponseValidationContextTest` | 上下文清理、嵌套、并发隔离均有断言 | pending |
| 2 | 补充 `RetryChatModelTest` / `RetryableExceptionTypesTest` | 校验异常可重试，未知异常不可重试，重试预算正确 | pending |
| 3 | 新增 `RoutingStructuredOutputValidatorTest` | 三阶段 Schema、DTO 和业务规则均覆盖 | pending |
| 4 | 补充 `IntentRoutingPromptTest` | UNIFIED Prompt 删除运行期字段，三阶段结构说明符合字段所有权 | pending |
| 5 | 补充 `IntentRoutingServiceTest` | JSON Mode options、validator 选择、parser/fallback 边界、服务端 executor 映射 | pending |
| 6 | 补充 `TaskGraphValidatorTest` | UNIFIED 与 Round1 任务图规则都能被 validator 调用覆盖 | pending |
| 7 | 补充在线评测单元测试 | `finalFailureType`、format error 归因、报告字段输出正确 | pending |
| 8 | 执行意图路由定向回归测试 | 旧 few-shot、split、节点、handler、metrics 测试通过 | pending |
| 9 | 执行模块编译与测试 | `ai-agent-study-domain` 和相关 app 测试通过 | pending |

建议执行命令：

```powershell
mvn test -pl ai-agent-study-domain -Dtest=RetryChatModelTest,RetryableExceptionTypesTest,ResponseValidationContextTest,RoutingStructuredOutputValidatorTest,IntentRoutingPromptTest,IntentRoutingServiceTest,TaskGraphValidatorTest
mvn test -pl ai-agent-study-app -Dtest=IntentRoutingOnlineEvaluatorTest
mvn test -pl ai-agent-study-domain
mvn compile -pl ai-agent-study-domain -am -DskipTests
```

### 6.2 手工验证步骤

| 步骤 | 操作 | 预期结果 | status |
|------|------|------|------|
| 1 | 使用 UNIFIED smoke 数据执行最多 3 个 case | 请求启用 JSON_OBJECT，合法结构成功，结构失败进入重试 | pending |
| 2 | 使用同一批 smoke 数据执行 SPLIT | Round1 与每个 Round2 都启用 JSON_OBJECT 和对应 Schema | pending |
| 3 | 注入一次未闭合 JSON 响应 | 第一次失败不被 parser fallback，第二次成功或耗尽后带 failureType fallback | pending |
| 4 | 注入模型输出 `executorNode`、`taskType`、`status` | Schema 拒绝，多次失败后报告 `SCHEMA_VALIDATION_ERROR` | pending |
| 5 | 检查在线评测报告 | `jsonModeEnabled`、`schemaValidationEnabled`、`finalFailureType`、format error rate 字段正确 | pending |
| 6 | 检查日志与 Langfuse Trace | ChatClient Advisor、最终响应 token、端到端延迟仍存在 | pending |

---

## 7. 验收标准

需求功能验收标准沿用设计文档第 12 章，本节只记录测试文档自身的交付验收，避免重复维护两份功能验收清单。

| 编号 | 验收项 | 标准 | status |
|------|------|------|------|
| AC-001 | 场景分层完整 | 正常、异常、边界、回归四类场景均有独立编号和 `status` | pending |
| AC-002 | 回归集独立 | `TC-301 ~ TC-315` 可作为独立回归清单执行，不依赖阅读设计文档第 11 章 | pending |
| AC-003 | 用例可落地 | 每个测试编号均映射到测试方法、目标类/方法或明确执行动作 | pending |
| AC-004 | 执行计划明确 | 自动化命令、手工验证步骤和结果记录位置完整 | pending |
| AC-005 | 不重复维护需求验收 | 功能验收只引用设计文档第 12 章，本文档不再复制同一份验收标准 | pending |

---

## 8. 风险与说明

| 风险点 | 影响 | 应对措施 |
|------|------|------|
| 测试文档与设计文档重复维护 | 后续需求调整时两处内容可能不一致 | 设计性结论回链到需求文档，测试文档只维护可执行材料 |
| 实现阶段类名或接口调整 | 用例映射可能与最终代码不一致 | 执行前根据实际实现同步第 4 章映射，不改动场景意图 |
| 在线评测依赖真实模型波动 | 验收结果不稳定 | 核心逻辑使用 mock 自动化验证，在线评测只做 smoke/challenge 补充 |

---

## 9. 执行结果记录

### 9.1 执行结果

| 项目 | 结果 |
|------|------|
| 核心用例设计 | complete |
| 单元测试 | pending |
| 集成测试 | pending |
| 在线评测 smoke | pending |
| 手工验证 | pending |
| 编译验证 | pending |

### 9.2 问题记录

| 编号 | 问题描述 | 影响范围 | 状态 |
|------|------|------|------|
| BUG-001 | 待执行测试后补充 | - | pending |

### 9.3 结论

- 是否达到提测/合并条件：否，当前仅完成测试设计。
- 结论说明：
  - 本文档已覆盖结构化输出稳定性改造的核心风险：JSON Mode、Schema 校验、DTO 转换、业务规则、重试预算、上下文隔离、fallback 边界和评测归因。
  - 后续代码实现完成后，应按第 6 章执行自动化与手工验证，并根据真实执行结果更新 `status` 与问题记录。
