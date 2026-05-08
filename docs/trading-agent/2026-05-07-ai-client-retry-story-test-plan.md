# Story Test Plan: AI Client LLM 调用重试能力

| 字段 | 内容 |
|------|------|
| Story 名称 | AI Client LLM 调用重试能力 |
| 关联需求文档 | `docs/trading-agent/2026-05-07-ai-client-retry-story.md` |
| 测试范围 | `RetryChatModel` 装饰器、`AiClientModelNode` 重试装饰器应用 |
| 测试策略 | Mock `ChatModel` 模拟第三方接口响应，比对预期 request/response |
| 创建日期 | 2026-05-07 |
| 测试负责人 | - |
| Status | draft |

---

## 1. 测试策略概述

### 1.1 测试分层

| 层级 | 测试目标 | 测试类型 | Mock 范围 |
|------|----------|----------|-----------|
| 单元测试 | `RetryChatModel` 装饰逻辑（call 方法） | JUnit 5 + Mockito | Mock `ChatModel.delegate` |
| 单元测试 | `extractErrorCode` / `isRetryable` | JUnit 5 | 无需 Mock，纯方法测试 |
| 集成测试 | `AiClientModelNode` 装饰器应用 | Spring Test + Mockito | Mock `AgentRepository` 数据加载 |
| 端到端测试 | 正常路径对话 | SpringBootTest | Mock `ChatModel` 真实行为 |

### 1.2 Mock 策略

- **Mock 对象**：`ChatModel`（`delegate`）
- **Mock 方式**：使用 Mockito `when(...).thenThrow(...)` / `thenAnswer(...)` 控制异常抛出时机和次数
- **验证重点**：
  - 调用次数（重试次数 = maxAttempts - 1）
  - 调用间隔（指数退避）
  - 最终抛出的异常类型

---

## 2. 测试用例清单

### 2.1 RetryChatModel 装饰器 - 正常路径

#### TC-Retry-001: 首次调用成功，无重试
status: pending

| 字段 | 内容 |
|------|------|
| 用例编号 | TC-Retry-001 |
| 用例名称 | 首次调用成功，无重试 |
| 前置条件 | RetryConfig enabled=true, maxAttempts=3 |
| 测试步骤 | 1. 构建 RetryChatModel，delegate.call() 首次返回正常响应 |
| 预期结果 | delegate.call() 仅被调用 1 次，无重试日志，正常返回 ChatResponse |

#### TC-Retry-002: 首次调用成功，无重试
status: pending

| 字段 | 内容 |
|------|------|
| 用例编号 | TC-Retry-002 |
| 用例名称 | 首次调用成功，无重试 |
| 前置条件 | RetryConfig enabled=true, maxAttempts=3 |
| 测试步骤 | 1. 构建 RetryChatModel，delegate.call() 首次返回正常响应 |
| 预期结果 | delegate.call() 仅被调用 1 次，无重试日志，正常返回 ChatResponse |

---

### 2.2 RetryChatModel 装饰器 - 黑名单异常（不重试）

#### TC-Retry-011: 异常命中黑名单 errorCode，直接抛出不重试
status: pending

| 字段 | 内容 |
|------|------|
| 用例编号 | TC-Retry-011 |
| 用例名称 | 异常命中黑名单 errorCode，直接抛出不重试 |
| 前置条件 | RetryConfig enabled=true, maxAttempts=3, nonRetryableErrorCodes=["401"] |
| 测试步骤 | 1. Mock delegate.call() 抛出包含 `"error":{"code":"401"}` 的异常<br>2. 调用 RetryChatModel.call() |
| Mock Request | `AiApiException` with message `{"error":{"code":"401","message":"认证失败"}}` |
| 预期结果 | delegate.call() 仅被调用 1 次，抛出原异常，无重试日志 |

#### TC-Retry-012: 多个黑名单 code 均不重试（覆盖 403/1211/1301）
status: pending

| 字段 | 内容 |
|------|------|
| 用例编号 | TC-Retry-012 |
| 用例名称 | 多个黑名单 code 均不重试 |
| 前置条件 | RetryConfig enabled=true, maxAttempts=3, nonRetryableErrorCodes=["403","1211","1301"] |
| 测试步骤 | 1. Mock delegate.call() 抛出 `"error":{"code":"403"}` 的异常<br>2. 调用 RetryChatModel.call() |
| Mock Request | `AiApiException` with message `{"error":{"code":"403","message":"权限不足"}}` |
| 预期结果 | delegate.call() 仅被调用 1 次，抛出原异常 |

#### TC-Retry-013: HTTP 401/403 状态码命中黑名单
status: pending

| 字段 | 内容 |
|------|------|
| 用例编号 | TC-Retry-013 |
| 用例名称 | HTTP 401/403 状态码命中黑名单 |
| 前置条件 | RetryConfig enabled=true, maxAttempts=3, nonRetryableErrorCodes=["401","403"] |
| 测试步骤 | 1. Mock delegate.call() 抛出异常消息含 `\b401\b` HTTP 状态码<br>2. 调用 RetryChatModel.call() |
| Mock Request | `Exception` with message `HTTP 401 Unauthorized` |
| 预期结果 | delegate.call() 仅被调用 1 次，extractErrorCode 提取到 "401" 后命中黑名单 |

---

### 2.3 RetryChatModel 装饰器 - 白名单异常（强制重试）

#### TC-Retry-021: 异常命中白名单 errorCode，触发重试后成功
status: pending

| 字段 | 内容 |
|------|------|
| 用例编号 | TC-Retry-021 |
| 用例名称 | 异常命中白名单 errorCode，触发重试后成功 |
| 前置条件 | RetryConfig enabled=true, maxAttempts=3, initialIntervalMs=1000, multiplier=2.0, retryableErrorCodes=["500"] |
| 测试步骤 | 1. Mock delegate.call()：<br>  - 第 1 次抛出 `"error":{"code":"500"}` 异常<br>  - 第 2 次返回正常响应<br>2. 调用 RetryChatModel.call() |
| Mock Request (Call 1) | `AiApiException` with message `{"error":{"code":"500","message":"内部错误"}}` |
| Mock Request (Call 2) | 正常 ChatResponse |
| 预期结果 | delegate.call() 被调用 2 次，间隔约 1000ms，返回第 2 次的正常响应 |

#### TC-Retry-022: 异常命中白名单 maxAttempts 次后成功
status: pending

| 字段 | 内容 |
|------|------|
| 用例编号 | TC-Retry-022 |
| 用例名称 | 异常命中白名单 maxAttempts 次后成功 |
| 前置条件 | RetryConfig enabled=true, maxAttempts=3, initialIntervalMs=500, multiplier=2.0, retryableErrorCodes=["503"] |
| 测试步骤 | 1. Mock delegate.call()：<br>  - 第 1-2 次抛出 `"error":{"code":"503"}` 异常<br>  - 第 3 次返回正常响应<br>2. 调用 RetryChatModel.call() |
| Mock Request | 第 1-2 次：`{"error":{"code":"503","message":"服务不可用"}}`，第 3 次：正常响应 |
| 预期结果 | delegate.call() 被调用 3 次，重试间隔约 500ms → 1000ms，返回第 3 次响应 |

#### TC-Retry-023: 异常命中白名单，达到最大重试次数后抛出
status: pending

| 字段 | 内容 |
|------|------|
| 用例编号 | TC-Retry-023 |
| 用例名称 | 异常命中白名单，达到最大重试次数后抛出 |
| 前置条件 | RetryConfig enabled=true, maxAttempts=3, retryableErrorCodes=["500"] |
| 测试步骤 | 1. Mock delegate.call() 始终抛出 `"error":{"code":"500"}` 异常<br>2. 调用 RetryChatModel.call() |
| Mock Request | 每次均为 `{"error":{"code":"500","message":"内部错误"}}` |
| 预期结果 | delegate.call() 被调用 3 次，抛出最后一次异常，记录 "达到最大重试次数" 日志 |

---

### 2.4 RetryChatModel 装饰器 - 默认规则（isRetryable 兜底）

#### TC-Retry-031: TransientAiException 触发重试
status: pending

| 字段 | 内容 |
|------|------|
| 用例编号 | TC-Retry-031 |
| 用例名称 | TransientAiException 触发重试 |
| 前置条件 | RetryConfig enabled=true, maxAttempts=3 |
| 测试步骤 | 1. Mock delegate.call() 抛出 `TransientAiException`<br>2. 第 2 次返回正常响应<br>3. 调用 RetryChatModel.call() |
| Mock Request | 第 1 次：`TransientAiException("AI service temporarily unavailable")`，第 2 次：正常响应 |
| 预期结果 | delegate.call() 被调用 2 次，isRetryable() 返回 true |

#### TC-Retry-032: SocketTimeoutException 触发重试
status: pending

| 字段 | 内容 |
|------|------|
| 用例编号 | TC-Retry-032 |
| 用例名称 | SocketTimeoutException 触发重试 |
| 前置条件 | RetryConfig enabled=true, maxAttempts=3 |
| 测试步骤 | 1. Mock delegate.call() 抛出 `SocketTimeoutException`<br>2. 第 2 次返回正常响应 |
| Mock Request | 第 1 次：`SocketTimeoutException("Read timed out")`，第 2 次：正常响应 |
| 预期结果 | delegate.call() 被调用 2 次，isRetryable() 识别超时异常返回 true |

#### TC-Retry-033: ResourceAccessException 触发重试
status: pending

| 字段 | 内容 |
|------|------|
| 用例编号 | TC-Retry-033 |
| 用例名称 | ResourceAccessException 触发重试 |
| 前置条件 | RetryConfig enabled=true, maxAttempts=3 |
| 测试步骤 | 1. Mock delegate.call() 抛出 `ResourceAccessException`<br>2. 第 2 次返回正常响应 |
| Mock Request | 第 1 次：`ResourceAccessException(cause=ConnectException)`，第 2 次：正常响应 |
| 预期结果 | delegate.call() 被调用 2 次，isRetryable() 识别连接异常返回 true |

#### TC-Retry-034: 异常消息含 ECONNRESET 关键词触发重试
status: pending

| 字段 | 内容 |
|------|------|
| 用例编号 | TC-Retry-034 |
| 用例名称 | 异常消息含 ECONNRESET 关键词触发重试 |
| 前置条件 | RetryConfig enabled=true, maxAttempts=3 |
| 测试步骤 | 1. Mock delegate.call() 抛出普通 Exception，消息含 "Connection reset"<br>2. 第 2 次返回正常响应 |
| Mock Request | 第 1 次：`Exception("java.net.SocketException: Connection reset by peer")`，第 2 次：正常响应 |
| 预期结果 | delegate.call() 被调用 2 次，isRetryable() 识别 "connection reset" 返回 true |

#### TC-Retry-035: 未知异常（不可重试）直接抛出
status: pending

| 字段 | 内容 |
|------|------|
| 用例编号 | TC-Retry-035 |
| 用例名称 | 未知异常（不可重试）直接抛出 |
| 前置条件 | RetryConfig enabled=true, maxAttempts=3 |
| 测试步骤 | 1. Mock delegate.call() 抛出普通 `IllegalArgumentException`<br>2. 调用 RetryChatModel.call() |
| Mock Request | `IllegalArgumentException("Invalid parameter: model not found")` |
| 预期结果 | delegate.call() 仅被调用 1 次，抛出原异常，无重试日志 |

#### TC-Retry-046: 无法提取 errorCode 时走 isRetryable 兜底
status: pending

| 字段 | 内容 |
|------|------|
| 用例编号 | TC-Retry-046 |
| 用例名称 | 无法提取 errorCode 时走 isRetryable 兜底 |
| 前置条件 | RetryConfig enabled=true, maxAttempts=3，无白/黑名单配置 |
| 测试步骤 | 1. Mock delegate.call() 抛出异常，消息为 `"some random error with no code"`<br>2. 调用 RetryChatModel.call() |
| Mock Request | `Exception("some random error with no code")` |
| 预期结果 | extractErrorCode 返回 "unknown"，不在白/黑名单，走 isRetryable → 返回 false，直接抛出原异常 |

#### TC-Retry-047: 异常消息为 null 时不抛 NPE
status: pending

| 字段 | 内容 |
|------|------|
| 用例编号 | TC-Retry-047 |
| 用例名称 | 异常消息为 null 时不抛 NPE |
| 前置条件 | RetryConfig enabled=true, maxAttempts=3 |
| 测试步骤 | 1. Mock delegate.call() 抛出消息为 null 的异常<br>2. 调用 RetryChatModel.call() |
| Mock Request | `Exception((String)null)` |
| 预期结果 | extractErrorCode 正常返回 "unknown"（NPE 已防护），不抛异常 |

---

### 2.5 extractErrorCode 方法测试

#### TC-Retry-041: 提取智谱格式 errorCode
status: pending

| 字段 | 内容 |
|------|------|
| 用例编号 | TC-Retry-041 |
| 用例名称 | 提取智谱格式 errorCode |
| 测试输入 | `{"error":{"code":"1002","message":"Authentication Token 非法"}}` |
| 预期输出 | `"1002"` |

#### TC-Retry-042: 提取 OpenAI 格式 errorCode
status: pending

| 字段 | 内容 |
|------|------|
| 用例编号 | TC-Retry-042 |
| 用例名称 | 提取 OpenAI 格式 errorCode |
| 测试输入 | `{"error":{"code":"rate_limit_exceeded","message":"Rate limit exceeded"}}` |
| 预期输出 | `"rate_limit_exceeded"` |

#### TC-Retry-043: 从类名推断 errorCode
status: pending

| 字段 | 内容 |
|------|------|
| 用例编号 | TC-Retry-043 |
| 用例名称 | 从类名推断 errorCode |
| 测试输入 | 异常类名 `RateLimitExceededException`，无特殊消息 |
| 预期输出 | `"429"` |

#### TC-Retry-044: 从 HTTP 状态码提取
status: pending

| 字段 | 内容 |
|------|------|
| 用例编号 | TC-Retry-044 |
| 用例名称 | 从 HTTP 状态码提取 |
| 测试输入 | 消息 `"Received HTTP 503 Service Unavailable response"` |
| 预期输出 | `"503"` |

#### TC-Retry-045: errorCode 统一小写化
status: pending

| 字段 | 内容 |
|------|------|
| 用例编号 | TC-Retry-045 |
| 用例名称 | errorCode 统一小写化 |
| 测试输入 | `{"error":{"code":"RATE_LIMIT_EXCEEDED","message":"..."}}` |
| 预期输出 | `"rate_limit_exceeded"`（白/黑名单 contains 比较一致性） |

---

### 2.6 指数退避时间验证

#### TC-Retry-051: 指数退避间隔验证
status: pending

| 字段 | 内容 |
|------|------|
| 用例编号 | TC-Retry-051 |
| 用例名称 | 指数退避间隔验证 |
| 前置条件 | RetryConfig enabled=true, maxAttempts=4, initialIntervalMs=1000, multiplier=2.0, maxIntervalMs=10000 |
| 测试步骤 | 1. Mock delegate.call() 始终抛出异常<br>2. 记录每次调用的时间戳<br>3. 调用 RetryChatModel.call() 等待重试全部失败 |
| 预期结果 | 重试间隔符合：1000ms → 2000ms → 4000ms（每次 ±100ms 误差容忍） |

#### TC-Retry-052: maxIntervalMs 封顶验证
status: pending

| 字段 | 内容 |
|------|------|
| 用例编号 | TC-Retry-052 |
| 用例名称 | maxIntervalMs 封顶验证 |
| 前置条件 | RetryConfig enabled=true, maxAttempts=5, initialIntervalMs=1000, multiplier=2.0, maxIntervalMs=5000 |
| 测试步骤 | 1. Mock delegate.call() 始终抛出异常<br>2. 记录每次重试间隔 |
| 预期结果 | 第 3 次起间隔封顶为 5000ms：1000ms → 2000ms → 4000ms → 5000ms → 5000ms |

#### TC-Retry-053: maxAttempts=1 边界条件，首次失败抛出 IllegalStateException
status: pending

| 字段 | 内容 |
|------|------|
| 用例编号 | TC-Retry-053 |
| 用例名称 | maxAttempts=1 边界条件测试 |
| 前置条件 | RetryConfig enabled=true, maxAttempts=1, retryableErrorCodes=["500"] |
| 测试步骤 | 1. Mock delegate.call() 抛出 `"error":{"code":"500"}` 异常<br>2. 调用 RetryChatModel.call() |
| Mock Request | `AiApiException` with message `{"error":{"code":"500","message":"内部错误"}}` |
| 预期结果 | delegate.call() 被调用 1 次，因达到 maxAttempts 且无可用重试次数，抛出 IllegalStateException("exhausted all attempts") 而非原 AiApiException |

---

### 2.7 配置禁用场景

#### TC-Retry-061: RetryConfig 为 null，不应用装饰器
status: pending

| 字段 | 内容 |
|------|------|
| 用例编号 | TC-Retry-061 |
| 用例名称 | RetryConfig 为 null，不应用装饰器 |
| 前置条件 | AiClientModelVO.retryConfig = null |
| 测试步骤 | 1. 调用 AiClientModelNode，构建模型节点<br>2. 检查 registerBean 注册的 ChatModel 类型 |
| 预期结果 | 返回原始 OpenAiChatModel，未被 RetryChatModel 包装 |

#### TC-Retry-062: RetryConfig.enabled=false，不应用装饰器
status: pending

| 字段 | 内容 |
|------|------|
| 用例编号 | TC-Retry-062 |
| 用例名称 | RetryConfig.enabled=false，不应用装饰器 |
| 前置条件 | RetryConfig enabled=false |
| 测试步骤 | 1. 调用 AiClientModelNode.applyRetryDecorator() |
| 预期结果 | 返回原始 OpenAiChatModel，不创建 RetryChatModel |

---

### 2.8 日志验证

#### TC-Retry-071: 验证重试日志输出
status: pending

| 字段 | 内容 |
|------|------|
| 用例编号 | TC-Retry-071 |
| 用例名称 | 验证重试日志输出 |
| 前置条件 | RetryConfig enabled=true, maxAttempts=3 |
| 测试步骤 | 1. Mock delegate.call() 前 2 次抛异常，第 3 次成功<br>2. 调用 RetryChatModel.call() 并捕获日志 |
| 预期日志 | 包含 "【重试】attempt 1/3 失败" → "【重试】attempt 2/3 失败" → 成功无日志 |

#### TC-Retry-072: 验证黑名单日志输出
status: pending

| 字段 | 内容 |
|------|------|
| 用例编号 | TC-Retry-072 |
| 用例名称 | 验证黑名单日志输出 |
| 前置条件 | RetryConfig enabled=true, nonRetryableErrorCodes=["401"] |
| 测试步骤 | 1. Mock delegate.call() 抛出 401 异常<br>2. 调用 RetryChatModel.call() 并捕获日志 |
| 预期日志 | 包含 "【重试】黑名单匹配不重试，errorCode=401" |

---

## 3. 测试文件清单

| 测试类 | 测试范围 | 文件路径 |
|--------|----------|----------|
| `RetryChatModelTest` | RetryChatModel 装饰器单元测试 | `ai-agent-study-domain/src/test/java/.../armory/RetryChatModelTest.java` |
| `ExtractErrorCodeTest` | extractErrorCode 方法测试 | `ai-agent-study-domain/src/test/java/.../armory/ExtractErrorCodeTest.java` |
| `IsRetryableTest` | isRetryable 方法测试 | `ai-agent-study-domain/src/test/java/.../armory/IsRetryableTest.java` |
| `AiClientModelNodeRetryTest` | AiClientModelNode 重试集成测试 | `ai-agent-study-domain/src/test/java/.../armory/AiClientModelNodeRetryTest.java` |

---

## 4. 测试执行计划

| 阶段 | 测试内容 | 执行时机 | 预期耗时 |
|------|----------|----------|----------|
| 单元测试 | TC-Retry-001 ~ TC-Retry-035, TC-Retry-046 ~ TC-Retry-047 | 开发完成后、编译前 | ~5 min |
| 方法测试 | TC-Retry-041 ~ TC-Retry-045 | 随单元测试一起执行 | ~2 min |
| 退避验证 | TC-Retry-051 ~ TC-Retry-053 | 单元测试完成后 | ~1 min |
| 配置测试 | TC-Retry-061 ~ TC-Retry-062 | 集成测试阶段 | ~2 min |
| 日志验证 | TC-Retry-071 ~ TC-Retry-072 | 集成测试阶段 | ~1 min |

---

## 5. 验收标准

| 验收项 | 标准 |
|--------|------|
| 所有测试用例通过 | 33 个测试用例 100% Pass |
| 编译无错误 | `mvn compile` 成功 |
| 单元测试覆盖率 | `RetryChatModel` 方法覆盖率 ≥ 90% |
| 重试次数正确 | delegate.call() 调用次数 = maxAttempts（失败时） |
| 退避间隔正确 | 间隔符合 initialInterval * multiplier^n |
| 日志输出正确 | 包含重试各阶段关键日志 |
| 配置禁用正确 | enabled=false 时不创建装饰器 |

---

## 6. 测试环境要求

| 环境 | 要求 |
|------|------|
| JDK | 17+ |
| Maven | 3.8+ |
| Spring Boot | 3.2.x |
| Spring AI | 1.0.0.M4+ |
| JUnit | 5.x |
| Mockito | 5.x |

---

## 7. 风险与注意事项

| 风险项 | 说明 | 缓解措施 |
|--------|------|----------|
| 时间敏感测试 | 指数退避测试依赖实际等待时间 | 使用 `Mockito.doAnswer` 记录时间戳，不依赖真实 sleep |
| 并发测试 | 多线程同时重试可能竞争 | 测试用例间隔离，不共享状态 |

---

*Document Status: Draft - Pending Review*
