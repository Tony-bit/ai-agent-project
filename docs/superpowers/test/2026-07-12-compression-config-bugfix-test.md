# Test: Compression Configuration Bugfix

## 1. 测试背景

### 1.1 对应设计

- 设计文档：`docs/superpowers/plans/2026-07-12-compression-config-bugfix-design.md`

### 1.2 测试目标

- 验证 DB 扩展配置能正确装配 retry 与 compression。
- 验证默认压缩提示词与 DB 覆盖规则。
- 验证用户 Query 主动超限和 1261 被动超限的压缩重试闭环。

### 1.3 测试范围

- `AgentRepository` 模型扩展配置解析。
- `AiClientModelNode` 压缩策略构造。
- `DefaultPromptCompressionService` 提示词选择。
- `RetryChatModel` Query 压缩闭环。

### 1.4 不在本次测试范围

- 真实数据库连接。
- 真实 LLM、HTTP、SSE 和网络重试。
- 压缩摘要的自然语言质量评测。

---

## 2. 测试策略

### 2.1 测试分层

| 测试层级 | 是否覆盖 | 说明 |
|------|------|------|
| 单元测试 | 是 | JSON 兼容解析、默认提示词、异常与边界 |
| 组件集成测试 | 是 | Query 到压缩后原 LLM 调用闭环 |
| 接口测试 | 否 | 本次不新增接口 |
| 回归测试 | 是 | 普通 retry、compression disabled、旧 JSON |
| 手工验证 | 否 | 自动化测试可完整覆盖 |

### 2.2 Mock 策略

| 依赖项 | 是否 Mock | Mock 方式 | 说明 |
|------|------|------|------|
| `AiClientModelDao` | 是 | Mockito | 返回指定 ext_param，不连接数据库 |
| 压缩 LLM | 是 | `PromptCompressionService` 或 ChatClient Mock | 捕获入参并返回固定 compressedPrompt/summary |
| 原 LLM | 是 | `ChatModel` Mock | 模拟成功、1261 和调用次数 |
| RetryChatModel | 否 | 真实组件 | 验证真实状态机和 Prompt 替换 |
| RetryRuntimeContextHolder | 否 | 真实组件 | 验证上下文绑定与清理 |

---

## 3. 测试场景设计

### 3.1 正常场景

| 编号 | 场景名称 | 前置条件 | 输入 | 预期结果 | status |
|------|------|------|------|------|------|
| TC-001 | 解析复合配置 | ext_param 合法 | 同时包含 retryConfig/compressionConfig | 两个 VO 字段均正确赋值 | append |
| TC-002 | Query 主动压缩闭环 | threshold=1，历史非空 | 超过 1 token 的用户 Query | 先 compress，再以 compressedPrompt 调原 LLM 一次 | append |
| TC-003 | 1261 被动压缩闭环 | compression enabled | 原 LLM 首次返回 1261 | compress 一次，原 LLM 第二次成功 | append |
| TC-004 | DB 提示词覆盖默认值 | prompt 7001 非空 | 自定义模板 | fallback ChatClient 使用 DB 模板 | append |

### 3.2 异常场景

| 编号 | 场景名称 | 前置条件 | 输入 | 预期结果 | status |
|------|------|------|------|------|------|
| TC-101 | 非法 ext_param | JSON 语法错误 | 非法字符串 | 两个配置为 null，记录告警，不阻断装配 | append |
| TC-102 | 压缩结果未缩短 | 主动超限 | compressedPrompt 大于等于原 Prompt | 抛 CompressionExhaustedException，原 LLM 不调用 | append |
| TC-103 | 压缩模型 1261 | 压缩已触发 | compress 抛 1261 | 包装领域异常并保留 cause | append |
| TC-104 | 无压缩模型 | 完整 Bean 和 modelId Bean 均不存在 | 合法历史 | IllegalStateException 包含 beanName/modelId | append |

### 3.3 边界场景

| 编号 | 场景名称 | 前置条件 | 输入 | 预期结果 | status |
|------|------|------|------|------|------|
| TC-201 | ext_param 为空 | 模型启用 | null/空字符串 | retryConfig/compressionConfig 均为 null | append |
| TC-202 | 仅 compression 配置 | retry 缺失 | compression enabled | 创建 RetryChatModel，普通预算为 1 | append |
| TC-203 | 默认提示词 fallback | DB 无 7001 | compressionModelId 有效 | 使用代码默认模板，非空且包含摘要协议 | append |
| TC-204 | threshold 恰好低于 Query | threshold=1 | 普通用户 Query | 主动压缩稳定触发 | append |

### 3.4 回归场景

| 编号 | 场景名称 | 前置条件 | 输入 | 预期结果 | status |
|------|------|------|------|------|------|
| TC-301 | 旧扁平 Retry JSON | 无复合键 | 旧 ext_param | RetryConfig 正确、CompressionConfig 为 null | append |
| TC-302 | compression disabled | retry enabled | 429 后成功 | 同一 Prompt 普通重试，不调用 compress | append |
| TC-303 | 完整压缩 ChatClient 优先 | 完整 Bean 存在 | compressionModelId 同时存在 | 不解析 modelId，不重复 system prompt | append |
| TC-304 | holder 清理 | Query 闭环结束或异常 | session/trace/history | current() 最终为 null | append |

---

## 4. 用例与代码映射

| 测试编号 | 对应用例方法 | 目标类/方法 | 覆盖类型 |
|------|------|------|------|
| TC-001 | `should_parse_retry_and_compression_when_ext_param_is_composite()` | `AgentRepository` 配置解析 | 正常 |
| TC-002 | `should_compress_before_delegate_call_when_query_exceeds_proactive_threshold()` | `RetryChatModel#call` | 正常 |
| TC-003 | `should_call_delegate_again_with_compressed_prompt_when_first_call_returns_1261()` | `RetryChatModel#call` | 正常 |
| TC-101 | `should_disable_decorators_when_ext_param_is_invalid()` | `AgentRepository` 配置解析 | 异常 |
| TC-203 | `should_use_code_default_prompt_when_database_prompt_is_missing()` | `AiClientModelNode`/压缩服务 | 边界 |
| TC-301 | `should_parse_legacy_flat_retry_config()` | `AgentRepository` 配置解析 | 回归 |

---

## 5. Query 闭环关键断言

- `PromptCompressionService.compress` 接收 originalPrompt、当前 RetryRuntimeContext 和 threshold=1 的策略。
- 主动压缩时 `delegate.call(originalPrompt)` 从未发生。
- 主动压缩时 `delegate.call(compressedPrompt)` 恰好一次。
- 被动 1261 时 delegate 依次接收 originalPrompt、compressedPrompt。
- compressedPrompt Token 数严格小于 originalPrompt。
- 最终 ChatResponse 与 delegate 成功响应为同一对象。
- 测试结束后 `RetryRuntimeContextHolder.current()` 为 null。

---

## 6. 执行计划

| 步骤 | 内容 | 预期结果 | status |
|------|------|------|------|
| 1 | 编写 repository 配置解析测试 | 新测试先因 compressionConfig 未加载而失败 | append |
| 2 | 编写默认提示词测试 | 新测试先因模板为空而失败 | append |
| 3 | 编写 Query 主动/被动压缩闭环测试 | 捕获压缩入参和两阶段模型调用 | append |
| 4 | 执行专项测试 | 全部通过 | append |
| 5 | 执行 domain clean 全量测试 | 无失败 | append |
| 6 | 执行全项目 clean compile | BUILD SUCCESS | append |

专项命令：

```bash
mvn -pl ai-agent-study-domain,ai-agent-study-infrastructure -am \
  -Dtest=AgentRepositoryCompressionConfigTest,CompressionQueryFlowIntegrationTest,DefaultPromptCompressionServiceTest,AiClientModelNodeRetryTest \
  -Dsurefire.failIfNoSpecifiedTests=false test
```

---

## 7. 验收标准

| 编号 | 验收项 | 标准 | status |
|------|------|------|------|
| AC-001 | 配置可用 | 复合配置同时装配 retry 和 compression | append |
| AC-002 | 向后兼容 | 旧扁平 Retry JSON 测试通过 | append |
| AC-003 | 提示词可用 | DB 缺失时使用非空代码默认模板 | append |
| AC-004 | 主动闭环 | threshold=1 时压缩后调用原 LLM | append |
| AC-005 | 被动闭环 | 1261 后压缩并第二次调用原 LLM | append |
| AC-006 | 无回归 | 专项、domain 全量和全项目编译通过 | append |

---

## 8. 风险与说明

| 风险点 | 影响 | 应对措施 |
|------|------|------|
| 旧版本不识别复合 JSON | 回滚后 retry 默认关闭 | 回滚前恢复旧扁平配置 |
| 测试误用真实 LLM | 测试不稳定、产生费用 | 所有模型依赖统一 Mock |
| 主动阈值测试依赖 Token 估算细节 | 用例偶发不触发 | threshold 固定为 1，Query 使用明显非空长文本 |
| 完整 Bean 与 fallback 路径混淆 | system prompt 重复 | 分别捕获请求并断言解析次数和 system message |

## 9. 执行结果记录

| 项目 | 结果 |
|------|------|
| 单元测试 | append |
| 组件集成测试 | append |
| domain 全量测试 | append |
| 编译验证 | append |

当前结论：设计待评审，尚未达到实现或提测条件。
