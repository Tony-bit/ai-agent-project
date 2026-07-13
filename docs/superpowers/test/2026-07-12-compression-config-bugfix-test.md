# Test: Mandatory Context Compression Bugfix

## 1. 测试目标

对应设计：`docs/superpowers/plans/2026-07-12-compression-config-bugfix-design.md`

- 验证压缩始终启用，不存在独立 enabled 或 per-model compressionModelId。
- 验证 DB 参数、代码默认值和旧 Retry JSON 兼容。
- 验证统一压缩助手别名和默认提示词。
- 验证用户 Query 主动超限及 1261 被动超限闭环。

不测试真实数据库、真实 LLM、HTTP/SSE 或摘要语言质量。

## 2. Mock 策略

| 依赖 | 方式 | 目的 |
|------|------|------|
| DAO | Mockito | 返回指定 ext_param 和 flow 配置 |
| 压缩 LLM | ChatClient/PromptCompressionService Mock | 捕获入参并返回固定摘要或 Prompt |
| 原 LLM | ChatModel Mock | 模拟成功和 1261 |
| ArmoryObjectRegistry | 真实对象 | 验证动态注册、主路径查找和装配校验 |
| ApplicationContext | Mockito | 只验证静态 Bean 兼容回退 |
| RetryChatModel | 真实组件 | 验证阈值、预算和 Prompt 替换 |
| RetryRuntimeContextHolder | 真实组件 | 验证上下文绑定与清理 |

## 3. 正常场景

| 编号 | 场景 | 预期结果 | status |
|------|------|------|------|
| TC-001 | 解析复合参数 | retry 和三个 compression 参数正确，无 enabled/modelId | append |
| TC-002 | threshold=1026、summaryTokens=1 的超长 Query | 先 compress，再以 compressedPrompt 调原 LLM 一次 | append |
| TC-003 | 原 LLM 首次返回 1261 | compress 一次，原 LLM 第二次成功 | append |
| TC-004 | flow 中存在压缩助手 | 同一 ChatClient 以普通名称和 compressionChatClient 写入真实 Registry | append |
| TC-005 | DB 有 prompt 7001 | 使用 DB 提示词且不重复 | append |
| TC-006 | Registry 主路径解析 | Registry 和 ApplicationContext 均可返回对象 | 使用 Registry 对象且不访问 ApplicationContext | append |
| TC-007 | Spring 静态 Bean 回退 | Registry 不含别名，ApplicationContext 含别名 | 正确返回 Spring ChatClient | append |
| TC-008 | 多 Agent 共享助手 | 多条 flow 均指向 clientId=3202 | 去重为 3202，忽略 sequence 差异并正常装配 | append |
| TC-009 | null retry 强制包装 | retryConfig=null + 默认 compression | 结果仍为 RetryChatModel，普通预算为 1 | append |
| TC-010 | disabled retry 强制包装 | retry.enabled=false | 结果仍为 RetryChatModel | append |
| TC-011 | 多模型强制包装 | 同时加载三个业务模型 | 三个注册对象全部是 RetryChatModel | append |
| TC-012 | 每模型 Policy 传递 | 三个模型配置不同合法参数 | 各包装实例分别持有对应 threshold/attempts/summaryTokens | append |
| TC-013 | 压缩模型包装防递归 | 压缩助手底层模型进入 compressionCall=true | 模型已包装但不调用 compress | append |

## 4. 异常场景

| 编号 | 场景 | 预期结果 | status |
|------|------|------|------|
| TC-101 | ext_param JSON 非法 | retry=null、compression 使用默认值；日志含 modelId 且不记录正文 | append |
| TC-102 | 压缩助手缺失 | Registry 校验失败，异常含 flow key/clientId/alias | append |
| TC-103 | 压缩结果未缩短 | CompressionExhaustedException，原 LLM 不调用 | append |
| TC-104 | 压缩模型返回 1261 | 包装领域异常并保留 cause | append |
| TC-105 | 多 Agent 配置冲突 | active flow 包含 clientId=3202 和 4202 | 装配失败，不写/覆盖稳定别名 | append |
| TC-106 | taskType 匹配缺失 | 唯一 clientId 没有 taskType=1 AiClientVO | 装配失败并包含 clientId/taskType | append |
| TC-107 | taskType 匹配重复 | 同一 clientId 有两个 taskType=1 关联 | 装配失败，不按列表顺序选择 | append |
| TC-108 | threshold 为 0/-1 | 显式非法值 | 拒绝装配 | append |
| TC-109 | attempts 越界 | 0、-1、4、Integer.MAX_VALUE | 全部拒绝装配，不截断 | append |
| TC-110 | summaryTokens 非正数 | 0、-1 | 拒绝装配 | append |
| TC-111 | 输入预算不足 | threshold <= maxSummaryTokens + 1024 | 拒绝装配 | append |
| TC-112 | 字段类型错误 | `maxSummaryTokens="abc"` | 拒绝装配，错误含字段路径 | append |

## 5. 边界场景

| 编号 | 场景 | 预期结果 | status |
|------|------|------|------|
| TC-201 | ext_param 为空 | retry=null，compression=160000/3/2000 | append |
| TC-202 | 复合配置缺 compression | 自动补默认值 | append |
| TC-203 | DB 无 prompt 7001 | 使用非空代码默认提示词 | append |
| TC-204 | retry 关闭 | 普通预算为 1，主动和 1261 压缩仍有效 | append |
| TC-205 | 原 command 不含压缩 clientId | 全局 flow 指向 3202 | 使用合并副本加载 3202，原 commandIdList 不变 | append |
| TC-206 | 合法最小输入预算 | threshold=maxSummaryTokens+1025 | 装配成功，预算为 1 | append |

## 6. 回归场景

| 编号 | 场景 | 预期结果 | status |
|------|------|------|------|
| TC-301 | 旧扁平 Retry JSON | RetryConfig 正确并获得默认 CompressionConfig | append |
| TC-302 | 普通 429 | 同一 Prompt 重试，不触发压缩 | append |
| TC-303 | compressionCall=true | 跳过递归压缩，保留普通瞬时重试 | append |
| TC-304 | Query 成功或异常结束 | holder.current() 最终为空 | append |

## 7. Query 闭环关键断言

- compress 接收 originalPrompt、当前 session/trace/history 和合法最小预算 policy（threshold=1026、summaryTokens=1）。
- 主动场景不调用 `delegate.call(originalPrompt)`。
- 主动场景只调用一次 `delegate.call(compressedPrompt)`。
- 1261 场景依次调用 originalPrompt、compressedPrompt。
- compressedPrompt Token 数严格小于 originalPrompt。
- 最终响应与 delegate 成功响应一致。
- holder 在成功和异常后均为空。

## 8. 用例映射

| 编号 | 测试方法 | 目标组件 |
|------|------|------|
| TC-001 | `should_parse_compression_parameters_without_switch_or_model_id()` | AgentRepository |
| TC-002 | `should_compress_before_delegate_call_when_query_exceeds_threshold()` | RetryChatModel |
| TC-003 | `should_call_delegate_again_with_compressed_prompt_after_1261()` | RetryChatModel |
| TC-004 | `should_register_canonical_alias_for_compression_assistant()` | AiClientNode |
| TC-006 | `should_resolve_compression_client_from_real_registry_first()` | DefaultPromptCompressionService |
| TC-007 | `should_fallback_to_application_context_when_registry_is_empty()` | DefaultPromptCompressionService |
| TC-102 | `should_fail_assembly_when_compression_assistant_is_missing()` | armory 装配 |
| TC-203 | `should_use_code_default_prompt_when_7001_is_missing()` | AiClientNode |
| TC-301 | `should_parse_legacy_retry_and_apply_default_compression()` | AgentRepository |
| TC-008 | `should_accept_multiple_agents_when_compression_client_id_is_identical()` | AiClientLoadDataStrategy |
| TC-105 | `should_reject_multiple_distinct_global_compression_client_ids()` | AiClientLoadDataStrategy |
| TC-106 | `should_reject_compression_client_without_task_type_one()` | AiClientNode |
| TC-107 | `should_reject_duplicate_task_type_one_compression_clients()` | AiClientNode |
| TC-205 | `should_load_global_compression_client_without_mutating_command_ids()` | AiClientLoadDataStrategy |
| TC-009 | `should_wrap_model_when_retry_config_is_null()` | AiClientModelNode |
| TC-010 | `should_wrap_model_when_retry_is_disabled()` | AiClientModelNode |
| TC-011 | `should_wrap_every_loaded_business_model()` | AiClientModelNode |
| TC-012 | `should_pass_each_models_validated_compression_policy_to_wrapper()` | AiClientModelNode |
| TC-013 | `should_wrap_compression_model_without_recursive_compression_call()` | RetryChatModel/AiClientModelNode |
| TC-108 | `should_reject_non_positive_proactive_threshold()` | CompressionConfigValidator |
| TC-109 | `should_reject_compression_attempts_outside_one_to_three()` | CompressionConfigValidator |
| TC-110 | `should_reject_non_positive_summary_tokens()` | CompressionConfigValidator |
| TC-111 | `should_reject_threshold_without_compression_input_budget()` | CompressionConfigValidator |
| TC-112 | `should_reject_wrong_compression_field_type()` | AgentRepository |
| TC-206 | `should_accept_minimum_positive_compression_input_budget()` | CompressionConfigValidator |

## 9. 执行与验收

| 步骤 | 预期 | status |
|------|------|------|
| 编写配置兼容测试 | 新测试先失败再通过 | pass |
| 编写统一助手测试 | 注册及缺失分支通过 | pass |
| 编写 Query 闭环测试 | 主动和 1261 场景通过 | pass |
| 运行专项测试 | 全部通过 | pass |
| 运行 domain clean 全量测试 | 0 失败 | pass |
| 运行全项目 clean compile | BUILD SUCCESS | pass |

| 验收项 | 标准 | status |
|------|------|------|
| 压缩强制开启 | 不存在 compression enabled | pass |
| 无重复模型配置 | 不存在 per-model compressionModelId | pass |
| 默认策略 | DB 缺失时得到 160000/3/2000 | pass |
| 非法配置 | 显式非法值和字段类型错误均拒绝装配 | pass |
| 统一助手 | 真实 Registry 正确注册，Registry 优先查找，缺失时失败 | pass |
| 全局唯一 | 所有 Agent 只能指向同一 compression clientId，冲突时拒绝装配 | pass |
| 强制包装 | null/disabled retry 和多模型均全部包装为 RetryChatModel | pass |
| Policy 隔离 | 每个模型使用自身验证后的三个 compression 参数 | pass |
| 主动闭环 | 合法最小预算参数下，超长 Query 压缩后调用原 LLM | pass |
| 被动闭环 | 1261 后压缩并第二次调用原 LLM | pass |
| 无回归 | 专项、全量测试和编译通过 | pass |

当前结论：需求实现及自动化验证已完成，达到提测条件。
