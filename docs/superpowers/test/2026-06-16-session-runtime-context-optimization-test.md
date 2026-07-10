# Test: Session Runtime Context 优化

> **文档版本:** v1.0  
> **创建日期:** 2026-06-16  
> **关联需求:** `docs/superpowers/plans/2026-06-14-session-runtime-context-optimization-design.md`  
> **状态:** draft  
> **用例规模:** 核心 46 条，后续增强 6 条，覆盖正常、异常、边界、回归四类场景

---

## 1. 测试背景

### 1.1 对应 Story / Change

- 设计来源：`docs/superpowers/plans/2026-06-14-session-runtime-context-optimization-design.md`
- 关联历史测试：
  - `docs/superpowers/test/2026-06-13-split-intent-routing-experiment-test.md`
  - `docs/superpowers/test/2026-06-14-routing-structured-output-validation-test.md`

### 1.2 测试目标

- 验证本次优化后，一轮请求内 history、flow config 等运行时上下文由统一入口准备，并通过 `DynamicContext` 兼容 key 供节点复用。
- 验证 `IntentRoutingNode`、`QueryDecompositionNode`、`TaskRoutingSlotNode` 优先使用统一加载的 recent history，避免 split routing 同一轮重复读取会话历史。
- 验证 flow config 缓存生效，并支持手动失效；同时保证 few-shot 仍然实时检索，不进入 runtime cache。
- 验证 `ChatClient` 仍为应用级复用对象，不引入 session 私有状态。
- 验证 persona、`GeneralChatNode`、PE、巡检和现有持久化语义保持不变。
- 明确同 session 后端串行、token-aware prompt context、session summary、active skill working set 属于后续增强，不作为本期阻塞验收项。

### 1.3 测试范围

- 本次需求涉及的模块：
  - 执行入口：`AutoAgentExecuteStrategy`
  - 上下文兼容容器：`DefaultAutoAgentExecuteStrategyFactory.DynamicContext`
  - runtime context：`RuntimeContextAssembler`、`SessionRuntimeContextManager`、`AgentRuntimeConfigCache`
  - runtime model：`TurnRuntimeContext`、`SessionRuntimeContext`
  - 路由节点：`RootNode`、`IntentRoutingNode`、`QueryDecompositionNode`、`TaskRoutingSlotNode`
  - 通用对话节点：`GeneralChatNode`
  - 会话历史：`ChatMemoryPersistenceService`
  - few-shot：`IntentRoutingService`、`IntentFewshotService`
  - persona：`AbstractExecuteSupport#injectPersonaContext`
  - skills 配置：`TradingSkillsConfig`
- 本次重点验证的类/组件：
  - `DefaultRuntimeContextAssembler#prepare`
  - `SessionRuntimeContextManager#getOrLoad`
  - `AgentRuntimeConfigCache`
  - `AutoAgentExecuteStrategy#execute`
  - `RootNode#get`
  - `RootNode#doApply`
  - `IntentRoutingNode#doApply`
  - `QueryDecompositionNode#doApply`
  - `TaskRoutingSlotNode#doApply`
  - `GeneralChatNode#doApply`

### 1.4 不在本次测试范围

- 不验证真实 LLM 语义准确率，只验证上下文加载、复用、降级和回归行为。
- 不接入真实 Redis、MySQL、PGvector、Mem0、Langfuse 或外部模型服务；中间件依赖统一 mock。
- 不验证后端 `SessionExecutionGuard` 的正式行为，除非后续 Phase 3 实现。
- 不验证完整 token-aware 裁剪、session summary 刷新和 active skill working set 的最终业务效果。
- 不新增或验证持久化失败补偿、outbox、pending queue、Redis 写失败重试。
- 不改变 `GeneralChatNode` 的 ChatMemoryAdvisor 注入语义，不要求它改为读取 runtime history。
- 不把 persona 扩展到 routing/split routing prompt，本期只验证现有链路保持不变。

---

## 2. 测试策略

### 2.1 测试分层

| 测试层级 | 是否覆盖 | 说明 |
|------|------|------|
| 单元测试 | 是 | 覆盖 assembler、session cache、flow config cache、节点 history 复用、fallback 和配置分支 |
| 集成测试 | 是 | 使用 mock 依赖验证 `AutoAgentExecuteStrategy -> RootNode -> routing nodes` 的协作 |
| 接口测试 | 部分 | 通过本地请求或手工 smoke 验证 SSE、通用对话和显式 Agent 行为不变 |
| 回归测试 | 是 | 覆盖 unified/split routing、通用对话、PE、巡检、few-shot、persona 和 skills 配置 |
| 手工验证 | 是 | 验证日志、metrics、缓存命中效果和配置失效操作 |

### 2.2 测试原则

- 中间件依赖统一 mock，只验证当前层业务逻辑。
- 每个自动化用例必须有明确断言，最终以 `assert`、`verify`、`assertThrows` 或参数捕获比对结果结束。
- 对“只读取一次”的场景，必须使用 mock 调用次数或同一对象引用断言，不以日志推断。
- 对缓存场景，必须分别覆盖 miss、hit、手动失效和异常 fallback。
- 对回归场景，优先断言旧链路关键参数不变，而不是只断言“不抛异常”。
- 后续增强项可以列入测试设计，但 `status` 保持 `pending-enhancement`，不作为本期提测阻塞。

### 2.3 Mock 策略

| 依赖项 | 是否 Mock | Mock 方式 | 说明 |
|------|------|------|------|
| `ChatMemoryPersistenceService` | 是 | Mockito Stub/Mock | 控制 history 返回、异常、空列表，并验证读取次数 |
| `IAgentRepository` | 是 | Mockito Stub/Mock | 控制 flow config 查询返回和异常，验证缓存命中后不再查库 |
| `IntentFewshotService` | 是 | Mockito Mock | 验证 few-shot 每次 route 都实时调用 `retrieveTopK` |
| `IntentRoutingService` | 是 | Mockito Mock/真实实例 | 节点测试用 mock 捕获 history；服务测试用真实实例验证 few-shot 行为 |
| `ChatClient` / `ChatModel` | 是 | Mockito Stub / 测试替身 | 验证全局复用边界、Advisor 参数和通用对话行为 |
| `IUserPersonaCacheService` | 是 | Mockito Mock | 验证 persona 仍沿用现有 `injectPersonaContext` 链路 |
| Caffeine/本地缓存 | 部分 | 真实 Caffeine 或测试替身 | 覆盖 session runtime cache 与 flow config cache 的命中、过期、失效 |
| `ResponseBodyEmitter` | 是 | 测试替身 | 验证 SSE 完成、错误和流式输出行为不因 runtime 改造变化 |

---

## 3. 测试场景设计

### 3.1 正常场景

| 编号 | 场景名称 | 前置条件 | 输入 | 预期结果 | status |
|------|------|------|------|------|------|
| TC-001 | assembler 构建 turn/session context | `ChatMemoryPersistenceService` 返回 2 条历史，flow config 查询成功 | 带 `sessionId`、`userId`、普通 query 的请求 | 返回 `TurnRuntimeContext`；`DynamicContext` 写入 `turnRuntimeContext`、`sessionRuntimeContext`、`recentHistoryMessages`、`sessionId`、`userId`、flow config map | pending |
| TC-002 | recent history 格式化正确 | 历史包含 user/assistant 且 content 非空 | `sessionId=session-1` | `recentHistoryMessages` 为 `role + ": " + content` 列表，过滤 role 或 content 为空的消息 | pending |
| TC-003 | session runtime cache miss 后加载并回填 | 本地 cache 为空，Redis/MySQL 读取路径返回历史 | 首次请求同一 session | 调用历史读取 1 次，构造 `SessionRuntimeContext` 并写入 cache | pending |
| TC-004 | session runtime cache hit 后不重复读历史 | cache 已有 `SessionRuntimeContext` | 第二次请求同一 session | 不调用 `ChatMemoryPersistenceService#getConversationHistory`，使用缓存 recent history 构造本轮只读视图 | pending |
| TC-005 | split routing 同一轮只读一次 history | `QueryDecompositionNode` 后接 `TaskRoutingSlotNode`，assembler 已写入 `recentHistoryMessages` | 多任务 split 请求 | 两个节点使用同一份 history；`ChatMemoryPersistenceService` 在本轮只被 assembler 或 manager 调用一次 | pending |
| TC-006 | IntentRoutingNode 优先使用 DynamicContext history | `DynamicContext` 已有 `recentHistoryMessages=["user: 上轮问题"]` | unified routing 请求 | `IntentRoutingService#routeUnified` 收到该列表；节点本地 fallback 读取方法不被调用 | pending |
| TC-007 | QueryDecompositionNode 优先使用 DynamicContext history | `DynamicContext` 已有 recent history | split 第一阶段请求 | `decomposeQueryWithMetric` 收到该列表；不重复调用 history service | pending |
| TC-008 | TaskRoutingSlotNode 优先使用 DynamicContext history | `DynamicContext` 已有 recent history 和 decomposition result | split 第二阶段请求 | 每个 `routeTaskIntentSlotsWithMetric` 都收到同一 history 列表；不重复调用 history service | pending |
| TC-009 | RootNode 使用 assembler 准备的 flow config | `DynamicContext` 已有 flow config map | 无 `aiAgentId` 的自动路由请求 | `RootNode#get` 不调用 `queryAllFlowConfigForIntentRouting`，按配置选择 unified/split 节点 | pending |
| TC-010 | 显式 aiAgentId 使用 agent flow config cache | `AgentRuntimeConfigCache` miss 后返回指定 agent config | `aiAgentId=1001` | 首次查库并缓存；后续同 `aiAgentId` 请求直接命中 cache | pending |
| TC-011 | flow config cache 手动失效生效 | cache 已缓存旧配置，随后调用 clear | 新请求读取同 key | clear 后重新调用 repository，返回新配置 | pending |
| TC-012 | few-shot 仍保持实时检索 | 连续两次相同 query，`IntentFewshotService` 返回不同样例版本 | 调用 `routeUnified` 或 split 阶段路由 | 每次都调用 `retrieveTopK(query, 5)`；第二次使用最新样例，不命中 runtime cache | pending |
| TC-013 | ChatClient 不持有 session 状态 | 两个 session 使用同一个 clientId/taskType | session-A 和 session-B 分别调用节点 | `getChatClientByClientId` 返回同一配置级对象；sessionId/userId/traceId 仅通过 request spec/advisor 参数传入 | pending |
| TC-014 | 通用对话继续走 ChatMemoryAdvisor 参数 | `GeneralChatNode` 正常执行文本对话 | `sessionId=session-1` | advisor 参数仍包含 `chat_memory_conversation_id` 和 `chat_memory_response_size=1024`，不改为直接拼接 runtime history | pending |
| TC-015 | persona 仍沿用现有注入链路 | request 带 `userId`，persona cache 返回画像 | PE 或现有消费链路请求 | `injectPersonaContext` 写入原有 key；routing/split 节点不额外注入完整 persona prompt | pending |
| TC-016 | production skills autoReload 可配置关闭 | 配置 `spring.ai.trading.skills.auto-reload=false` | 初始化 `TradingSkillsConfig` | `SkillsAgentHook.autoReload(false)` 生效；lazyLoad 和 read_skill 行为保持 | pending |

### 3.2 异常场景

| 编号 | 场景名称 | 前置条件 | 输入 | 预期结果 | status |
|------|------|------|------|------|------|
| TC-101 | history 加载异常降级为空列表 | `ChatMemoryPersistenceService#getConversationHistory` 抛异常 | 任意 session 请求 | assembler 或 manager 不抛出中断异常；`recentHistoryMessages=[]`；日志记录 warn | pending |
| TC-102 | flow config 缓存加载异常保留原失败语义 | repository 查询 flow config 抛异常 | 自动路由或显式 agent 请求 | 异常按现有链路暴露或由上层捕获；cache 不写入失败结果 | pending |
| TC-103 | DynamicContext 缺少 recent history 时节点 fallback | 未接入 assembler 或 key 不存在 | 直接单测节点 | 节点调用现有 `history(sessionId)` 方法读取历史，保持兼容 | pending |
| TC-104 | DynamicContext history 类型错误安全降级 | `recentHistoryMessages` 被写入非 `List<String>` | 节点执行 | 节点不产生 `ClassCastException` 逃逸；降级调用现有 history 方法或使用空列表 | pending |
| TC-105 | flow config cache 返回空配置时保持原错误 | repository 返回空 map 或缺少 `INTENT_ROUTING` | routing 节点执行 | `IntentRoutingNode`/`QueryDecompositionNode` 抛出既有 `Missing INTENT_ROUTING client configuration` 语义 | pending |
| TC-106 | 手动失效不存在 key 不报错 | cache 为空或 key 不存在 | 调用 clear 指定 key/全部清理 | 操作幂等，不抛异常，后续请求正常加载 | pending |
| TC-107 | few-shot 检索异常不污染 runtime cache | `IntentFewshotService#retrieveTopK` 抛异常 | routing 请求 | 按 `IntentRoutingService` 既有降级策略处理；下一次请求仍重新调用 few-shot 检索 | pending |
| TC-108 | SSE 执行异常仍安全 complete | 下游节点抛异常 | `AutoAgentExecuteStrategy#execute` | emitter 收到 error 事件并 complete；runtime context 不改变异常关闭行为 | pending |

### 3.3 边界场景

| 编号 | 场景名称 | 前置条件 | 输入 | 预期结果 | status |
|------|------|------|------|------|------|
| TC-201 | sessionId 为空 | request `sessionId=null` | 普通请求 | assembler 不写入错误 cache key；history 使用空列表；下游保留现有空 session 行为 | pending |
| TC-202 | userId 为空 | request `userId=null` | 普通请求 | 不调用 persona cache；`DynamicContext` 仍能写入 turn context；路由正常执行 | pending |
| TC-203 | history 为空列表 | 历史读取返回 `List.of()` | unified/split 请求 | routing/decomposition/slot 均收到空列表，不出现 null 或字符串 `"null"` | pending |
| TC-204 | history 含空 role/content | 历史消息包含空 role、空 content、合法消息 | 任意请求 | 只保留合法消息，顺序不变 | pending |
| TC-205 | cache 过期后重新加载 | Caffeine expire-after-write 到期 | 同 session 新请求 | 重新调用 history 读取并回填新 `SessionRuntimeContext` | pending |
| TC-206 | 同 userId 不同 session 并行隔离 | 两个 session 同时请求，persona cache 可共享 | session-A/session-B | 各自 session history 不共享；persona 仍按 user 级缓存读取 | pending |
| TC-207 | 不同 session 并发不全局阻塞 | 多个 session 同时执行 assembler prepare | session-A/B/C | 各请求都能并行准备上下文，不因全局锁串行 | pending |
| TC-208 | 同 session 并发风险记录 | 后端未启用 `SessionExecutionGuard` | 同 session 两个请求绕过前端同时进入 | 当前阶段不保证串行；测试文档和风险说明标记为已知边界，不作为 Phase 1/2 失败 | pending |
| TC-209 | flow config TTL 边界 | TTL 配置为较短值 | TTL 内和 TTL 后分别请求 | TTL 内命中缓存；TTL 后重新查库；few-shot 不受 TTL 影响 | pending |
| TC-210 | cache size 达到上限 | session runtime cache 接近 maximum-size | 新 session 请求 | 按缓存策略淘汰旧条目；被淘汰 session 下一轮可重新回源加载 | pending |

### 3.4 回归场景

| 编号 | 场景名称 | 前置条件 | 输入 | 预期结果 | status |
|------|------|------|------|------|------|
| TC-301 | unified routing 行为不变 | mode=UNIFIED 或默认 | 单任务和澄清请求 | 结果、clarification key、metrics 和下游分发与改造前一致 | pending |
| TC-302 | split routing 行为不变 | mode=SPLIT | 多任务 Query | 拆解、切槽、任务图校验、metrics 和下游分发与改造前一致，仅 history 读取收敛 | pending |
| TC-303 | GeneralChatNode 流式输出不变 | `GENERAL_CHAT` 路由结果 | 普通文本对话 | SSE start/content/complete 事件、`generalChatResponse` 写入和 advisor 参数不变 | pending |
| TC-304 | 多模态通用对话不变 | inputType=1 且 file 不为空 | 图片请求 | OSS 上传、multimodal clientId 选择、advisor retrieve size=0 和 SSE 行为不变 | pending |
| TC-305 | PE 链路不受 runtime 改造影响 | 显式 PE agent 或 routing 到 PE | PE 请求 | `Step1AnalyzerNode` 仍使用既有 flow config 和 persona 链路 | pending |
| TC-306 | 巡检链路不受 runtime 改造影响 | `aiAgentId=5` 或巡检 agentType | 巡检请求 | `IntelligentInspection` 仍能获取客户端配置；SSE 和持久化行为不变 | pending |
| TC-307 | RootNode 持久化语义不变 | 下游写入 finalSummary/inspectionResult/generalChatResponse | 任意完成请求 | `persistRootConversation` 仍按既有优先级选择 output 并调用 `persistConversation` | pending |
| TC-308 | ChatMemoryAdvisor 与业务节点 prompt 边界不变 | 通用对话和 routing 节点分别执行 | 多轮对话请求 | GeneralChat 可继续依赖 advisor；routing/slot 节点显式使用 history 素材，不混用最终 prompt | pending |
| TC-309 | few-shot 相关旧测试不回归 | 现有 few-shot 单测存在 | 路由请求 | `IntentRoutingServiceTest` 中 trivial 不检索、非 trivial 检索等旧断言继续通过 | pending |
| TC-310 | 结构化输出和 split 旧测试不回归 | 执行既有测试集 | 现有测试命令 | split、structured output、RoutingResultHandler、TaskGraphValidator 相关测试均通过 | pending |
| TC-311 | DynamicContext 既有 key 兼容 | 旧节点依赖 `sessionId`、`userId`、flow config map 等 key | 任意请求 | 新增 key 不覆盖旧 key；旧节点仍可读取原字段 | pending |
| TC-312 | 持久化失败补偿不被误引入 | 模拟 `persistConversation` 失败或 Redis 写失败 | 完成请求 | 保持现有日志降级策略；不刷新本地 session cache 为新的事实源 | pending |

### 3.5 后续增强场景

| 编号 | 场景名称 | 触发阶段 | 预期结果 | status |
|------|------|------|------|------|
| TC-401 | 同 session 串行 guard | Phase 3 | 同 session 并发第二个请求等待或 busy，不同 session 并发执行 | pending-enhancement |
| TC-402 | guard 异常释放锁 | Phase 3 | 第一个请求异常后锁必定释放，第二个请求可继续 | pending-enhancement |
| TC-403 | PromptContextBuilder 按节点裁剪 | Phase 4 | 不同节点根据 policy 选择 history、persona、summary、few-shot，且不共享最终 prompt | pending-enhancement |
| TC-404 | token 超预算裁剪 | Phase 4 | 按优先级裁剪，当前输入和必要 system/task prompt 不被裁掉 | pending-enhancement |
| TC-405 | session summary 刷新 | Phase 5 | 长会话可通过 summary 保留关键上下文，不依赖完整历史 | pending-enhancement |
| TC-406 | active skill working set | Phase 5 | 同 session 已读取 skill 可被记录和提示，不重复加载完整 skill 内容 | pending-enhancement |

---

## 4. 用例与代码映射

| 测试编号 | 对应用例方法 | 目标类/方法 | 覆盖类型 | 说明 |
|------|------|------|------|------|
| TC-001 | `should_prepare_turn_and_session_context_and_write_compatibility_keys()` | `DefaultRuntimeContextAssembler#prepare` | 正常 | 构建 runtime context 和兼容 key |
| TC-002 | `should_format_recent_history_messages_and_filter_invalid_items()` | `SessionRuntimeContextManager#getOrLoad` | 正常 | history 表达形式 |
| TC-003 | `should_load_and_cache_session_context_when_cache_misses()` | `SessionRuntimeContextManager#getOrLoad` | 正常 | cache miss |
| TC-004 | `should_reuse_cached_session_context_without_loading_history_again()` | `SessionRuntimeContextManager#getOrLoad` | 正常 | cache hit |
| TC-005 | `should_load_conversation_history_once_in_split_routing_turn()` | `QueryDecompositionNode` / `TaskRoutingSlotNode` | 正常 | split 同轮复用 |
| TC-006 | `should_use_dynamic_context_history_before_fallback_in_unified_node()` | `IntentRoutingNode#doApply` | 正常 | unified history 优先级 |
| TC-007 | `should_use_dynamic_context_history_before_fallback_in_decomposition_node()` | `QueryDecompositionNode#doApply` | 正常 | split round1 history 优先级 |
| TC-008 | `should_use_dynamic_context_history_for_each_slot_routing_call()` | `TaskRoutingSlotNode#doApply` | 正常 | split round2 history 优先级 |
| TC-009 | `should_use_prepared_flow_config_without_querying_repository_in_root_node()` | `RootNode#get` | 正常 | RootNode flow config 复用 |
| TC-010 | `should_cache_agent_flow_config_by_ai_agent_id()` | `AgentRuntimeConfigCache` | 正常 | 显式 agent config cache |
| TC-011 | `should_reload_flow_config_after_manual_cache_clear()` | `AgentRuntimeConfigCache` | 正常 | 手动失效 |
| TC-012 | `should_retrieve_fewshot_samples_realtime_without_runtime_cache()` | `IntentRoutingService` | 正常 | few-shot 不缓存 |
| TC-013 | `should_keep_chat_client_application_scoped_and_pass_session_params_per_request()` | `AbstractExecuteSupport#getChatClientByClientId` / 节点调用 | 正常 | ChatClient 边界 |
| TC-014 | `should_keep_general_chat_memory_advisor_params_unchanged()` | `GeneralChatNode#doApply` | 正常 | 通用对话回归 |
| TC-015 | `should_keep_persona_injection_on_existing_execute_support_chain()` | `AbstractExecuteSupport#injectPersonaContext` | 正常 | persona 非目标 |
| TC-016 | `should_configure_trading_skill_auto_reload_from_property()` | `TradingSkillsConfig` | 正常 | skills 配置 |
| TC-101 | `should_fallback_to_empty_history_when_history_load_fails()` | `SessionRuntimeContextManager#getOrLoad` | 异常 | history 异常降级 |
| TC-102 | `should_not_cache_failed_flow_config_load()` | `AgentRuntimeConfigCache` | 异常 | cache 异常 |
| TC-103 | `should_fallback_to_legacy_history_loader_when_recent_history_key_absent()` | routing nodes | 异常 | 兼容 fallback |
| TC-104 | `should_fallback_when_recent_history_key_has_invalid_type()` | routing nodes | 异常 | key 类型错误 |
| TC-105 | `should_keep_missing_intent_routing_config_error()` | `IntentRoutingNode` / `QueryDecompositionNode` | 异常 | 缺配置 |
| TC-106 | `should_ignore_clear_for_missing_cache_key()` | `AgentRuntimeConfigCache` | 异常 | 幂等清理 |
| TC-107 | `should_not_cache_fewshot_failure()` | `IntentRoutingService` | 异常 | few-shot 异常 |
| TC-108 | `should_complete_emitter_safely_when_node_chain_fails()` | `AutoAgentExecuteStrategy#execute` | 异常 | SSE 异常 |
| TC-201 | `should_handle_blank_session_id_without_invalid_cache_key()` | `DefaultRuntimeContextAssembler#prepare` | 边界 | 空 session |
| TC-202 | `should_skip_persona_when_user_id_is_blank()` | `AbstractExecuteSupport#injectPersonaContext` | 边界 | 空 user |
| TC-203 | `should_pass_empty_history_list_to_routing_nodes()` | routing nodes | 边界 | 空 history |
| TC-204 | `should_filter_history_messages_with_blank_role_or_content()` | `SessionRuntimeContextManager#getOrLoad` | 边界 | 空字段过滤 |
| TC-205 | `should_reload_session_context_after_cache_expiration()` | `SessionRuntimeContextManager#getOrLoad` | 边界 | 过期重载 |
| TC-206 | `should_isolate_history_between_sessions_with_same_user()` | `SessionRuntimeContextManager` | 边界 | session 隔离 |
| TC-207 | `should_prepare_contexts_for_different_sessions_without_global_lock()` | `DefaultRuntimeContextAssembler#prepare` | 边界 | 不同 session 并行 |
| TC-208 | `should_document_same_session_concurrency_as_known_phase_one_risk()` | 测试文档/并发 smoke | 边界 | 非阻塞风险 |
| TC-209 | `should_reload_flow_config_after_ttl_without_affecting_fewshot()` | `AgentRuntimeConfigCache` / `IntentRoutingService` | 边界 | TTL |
| TC-210 | `should_reload_evicted_session_context_from_source()` | `SessionRuntimeContextManager` | 边界 | cache size |
| TC-301 | `should_keep_unified_routing_behavior()` | `IntentRoutingNodeTest` | 回归 | unified |
| TC-302 | `should_keep_split_routing_behavior_with_history_read_converged()` | `QueryDecompositionNodeTest` / `TaskRoutingSlotNodeTest` | 回归 | split |
| TC-303 | `should_keep_general_chat_streaming_behavior()` | `GeneralChatNodeTest` | 回归 | 文本对话 |
| TC-304 | `should_keep_multimodal_chat_behavior()` | `GeneralChatNodeTest` | 回归 | 多模态 |
| TC-305 | `should_keep_pe_chain_behavior()` | `RootNodeTest` / PE 节点测试 | 回归 | PE |
| TC-306 | `should_keep_inspection_chain_behavior()` | `AutoAgentExecuteStrategyTest` / `RootNodeTest` | 回归 | 巡检 |
| TC-307 | `should_keep_root_conversation_persistence_priority()` | `RootNodeTest` | 回归 | 持久化 |
| TC-308 | `should_keep_chat_memory_advisor_separate_from_routing_prompt_context()` | `GeneralChatNodeTest` / routing node tests | 回归 | prompt 边界 |
| TC-309 | `should_keep_existing_fewshot_routing_tests_passing()` | `IntentRoutingServiceTest` | 回归 | few-shot |
| TC-310 | `should_pass_existing_split_and_structured_output_regression_suite()` | 既有测试集 | 回归 | 旧测试集 |
| TC-311 | `should_keep_existing_dynamic_context_keys_compatible()` | `DefaultAutoAgentExecuteStrategyFactoryDynamicContextTest` | 回归 | DynamicContext |
| TC-312 | `should_not_refresh_local_session_cache_when_persistence_result_is_unknown_or_failed()` | `SessionRuntimeContextManager` / `RootNode` | 回归 | 持久化失败边界 |

建议新增或补充测试类：

- `DefaultRuntimeContextAssemblerTest`
- `SessionRuntimeContextManagerTest`
- `AgentRuntimeConfigCacheTest`
- `RuntimeContextKeysTest`
- `AutoAgentExecuteStrategyTest`
- `RootNodeTest`
- `IntentRoutingNodeTest`
- `QueryDecompositionNodeTest`
- `TaskRoutingSlotNodeTest`
- `GeneralChatNodeTest`
- `TradingSkillsConfigTest`

---

## 5. 关键校验点

### 5.1 数据正确性

- `TurnRuntimeContext`、`SessionRuntimeContext`、`recentHistoryMessages` 必须在同一轮内保持只读视图，不被下游节点改写。
- `DynamicContext` 新增 key 不得覆盖 `sessionId`、`userId`、`traceId`、`emitter`、`aiAgentClientFlowConfigVOMap` 等既有字段。
- recent history 必须统一格式化为 `role: content`，并保持原始顺序。
- flow config cache key 需要区分 `intent-routing-all` 和 `agent-flow-config:{aiAgentId}`。
- few-shot 结果不得进入 session runtime cache 或 flow config cache。

### 5.2 状态流转正确性

- `AutoAgentExecuteStrategy` 必须先创建 `DynamicContext` 和 traceId，再进入 runtime assembler，最后进入 `RootNode`。
- `RootNode` 在已有 flow config map 时不得再次查库。
- routing/split 节点必须先读 `DynamicContext` 的 recent history，缺失时才走 legacy fallback。
- session runtime cache miss、hit、expire、evict 均要有明确行为。
- 手动清理 flow config cache 后，下一次请求必须重新查库。

### 5.3 异常处理正确性

- history 读取失败不应阻断当前轮路由。
- flow config 加载失败不应写入错误缓存。
- `DynamicContext` 兼容 key 缺失时，节点保持旧行为。
- 本期不做 `afterTurn` 主动刷新；持久化结果未知或失败时，不应把本地 session cache 更新成新的事实源。
- 同 session 后端并发乱序是已知边界风险，不应在 Phase 1/2 测试里误判为已解决能力。

### 5.4 日志/监控/告警

- 是否需要校验日志输出：是，优先验证关键行为指标，不匹配完整日志文本。
- 关键日志点：
  - assembler prepare 开始/结束、sessionId、traceId、history 条数。
  - session runtime cache hit/miss/expire。
  - flow config cache hit/miss/clear。
  - history 加载失败和 flow config 加载失败的 warn/error。
  - split routing 阶段应能观察到同一轮 history 只加载一次。

---

## 6. 执行计划

### 6.1 自动化测试执行

| 步骤 | 内容 | 预期结果 | status |
|------|------|------|------|
| 1 | 新增 `DefaultRuntimeContextAssemblerTest` | context 构建、兼容 key、异常降级、非目标 persona 边界均有断言 | pending |
| 2 | 新增 `SessionRuntimeContextManagerTest` | cache miss/hit/expire/evict、history 格式化、异常 fallback 均通过 | pending |
| 3 | 新增 `AgentRuntimeConfigCacheTest` | intent routing config、agent config、TTL、手动失效、异常不缓存均通过 | pending |
| 4 | 补充 routing 节点测试 | 三个 routing 节点优先读取 `recentHistoryMessages`，缺失时 fallback | pending |
| 5 | 补充 `RootNodeTest` 和 `AutoAgentExecuteStrategyTest` | assembler 调用顺序、flow config 复用、显式 Agent 和巡检回归通过 | pending |
| 6 | 补充 `GeneralChatNodeTest` | advisor 参数、流式输出、多模态行为保持不变 | pending |
| 7 | 补充 `IntentRoutingServiceTest` | few-shot 仍实时检索，异常不污染 runtime cache | pending |
| 8 | 补充 `TradingSkillsConfigTest` | autoReload 属性可配置，默认值符合环境预期 | pending |
| 9 | 执行模块级回归测试 | routing、split、structured output、chat、PE、巡检相关测试通过 | pending |
| 10 | 执行编译验证 | 模块编译成功 | pending |

建议执行命令（实现后按实际 Maven 配置调整）：

```powershell
mvn test -pl ai-agent-study-domain -Dtest=DefaultRuntimeContextAssemblerTest,SessionRuntimeContextManagerTest,AgentRuntimeConfigCacheTest,IntentRoutingNodeTest,QueryDecompositionNodeTest,TaskRoutingSlotNodeTest,RootNodeTest,AutoAgentExecuteStrategyTest,GeneralChatNodeTest,IntentRoutingServiceTest
mvn test -pl ai-agent-study-trading/ai-agent-study-trading-infra -Dtest=TradingSkillsConfigTest
mvn test -pl ai-agent-study-domain
mvn compile -pl ai-agent-study-domain -am -DskipTests
```

### 6.2 手工验证步骤

| 步骤 | 操作 | 预期结果 | status |
|------|------|------|------|
| 1 | 启动应用，发送默认 unified 单任务请求 | 日志显示 assembler prepare；routing 节点使用统一 recent history；通用功能正常返回 | pending |
| 2 | 切换 split mode，发送多任务请求 | 同一轮只加载一次 history，拆解和切槽均可使用相同 history | pending |
| 3 | 连续发送相同自动路由请求 | flow config cache 命中，repository 查询次数下降；few-shot 仍每次检索 | pending |
| 4 | 修改 flow config 后执行手动 clear | 下一次请求读取新配置 | pending |
| 5 | 发送通用对话请求 | ChatMemoryAdvisor 参数、SSE start/content/complete 和持久化行为不变 | pending |
| 6 | 发送显式 PE 和巡检请求 | 原业务链路不受 runtime 改造影响 | pending |
| 7 | 模拟多标签页同 session 并发 | 记录当前阶段仍可能乱序，作为 Phase 3 风险输入 | pending |

---

## 7. 验收标准

| 编号 | 验收项 | 标准 | status |
|------|------|------|------|
| AC-001 | 上下文统一准备 | `RuntimeContextAssembler` 能构建 turn/session context 并写入兼容 key | pending |
| AC-002 | history 读取收敛 | split routing 同一轮 conversation history 只加载一次，三类 routing 节点优先复用 `recentHistoryMessages` | pending |
| AC-003 | fallback 兼容 | assembler 未接入或 key 缺失时，节点仍可使用现有 history 读取逻辑 | pending |
| AC-004 | flow config 缓存生效 | 高频请求下 flow config repository 调用减少，且支持手动清理和 TTL 失效 | pending |
| AC-005 | few-shot 实时检索 | few-shot 不进入 runtime cache，同一 query 多次调用仍访问 `IntentFewshotService#retrieveTopK` | pending |
| AC-006 | ChatClient 边界清晰 | 没有 sessionId、userId、history、persona、active skill 状态进入全局 `ChatClient` | pending |
| AC-007 | 非目标行为不变 | persona、GeneralChat、PE、巡检、持久化失败降级语义保持现状 | pending |
| AC-008 | 关键回归通过 | 既有 routing、split、structured output、few-shot、chat、PE、巡检相关测试通过 | pending |
| AC-009 | 编译通过 | 相关模块编译成功 | pending |

---

## 8. 风险与说明

| 风险点 | 影响 | 应对措施 |
|------|------|------|
| DynamicContext 兼容期仍依赖字符串 key | key 写错会导致节点 fallback 或上下文缺失 | 增加 `RuntimeContextKeys` 常量测试和节点优先级测试 |
| 本地 session cache 与 Redis/MySQL 一致性边界不清 | 可能误把本地缓存当事实源 | 本期不做 afterTurn 主动刷新；测试持久化失败时不刷新本地 cache |
| flow config cache 带来配置变更延迟 | 配置发布后短时间仍使用旧配置 | 验证 TTL 和手动 clear；生产需配套管理入口 |
| few-shot 未缓存可能仍有性能成本 | 高频重复 query 仍访问 PGvector | 本期按设计保留实时检索；只通过指标观察，不在本需求优化 |
| 同 session 并发仍由前端约束 | API 直连、多标签页可能导致乱序 | Phase 1/2 记录风险；Phase 3 再补 `SessionExecutionGuard` 自动化测试 |
| GeneralChat 与 routing history 来源不同 | 误以为所有节点都必须读 runtime history | 回归测试明确 GeneralChat 继续走 ChatMemoryAdvisor |
| persona 注入范围被误扩大 | 可能污染意图路由结构化输出 | 测试断言 routing/split 不额外注入完整 persona |

---

## 9. 执行结果记录

### 9.1 执行结果

| 项目 | 结果 |
|------|------|
| 核心用例设计 | complete |
| 单元测试 | pending |
| 集成测试 | pending |
| 手工验证 | pending |
| 编译验证 | pending |

### 9.2 问题记录

| 编号 | 问题描述 | 影响范围 | 状态 |
|------|------|------|------|
| BUG-001 | 待执行测试后补充 | - | pending |

### 9.3 结论

- 是否达到提测/合并条件：`否，当前仅完成测试设计`
- 结论说明：
  - 本文档已覆盖 Session Runtime Context 优化的核心风险：history 读取收敛、flow config 缓存、few-shot 实时检索、ChatClient 状态边界、DynamicContext 兼容、非目标链路回归和同 session 并发边界。
  - 后续实现完成后，应按第 6 章执行自动化与手工验证，并根据真实执行结果更新 `status` 与问题记录。
