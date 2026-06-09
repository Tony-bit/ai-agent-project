# Intent Routing 可评测集建设方案（本地回归优先，Langfuse 兼容预留）

> **创建时间:** 2026-06-08
> **状态:** draft
> **目标范围:** 先建设 `parseUnifiedResponse` 的本地可评测集，并为后续 Langfuse Dataset / Experiment 接入预留统一数据结构

---

## 1. 背景

当前统一意图路由已经具备 `parseUnifiedResponse` 解析能力，并在 `IntentRoutingServiceTest` 中存在若干散点式单元测试，但仍缺少一套可持续扩展、可批量执行、可回归对比的意图评测集机制。

现阶段问题主要有：

- 测试 case 分散在单元测试方法中，缺少统一数据源
- 缺少对 5 类核心意图的系统化覆盖
- 缺少多任务、澄清、降级等统一回归入口
- 现有测试更偏“点测”，尚未形成“评测集”
- 与 Langfuse 的 Dataset / Experiment 能力尚未形成结构兼容

因此，本次优先建设一套 **本地封闭可回归的意图评测集**，先把结果契约稳定下来，再平滑升级到 Langfuse 在线评测体系。

---

## 2. 目标

### 2.1 本次目标

1. 为 `parseUnifiedResponse` 建立统一 JSON 可评测集
2. 覆盖 5 类核心意图：
   - `STOCK_ANALYSIS`
   - `GENERAL_CHAT`
   - `PE_REASONING`
   - `PE_CALCULATION`
   - `PE_RETRIEVAL`
3. 覆盖 4 类核心路由行为：
   - 单任务
   - 多任务
   - 需要澄清
   - 降级回退
4. 建立参数化测试执行方式，支持批量回归
5. 让 case schema 可兼容后续 Langfuse Dataset 接入

### 2.2 非目标

本次不包含以下内容：

- 不直接接入 Langfuse Dataset API
- 不执行真实 LLM 在线评测
- 不改造 `routeUnified` 主流程为线上实验链路
- 不实现评测结果写回 Langfuse 平台
- 不引入基于模型真实返回的 prompt A/B 对比

---

## 3. 当前现状

### 3.1 当前评测对象

当前统一路由解析核心在 `IntentRoutingService.parseUnifiedResponse`：

```182:227:ai-agent-study-domain/src/main/java/denny/ai/agent/domain/service/auto/step/routing/IntentRoutingService.java
public MultiIntentRoutingResult parseUnifiedResponse(String response) {
    if (response == null || response.isBlank()) {
        log.warn("统一路由 LLM 返回为空，降级为 GENERAL_CHAT");
        return fallbackMultiIntentResult("LLM返回为空");
    }

    try {
        String jsonStr = extractJson(response);
        JSONObject json = JSON.parseObject(jsonStr);

        Boolean multiTask = json.getBoolean("multiTask");
        Boolean needsClarification = json.getBoolean("needsClarification");
        String reasoning = defaultReasoning(json.getString("reasoning"));
        List<String> missingInfo = extractMissingInfo(json.getJSONArray("missingInfo"));
        String clarificationPrompt = defaultClarificationPrompt(json.getString("clarificationPrompt"), missingInfo);
        List<SubTask> taskList = extractTaskList(json.getJSONArray("taskList"));

        if (Boolean.TRUE.equals(needsClarification)) {
            return MultiIntentRoutingResult.builder()
                    .multiTask(Boolean.TRUE.equals(multiTask))
                    .needsClarification(true)
                    .missingInfo(missingInfo)
                    .clarificationPrompt(clarificationPrompt)
                    .reasoning(reasoning)
                    .taskList(taskList)
                    .build();
        }

        if (taskList.isEmpty()) {
            log.warn("统一路由 taskList 为空，降级为 GENERAL_CHAT: response={}", response);
            return fallbackMultiIntentResult("taskList为空");
        }

        return MultiIntentRoutingResult.builder()
                .multiTask(Boolean.TRUE.equals(multiTask) && taskList.size() > 1)
                .needsClarification(false)
                .missingInfo(missingInfo)
                .clarificationPrompt(clarificationPrompt)
                .reasoning(reasoning)
                .taskList(taskList)
                .build();
    } catch (Exception e) {
        log.warn("统一路由 JSON 解析失败，降级为 GENERAL_CHAT: response={}, error={}",
                response, e.getMessage());
        return fallbackMultiIntentResult("JSON解析失败: " + e.getMessage());
    }
}
```

### 3.2 当前结果对象

```20:51:ai-agent-study-domain/src/main/java/denny/ai/agent/domain/model/valobj/MultiIntentRoutingResult.java
public class MultiIntentRoutingResult {

    private Boolean multiTask;

    private Boolean needsClarification;

    private List<String> missingInfo;

    private String clarificationPrompt;

    private List<SubTask> taskList;

    private String reasoning;
}
```

### 3.3 当前测试基础

项目已经存在 `IntentRoutingServiceTest`，覆盖了若干关键场景，例如：

- 单任务解析
- 多任务解析
- 澄清场景解析
- 非法 JSON 降级
- Few-Shot 检索失败但主链路继续执行

但这些测试主要采用“方法内硬编码 response + 手工断言”的方式，缺少统一 case 数据源和参数化执行模型。

---

## 4. 方案边界

### 4.1 本次范围

本次只建设 **`parseUnifiedResponse` 层的本地可评测集**。

输入是：
- LLM 返回字符串（`response`）

输出断言对象是：
- `MultiIntentRoutingResult`

### 4.2 本次覆盖的行为类型

1. **单任务**
   - 一个 `taskList` 元素
   - 验证 intent、executorNode、confidence 等核心字段

2. **多任务**
   - 多个 `taskList` 元素
   - 验证 `multiTask=true`
   - 验证任务数与任务顺序

3. **需要澄清**
   - `needsClarification=true`
   - 验证 `missingInfo`、`clarificationPrompt`

4. **降级**
   - 非法 JSON
   - 空 `taskList`
   - 验证是否正确回退到 `GENERAL_CHAT`

### 4.3 本次不覆盖的链路

以下内容明确不在本次实现范围：

- `routeUnified` 的真实模型调用评测
- prompt 质量评测
- 基于真实 trace 的实验回归
- Langfuse 平台侧数据同步与展示

---

## 5. 为什么先做本地评测集，而不是直接接 Langfuse

这是一个阶段性决策，不是最终架构。

### 5.1 先做本地评测集的原因

1. 当前评测标准仍在收敛阶段，先需要把断言模型固定下来
2. 本地测试稳定、快速、适合 CI 和日常回归
3. 先统一 case schema，再接 Langfuse，可以减少重复建设
4. 若直接接 Langfuse 而没有稳定 case 结构，会导致评测数据标准不统一

### 5.2 本地评测集的价值

- 提供稳定的结构契约测试
- 验证解析逻辑与降级逻辑
- 为统一路由结果建立回归基线
- 为后续 Langfuse evaluator 定义奠定数据基础

### 5.3 Langfuse 的价值将在下一阶段释放

Langfuse 真正擅长的是：

- 记录真实 prompt / response / trace
- 进行多版本实验对比
- 管理 dataset
- 观察线上真实失败 case
- 汇总 latency / token / cost 等指标

因此本次方案会**预留兼容字段**，但不急于把平台接入和本地回归耦合在一起。

---

## 6. 可评测集数据结构设计

### 6.1 设计原则

1. **当前可直接服务于 `parseUnifiedResponse` 测试**
2. **后续可平滑升级为 Langfuse Dataset item**
3. **断言字段尽量稳定，避免脆弱匹配**
4. **每个 case 可唯一识别、可分类、可筛选**

### 6.2 推荐 Schema

```json
{
  "caseId": "intent-single-stock-001",
  "status": "pending",
  "category": "single-task",
  "description": "单任务股票分析，高置信度",
  "response": {
    "multiTask": false,
    "needsClarification": false,
    "reasoning": "用户明确询问股票分析",
    "taskList": [
      {
        "taskId": "sub-1",
        "taskIndex": 1,
        "totalTasks": 1,
        "content": "分析腾讯股票走势",
        "intent": "STOCK_ANALYSIS",
        "executorNode": "tradingStarter",
        "confidence": "HIGH",
        "taskType": 0,
        "slots": {
          "baseSlot": {
            "topic": "腾讯股票",
            "sentiment": "neutral"
          },
          "intentSpecificSlots": {
            "stockCode": "0700",
            "stockQueryType": "TREND",
            "timeRange": "近一个月",
            "exchange": "HK"
          }
        }
      }
    ]
  },
  "expected": {
    "multiTask": false,
    "needsClarification": false,
    "taskCount": 1,
    "taskIntents": ["STOCK_ANALYSIS"],
    "executorNodes": ["tradingStarter"]
  },
  "tags": ["intent-routing", "stock-analysis", "local-eval"]
}
```

### 6.3 字段说明

| 字段 | 含义 | 当前用途 | Langfuse 兼容用途 |
|------|------|----------|-------------------|
| `caseId` | 唯一标识 | 失败定位 | dataset item key |
| `status` | 执行状态 | 文档/数据集治理 | dataset metadata |
| `category` | 用例分类 | 分组执行/统计 | dataset metadata |
| `description` | 用例说明 | 提高可读性 | dataset description |
| `response` | 模拟 LLM 输出 | 当前直接输入解析器 | 可转成 expected output / fixture |
| `expected` | 断言目标 | 稳定断言 | evaluator 输入 |
| `tags` | 标签 | 本地过滤 | dataset metadata tags |

---

## 7. 断言策略

### 7.1 第一版强断言字段

建议只对以下稳定字段做强断言：

- `multiTask`
- `needsClarification`
- `taskList.size()`
- `taskList[i].intent`
- `taskList[i].executorNode`

### 7.2 第一版弱断言字段

以下字段只做存在性或最小约束断言：

- `clarificationPrompt`：非空
- `missingInfo`：非空或数量大于等于最小值
- `reasoning`：可选，不做全文匹配

### 7.3 第一版不建议强断言字段

以下字段不建议在第一版做逐字精确比对：

- `reasoning` 文案
- `content` 文案
- `slots` 全量字段逐层深比对

这样可以降低测试对文案小变动的敏感度，优先锁住“结构正确性”和“路由意图正确性”。

---

## 8. 第一版 case 分类建议

### 8.1 总量建议

第一版建议 **10 ~ 12 条 case**。

### 8.2 分类分布

#### A. 单任务（6 条）

- `STOCK_ANALYSIS` × 2
- `GENERAL_CHAT` × 1
- `PE_REASONING` × 1
- `PE_CALCULATION` × 1
- `PE_RETRIEVAL` × 1

#### B. 多任务（3 条）

- `GENERAL_CHAT + STOCK_ANALYSIS`
- `STOCK_ANALYSIS + STOCK_ANALYSIS`
- `PE_RETRIEVAL + PE_REASONING`

#### C. 需要澄清（2 条）

- 缺股票代码
- 缺检索对象 / 检索范围

#### D. 降级（1 ~ 2 条）

- 非法 JSON
- `taskList` 为空

### 8.3 第一版具体 case 清单

以下示例优先覆盖“结构契约正确性”，文案只做参考，不作为强断言依据。

#### Case 01：单任务股票分析

```json
{
  "caseId": "intent-single-stock-001",
  "status": "pending",
  "category": "single-task",
  "description": "单任务股票分析，高置信度",
  "response": {
    "multiTask": false,
    "needsClarification": false,
    "reasoning": "用户明确询问股票分析",
    "taskList": [
      {
        "taskId": "sub-1",
        "taskIndex": 1,
        "totalTasks": 1,
        "content": "分析腾讯股票走势",
        "intent": "STOCK_ANALYSIS",
        "executorNode": "tradingStarter",
        "confidence": "HIGH",
        "taskType": 0,
        "slots": {
          "baseSlot": {
            "topic": "腾讯股票",
            "sentiment": "neutral"
          },
          "intentSpecificSlots": {
            "stockCode": "0700",
            "stockQueryType": "TREND",
            "timeRange": "近一个月",
            "exchange": "HK"
          }
        }
      }
    ]
  },
  "expected": {
    "multiTask": false,
    "needsClarification": false,
    "taskCount": 1,
    "taskIntents": ["STOCK_ANALYSIS"],
    "executorNodes": ["tradingStarter"]
  },
  "tags": ["intent-routing", "stock-analysis", "local-eval"]
}
```

#### Case 02：单任务股票分析（A 股）

```json
{
  "caseId": "intent-single-stock-002",
  "status": "pending",
  "category": "single-task",
  "description": "单任务股票分析，A股标的",
  "response": {
    "multiTask": false,
    "needsClarification": false,
    "reasoning": "用户要求分析具体股票",
    "taskList": [
      {
        "taskId": "sub-1",
        "taskIndex": 1,
        "totalTasks": 1,
        "content": "分析贵州茅台近期走势",
        "intent": "STOCK_ANALYSIS",
        "executorNode": "tradingStarter",
        "confidence": "HIGH",
        "taskType": 0,
        "slots": {
          "baseSlot": {
            "topic": "贵州茅台",
            "sentiment": "neutral"
          },
          "intentSpecificSlots": {
            "stockCode": "600519",
            "stockQueryType": "TECHNICAL",
            "timeRange": "近三个月",
            "exchange": "SH"
          }
        }
      }
    ]
  },
  "expected": {
    "multiTask": false,
    "needsClarification": false,
    "taskCount": 1,
    "taskIntents": ["STOCK_ANALYSIS"],
    "executorNodes": ["tradingStarter"]
  },
  "tags": ["intent-routing", "stock-analysis", "ashare"]
}
```

#### Case 03：单任务通用对话

```json
{
  "caseId": "intent-single-general-001",
  "status": "pending",
  "category": "single-task",
  "description": "通用对话场景",
  "response": {
    "multiTask": false,
    "needsClarification": false,
    "reasoning": "用户在进行闲聊",
    "taskList": [
      {
        "taskId": "sub-1",
        "taskIndex": 1,
        "totalTasks": 1,
        "content": "回答用户关于今天天气的闲聊",
        "intent": "GENERAL_CHAT",
        "executorNode": "generalChatNode",
        "confidence": "MEDIUM",
        "taskType": 0,
        "slots": {
          "baseSlot": {
            "topic": "天气",
            "sentiment": "neutral"
          },
          "intentSpecificSlots": {}
        }
      }
    ]
  },
  "expected": {
    "multiTask": false,
    "needsClarification": false,
    "taskCount": 1,
    "taskIntents": ["GENERAL_CHAT"],
    "executorNodes": ["generalChatNode"]
  },
  "tags": ["intent-routing", "general-chat"]
}
```

#### Case 04：单任务 PE 推理

```json
{
  "caseId": "intent-single-reasoning-001",
  "status": "pending",
  "category": "single-task",
  "description": "PE 推理任务",
  "response": {
    "multiTask": false,
    "needsClarification": false,
    "reasoning": "用户请求逻辑分析",
    "taskList": [
      {
        "taskId": "sub-1",
        "taskIndex": 1,
        "totalTasks": 1,
        "content": "解释加息对成长股估值的影响",
        "intent": "PE_REASONING",
        "executorNode": "step1AnalyzerNode",
        "confidence": "HIGH",
        "taskType": 0,
        "slots": {
          "baseSlot": {
            "topic": "加息与成长股估值",
            "sentiment": "neutral"
          },
          "intentSpecificSlots": {}
        }
      }
    ]
  },
  "expected": {
    "multiTask": false,
    "needsClarification": false,
    "taskCount": 1,
    "taskIntents": ["PE_REASONING"],
    "executorNodes": ["step1AnalyzerNode"]
  },
  "tags": ["intent-routing", "pe-reasoning"]
}
```

#### Case 05：单任务 PE 计算

```json
{
  "caseId": "intent-single-calculation-001",
  "status": "pending",
  "category": "single-task",
  "description": "PE 计算任务",
  "response": {
    "multiTask": false,
    "needsClarification": false,
    "reasoning": "用户明确要求数值计算",
    "taskList": [
      {
        "taskId": "sub-1",
        "taskIndex": 1,
        "totalTasks": 1,
        "content": "计算复利收益率",
        "intent": "PE_CALCULATION",
        "executorNode": "step1AnalyzerNode",
        "confidence": "HIGH",
        "taskType": 0,
        "slots": {
          "baseSlot": {
            "topic": "复利收益率",
            "sentiment": "neutral"
          },
          "intentSpecificSlots": {}
        }
      }
    ]
  },
  "expected": {
    "multiTask": false,
    "needsClarification": false,
    "taskCount": 1,
    "taskIntents": ["PE_CALCULATION"],
    "executorNodes": ["step1AnalyzerNode"]
  },
  "tags": ["intent-routing", "pe-calculation"]
}
```

#### Case 06：单任务 PE 检索

```json
{
  "caseId": "intent-single-retrieval-001",
  "status": "pending",
  "category": "single-task",
  "description": "PE 检索任务",
  "response": {
    "multiTask": false,
    "needsClarification": false,
    "reasoning": "用户需要知识解释",
    "taskList": [
      {
        "taskId": "sub-1",
        "taskIndex": 1,
        "totalTasks": 1,
        "content": "解释什么是向量数据库",
        "intent": "PE_RETRIEVAL",
        "executorNode": "step1AnalyzerNode",
        "confidence": "HIGH",
        "taskType": 0,
        "slots": {
          "baseSlot": {
            "topic": "向量数据库",
            "sentiment": "neutral"
          },
          "intentSpecificSlots": {
            "topic": "向量数据库"
          }
        }
      }
    ]
  },
  "expected": {
    "multiTask": false,
    "needsClarification": false,
    "taskCount": 1,
    "taskIntents": ["PE_RETRIEVAL"],
    "executorNodes": ["step1AnalyzerNode"]
  },
  "tags": ["intent-routing", "pe-retrieval"]
}
```

#### Case 07：多任务（通用对话 + 股票分析）

```json
{
  "caseId": "intent-multi-mixed-001",
  "status": "pending",
  "category": "multi-task",
  "description": "先闲聊后股票分析的复合请求",
  "response": {
    "multiTask": true,
    "needsClarification": false,
    "reasoning": "包含闲聊和股票分析两个独立任务",
    "taskList": [
      {
        "taskId": "sub-1",
        "taskIndex": 1,
        "totalTasks": 2,
        "content": "简单回应用户问候",
        "intent": "GENERAL_CHAT",
        "executorNode": "generalChatNode",
        "confidence": "MEDIUM",
        "taskType": 0,
        "slots": {
          "baseSlot": {
            "topic": "问候",
            "sentiment": "positive"
          },
          "intentSpecificSlots": {}
        }
      },
      {
        "taskId": "sub-2",
        "taskIndex": 2,
        "totalTasks": 2,
        "content": "分析宁德时代走势",
        "intent": "STOCK_ANALYSIS",
        "executorNode": "tradingStarter",
        "confidence": "HIGH",
        "taskType": 0,
        "slots": {
          "baseSlot": {
            "topic": "宁德时代",
            "sentiment": "neutral"
          },
          "intentSpecificSlots": {
            "stockCode": "300750",
            "stockQueryType": "TREND",
            "timeRange": "近一个月",
            "exchange": "SZ"
          }
        }
      }
    ]
  },
  "expected": {
    "multiTask": true,
    "needsClarification": false,
    "taskCount": 2,
    "taskIntents": ["GENERAL_CHAT", "STOCK_ANALYSIS"],
    "executorNodes": ["generalChatNode", "tradingStarter"]
  },
  "tags": ["intent-routing", "multi-task", "mixed"]
}
```

#### Case 08：多任务（双股票分析）

```json
{
  "caseId": "intent-multi-stock-001",
  "status": "pending",
  "category": "multi-task",
  "description": "两个独立股票分析任务",
  "response": {
    "multiTask": true,
    "needsClarification": false,
    "reasoning": "用户一次询问两个股票标的",
    "taskList": [
      {
        "taskId": "sub-1",
        "taskIndex": 1,
        "totalTasks": 2,
        "content": "分析贵州茅台",
        "intent": "STOCK_ANALYSIS",
        "executorNode": "tradingStarter",
        "confidence": "HIGH",
        "taskType": 0,
        "slots": {
          "baseSlot": {
            "topic": "贵州茅台",
            "sentiment": "neutral"
          },
          "intentSpecificSlots": {
            "stockCode": "600519",
            "stockQueryType": "TECHNICAL",
            "timeRange": "近三个月",
            "exchange": "SH"
          }
        }
      },
      {
        "taskId": "sub-2",
        "taskIndex": 2,
        "totalTasks": 2,
        "content": "分析五粮液",
        "intent": "STOCK_ANALYSIS",
        "executorNode": "tradingStarter",
        "confidence": "HIGH",
        "taskType": 0,
        "slots": {
          "baseSlot": {
            "topic": "五粮液",
            "sentiment": "neutral"
          },
          "intentSpecificSlots": {
            "stockCode": "000858",
            "stockQueryType": "TECHNICAL",
            "timeRange": "近三个月",
            "exchange": "SZ"
          }
        }
      }
    ]
  },
  "expected": {
    "multiTask": true,
    "needsClarification": false,
    "taskCount": 2,
    "taskIntents": ["STOCK_ANALYSIS", "STOCK_ANALYSIS"],
    "executorNodes": ["tradingStarter", "tradingStarter"]
  },
  "tags": ["intent-routing", "multi-task", "stock-analysis"]
}
```

#### Case 09：多任务（检索 + 推理）

```json
{
  "caseId": "intent-multi-pe-001",
  "status": "pending",
  "category": "multi-task",
  "description": "先检索概念再做推理分析",
  "response": {
    "multiTask": true,
    "needsClarification": false,
    "reasoning": "包含知识查询与逻辑推理两个子任务",
    "taskList": [
      {
        "taskId": "sub-1",
        "taskIndex": 1,
        "totalTasks": 2,
        "content": "解释什么是企业自由现金流",
        "intent": "PE_RETRIEVAL",
        "executorNode": "step1AnalyzerNode",
        "confidence": "HIGH",
        "taskType": 0,
        "slots": {
          "baseSlot": {
            "topic": "企业自由现金流",
            "sentiment": "neutral"
          },
          "intentSpecificSlots": {
            "topic": "企业自由现金流"
          }
        }
      },
      {
        "taskId": "sub-2",
        "taskIndex": 2,
        "totalTasks": 2,
        "content": "分析自由现金流下降意味着什么",
        "intent": "PE_REASONING",
        "executorNode": "step1AnalyzerNode",
        "confidence": "MEDIUM",
        "taskType": 0,
        "slots": {
          "baseSlot": {
            "topic": "自由现金流下降",
            "sentiment": "neutral"
          },
          "intentSpecificSlots": {}
        }
      }
    ]
  },
  "expected": {
    "multiTask": true,
    "needsClarification": false,
    "taskCount": 2,
    "taskIntents": ["PE_RETRIEVAL", "PE_REASONING"],
    "executorNodes": ["step1AnalyzerNode", "step1AnalyzerNode"]
  },
  "tags": ["intent-routing", "multi-task", "pe"]
}
```

#### Case 10：需要澄清（缺股票代码）

```json
{
  "caseId": "intent-clarification-stock-001",
  "status": "pending",
  "category": "clarification",
  "description": "股票分析缺少明确标的",
  "response": {
    "multiTask": false,
    "needsClarification": true,
    "missingInfo": ["stockCode"],
    "clarificationPrompt": "请提供股票代码或股票名称",
    "reasoning": "用户表达了股票分析意图，但缺少明确股票标的",
    "taskList": []
  },
  "expected": {
    "multiTask": false,
    "needsClarification": true,
    "taskCount": 0,
    "missingInfo": ["stockCode"]
  },
  "tags": ["intent-routing", "clarification", "stock-analysis"]
}
```

#### Case 11：需要澄清（缺检索对象）

```json
{
  "caseId": "intent-clarification-retrieval-001",
  "status": "pending",
  "category": "clarification",
  "description": "检索意图明确但缺少检索主题",
  "response": {
    "multiTask": false,
    "needsClarification": true,
    "missingInfo": ["topic"],
    "clarificationPrompt": "请说明你想查询的具体主题或概念",
    "reasoning": "用户表达了查询意图，但缺少检索对象",
    "taskList": []
  },
  "expected": {
    "multiTask": false,
    "needsClarification": true,
    "taskCount": 0,
    "missingInfo": ["topic"]
  },
  "tags": ["intent-routing", "clarification", "pe-retrieval"]
}
```

#### Case 12：降级（非法 JSON）

```json
{
  "caseId": "intent-fallback-invalid-json-001",
  "status": "pending",
  "category": "fallback",
  "description": "LLM 返回非法 JSON，触发降级",
  "response": "invalid json",
  "expected": {
    "multiTask": false,
    "needsClarification": false,
    "taskCount": 1,
    "taskIntents": ["GENERAL_CHAT"],
    "executorNodes": ["generalChatNode"]
  },
  "tags": ["intent-routing", "fallback", "invalid-json"]
}
```

#### Case 13：降级（taskList 为空）

```json
{
  "caseId": "intent-fallback-empty-tasklist-001",
  "status": "pending",
  "category": "fallback",
  "description": "结构合法但 taskList 为空，触发降级",
  "response": {
    "multiTask": false,
    "needsClarification": false,
    "reasoning": "模型未产出有效任务",
    "taskList": []
  },
  "expected": {
    "multiTask": false,
    "needsClarification": false,
    "taskCount": 1,
    "taskIntents": ["GENERAL_CHAT"],
    "executorNodes": ["generalChatNode"]
  },
  "tags": ["intent-routing", "fallback", "empty-tasklist"]
}
```

### 8.4 第一版落地建议

若希望首批实现更聚焦，建议第一轮先落 10 条：

- Case 01 ~ Case 11 中任选 10 条
- 至少保留：
  - 5 条单任务主意图
  - 2 条多任务
  - 1 条澄清
  - 2 条降级

待参数化测试框架跑通后，再把剩余 case 增补为第二批。

---

## 9. 文件设计

### 9.1 新增设计文档

- `docs/superpowers/plans/2026-06-08-intent-routing-eval-dataset-plan.md`

### 9.2 新增测试资源文件

- `ai-agent-study-domain/src/test/resources/eval/intent-routing-cases.json`

职责：
- 存放第一版统一评测集
- 后续作为 Langfuse Dataset 的源数据候选

### 9.3 新增 case 模型

- `ai-agent-study-domain/src/test/java/denny/ai/agent/domain/service/auto/step/routing/model/IntentRoutingEvalCase.java`

职责：
- 映射 JSON 数据结构
- 提供参数化测试输入对象

### 9.4 新增 loader

- `ai-agent-study-domain/src/test/java/denny/ai/agent/domain/service/auto/step/routing/support/IntentRoutingEvalCaseLoader.java`

职责：
- 加载 JSON 资源
- 反序列化为 case 列表
- 输出参数化执行数据

### 9.5 新增参数化测试

- `ai-agent-study-domain/src/test/java/denny/ai/agent/domain/service/auto/step/routing/IntentRoutingEvalTest.java`

职责：
- 遍历 case 执行 `parseUnifiedResponse`
- 依据 `expected` 做稳定断言
- 输出失败 `caseId`

### 9.6 现有测试保留策略

保留当前 `IntentRoutingServiceTest` 作为：

- 点状功能测试
- 代码级边界行为测试
- 链路级 mock 验证测试

新增的 `IntentRoutingEvalTest` 作为：

- 统一数据驱动回归测试
- 批量评测集执行入口

二者并行存在，不互相替代。

---

## 10. Langfuse 兼容预留设计

### 10.1 兼容目标

本次不直接接入 Langfuse，但要求本地评测集结构能够低成本迁移。

### 10.2 预留策略

1. `caseId` 作为后续 dataset item 的唯一键
2. `category` 与 `tags` 作为 metadata 维度
3. `expected` 结构作为 evaluator 输入模型
4. 后续可补充 `input` 节点以支持 `routeUnified` 真正的输入输出评测

### 10.3 后续升级路径

#### Phase 1
本地 `parseUnifiedResponse` 评测集跑通

#### Phase 2
为 case 增加：
- `message`
- `historyMessages`
- `expected` 维度细化

升级为 `routeUnified` 评测输入集

#### Phase 3
同步到 Langfuse Dataset，并建立 Experiment：
- 输入 case
- 执行真实 `routeUnified`
- 计算 evaluator score
- 在 Langfuse UI 中查看结果

#### Phase 4
将线上真实失败 trace 反哺到本地评测集，形成闭环

---

## 11. 执行清单

> 按项目规范，所有任务项均带 `status` 字段。

| 序号 | 任务 | status | 说明 |
|------|------|--------|------|
| 1 | 梳理现有 `IntentRoutingServiceTest` 可复用断言逻辑 | pending | 提炼已有断言模式 |
| 2 | 设计 `intent-routing-cases.json` schema | pending | 固化统一 case 数据格式 |
| 3 | 编写第一版 10~12 条评测 case | pending | 覆盖 5 类主意图与 4 类路由行为 |
| 4 | 新增 `IntentRoutingEvalCase` 模型 | pending | 映射 JSON 结构 |
| 5 | 新增 `IntentRoutingEvalCaseLoader` | pending | 负责资源加载与参数化输入 |
| 6 | 新增 `IntentRoutingEvalTest` | pending | 统一数据驱动测试入口 |
| 7 | 本地执行 `mvn test` 验证通过 | pending | 验证编译与测试结果 |
| 8 | 评估第二阶段 Langfuse Dataset 接入方案 | pending | 输出后续接入计划 |

---

## 12. 风险与注意事项

### 12.1 风险

1. 若 `expected` 定义过细，测试容易脆弱
2. 若 `response` 示例不贴近真实模型输出，case 参考价值会下降
3. 若未来 `SubTask` 结构变化，需同步调整 case schema
4. 若第一版引入过多 slot 深层断言，会增加维护成本

### 12.2 控制措施

1. 第一版优先断言结构与核心意图，不断言文案细节
2. 优先复用现有测试中的真实 response 风格
3. 统一 `caseId` 命名规则，便于回归定位
4. 预留 `tags` / `category`，方便后续批量筛选与迁移

---

## 13. 结论

本方案不是 Langfuse 的替代方案，而是 **Langfuse 评测体系之前的本地回归基座**。

当前先通过 `parseUnifiedResponse` 可评测集，建立：

- 统一 case 数据源
- 稳定断言模型
- 批量回归入口
- 面向 Langfuse 的兼容结构

在此基础上，后续再扩展到：

- `routeUnified` 真链路评测
- Langfuse Dataset 管理
- Experiment 对比与线上失败 case 回流

这样能以最小风险和最快速度，把“散点测试”升级为“可持续的评测资产”。
