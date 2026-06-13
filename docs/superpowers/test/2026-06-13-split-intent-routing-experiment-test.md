# Test: Query 拆解与意图切槽分离路由实验

> **文档版本:** v1.0  
> **创建日期:** 2026-06-13  
> **关联需求:** `docs/superpowers/plans/2026-06-13-split-intent-routing-experiment-design.md`  
> **状态:** draft  
> **用例规模:** 精简核心集，共 25 条 P0/P1 用例

---

## 1. 测试背景

### 1.1 测试目标

- 验证 `intent.routing.mode` 能在 unified 与 split 链路之间正确选择，且默认行为不变。
- 验证 split 链路按“Query 拆解 -> 逐任务意图切槽 -> 最终结果组装”串行执行。
- 验证 unified 与 split 共用任务图校验、结果落地、上下文写入和下游分发逻辑。
- 验证拆解失败、任务切槽失败、任务图非法等场景按需求降级，且 split 不回退 unified。
- 验证总耗时、阶段耗时、token 和调用顺序指标可被正确采集与聚合。

### 1.2 测试范围

- 配置与入口：`IntentRoutingProperties`、`IntentRoutingMode`、`RootNode`
- 阶段模型：`QueryDecompositionResult`、`DecomposedTask`、`RoutingExecutionMetrics`、`RoutingStageMetric`
- Prompt 与服务：`IntentRoutingPrompt`、`IntentRoutingService`
- 节点：`QueryDecompositionNode`、`TaskRoutingSlotNode`、`IntentRoutingNode`
- 公共组件：`TaskGraphValidator`、`RoutingResultHandler`
- 兼容对象：`MultiIntentRoutingResult`、`SubTask`、`DynamicContext`

### 1.3 不在本次测试范围

- unified 与 split 的准确率对比和完整评测体系。
- 在线评测数据集、Evaluator、报告 writer 和 baseline 改造。
- 真实 LLM 稳定性、并发、压力和容量测试。
- split 链路的不完整 Query 澄清能力。
- 下游 PE、通用对话、巡检、交易节点内部业务正确性。
- shadow 双跑、动态灰度和单请求路由模式切换。

---

## 2. 测试策略

### 2.1 测试分层

| 测试层级 | 是否覆盖 | 说明 |
|---|---|---|
| 单元测试 | 是 | 核心覆盖方式，验证配置分支、解析、校验、组装、降级和指标 |
| 模块集成测试 | 部分 | 验证节点、服务、上下文和公共组件协作，不连接真实中间件 |
| 接口/E2E 测试 | 部分 | 实现完成后手工验证 unified/split 典型请求及日志 |
| 回归测试 | 是 | 验证 unified 澄清、显式 Agent、巡检及下游上下文兼容 |
| 准确率评测 | 否 | 属于后续独立需求 |

### 2.2 测试原则

- 中间件和 LLM 统一 Mock，只验证当前层业务逻辑。
- 每条自动化用例必须以明确的状态、数据或调用断言结束。
- 串行性通过 `InOrder`、调用序号和任务内容捕获联合验证，不以耗时推断。
- 时间指标使用可控时钟或范围断言，避免依赖真实机器时间。
- 同类校验规则使用参数化测试，减少重复代码但不减少规则覆盖。
- 所有用例初始状态为 `pending`，代码完成并实际执行后再更新。

### 2.3 Mock 策略

| 依赖项 | Mock 方式 | 主要控制点 |
|---|---|---|
| `ChatModel` / `ChatClient` | Mockito Stub | 返回合法 JSON、空响应、非法 JSON、异常及 usage metadata |
| `ArmoryObjectRegistry` | Mockito Stub | 返回 INTENT_ROUTING 对应 ChatClient |
| `ChatMemoryPersistenceService` | Mockito Stub | 返回空历史或指定历史消息 |
| `TaskGraphValidator` | Mockito Mock/真实实例 | 节点测试 Mock，规则测试使用真实实例 |
| `RoutingResultHandler` | Mockito Mock/真实实例 | 节点测试验证委托，组件测试验证真实上下文落地 |
| 下游节点与 `ApplicationContext` | Mockito Stub | 验证节点选择和 trading 缺失降级 |
| token 估算与时间来源 | Stub/范围断言 | 验证 usage 优先、估算标记和聚合口径 |

---

## 3. 测试场景设计

### 3.1 正常场景

| 编号 | 优先级 | 场景名称 | 前置条件/输入 | 预期结果 | status |
|---|---|---|---|---|---|
| TC-001 | P0 | 未配置模式默认走 unified | `aiAgentId` 为空，未配置 `intent.routing.mode` | `RootNode#get` 返回 `IntentRoutingNode`，不进入 split 节点 | pending |
| TC-002 | P0 | 配置 split 进入拆解节点 | `aiAgentId` 为空，mode=`SPLIT` | `RootNode#get` 返回 `QueryDecompositionNode` | pending |
| TC-003 | P0 | 单任务 Query 拆解成功 | LLM 返回一个 `DecomposedTask` | 结果包含一个任务；`multiTask=false`；无 intent、slots、executor 和澄清语义 | pending |
| TC-004 | P0 | 多任务及前置依赖拆解成功 | LLM 返回两个合法任务，第二项依赖第一项 | 任务 ID、索引、总数、内容和依赖正确；`multiTask=true` | pending |
| TC-005 | P0 | 单任务意图切槽及股票槽位标准化 | 输入“分析贵州茅台最近一个月走势” | 返回 `STOCK_ANALYSIS`；股票代码、类型、时间和交易所正确标准化；不产生澄清 | pending |
| TC-006 | P0 | split 多任务严格串行组装 | 拆解结果含 3 个乱序存放但 `taskIndex` 合法的任务 | 按 `taskIndex` 依次调用切槽；每项仅调用一次；组装后的 `SubTask.taskType=0`；最终校验并委托 Handler | pending |
| TC-007 | P0 | 两类任务模型通过合法任务图校验 | 分别传入合法 `DecomposedTask` 和合法 `SubTask` 单/多任务图 | 两个校验入口均不抛异常，合法依赖链通过 | pending |
| TC-008 | P0 | unified 与 split 最终上下文兼容 | 分别构造等价的单任务和多任务最终结果 | Handler 写入既有单任务或多任务 key；选择相同下游节点；写入 `intentRoutingMetrics` | pending |
| TC-009 | P0 | 指标按真实 usage 和调用顺序聚合 | 拆解 1 次、任务切槽 2 次，均返回 usage | mode=`SPLIT`；stage 顺序为 0/1/2；taskId 正确；总 token 为各阶段之和；`estimated=false`；总耗时覆盖完整链路 | pending |

### 3.2 异常场景

| 编号 | 优先级 | 场景名称 | 前置条件/输入 | 预期结果 | status |
|---|---|---|---|---|---|
| TC-101 | P0 | 非法配置值启动失败 | `intent.routing.mode=unknown` | Spring 配置绑定失败，不静默回退 unified | pending |
| TC-102 | P0 | 拆解调用或解析失败降级 | 参数化注入：LLM 异常、空响应、非法 JSON、空 `taskList` | 使用原始 Query 构造 `fallback-1` 单任务并继续切槽；不调用 `routeUnified`；失败阶段指标 `success=false` 且有 `errorMessage` | pending |
| TC-103 | P0 | 第一阶段非法任务图降级 | 参数化覆盖 taskId 空/重复、content 空、索引非法、总数不符、依赖不存在、自依赖、循环依赖、依赖后置任务 | 抛出校验异常后由 split 流程重建原始 Query 单任务并继续第二阶段 | pending |
| TC-104 | P0 | 单个任务切槽失败不阻断其它任务 | 三个任务中第二个调用异常或解析失败 | 第二项降级 `GENERAL_CHAT + LOW + 空槽位`；第一、三项继续；阶段指标保留三次任务调用及第二项错误 | pending |
| TC-105 | P0 | split 最终 SubTask 图非法降级 | 第二阶段组装后最终校验失败 | 返回单个 `GENERAL_CHAT` 最终结果，不把非法图交给 `MultiTaskExecutionNode`，不回退 unified | pending |
| TC-106 | P0 | unified 非法任务图安全降级 | `routeUnified` 解析出非法任务图 | 在 executor 归一化前发现异常；降级现有 `GENERAL_CHAT` 最终结果；指标记录失败 | pending |
| TC-107 | P1 | usage 缺失时估算 token | 任一阶段 `ChatResponse.metadata.usage` 缺失 | 使用 `TokenCountUtils` 估算 prompt/response token；对应阶段 `estimatedTokens=true`；总指标 `estimated=true` | pending |

### 3.3 边界场景

| 编号 | 优先级 | 场景名称 | 前置条件/输入 | 预期结果 | status |
|---|---|---|---|---|---|
| TC-201 | P1 | LLM 的 multiTask 标志与列表数量不一致 | 分别输入“标记 true 但 1 项”和“标记 false 但 2 项” | 最终一律按 `taskList.size() > 1` 归一化 | pending |
| TC-202 | P1 | 空历史消息参与两阶段调用 | `historyMessages=[]` | 两类 Prompt 均可构建和解析；不出现 null 字符串或空指针 | pending |
| TC-203 | P1 | dependsOn 为空和槽位不完整 | `dependsOn=null`；切槽结果仅有部分股票槽位 | 依赖归一化为空列表；保留已识别意图和现有槽位；split 固定不澄清 | pending |
| TC-204 | P1 | 指标部分估算与零 token | 一阶段 usage 为 0，另一阶段缺少 usage | token 聚合无负数/溢出；只要任一阶段估算，总 `estimated=true`；stage 记录不丢失 | pending |

### 3.4 回归场景

| 编号 | 优先级 | 场景名称 | 前置条件/输入 | 预期结果 | status |
|---|---|---|---|---|---|
| TC-301 | P0 | 显式 aiAgentId 不受模式影响 | mode 分别为 unified/split，`aiAgentId` 为普通显式值 | 两种模式均进入 `Step1AnalyzerNode` | pending |
| TC-302 | P0 | 巡检入口不受模式影响 | mode 分别为 unified/split，`aiAgentId=5` | 两种模式均进入 `IntelligentInspection` | pending |
| TC-303 | P0 | unified 澄清能力保持 | unified 返回 `needsClarification=true` | Handler 写入 `clarificationPrompt` 和 `missingInfo`，维持现有澄清返回；split 不执行该分支 | pending |
| TC-304 | P0 | 既有单/多任务下游行为保持 | 覆盖 PE、GENERAL_CHAT、STOCK_ANALYSIS 和多任务结果；trading Bean 缺失 | 既有上下文 key 与节点选择不变；股票槽位兼容；trading 缺失降级 `generalChatNode` | pending |
| TC-305 | P1 | 非目标模块与既有测试无回归 | 执行编译、意图路由相关测试并检查变更清单 | Evaluator、报告 writer、数据集和 `MultiTaskExecutionNode` 执行方式未被修改；既有用例通过 | pending |

---

## 4. 用例与代码映射

| 测试编号 | 建议测试方法 | 目标类/方法 |
|---|---|---|
| TC-001 | `should_route_to_unified_when_mode_is_not_configured()` | `RootNode#get` / `IntentRoutingProperties` |
| TC-002 | `should_route_to_query_decomposition_when_mode_is_split()` | `RootNode#get` |
| TC-003 | `should_parse_single_decomposed_task_without_final_routing_fields()` | `IntentRoutingService#decomposeQuery` |
| TC-004 | `should_parse_multi_task_dependencies_when_decomposition_is_valid()` | `IntentRoutingService#decomposeQuery` |
| TC-005 | `should_route_stock_intent_and_normalize_slots_for_single_task()` | `IntentRoutingService#routeTaskIntentSlots` |
| TC-006 | `should_route_split_tasks_serially_in_task_index_order()` | `TaskRoutingSlotNode#doApply` / `IntentRoutingService#routeSplit` |
| TC-007 | `should_accept_valid_graph_for_both_task_models()` | `TaskGraphValidator` 两个入口 |
| TC-008 | `should_write_compatible_context_for_unified_and_split_results()` | `RoutingResultHandler#handle` |
| TC-009 | `should_aggregate_real_usage_metrics_in_serial_call_order()` | `IntentRoutingService#routeSplit` |
| TC-101 | `should_fail_application_binding_when_routing_mode_is_invalid()` | `IntentRoutingProperties` 配置绑定测试 |
| TC-102 | `should_fallback_to_original_query_when_decomposition_fails()` | `IntentRoutingService#decomposeQuery` / `routeSplit` |
| TC-103 | `should_reject_invalid_task_graph_rules()` | `TaskGraphValidatorTest` 参数化用例 |
| TC-104 | `should_continue_other_tasks_when_one_task_routing_fails()` | `TaskRoutingSlotNode#doApply` / `routeSplit` |
| TC-105 | `should_fallback_to_general_chat_when_split_final_graph_is_invalid()` | `TaskRoutingSlotNode#doApply` / `routeSplit` |
| TC-106 | `should_fallback_before_normalization_when_unified_graph_is_invalid()` | `IntentRoutingService#routeUnified` / `IntentRoutingNode#doApply` |
| TC-107 | `should_estimate_tokens_when_usage_metadata_is_missing()` | 路由 LLM 调用指标封装 |
| TC-201 | `should_normalize_multi_task_flag_from_task_list_size()` | decomposition parser / split assembler |
| TC-202 | `should_build_both_stage_prompts_when_history_is_empty()` | `IntentRoutingPrompt` |
| TC-203 | `should_normalize_null_dependencies_and_keep_partial_slots_without_clarification()` | parser / `routeTaskIntentSlots` |
| TC-204 | `should_keep_all_stages_when_metrics_mix_zero_and_estimated_tokens()` | `RoutingExecutionMetrics` 聚合逻辑 |
| TC-301 | `should_ignore_routing_mode_when_ai_agent_id_is_explicit()` | `RootNode#get` |
| TC-302 | `should_ignore_routing_mode_for_inspection_agent()` | `RootNode#get` |
| TC-303 | `should_keep_unified_clarification_behavior()` | `IntentRoutingNode` / `RoutingResultHandler` |
| TC-304 | `should_keep_existing_context_and_downstream_dispatch_behavior()` | `RoutingResultHandlerTest` |
| TC-305 | `should_pass_existing_intent_routing_regression_suite()` | 模块编译与相关测试集 |

建议新增或补充测试类：

- `IntentRoutingPropertiesTest`
- `TaskGraphValidatorTest`
- `RoutingResultHandlerTest`
- `QueryDecompositionNodeTest`
- `TaskRoutingSlotNodeTest`
- `IntentRoutingServiceTest`
- `IntentRoutingPromptTest`
- `IntentRoutingNodeTest`
- `RootNodeTest`

---

## 5. 关键校验点

### 5.1 数据与状态

- 第一阶段模型不得混入 intent、slots、executorNode、taskType 和澄清字段。
- `multiTask` 必须以最终任务数量为准。
- `dependsOn`、任务索引、任务总数和列表顺序必须保持一致。
- split 最终固定 `needsClarification=false`、`missingInfo=[]`、`clarificationPrompt=""`。
- 单任务和多任务 DynamicContext key 必须与现有链路兼容。

### 5.2 调用与降级

- split 第二阶段必须串行，且调用顺序等于 `taskIndex` 顺序。
- 第一阶段失败后继续第二阶段，不得调用 unified。
- 单任务切槽失败只影响当前任务。
- 最终任务图非法不得进入多任务执行节点。
- unified 的任务图校验必须发生在 executorNode 归一化之前。

### 5.3 指标与日志

- `stageMetrics` 数量等于实际 LLM 调用次数。
- `callIndex`、`taskId`、阶段名称、成功状态和错误信息可追踪。
- 总 token 等于阶段 token 求和；任一阶段估算则总指标标记估算。
- `totalLatencyMs` 覆盖完整路由链路，而非简单累加阶段耗时。
- 最终指标同时进入结果对象、DynamicContext 和日志。

---

## 6. 执行计划

### 6.1 自动化测试

| 步骤 | 内容 | 预期结果 | status |
|---|---|---|---|
| 1 | 实现 TaskGraphValidator 参数化测试 | 全部规则均有断言 | pending |
| 2 | 实现 Service、Prompt 和指标测试 | 解析、降级、串行与指标用例通过 | pending |
| 3 | 实现节点和 Handler 测试 | 上下文、委托和下游分支用例通过 | pending |
| 4 | 实现配置与 RootNode 测试 | 默认、split、显式 Agent 和巡检分支通过 | pending |
| 5 | 执行意图路由定向回归与模块编译 | 无关键回归，编译成功 | pending |

建议执行命令（实现后按实际 Maven 配置调整）：

```powershell
mvn test -pl ai-agent-study-domain -Dtest=TaskGraphValidatorTest,RoutingResultHandlerTest,QueryDecompositionNodeTest,TaskRoutingSlotNodeTest,IntentRoutingServiceTest,IntentRoutingPromptTest,IntentRoutingNodeTest,RootNodeTest
mvn test -pl ai-agent-study-domain
mvn compile -pl ai-agent-study-domain -am -DskipTests
```

### 6.2 手工验证

| 步骤 | 操作 | 预期结果 | status |
|---|---|---|---|
| 1 | 不配置 mode，发送完整单任务 Query | 日志显示 UNIFIED，既有结果和上下文不变 | pending |
| 2 | 配置 mode=split 并重启，发送双股票分析 Query | 日志显示 1 次拆解和 2 次串行切槽，最终进入多任务下游 | pending |
| 3 | 在 split 环境发送带显式 aiAgentId 和巡检请求 | 请求仍进入原显式业务链路 | pending |
| 4 | 注入拆解或单任务切槽异常 | 按定义降级，日志和 metrics 记录失败且不回退 unified | pending |
| 5 | 检查最终日志与上下文 | metrics、任务列表、槽位和下游节点均符合预期 | pending |

---

## 7. 验收标准

| 编号 | 验收项 | 标准 | status |
|---|---|---|---|
| AC-001 | 配置切换正确 | 默认 unified、显式 split 生效、非法值启动失败 | pending |
| AC-002 | 两阶段职责隔离 | 第一阶段只拆任务，第二阶段只识别意图和槽位 | pending |
| AC-003 | 串行与任务图正确 | 调用顺序稳定，两个模型入口均通过完整任务图校验 | pending |
| AC-004 | 降级符合设计 | 各阶段异常均按需求降级，split 从不回退 unified | pending |
| AC-005 | 上下文兼容 | unified 与 split 通过同一 Handler 输出兼容 DynamicContext | pending |
| AC-006 | 指标完整 | 每次调用均有独立阶段指标，token 和耗时聚合正确 | pending |
| AC-007 | 关键回归通过 | unified 澄清、显式 Agent、巡检、股票槽位和下游选择无回归 | pending |
| AC-008 | 自动化验证通过 | 25 条核心场景已映射到自动化测试，P0/P1 用例全部通过 | pending |

---

## 8. 风险与说明

| 风险点 | 影响 | 测试应对 |
|---|---|---|
| 串行实现被误改为异步或按列表原顺序执行 | 指标和依赖顺序不可信 | 使用 `InOrder` 和参数捕获明确断言调用顺序 |
| LLM 输出字段超出阶段职责 | 阶段边界失效 | 断言第一阶段模型不承载最终路由字段 |
| 降级流程误调用 unified | 实验结果被修正，无法真实对比 | 所有 split 降级用例断言 `routeUnified` 从未调用 |
| 指标依赖真实时间导致测试抖动 | CI 偶发失败 | 使用可控时间源或非精确范围断言 |
| Handler 抽取导致旧上下文行为变化 | 下游节点回归 | 对既有 key 和节点分发做统一参数化回归 |
| 需求文档为 reviewed draft | 实现阶段可能调整接口或类名 | 保持用例意图与验收标准稳定，代码映射随实现同步更新 |

---

## 9. 执行结果记录

### 9.1 执行结果

| 项目 | 结果 |
|---|---|
| 核心用例设计 | complete |
| 单元测试 | pending |
| 模块集成测试 | pending |
| 手工验证 | pending |
| 编译验证 | pending |

### 9.2 问题记录

| 编号 | 问题描述 | 影响范围 | 状态 |
|---|---|---|---|
| BUG-001 | 待执行测试后补充 | - | pending |

### 9.3 结论

- 是否达到提测/合并条件：`否，当前仅完成测试设计`
- 后续代码完成后，以第 7 章验收标准为准更新用例和执行状态。
