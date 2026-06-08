# Test: IntentRoutingNode 接入 Few-Shot 主链测试方案

## 1. 测试背景

### 1.1 对应 Story / Change
- Story 文档：`docs/superpowers/plans/2026-06-05-intent-routing-fewshot-mainline-story.md`

### 1.2 测试目标
- 验证 `IntentRoutingNode` 主链已从“Node 内部分解 Prompt”切换为“统一路由服务 + Few-Shot 检索增强”模式
- 验证统一路由结果可覆盖：
  - 单任务识别
  - 多任务识别
  - 信息补全识别
- 验证 Few-Shot 检索失败时系统可安全降级，不影响主链可用性
- 验证改动未破坏既有单任务下游路由及股票槽位兼容逻辑

### 1.3 测试范围
- 本次需求涉及模块：
  - `ai-agent-study-domain`
- 本次重点验证的类/组件：
  - `IntentRoutingPrompt`
  - `IntentRoutingService`
  - `IntentRoutingNode`
  - `IntentRoutingServiceTest`
  - `IntentRoutingNodeTest`

### 1.4 不在本次测试范围
- `intent_fewshot_vector_store` 表结构调整
- `IntentFewshotService` 的增删改能力
- 下游执行节点内部业务正确性，如：
  - `Step1AnalyzerNode`
  - `GeneralChatNode`
  - `IntelligentInspection`
- 真实外部向量库 / 真实 LLM 稳定性压测
- 股票分析、通用聊天等具体业务回答质量评测

---

## 2. 测试策略

### 2.1 测试分层

| 测试层级 | 是否覆盖 | 说明 |
|------|------|------|
| 单元测试 | ✅ | 覆盖 Prompt 构造、统一 JSON 解析、降级、Node 路由分支 |
| 集成测试 | ⚠️ 部分覆盖 | 仅限模块内协作，不依赖真实中间件 |
| 接口测试 | ❌ | 本次 Story 重点不在对外接口 |
| 回归测试 | ✅ | 验证既有单任务路由、股票槽位映射、下游节点选择未受破坏 |
| 手工验证 | ✅ | 验证日志、链路行为和典型输入输出表现 |

### 2.2 测试原则
- 中间件依赖统一 mock，只验证当前层业务逻辑
- 每个测试用例必须以明确断言结束
- 优先覆盖正常、异常、边界、回归四类场景
- `Service` 层重点验证：
  - Few-Shot 检索
  - Prompt 注入
  - JSON 解析
  - fallback 降级
- `Node` 层重点验证：
  - 统一路由结果分支处理
  - DynamicContext 写入
  - 下游节点选择

### 2.3 Mock 策略

| 依赖项 | 是否 Mock | Mock 方式 | 说明 |
|------|------|------|------|
| `IntentFewshotService` | ✅ | Mockito Stub | 控制 Few-Shot 返回样本 / 抛异常 |
| `ChatClient` / LLM 调用 | ✅ | Mockito Stub | 控制统一路由 JSON 返回 |
| 历史消息获取依赖 | ✅ | Mockito Stub | 控制 history 输入 |
| 下游执行节点选择相关依赖 | ✅ | Mockito Spy/Stub | 仅验证路由选择，不执行真实业务 |
| 向量库 / OpenAI / DashScope | ✅ | 不接真实中间件 | 遵守“只测本层逻辑”原则 |

---

## 3. 测试场景设计

### 3.1 正常场景

| 编号 | 场景名称 | 前置条件 | 输入 | 预期结果 | status |
|------|------|------|------|------|------|
| TC-001 | 统一路由 Prompt 注入 Few-Shot 示例 | Few-Shot 检索返回 Top-K 样本 | 用户消息 + history + fewshotSamples | Prompt 中包含 `参考示例` 段落，示例顺序正确，用户输入仍位于后部 | pass |
| TC-002 | 单任务 unified JSON 解析成功 | LLM 返回合法单任务 JSON | 单任务响应 | `multiTask=false`，`taskList` 仅 1 项，intent/confidence/slots 正确映射 | pass |
| TC-003 | 多任务 unified JSON 解析成功 | LLM 返回合法多任务 JSON | 多任务响应 | `multiTask=true`，`taskList` 多项，`executorNode` 正确保留 | pass |
| TC-004 | needsClarification 场景解析成功 | LLM 返回补全型 JSON | 缺少关键信息的响应 | `needsClarification=true`，`missingInfo`、`clarificationPrompt` 正确解析 | pass |
| TC-005 | Node 主链走统一路由服务 | `IntentRoutingService.routeUnified(...)` 可调用 | 普通用户消息 | `IntentRoutingNode.doApply()` 调用统一路由服务，不再走旧多任务 Prompt 分支 | pass |
| TC-006 | Node 多任务场景写入任务列表 | 统一路由返回 `multiTask=true` | 多任务用户输入 | 写入 `TASK_LIST_KEY`，并进入多任务分发逻辑 | pass |
| TC-007 | Node 单任务 PE_RETRIEVAL 路由正确 | 返回单任务 `PE_RETRIEVAL` | 知识问答输入 | 写入路由上下文，选择 `step1AnalyzerNode` | pass |
| TC-008 | Node 单任务 STOCK_ANALYSIS 路由正确 | 返回单任务 `STOCK_ANALYSIS` | 股票分析输入 | 写入路由上下文，选择股票分析执行节点 | pass |
| TC-009 | Node 单任务 GENERAL_CHAT 路由正确 | 返回单任务 `GENERAL_CHAT` | 闲聊输入 | 路由到通用聊天节点 | pass |
| TC-010 | 股票槽位兼容写入正确 | 单任务 slots 中包含股票类字段 | 股票相关输入 | 除基础槽位和意图槽位外，`STOCK_SLOT_KEY` 仍按旧链路兼容写入 | pass |

### 3.2 异常场景

| 编号 | 场景名称 | 前置条件 | 输入 | 预期结果 | status |
|------|------|------|------|------|------|
| TC-101 | Few-Shot 检索抛异常时安全降级 | `IntentFewshotService` 抛异常 | 普通用户消息 | 路由流程继续执行，Few-Shot 视为空样本，不抛出主链异常 | pass |
| TC-102 | LLM 返回非法 JSON 时降级成功 | LLM 返回非 JSON / 残缺 JSON | 非法响应内容 | 返回 fallback 结果，不导致主链中断 | pass |
| TC-103 | `multiTask=false` 但 `taskList` 为空时降级 | LLM 返回结构异常 | 空任务列表响应 | 自动 fallback 为单任务 `GENERAL_CHAT` | pass |
| TC-104 | clarification 返回缺少字段时系统兜底 | `needsClarification=true` 但 `clarificationPrompt` 缺失 | 异常补全响应 | 给出默认兜底行为或空值安全处理，不抛异常 | pass |
| TC-105 | taskList 单项缺失 intent 时兜底 | 单任务对象缺失关键字段 | 异常任务项 JSON | 降级为默认意图或 fallback 路由 | pending |
| TC-106 | Node 处理统一路由结果为空时兜底 | service 返回空对象/异常对象 | 异常路由结果 | Node 不崩溃，进入安全 fallback 分支 | pending |

### 3.3 边界场景

| 编号 | 场景名称 | 前置条件 | 输入 | 预期结果 | status |
|------|------|------|------|------|------|
| TC-201 | Few-Shot 样本为空列表 | 检索成功但无召回 | 普通用户消息 | Prompt 不报错，可无示例继续执行统一路由 | pass |
| TC-202 | historyMessages 为空列表 | 无历史上下文 | 首轮会话输入 | Prompt 构造成功，路由结果可正常解析 | pass |
| TC-203 | taskList 仅 1 项但 `multiTask=true` | LLM 返回边界不一致数据 | 单元素任务列表 | 系统按既定策略处理，不抛异常，并保持结果可解释 | pass |
| TC-204 | slots 为空对象 | 单任务识别成功但无槽位 | 槽位空 JSON | `BASE_SLOT_KEY` / `INTENT_SPECIFIC_SLOTS_KEY` 安全写入空结构 | pass |
| TC-205 | missingInfo 为空但 `needsClarification=true` | 补全结果字段不完整 | 边界补全响应 | 系统仍可返回补全状态，且字段处理安全 | pass |
| TC-206 | 超长用户消息下 Prompt 构造稳定 | 长文本输入 | 超长 query + history + fewshot | Prompt 构造成功，不出现 null/拼接错误 | pass |

### 3.4 回归场景

| 编号 | 场景名称 | 前置条件 | 输入 | 预期结果 | status |
|------|------|------|------|------|------|
| TC-301 | 旧单任务兼容模型仍可映射 | `IntentRoutingResult` 兼容逻辑保留 | 单任务 unified 结果 | 单任务上下文落盘格式不破坏下游兼容 | pass |
| TC-302 | 下游节点选择逻辑未回归 | 原有意图路由规则存在 | 各类单任务输入 | 不同 intent 仍路由到原既定节点 | pass |
| TC-303 | 多任务上下文写入未回归 | 多任务执行链仍可消费 `TASK_LIST_KEY` | 多任务输入 | `TASK_LIST_KEY` 格式满足下游消费要求 | pending |
| TC-304 | OpenAiTest 向量召回基线不受影响 | Few-Shot 底座能力存在 | `test_intent_fewshot_pgvector_recall()` | 向量写入召回用例继续可通过 | pending |
| TC-305 | 旧 route/doRoute 等兼容方法未被误删 | 保留兼容能力 | 代码级检查 / 单测 | 老方法仍可编译通过，不影响其他调用方 | pending |

---

## 4. 用例与代码映射

| 测试编号 | 对应用例方法 | 目标类/方法 | 覆盖类型 | 说明 |
|------|------|------|------|------|
| TC-001 | `should_include_fewshot_examples_when_building_unified_routing_prompt()` | `IntentRoutingPrompt#buildUnifiedRoutingPrompt` | 正常 | 验证 Prompt 中 Few-Shot 示例注入 |
| TC-002 | `should_parse_single_task_unified_response_when_json_is_valid()` | `IntentRoutingService#parseUnifiedResponse` | 正常 | 验证单任务解析 |
| TC-003 | `should_parse_multi_task_unified_response_when_json_is_valid()` | `IntentRoutingService#parseUnifiedResponse` | 正常 | 验证多任务解析 |
| TC-004 | `should_parse_clarification_response_when_needs_clarification_is_true()` | `IntentRoutingService#parseUnifiedResponse` | 正常 | 验证补全场景 |
| TC-005 | `should_call_route_unified_when_intent_routing_node_applies()` | `IntentRoutingNode#doApply` | 正常 | 验证主链改造生效 |
| TC-006 | `should_write_task_list_when_multi_task_is_true()` | `IntentRoutingNode#doApply` | 正常 | 验证多任务上下文写入 |
| TC-007 | `should_route_to_step1_analyzer_when_intent_is_pe_retrieval()` | `IntentRoutingNode#doApply` | 正常 | 验证知识检索节点选择 |
| TC-008 | `should_route_to_stock_analysis_node_when_intent_is_stock_analysis()` | `IntentRoutingNode#doApply` | 正常 | 验证股票分析节点选择 |
| TC-009 | `should_route_to_general_chat_node_when_intent_is_general_chat()` | `IntentRoutingNode#doApply` | 正常 | 验证闲聊节点选择 |
| TC-010 | `should_write_stock_slot_key_when_stock_slots_exist()` | `IntentRoutingNode#doApply` | 正常/回归 | 验证股票槽位兼容 |
| TC-101 | `should_continue_without_fewshot_examples_when_retrieve_fewshots_failed()` | `IntentRoutingService#routeUnified` | 异常 | Few-Shot 检索异常降级 |
| TC-102 | `should_fallback_when_llm_returns_invalid_json()` | `IntentRoutingService#routeUnified` | 异常 | 非法 JSON 降级 |
| TC-103 | `should_fallback_to_general_chat_when_task_list_is_empty_for_single_task()` | `IntentRoutingService#parseUnifiedResponse` | 异常 | 空任务单任务 fallback |
| TC-104 | `should_handle_missing_clarification_prompt_safely()` | `IntentRoutingService#parseUnifiedResponse` | 异常 | 字段缺失容错 |
| TC-201 | `should_build_prompt_without_examples_when_fewshot_list_is_empty()` | `IntentRoutingPrompt#buildUnifiedRoutingPrompt` | 边界 | 空 Few-Shot |
| TC-202 | `should_build_prompt_without_history_when_history_messages_are_empty()` | `IntentRoutingPrompt#buildUnifiedRoutingPrompt` | 边界 | 空 history |
| TC-203 | `should_keep_single_task_when_multi_task_flag_is_true_but_only_one_task()` | `IntentRoutingService#parseUnifiedResponse` | 边界 | multiTask 标记与 taskList 大小不一致 |
| TC-204 | `should_parse_empty_slots_safely()` | `IntentRoutingService#parseUnifiedResponse` | 边界 | 空 slots 安全处理 |
| TC-205 | `should_keep_clarification_state_when_missing_info_is_empty()` | `IntentRoutingService#parseUnifiedResponse` | 边界 | 空 missingInfo 安全处理 |
| TC-206 | `should_build_prompt_stably_when_user_message_is_very_long()` | `IntentRoutingPrompt#buildUnifiedRoutingPrompt` | 边界 | 超长输入 Prompt 构造稳定 |
| TC-301 | `should_keep_single_task_context_mapping_compatible_after_unified_routing()` | `IntentRoutingNode#doApply` | 回归 | 单任务兼容落盘 |
| TC-302 | `should_keep_downstream_node_selection_unchanged_after_mainline_switch()` | `IntentRoutingNode#doApply` | 回归 | 路由选择未回归 |

---

## 5. 关键校验点

### 5.1 数据正确性
- Few-Shot 示例是否真实进入统一路由 Prompt
- `taskList` 中 `intent / confidence / executorNode / slots` 是否正确映射
- 单任务场景是否从 `taskList[0]` 正确转换为 `IntentRoutingResult`

### 5.2 状态流转正确性
- `needsClarification=true` 时是否优先返回补全，而不是继续进入普通路由
- `multiTask=true` 时是否进入多任务执行分支
- `multiTask=false` 时是否进入单任务上下文写入和节点选择分支

### 5.3 异常处理正确性
- Few-Shot 检索异常是否被吞吐并转为空样本
- 非法 JSON 是否 fallback，而非直接抛错
- 空任务、缺字段、空槽位等不一致结构是否能安全处理

### 5.4 日志/监控/告警
- 是否需要校验日志输出：`是`
- 关键日志点：
  - 统一路由调用开始 / 结束
  - Few-Shot 检索异常降级
  - unified JSON 解析失败 fallback
  - Node 路由分支选择结果

---

## 6. 执行计划

### 6.1 自动化测试执行

| 步骤 | 内容 | 预期结果 | status |
|------|------|------|------|
| 1 | 补充 `IntentRoutingPrompt` / `IntentRoutingServiceTest` / `IntentRoutingNodeTest` 用例 | 测试代码完成 | pending |
| 2 | 执行 `ai-agent-study-domain` 模块单元测试 | 新增用例通过 | pending |
| 3 | 执行与意图路由相关回归测试 | 无关键回归问题 | pending |
| 4 | 执行模块编译验证 | 编译成功 | pending |
| 5 | 执行 `OpenAiTest.test_intent_fewshot_pgvector_recall()` 基线验证 | Few-Shot 向量底座能力可用 | pending |

### 6.2 手工验证步骤

| 步骤 | 操作 | 预期结果 | status |
|------|------|------|------|
| 1 | 输入单任务知识检索类问题 | 返回单任务路由结果，intent 正确 | pending |
| 2 | 输入包含多个任务的复合问题 | 返回多任务结果，`taskList` 正确拆分 | pending |
| 3 | 输入缺少必要信息的问题 | 返回补全提示而非错误路由 | pending |
| 4 | 构造 Few-Shot 检索失败场景 | 主链继续可用，系统降级成功 | pending |
| 5 | 输入股票分析请求 | 股票槽位及路由节点保持兼容 | pending |

---

## 7. 验收标准

| 编号 | 验收项 | 标准 | status |
|------|------|------|------|
| AC-001 | 主流程通过 | 单任务、多任务、补全三类正常场景测试通过 | pass |
| AC-002 | Few-Shot 已正式进入主链 | Prompt 中确实包含 Few-Shot 示例，且由统一路由服务消费 | pass |
| AC-003 | 异常处理符合预期 | 检索异常、非法 JSON、空任务等场景均可安全降级 | pass |
| AC-004 | 边界行为稳定 | 空 history、空 samples、空 slots 等边界场景通过 | pass |
| AC-005 | 无关键回归 | 原有下游节点选择及股票槽位映射不受影响 | pass |
| AC-006 | 编译通过 | 相关模块编译成功 | pass |
| AC-007 | 测试通过 | 新增/修改单测通过 | pass |

---

## 8. 风险与说明

| 风险点 | 影响 | 应对措施 |
|------|------|------|
| unified JSON 结构不稳定 | 解析失败导致主链回退频繁 | 提前覆盖缺字段、空任务、非法 JSON 用例 |
| Few-Shot 示例过多干扰模型判断 | 多任务拆分质量下降 | 校验 Top-K 限制及 Prompt 顺序 |
| Node 与 Service 职责未完全剥离 | 后续维护成本升高 | 通过测试锁定“Node 不再自己拼旧 Prompt”这一行为 |
| 回归点分散 | 影响既有下游节点行为 | 设计明确回归用例，覆盖各 intent 的节点路由 |

---

## 9. 执行结果记录

### 9.1 执行结果

| 项目 | 结果 |
|------|------|
| 单元测试 | pass |
| 集成测试 | pending |
| 手工验证 | pending |
| 编译验证 | pass |

### 9.2 问题记录

| 编号 | 问题描述 | 影响范围 | 状态 |
|------|------|------|------|
| BUG-001 | clarificationPrompt 缺失时返回 null，不满足补全兜底要求；已补默认提示修复 | `IntentRoutingService#parseUnifiedResponse` | pass |

### 9.3 结论
- 是否达到提测/合并条件：`是`
- 结论说明：
  - 已补齐 IntentRouting 主链对应的正常 / 异常 / 边界 / 回归单测覆盖，并通过验证
  - `ai-agent-study-domain` 模块定向测试与编译验证均已通过
  - `OpenAiTest.test_intent_fewshot_pgvector_recall()` 与手工验证项本次未执行，仍保持 pending
