# 统一意图路由 (Intent Routing) 测试用例文档

> **创建时间:** 2026-05-10
> **所属需求:** [2026-05-10-unified-intent-routing.md](../2026-05-10-unified-intent-routing.md)
> **测试范围:** 新增代码链路（query输入 → 路由到指定Agent入口）

---

## 1. 测试概述

### 1.1 测试目标

验证意图路由功能的正确性，确保用户请求能够根据 `aiAgentId` 和 LLM 意图识别结果，正确路由到对应的 Agent 节点。

### 1.2 测试链路

```
用户请求 → RootNode
              ├─ aiAgentId="5" → IntelligentInspection
              ├─ aiAgentId 非空 → Step1AnalyzerNode
              └─ aiAgentId 为空 → IntentRoutingNode → (识别后)
                                   ├─ STOCK_ANALYSIS → TradingNode
                                   ├─ PE_* → Step1AnalyzerNode
                                   ├─ INSPECTION → IntelligentInspection
                                   └─ GENERAL_CHAT/AMBIGUOUS/UNKNOWN → GeneralChatNode
```

### 1.3 Mock 策略

- **LLM 调用**: 使用 Mockito Mock `ChatModel`，注入预设的 JSON 响应
- **中间件依赖**: Mock `Repository`、`ArmoryObjectRegistry`、`ObservabilityService` 等基础设施
- **节点依赖**: Mock 被测节点引用的其他 Service 节点（如 `Step1AnalyzerNode`、`IntelligentInspection`）

---

## 2. 测试类清单

| 测试类 | 文件路径 | 测试用例数 | 负责人 |
|--------|----------|----------|--------|
| `IntentTypeEnumTest` | `ai-agent-study-domain/src/test/java/denny/ai/agent/domain/service/auto/step/routing/IntentTypeEnumTest.java` | 8 | - |
| `IntentRoutingServiceTest` | `ai-agent-study-domain/src/test/java/denny/ai/agent/domain/service/auto/step/routing/IntentRoutingServiceTest.java` | 10 | - |
| `IntentRoutingNodeTest` | `ai-agent-study-domain/src/test/java/denny/ai/agent/domain/service/auto/step/routing/IntentRoutingNodeTest.java` | 8 | - |
| `RootNodeTest` | `ai-agent-study-domain/src/test/java/denny/ai/agent/domain/service/auto/step/RootNodeTest.java` | 6 | - |
| `GeneralChatNodeTest` | `ai-agent-study-domain/src/test/java/denny/ai/agent/domain/service/auto/step/chat/GeneralChatNodeTest.java` | 3 | - |

---

## 3. 测试用例详情

### 3.1 IntentTypeEnumTest

> **被测类:** `denny.ai.agent.domain.model.valobj.enums.IntentTypeEnum`
> **测试目标:** 验证 6 种意图类型枚举的定义正确性

| 用例ID | 用例名称 | 测试输入 | 预期结果 | 测试方法 |
|--------|----------|----------|----------|----------|
| TC-Enum-001 | 股票分析枚举_校验code | `IntentTypeEnum.STOCK_ANALYSIS.getCode()` | `"STOCK_ANALYSIS"` | `testStockAnalysisCode()` |
| TC-Enum-002 | PE推理枚举_校验code | `IntentTypeEnum.PE_REASONING.getCode()` | `"PE_REASONING"` | `testPEReasoningCode()` |
| TC-Enum-003 | PE计算枚举_校验code | `IntentTypeEnum.PE_CALCULATION.getCode()` | `"PE_CALCULATION"` | `testPECalculationCode()` |
| TC-Enum-004 | PE检索枚举_校验code | `IntentTypeEnum.PE_RETRIEVAL.getCode()` | `"PE_RETRIEVAL"` | `testPERetrievalCode()` |
| TC-Enum-005 | 巡检枚举_校验code | `IntentTypeEnum.INSPECTION.getCode()` | `"INSPECTION"` | `testInspectionCode()` |
| TC-Enum-006 | 通用对话枚举_校验code | `IntentTypeEnum.GENERAL_CHAT.getCode()` | `"GENERAL_CHAT"` | `testGeneralChatCode()` |
| TC-Enum-007 | 模糊意图枚举_校验code | `IntentTypeEnum.AMBIGUOUS.getCode()` | `"AMBIGUOUS"` | `testAmbiguousCode()` |
| TC-Enum-008 | 未知意图枚举_校验code | `IntentTypeEnum.UNKNOWN.getCode()` | `"UNKNOWN"` | `testUnknownCode()` |

---

### 3.2 IntentRoutingServiceTest

> **被测类:** `denny.ai.agent.domain.service.auto.step.routing.IntentRoutingService`
> **测试目标:** 验证 LLM 响应解析、意图识别、置信度判断、历史消息注入
> **Mock 对象:** `ChatModel`

#### 3.2.1 LLM 响应解析测试

| 用例ID | 用例名称 | Mock LLM返回 | 预期意图 | 预期置信度 | 预期推理 |
|--------|----------|-------------|----------|-----------|----------|
| TC-Service-001 | 股票分析_高置信度 | `{"intent":"STOCK_ANALYSIS","confidence":"HIGH","reasoning":"用户询问股票走势"}` | STOCK_ANALYSIS | HIGH | 用户询问股票走势 |
| TC-Service-002 | PE推理_中置信度 | `{"intent":"PE_REASONING","confidence":"MEDIUM","reasoning":"逻辑推理任务"}` | PE_REASONING | MEDIUM | 逻辑推理任务 |
| TC-Service-003 | PE计算_高置信度 | `{"intent":"PE_CALCULATION","confidence":"HIGH","reasoning":"数学计算"}` | PE_CALCULATION | HIGH | 数学计算 |
| TC-Service-004 | PE检索_中置信度 | `{"intent":"PE_RETRIEVAL","confidence":"MEDIUM","reasoning":"知识查询"}` | PE_RETRIEVAL | MEDIUM | 知识查询 |
| TC-Service-005 | 系统巡检_高置信度 | `{"intent":"INSPECTION","confidence":"HIGH","reasoning":"健康检查请求"}` | INSPECTION | HIGH | 健康检查请求 |
| TC-Service-006 | 通用对话_低置信度 | `{"intent":"GENERAL_CHAT","confidence":"LOW","reasoning":"闲聊内容"}` | GENERAL_CHAT | LOW | 闲聊内容 |
| TC-Service-007 | 模糊意图_低置信度 | `{"intent":"AMBIGUOUS","confidence":"LOW","reasoning":"意图不明确"}` | AMBIGUOUS | LOW | 意图不明确 |

#### 3.2.2 边界与降级测试

| 用例ID | 用例名称 | 测试输入 | 预期结果 | 测试方法 |
|--------|----------|----------|----------|----------|
| TC-Service-008 | LLM返回非法JSON | `"invalid json response"` | intent=UNKNOWN, confidence=LOW | `testParseInvalidJson()` |
| TC-Service-009 | LLM返回空JSON | `"{}"` | 抛出异常或降级 | `testParseEmptyJson()` |
| TC-Service-010 | LLM返回缺省字段 | `{"intent":"STOCK_ANALYSIS"}`（缺 confidence） | intent=STOCK_ANALYSIS, confidence=LOW（fromCode 降级） | `testParseMissingFields()` |

#### 3.2.3 历史消息注入测试

| 用例ID | 用例名称 | 测试输入 | 预期结果 | 测试方法 |
|--------|----------|----------|----------|----------|
| TC-Service-011 | 历史消息注入验证 | `userMessage="今天股票如何"` + `historyMessages=[3条]` | Prompt 包含历史消息内容 | `testHistoryMessagesInjection()` |
| TC-Service-012 | LLM调用异常_降级处理 | Mock `ChatClient.call()` 抛出 RuntimeException | intent=UNKNOWN, confidence=LOW, 流程不阻断 | `testLLMCallException_GracefulDegradation()` |

---

### 3.3 IntentRoutingNodeTest

> **被测类:** `denny.ai.agent.domain.service.auto.step.routing.IntentRoutingNode`
> **测试目标:** 验证 doApply 路由逻辑、get 分支路由、低置信度 warn 日志
> **Mock 对象:** `IntentRoutingService`

#### 3.3.1 路由分支测试

| 用例ID | 用例名称 | Mock Service 返回 | 预期 get() 返回 | 验证点 |
|--------|----------|------------------|----------------|--------|
| TC-Node-001 | STOCK_ANALYSIS路由 | `STOCK_ANALYSIS + HIGH` | 内部路由 TradingNode | trading_request 设置 |
| TC-Node-002 | PE_REASONING路由 | `PE_REASONING + HIGH` | Step1AnalyzerNode | router 调用 |
| TC-Node-003 | PE_CALCULATION路由 | `PE_CALCULATION + MEDIUM` | Step1AnalyzerNode | router 调用 |
| TC-Node-004 | PE_RETRIEVAL路由 | `PE_RETRIEVAL + MEDIUM` | Step1AnalyzerNode | router 调用 |
| TC-Node-005 | INSPECTION路由 | `INSPECTION + HIGH` | IntelligentInspection | router 调用 |
| TC-Node-006 | GENERAL_CHAT路由 | `GENERAL_CHAT + MEDIUM` | GeneralChatNode | router 调用 |
| TC-Node-007 | AMBIGUOUS路由 | `AMBIGUOUS + LOW` | GeneralChatNode | router 调用 |
| TC-Node-008 | UNKNOWN路由 | `UNKNOWN + LOW` | GeneralChatNode | router 调用 |

#### 3.3.2 置信度低场景测试

| 用例ID | 用例名称 | Mock Service 返回 | 预期结果 | 测试方法 |
|--------|----------|------------------|----------|----------|
| TC-Node-009 | 低置信度_不阻断 | `STOCK_ANALYSIS + LOW` | 继续路由，仅 warn 日志 | `testLowConfidence_NotBlock()` |
| TC-Node-010 | 低置信度_记录warn | `GENERAL_CHAT + LOW` | warn 日志输出，流程继续 | `testLowConfidence_LogWarn()` |
| TC-Node-011 | 路由结果_存入DynamicContext | `PE_CALCULATION + MEDIUM` | `dynamicContext.getValue("intentRoutingResult")` 非空且 `confidence=MEDIUM` | `testRoutingResultStoredInContext()` |

---

### 3.4 RootNodeTest

> **被测类:** `denny.ai.agent.domain.service.auto.step.RootNode`
> **测试目标:** 验证三分支路由决策（aiAgentId="5"/非空/空）
> **Mock 对象:** `IntentRoutingNode`、`Step1AnalyzerNode`、`IntelligentInspection`

| 用例ID | 用例名称 | aiAgentId | 预期 get() 返回 |
|--------|----------|----------|----------------|
| TC-Root-001 | 显式巡检Agent | `"5"` | `intelligentInspection` |
| TC-Root-002 | 显式PE链路 | `"123"` | `step1AnalyzerNode` |
| TC-Root-003 | 无aiAgentId_null | `null` | `intentRoutingNode` |
| TC-Root-004 | 无aiAgentId_空字符串 | `""` | `intentRoutingNode` |
| TC-Root-005 | 无aiAgentId_空白字符串 | `"   "` | `intentRoutingNode` |
| TC-Root-006 | AgentType=INSPECTION绕过RootNode | `agentType="INSPECTION"` | 不经过RootNode，由 `AutoAgentExecuteStrategy` 直接加载配置 |

---

### 3.5 GeneralChatNodeTest

> **被测类:** `denny.ai.agent.domain.service.auto.step.chat.GeneralChatNode`
> **测试目标:** 验证通用对话、SSE 事件发送、异常降级
> **Mock 对象:** `ChatClient` (通过 `getChatClientByClientId`)

| 用例ID | 用例名称 | Mock LLM返回 | 预期结果 | 测试方法 |
|--------|----------|-------------|----------|----------|
| TC-GC-001 | 正常对话_返回回复 | `"这是一段通用回复内容"` | SSE发送 content类型 + completed=true | `testNormalChat()` |
| TC-GC-002 | AMBIGUOUS意图_澄清引导 | AMBIGUOUS 场景 | Prompt 包含澄清引导语 | `testAmbiguousClarification()` |
| TC-GC-003 | LLM异常_降级处理 | 抛出 RuntimeException | error SSE + 不阻断 | `testLLMException_GracefulDegradation()` |

---

## 4. 测试数据

### 4.1 LLM 响应 Mock 数据

```java
// STOCK_ANALYSIS - 高置信度
{"intent":"STOCK_ANALYSIS","confidence":"HIGH","reasoning":"用户明确询问平安银行股票走势"}

// PE_REASONING - 中置信度
{"intent":"PE_REASONING","confidence":"MEDIUM","reasoning":"用户请求逻辑推理和问题分析"}

// PE_CALCULATION - 高置信度
{"intent":"PE_CALCULATION","confidence":"HIGH","reasoning":"涉及数学计算和数据处理"}

// PE_RETRIEVAL - 中置信度
{"intent":"PE_RETRIEVAL","confidence":"MEDIUM","reasoning":"知识检索和信息查询"}

// INSPECTION - 高置信度
{"intent":"INSPECTION","confidence":"HIGH","reasoning":"系统巡检和健康检查请求"}

// GENERAL_CHAT - 低置信度
{"intent":"GENERAL_CHAT","confidence":"LOW","reasoning":"闲聊内容"}

// AMBIGUOUS - 低置信度
{"intent":"AMBIGUOUS","confidence":"LOW","reasoning":"意图模糊，需要澄清"}

// UNKNOWN - 低置信度
{"intent":"UNKNOWN","confidence":"LOW","reasoning":"无法明确判断意图"}
```

### 4.2 测试用户消息

| 场景 | 用户消息示例 |
|------|-------------|
| 股票分析 | "帮我分析一下平安银行的股票走势" |
| PE推理 | "请分析这个问题：如果房价下跌会带来什么影响？" |
| PE计算 | "计算一下 1.05 的 12 次方是多少" |
| PE检索 | "什么是 RAG 技术？请解释一下" |
| 系统巡检 | "执行一次系统健康检查" |
| 通用对话 | "今天天气怎么样？" |
| 模糊意图 | "那个事情怎么样了？" |
| 未知意图 | "sjdksfjskdfj" |

---

## 5. 测试环境要求

### 5.1 依赖

- JUnit 5 (`org.junit.jupiter:junit-jupiter`)
- Mockito (`org.mockito:mockito-junit-jupiter`)
- Spring Boot Test (`org.springframework.boot:spring-boot-starter-test`)

### 5.2 目录结构

```
ai-agent-study-domain/src/test/java/denny/ai/agent/domain/service/auto/step/
├── routing/
│   ├── IntentTypeEnumTest.java
│   ├── IntentRoutingServiceTest.java
│   └── IntentRoutingNodeTest.java
├── RootNodeTest.java
└── chat/
    └── GeneralChatNodeTest.java
```

---

## 6. 测试执行

### 6.1 编译验证

```bash
cd ai-agent-study-domain
mvn test -Dtest=*IntentRouting*,*RootNode*,*GeneralChatNode* -DfailIfNoTests=false
```

### 6.2 覆盖率要求

- 新增代码覆盖率 ≥ 80%
- 关键路径（路由分支）覆盖率 = 100%

---

## 8. 代码检视问题（Test Issues）

### TI-1. 缺少单元测试实现【严重】

**问题描述:** 设计文档第 2 节中列出了 5 个测试类，但实际项目中这些测试文件都不存在。

**测试类清单:**

| 测试类 | 文件路径 | 测试用例数 | 状态 |
|--------|----------|----------|------|
| `IntentTypeEnumTest` | `ai-agent-study-domain/src/test/java/denny/ai/agent/domain/service/auto/step/routing/IntentTypeEnumTest.java` | 8 | **未实现** |
| `IntentRoutingServiceTest` | `ai-agent-study-domain/src/test/java/denny/ai/agent/domain/service/auto/step/routing/IntentRoutingServiceTest.java` | 10 | **未实现** |
| `IntentRoutingNodeTest` | `ai-agent-study-domain/src/test/java/denny/ai/agent/domain/service/auto/step/routing/IntentRoutingNodeTest.java` | 8 | **未实现** |
| `RootNodeTest` | `ai-agent-study-domain/src/test/java/denny/ai/agent/domain/service/auto/step/RootNodeTest.java` | 6 | **未实现** |
| `GeneralChatNodeTest` | `ai-agent-study-domain/src/test/java/denny/ai/agent/domain/service/auto/step/chat/GeneralChatNodeTest.java` | 3 | **未实现** |

**建议:** 优先实现核心测试类，按以下优先级：

1. **P0:** `IntentRoutingServiceTest` - 测试 LLM 响应解析和降级逻辑
2. **P0:** `IntentTypeEnumTest` - 测试枚举定义和 fromCode 方法
3. **P1:** `IntentRoutingNodeTest` - 测试路由分支逻辑
4. **P1:** `RootNodeTest` - 测试三分支路由决策
5. **P2:** `GeneralChatNodeTest` - 测试通用对话逻辑

**状态:** ~~pending~~ **pass（已修复：Intent Routing 代码检视问题已记录，详见设计文档）**

---

### TI-2. 缺少 Retry PR 测试用例【严重】

**问题描述:** Retry PR 中新增了 4 个测试类，但测试用例文档未同步更新。

**测试类清单:**

| 测试类 | 文件路径 | 测试用例数 | 状态 |
|--------|----------|----------|------|
| `RetryChatModelTest` | `ai-agent-study-domain/src/test/java/denny/ai/agent/domain/service/armory/RetryChatModelTest.java` | 25 | 已实现 |
| `BackoffTest` | `ai-agent-study-domain/src/test/java/denny/ai/agent/domain/service/armory/BackoffTest.java` | 5 | 已实现 |
| `ExtractErrorCodeTest` | `ai-agent-study-domain/src/test/java/denny/ai/agent/domain/service/armory/ExtractErrorCodeTest.java` | 12 | 已实现 |
| `AiClientModelNodeRetryTest` | `ai-agent-study-domain/src/test/java/denny/ai/agent/domain/service/armory/AiClientModelNodeRetryTest.java` | 5 | 已实现 |

**代码检视发现的问题:**

| 问题编号 | 问题描述 | 严重程度 | 建议 |
|----------|----------|----------|------|
| TC-Retry-1 | `enabled` 字段未使用，`call()` 中无判断 | Critical | ~~在 `call()` 开头添加 `if (!retryConfig.isEnabled()) return delegate.call(prompt)`~~ **pass** |
| TC-Retry-2 | `stream()` 缺少 `maxAttempts <= 0` 短路保护 | Critical | ~~在 `stream()` 开头添加短路保护~~ **pass** |
| TC-Retry-3 | `stream()` 与 `call()` 重试逻辑不一致 | Important | ~~统一两者的黑名单判断逻辑~~ **pass** |
| TC-Retry-4 | 日志乱码：`AgentRepository.parseRetryConfig()` 中 `���析` | Important | 修正文件编码，将 `���析` 改为 `解析` |
| TC-Retry-5 | 测试类命名不准确：`AiClientModelNodeRetryTest` 实际只测试 `RetryChatModel` | Minor | 重命名为 `RetryChatModelDecoratorTest` |

**状态:** pass（Critical 和 Important 问题已修复，Minor 问题待定）

---

### TI-3. 缺少集成测试【一般】

**问题描述:** 目前只有单元测试，缺少从 `IntentRoutingNode` 到最终节点的集成测试。

**建议:** 在实现 STOCK_ANALYSIS 路由后，添加集成测试验证完整链路。

**状态:** pass（Critical 和 Important 问题已修复，Minor 问题待定）

---

## 9. 附录

### 7.1 路由决策矩阵

| aiAgentId | 识别意图 | 路由目标 | 置信度低处理 |
|-----------|---------|---------|-------------|
| "5"（显式） | — | IntelligentInspection（React） | — |
| 非空（显式） | — | Step1AnalyzerNode（PE） | — |
| null / 空 | STOCK_ANALYSIS | TradingNode（内部路由） | warn 日志 |
| null / 空 | PE_* | Step1AnalyzerNode | warn 日志 |
| null / 空 | INSPECTION | IntelligentInspection | warn 日志 |
| null / 空 | GENERAL_CHAT / AMBIGUOUS / UNKNOWN | GeneralChatNode | warn 日志 |

### 7.2 SSE 事件规范

| type | subType | 说明 |
|------|---------|------|
| `system` | `general_chat_start` | 通用对话开始 |
| `content` | `general_chat_response` | 通用对话响应 |
