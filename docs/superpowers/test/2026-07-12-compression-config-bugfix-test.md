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
| TC-002 | threshold=1 的用户 Query | 先 compress，再以 compressedPrompt 调原 LLM 一次 | append |
| TC-003 | 原 LLM 首次返回 1261 | compress 一次，原 LLM 第二次成功 | append |
| TC-004 | flow 中存在压缩助手 | 同一 ChatClient 以普通名称和 compressionChatClient 写入真实 Registry | append |
| TC-005 | DB 有 prompt 7001 | 使用 DB 提示词且不重复 | append |
| TC-006 | Registry 主路径解析 | Registry 和 ApplicationContext 均可返回对象 | 使用 Registry 对象且不访问 ApplicationContext | append |
| TC-007 | Spring 静态 Bean 回退 | Registry 不含别名，ApplicationContext 含别名 | 正确返回 Spring ChatClient | append |

## 4. 异常场景

| 编号 | 场景 | 预期结果 | status |
|------|------|------|------|
| TC-101 | ext_param JSON 非法 | retry=null，compression=默认值，不记录正文 | append |
| TC-102 | 压缩助手缺失 | Registry 校验失败，异常含 flow key/clientId/alias | append |
| TC-103 | 压缩结果未缩短 | CompressionExhaustedException，原 LLM 不调用 | append |
| TC-104 | 压缩模型返回 1261 | 包装领域异常并保留 cause | append |

## 5. 边界场景

| 编号 | 场景 | 预期结果 | status |
|------|------|------|------|
| TC-201 | ext_param 为空 | retry=null，compression=160000/3/2000 | append |
| TC-202 | 复合配置缺 compression | 自动补默认值 | append |
| TC-203 | DB 无 prompt 7001 | 使用非空代码默认提示词 | append |
| TC-204 | retry 关闭 | 普通预算为 1，主动和 1261 压缩仍有效 | append |

## 6. 回归场景

| 编号 | 场景 | 预期结果 | status |
|------|------|------|------|
| TC-301 | 旧扁平 Retry JSON | RetryConfig 正确并获得默认 CompressionConfig | append |
| TC-302 | 普通 429 | 同一 Prompt 重试，不触发压缩 | append |
| TC-303 | compressionCall=true | 跳过递归压缩，保留普通瞬时重试 | append |
| TC-304 | Query 成功或异常结束 | holder.current() 最终为空 | append |

## 7. Query 闭环关键断言

- compress 接收 originalPrompt、当前 session/trace/history 和 threshold=1 的 policy。
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

## 9. 执行与验收

| 步骤 | 预期 | status |
|------|------|------|
| 编写配置兼容测试 | 新测试先失败再通过 | append |
| 编写统一助手测试 | 注册及缺失分支通过 | append |
| 编写 Query 闭环测试 | 主动和 1261 场景通过 | append |
| 运行专项测试 | 全部通过 | append |
| 运行 domain clean 全量测试 | 0 失败 | append |
| 运行全项目 clean compile | BUILD SUCCESS | append |

| 验收项 | 标准 | status |
|------|------|------|
| 压缩强制开启 | 不存在 compression enabled | append |
| 无重复模型配置 | 不存在 per-model compressionModelId | append |
| 默认策略 | DB 缺失时得到 160000/3/2000 | append |
| 统一助手 | 真实 Registry 正确注册，Registry 优先查找，缺失时失败 | append |
| 主动闭环 | threshold=1 时压缩后调用原 LLM | append |
| 被动闭环 | 1261 后压缩并第二次调用原 LLM | append |
| 无回归 | 专项、全量测试和编译通过 | append |

当前结论：修订规格待评审，尚未进入实现。
