# 统一意图路由 + 动态 Few-Shot + 切槽 测试用例

> **创建时间:** 2026-05-11
> **所属 story:** [2026-05-11-unified-intent-routing-fewshot.md](./2026-05-11-unified-intent-routing-fewshot.md)
> **测试范围:** 新增代码链路（意图识别 + 切槽 + 动态 Few-Shot）

---

## 1. 测试链路

```
用户请求 → RootNode
              ├─ aiAgentId="5" → IntelligentInspection
              ├─ aiAgentId 非空 → Step1AnalyzerNode
              └─ aiAgentId 为空 → IntentRoutingNode
                                   ├─ Few-Shot Top-K 检索
                                   └─ LLM 识别 + 切槽
                                       ├─ STOCK_ANALYSIS → TradingNode（切槽生效）
                                       ├─ PE_* → Step1AnalyzerNode
                                       ├─ INSPECTION → IntelligentInspection
                                       └─ GENERAL_CHAT/AMBIGUOUS/UNKNOWN → GeneralChatNode
```

---

## 2. 测试类清单

| 测试类 | 文件路径 | 用例数 | 优先级 | status |
|--------|----------|--------|--------|--------|
| `IntentRoutingServiceTest` | `domain/src/test/java/.../routing/IntentRoutingServiceTest.java` | 12 | P0 | pending |
| `IntentRoutingNodeTest` | `domain/src/test/java/.../routing/IntentRoutingNodeTest.java` | 10 | P0 | pending |
| `IntentFewshotServiceTest` | `domain/src/test/java/.../intent/IntentFewshotServiceTest.java` | 8 | P1 | pending |
| `StockSlotTest` | `domain/src/test/java/.../valobj/StockSlotTest.java` | 5 | P1 | pending |
| `IntentRoutingResultTest` | `domain/src/test/java/.../valobj/IntentRoutingResultTest.java` | 5 | P1 | pending |
| `RootNodeTest` | `domain/src/test/java/.../step/RootNodeTest.java` | 6 | P1 | pending |
| `GeneralChatNodeTest` | `domain/src/test/java/.../step/chat/GeneralChatNodeTest.java` | 3 | P2 | pending |

---

## 3. 测试用例详情

### 3.1 IntentRoutingServiceTest

> **被测类:** `IntentRoutingService`
> **测试目标:** LLM 响应解析、意图识别、置信度判断、Few-Shot 注入、切槽解析
> **Mock 对象:** `ChatModel`, `IntentFewshotService`

#### 3.1.1 正常识别测试

| 用例ID | 用例名称 | Mock LLM返回 | 预期意图 | 预期置信度 | 预期切槽 |
|--------|----------|-------------|----------|-----------|---------|
| TC-IR-001 | 股票分析_高置信度_含切槽 | `{"intent":"STOCK_ANALYSIS","confidence":"HIGH","reasoning":"用户询问股票走势","baseSlot":{"topic":"股票分析","sentiment":"neutral"},"intentSpecificSlots":{"stockCode":"平安银行","stockQueryType":"走势分析","timeRange":"近一年","exchange":"SZ"}}` | STOCK_ANALYSIS | HIGH | ✅ stockCode=平安银行 |
| TC-IR-002 | PE推理_中置信度 | `{"intent":"PE_REASONING","confidence":"MEDIUM","reasoning":"逻辑推理任务","baseSlot":{"topic":"推理分析","sentiment":"neutral"}}` | PE_REASONING | MEDIUM | ❌ |
| TC-IR-003 | PE计算_高置信度 | `{"intent":"PE_CALCULATION","confidence":"HIGH","reasoning":"数学计算","baseSlot":{"topic":"计算","sentiment":"neutral"}}` | PE_CALCULATION | HIGH | ❌ |
| TC-IR-004 | PE检索_中置信度 | `{"intent":"PE_RETRIEVAL","confidence":"MEDIUM","reasoning":"知识查询","baseSlot":{"topic":"检索","sentiment":"neutral"}}` | PE_RETRIEVAL | MEDIUM | ❌ |
| TC-IR-005 | 系统巡检_高置信度 | `{"intent":"INSPECTION","confidence":"HIGH","reasoning":"健康检查","baseSlot":{"topic":"巡检","sentiment":"neutral"}}` | INSPECTION | HIGH | ❌ |
| TC-IR-006 | 通用对话_低置信度 | `{"intent":"GENERAL_CHAT","confidence":"LOW","reasoning":"闲聊","baseSlot":{"topic":"闲聊","sentiment":"neutral"}}` | GENERAL_CHAT | LOW | ❌ |
| TC-IR-007 | 模糊意图_低置信度 | `{"intent":"AMBIGUOUS","confidence":"LOW","reasoning":"意图模糊","baseSlot":{"topic":"模糊","sentiment":"neutral"}}` | AMBIGUOUS | LOW | ❌ |
| TC-IR-008 | 未知意图_低置信度 | `{"intent":"UNKNOWN","confidence":"LOW","reasoning":"无法判断","baseSlot":null}` | UNKNOWN | LOW | ❌ |

#### 3.1.2 Few-Shot 注入测试

| 用例ID | 用例名称 | Mock Few-Shot | 预期结果 |
|--------|----------|--------------|---------|
| TC-IR-009 | Few-Shot Top-K 检索_返回5条 | 检索返回5条样本 | Prompt 包含5条样本 |
| TC-IR-010 | Few-Shot Top-K 检索_返回空 | 检索返回空列表 | Prompt 不含参考示例 |
| TC-IR-011 | Few-Shot 检索异常_降级 | Mock 抛出异常 | 降级为空列表，继续 Zero-Shot |

#### 3.1.3 边界与降级测试

| 用例ID | 用例名称 | 测试输入 | 预期结果 |
|--------|----------|----------|---------|
| TC-IR-012 | LLM返回非法JSON | `"invalid json"` | intent=UNKNOWN, confidence=LOW |
| TC-IR-013 | LLM返回缺省字段 | `{"intent":"STOCK_ANALYSIS"}`（缺 confidence/baseSlot） | intent=STOCK_ANALYSIS, confidence=LOW |
| TC-IR-014 | LLM调用异常_降级 | Mock `chatModel.call()` 抛出 RuntimeException | intent=UNKNOWN, confidence=LOW, 流程不阻断 |

---

### 3.2 IntentRoutingNodeTest

> **被测类:** `IntentRoutingNode`
> **测试目标:** doApply 路由逻辑、get 分支路由、slots 解析、低置信度 warn 日志
> **Mock 对象:** `IntentRoutingService`, `TradingNode`, `Step1AnalyzerNode`, `IntelligentInspection`, `GeneralChatNode`

#### 3.2.1 路由分支测试

| 用例ID | 用例名称 | Mock Service 返回 | 预期 get() 返回 |
|--------|----------|------------------|----------------|
| TC-IN-001 | STOCK_ANALYSIS路由_TradingNode | `STOCK_ANALYSIS + HIGH + StockSlot` | tradingNode |
| TC-IN-002 | PE_REASONING路由 | `PE_REASONING + HIGH` | step1AnalyzerNode |
| TC-IN-003 | PE_CALCULATION路由 | `PE_CALCULATION + MEDIUM` | step1AnalyzerNode |
| TC-IN-004 | PE_RETRIEVAL路由 | `PE_RETRIEVAL + MEDIUM` | step1AnalyzerNode |
| TC-IN-005 | INSPECTION路由 | `INSPECTION + HIGH` | intelligentInspection |
| TC-IN-006 | GENERAL_CHAT路由 | `GENERAL_CHAT + MEDIUM` | generalChatNode |
| TC-IN-007 | AMBIGUOUS路由 | `AMBIGUOUS + LOW` | generalChatNode |
| TC-IN-008 | UNKNOWN路由 | `UNKNOWN + LOW` | generalChatNode |

#### 3.2.2 切槽解析测试

| 用例ID | 用例名称 | Mock Service 返回 | 预期结果 |
|--------|----------|------------------|---------|
| TC-IN-009 | STOCK_ANALYSIS_切槽存入context | `STOCK_ANALYSIS + StockSlot(平安银行,走势分析)` | `dynamicContext.getValue("stockSlot")` 非空 |
| TC-IN-010 | 非STOCK_ANALYSIS_不存入stockSlot | `PE_REASONING` | `dynamicContext.getValue("stockSlot")` 为 null |
| TC-IN-011 | 低置信度_不阻断仅warn | `STOCK_ANALYSIS + LOW` | 继续路由，仅 warn 日志 |
| TC-IN-012 | 切槽为空_降级 | `STOCK_ANALYSIS + HIGH + null stockSlot` | stockSlot 降级为默认值，打 warn |

---

### 3.3 IntentFewshotServiceTest

> **被测类:** `IntentFewshotService`
> **测试目标:** 样本 CRUD、PGvector Top-K 检索、embedding 生成
> **Mock 对象:** `IntentFewshotSampleRepository`, `VectorStore`

#### 3.3.1 CRUD 测试

| 用例ID | 用例名称 | 测试输入 | 预期结果 |
|--------|----------|----------|---------|
| TC-IF-001 | 新增样本_自动生成embedding | `addSample(query, STOCK_ANALYSIS, json)` | 样本入库，embedding 非空 |
| TC-IF-002 | 删除样本_软删除 | `deleteSample(id)` | status=0，不物理删除 |
| TC-IF-003 | 更新样本 | `updateSample(id, newJson)` | embedding 重新生成 |

#### 3.3.2 PGvector 检索测试

| 用例ID | 用例名称 | 测试输入 | 预期结果 |
|--------|----------|----------|---------|
| TC-IF-004 | Top-K 检索_返回5条 | `retrieveTopK("股票分析", 5)` | 返回5条样本 |
| TC-IF-005 | Top-K 检索_不足5条 | 数据库仅3条样本 | 返回3条 |
| TC-IF-006 | Top-K 检索_数据库空 | 数据库无样本 | 返回空列表 |
| TC-IF-007 | Top-K 检索_仅返回启用状态 | 数据库有启用/禁用样本 | 仅返回 status=1 的样本 |
| TC-IF-008 | PGvector 检索异常_降级 | Mock `vectorStore.similaritySearch()` 抛异常 | 返回空列表，打 warn |

---

### 3.4 StockSlotTest

> **被测类:** `StockSlot`
> **测试目标:** 字段赋值、builder 模式

| 用例ID | 用例名称 | 测试输入 | 预期结果 |
|--------|----------|----------|---------|
| TC-SS-001 | builder正常构建 | `StockSlot.builder().stockCode("平安银行").build()` | 字段正确赋值 |
| TC-SS-002 | 空字段处理 | `StockSlot.builder().build()` | 所有字段为 null |
| TC-SS-003 | 完整字段 | 5个字段全部赋值 | 所有字段非空 |
| TC-SS-004 | equals/hashCode | 相同字段两个对象 | equals=true, hashCode 相同 |
| TC-SS-005 | toString | 正常构建 | 包含 stockCode 等字段信息 |

---

### 3.5 IntentRoutingResultTest

> **被测类:** `IntentRoutingResult`
> **测试目标:** builder 模式、slots 字段

| 用例ID | 用例名称 | 测试输入 | 预期结果 |
|--------|----------|----------|---------|
| TC-IRR-001 | builder正常构建 | 填充所有字段 | 所有字段正确赋值 |
| TC-IRR-002 | baseSlot为null | `baseSlot=null` | 正常构建 |
| TC-IRR-003 | intentSpecificSlots为null | `intentSpecificSlots=null` | 正常构建 |
| TC-IRR-004 | intentSpecificSlots含stockSlot | 嵌套 StockSlot | 正常解析 |
| TC-IRR-005 | equals/hashCode | 相同字段两个对象 | equals=true |

---

### 3.6 RootNodeTest

> **被测类:** `RootNode`
> **测试目标:** 三分支路由决策
> **Mock 对象:** `IntentRoutingNode`, `Step1AnalyzerNode`, `IntelligentInspection`

| 用例ID | 用例名称 | aiAgentId | 预期 get() 返回 |
|--------|----------|-----------|----------------|
| TC-RN-001 | 显式巡检Agent | `"5"` | intelligentInspection |
| TC-RN-002 | 显式PE链路 | `"123"` | step1AnalyzerNode |
| TC-RN-003 | 无aiAgentId_null | `null` | intentRoutingNode |
| TC-RN-004 | 无aiAgentId_空字符串 | `""` | intentRoutingNode |
| TC-RN-005 | 无aiAgentId_空白字符串 | `"   "` | intentRoutingNode |
| TC-RN-006 | aiAgentId_5_前后空格 | `"  5  "` | intelligentInspection |

---

### 3.7 GeneralChatNodeTest

> **被测类:** `GeneralChatNode`
> **测试目标:** 通用对话、SSE 事件发送、异常降级
> **Mock 对象:** `ChatClient`

| 用例ID | 用例名称 | Mock LLM返回 | 预期结果 |
|--------|----------|-------------|---------|
| TC-GC-001 | 正常对话_返回回复 | `"通用回复内容"` | SSE发送 content类型 |
| TC-GC-002 | AMBIGUOUS意图_澄清引导 | Prompt 包含澄清引导 | Prompt 含澄清语句 |
| TC-GC-003 | LLM异常_降级处理 | 抛出 RuntimeException | error SSE，不阻断 |

---

## 4. Mock LLM 响应数据

```java
// STOCK_ANALYSIS - 高置信度 + 完整切槽
{
  "intent": "STOCK_ANALYSIS",
  "confidence": "HIGH",
  "reasoning": "用户明确询问平安银行股票走势",
  "baseSlot": {"topic": "股票分析", "sentiment": "neutral"},
  "intentSpecificSlots": {
    "stockCode": "平安银行",
    "stockQueryType": "走势分析",
    "timeRange": "近一年",
    "exchange": "SZ"
  }
}

// PE_REASONING - 中置信度
{
  "intent": "PE_REASONING",
  "confidence": "MEDIUM",
  "reasoning": "用户请求逻辑推理和问题分析",
  "baseSlot": {"topic": "推理分析", "sentiment": "neutral"},
  "intentSpecificSlots": null
}

// PE_CALCULATION - 高置信度
{
  "intent": "PE_CALCULATION",
  "confidence": "HIGH",
  "reasoning": "涉及数学计算和数据处理",
  "baseSlot": {"topic": "计算", "sentiment": "neutral"},
  "intentSpecificSlots": null
}

// PE_RETRIEVAL - 中置信度
{
  "intent": "PE_RETRIEVAL",
  "confidence": "MEDIUM",
  "reasoning": "知识检索和信息查询",
  "baseSlot": {"topic": "检索", "sentiment": "neutral"},
  "intentSpecificSlots": null
}

// INSPECTION - 高置信度
{
  "intent": "INSPECTION",
  "confidence": "HIGH",
  "reasoning": "系统巡检和健康检查请求",
  "baseSlot": {"topic": "巡检", "sentiment": "neutral"},
  "intentSpecificSlots": null
}

// GENERAL_CHAT - 低置信度
{
  "intent": "GENERAL_CHAT",
  "confidence": "LOW",
  "reasoning": "闲聊内容",
  "baseSlot": {"topic": "闲聊", "sentiment": "neutral"},
  "intentSpecificSlots": null
}

// AMBIGUOUS - 低置信度
{
  "intent": "AMBIGUOUS",
  "confidence": "LOW",
  "reasoning": "意图模糊，需要澄清",
  "baseSlot": {"topic": "模糊", "sentiment": "neutral"},
  "intentSpecificSlots": null
}

// UNKNOWN - 低置信度
{
  "intent": "UNKNOWN",
  "confidence": "LOW",
  "reasoning": "无法明确判断意图",
  "baseSlot": null,
  "intentSpecificSlots": null
}
```

---

## 5. 测试环境要求

### 5.1 依赖

- JUnit 5 (`org.junit.jupiter:junit-jupiter`)
- Mockito (`org.mockito:mockito-junit-jupiter`)
- Spring Boot Test (`org.springframework.boot:spring-boot-starter-test`)

### 5.2 目录结构

```
ai-agent-study-domain/src/test/java/denny/ai/agent/domain/
├── model/valobj/
│   ├── StockSlotTest.java
│   └── IntentRoutingResultTest.java
├── repository/
├── service/
│   ├── auto/step/
│   │   ├── routing/
│   │   │   ├── IntentRoutingServiceTest.java
│   │   │   └── IntentRoutingNodeTest.java
│   │   ├── step/
│   │   │   └── RootNodeTest.java
│   │   └── step/chat/
│   │       └── GeneralChatNodeTest.java
│   └── intent/
│       └── IntentFewshotServiceTest.java
```

---

## 6. 测试执行

### 6.1 编译验证

```bash
cd ai-agent-study-domain
mvn test -Dtest=*IntentRouting*,*IntentFewshot*,*StockSlot*,*IntentRoutingResult*,*RootNode*,*GeneralChatNode* -DfailIfNoTests=false
```

### 6.2 覆盖率要求

- 新增代码覆盖率 ≥ 80%
- 关键路径（路由分支、切槽解析）覆盖率 = 100%

---

## 7. 测试数据

### 7.1 测试用户消息

| 场景 | 用户消息示例 |
|------|------------|
| 股票分析 | "帮我分析一下平安银行的股票走势" |
| PE推理 | "请分析这个问题：如果房价下跌会带来什么影响？" |
| PE计算 | "计算一下 1.05 的 12 次方是多少" |
| PE检索 | "什么是 RAG 技术？请解释一下" |
| 系统巡检 | "执行一次系统健康检查" |
| 通用对话 | "今天天气怎么样？" |
| 模糊意图 | "那个事情怎么样了？" |
| 未知意图 | "sjdksfjskdfj" |

### 7.2 Few-Shot 样本测试数据

```java
// 样本1: 股票分析
IntentFewshotSample sample1 = IntentFewshotSample.builder()
    .queryText("帮我看看腾讯的股价")
    .intentCode("STOCK_ANALYSIS")
    .exampleJson("{\"intent\":\"STOCK_ANALYSIS\",\"confidence\":\"HIGH\",\"reasoning\":\"用户询问股票\",\"baseSlot\":{\"topic\":\"股票分析\",\"sentiment\":\"neutral\"},\"intentSpecificSlots\":{\"stockCode\":\"腾讯\",\"stockQueryType\":\"走势分析\"}}")
    .build();

// 样本2: PE推理
IntentFewshotSample sample2 = IntentFewshotSample.builder()
    .queryText("分析一下产业链的影响")
    .intentCode("PE_REASONING")
    .exampleJson("{\"intent\":\"PE_REASONING\",\"confidence\":\"HIGH\",\"reasoning\":\"逻辑推理\",\"baseSlot\":{\"topic\":\"推理分析\"}}")
    .build();

// 样本3: PE计算
IntentFewshotSample sample3 = IntentFewshotSample.builder()
    .queryText("算一下复合增长率")
    .intentCode("PE_CALCULATION")
    .exampleJson("{\"intent\":\"PE_CALCULATION\",\"confidence\":\"HIGH\",\"reasoning\":\"计算\",\"baseSlot\":{\"topic\":\"计算\"}}")
    .build();
```
