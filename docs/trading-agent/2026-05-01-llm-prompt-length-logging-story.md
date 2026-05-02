# Story: LLM 调用 Prompt 长度日志增强

## 1. 背景与目标

### 背景

当前交易 Agent 执行过程中，各 Analyst / Researcher / Recommendation 节点均通过 `ChatClient` 调用 LLM 生成分析结果。现有的日志输出仅包含 `latencyMs`（耗时），缺少对 **prompt 规模**的可见性。

在以下场景中，prompt 长度是关键的排查和优化指标：

- 多轮辩论后 prompt 膨胀，接近模型上下文窗口上限
- 各节点 prompt 规模差异大，需要横向对比以识别异常节点
- 分析 prompt 中嵌套 JSON 摘要导致 prompt 过长，影响 token 消耗和响应质量

### 目标

在所有 12 个交易领域节点的 LLM 调用处统一增加 `prompt 长度` 日志字段，便于：
1. 监控各节点的 prompt 规模趋势
2. 快速定位因 prompt 过长导致的响应质量下降问题
3. 为后续 token 计数和成本分析提供数据基础

---

## 2. 技术方案

### 2.1 现状分析

所有交易领域节点均继承 `AbstractExecuteSupport`，调用模式高度统一：

```java
String prompt = XXXPromptTemplate.XXX_PROMPT.formatted(...);
ChatClient chatClient = getChatClientByClientId("clientId", 0);
long startAt = System.currentTimeMillis();
String response = chatClient.prompt().user(prompt).call().content();
long latencyMs = System.currentTimeMillis() - startAt;
log.info("XXX LLM 响应耗时: {}ms", latencyMs);
```

现有日志仅有 `latencyMs`，无 prompt 长度记录。

### 2.2 方案设计

在每个节点的 LLM 调用处（`call()` 前后），统一增加一行日志：

```java
log.info("节点名称调用LLM | prompt长度={} | 响应长度={} | 耗时={}ms",
        prompt.length(), response.length(), latencyMs);
```

**说明**：记录的是字符数（`String.length()`），非 token 数。token 计数作为后续优化项。

### 2.3 节点角色对照

| 节点 | Client ID | 调用方法 |
|------|-----------|---------|
| BullResearcherNode | 6006 | `generateBullThesis()` |
| BearResearcherNode | 6007 | `generateBearThesis()` |
| ResearchManagerNode | 6008 | `generateJudgeDecision()` |
| TechnicalAnalystNode | 6003 | `generateReport()` |
| SentimentAnalystNode | 6004 | `generateReport()` |
| NewsAnalystNode | 6005 | `generateReport()` |
| FundamentalAnalystNode | 6002 | `generateReport()` |
| RecommendationNode | 6013 | `generateInvestmentPlan()` |
| PortfolioManagerNode | 6009 | `generateDecision()` |
| AggressiveRiskAnalystNode | 6012 | `generateRiskReport()` |
| ConservativeRiskAnalystNode | 6011 | `generateRiskReport()` |
| NeutralRiskAnalystNode | 6010 | `generateRiskReport()` |

---

## 3. 变更计划

### 3.1 BullResearcherNode

**文件**：`ai-agent-study-trading/ai-agent-study-trading-domain/src/main/java/denny/ai/agent/trading/domain/node/BullResearcherNode.java`

**改动位置**：`generateBullThesis()` 方法

| status | 任务项 |
|--------|--------|
| pass | 在第 101 行 `call()` 前插入 `log.info("多头研究员调用LLM \| prompt长度={}", prompt.length());` |
| pass | 将第 105 行日志改为 `log.info("多头研究员LLM响应 \| prompt长度={} \| 响应长度={} \| 耗时={}ms", prompt.length(), response.length(), latencyMs);` |
| pass | 编译验证 |

---

### 3.2 BearResearcherNode

**文件**：`ai-agent-study-trading/ai-agent-study-trading-domain/src/main/java/denny/ai/agent/trading/domain/node/BearResearcherNode.java`

**改动位置**：`generateBearThesis()` 方法

| status | 任务项 |
|--------|--------|
| pass | 在 `call()` 前插入 `log.info("空头研究员调用LLM \| prompt长度={}", prompt.length());` |
| pass | 将响应日志改为 `log.info("空头研究员LLM响应 \| prompt长度={} \| 响应长度={} \| 耗时={}ms", prompt.length(), response.length(), latencyMs);` |
| pass | 编译验证 |

---

### 3.3 ResearchManagerNode

**文件**：`ai-agent-study-trading/ai-agent-study-trading-domain/src/main/java/denny/ai/agent/trading/domain/node/ResearchManagerNode.java`

**改动位置**：`generateJudgeDecision()` 方法

| status | 任务项 |
|--------|--------|
| pass | 在 `call()` 前插入 `log.info("研究主管调用LLM \| prompt长度={}", prompt.length());` |
| pass | 将响应日志改为 `log.info("研究主管LLM响应 \| prompt长度={} \| 响应长度={} \| 耗时={}ms", prompt.length(), response.length(), latencyMs);` |
| pass | 编译验证 |

---

### 3.4 TechnicalAnalystNode

**文件**：`ai-agent-study-trading/ai-agent-study-trading-domain/src/main/java/denny/ai/agent/trading/domain/node/TechnicalAnalystNode.java`

**改动位置**：`generateReport()` 方法

| status | 任务项 |
|--------|--------|
| pass | 在第 129 行 `call()` 前插入 `log.info("技术分析师调用LLM \| prompt长度={}", prompt.length());` |
| pass | 将第 132 行日志改为 `log.info("技术分析师LLM响应 \| prompt长度={} \| 响应长度={} \| 耗时={}ms", prompt.length(), response.length(), latencyMs);` |
| pass | 编译验证 |

---

### 3.5 SentimentAnalystNode

**文件**：`ai-agent-study-trading/ai-agent-study-trading-domain/src/main/java/denny/ai/agent/trading/domain/node/SentimentAnalystNode.java`

**改动位置**：`generateReport()` 方法

| status | 任务项 |
|--------|--------|
| pass | 在 `call()` 前插入 `log.info("情绪分析师调用LLM \| prompt长度={}", prompt.length());` |
| pass | 将响应日志改为 `log.info("情绪分析师LLM响应 \| prompt长度={} \| 响应长度={} \| 耗时={}ms", prompt.length(), response.length(), latencyMs);` |
| pass | 编译验证 |

---

### 3.6 NewsAnalystNode

**文件**：`ai-agent-study-trading/ai-agent-study-trading-domain/src/main/java/denny/ai/agent/trading/domain/node/NewsAnalystNode.java`

**改动位置**：`generateReport()` 方法

| status | 任务项 |
|--------|--------|
| pass | 在 `call()` 前插入 `log.info("新闻分析师调用LLM \| prompt长度={}", prompt.length());` |
| pass | 将响应日志改为 `log.info("新闻分析师LLM响应 \| prompt长度={} \| 响应长度={} \| 耗时={}ms", prompt.length(), response.length(), latencyMs);` |
| pass | 编译验证 |

---

### 3.7 FundamentalAnalystNode

**文件**：`ai-agent-study-trading/ai-agent-study-trading-domain/src/main/java/denny/ai/agent/trading/domain/node/FundamentalAnalystNode.java`

**改动位置**：`generateReport()` 方法

| status | 任务项 |
|--------|--------|
| pass | 在 `call()` 前插入 `log.info("基本面分析师调用LLM \| prompt长度={}", prompt.length());` |
| pass | 将响应日志改为 `log.info("基本面分析师LLM响应 \| prompt长度={} \| 响应长度={} \| 耗时={}ms", prompt.length(), response.length(), latencyMs);` |
| pass | 编译验证 |

---

### 3.8 RecommendationNode

**文件**：`ai-agent-study-trading/ai-agent-study-trading-domain/src/main/java/denny/ai/agent/trading/domain/node/RecommendationNode.java`

**改动位置**：`generateInvestmentPlan()` 方法

| status | 任务项 |
|--------|--------|
| pass | 在第 122 行 `call()` 前插入 `log.info("推荐节点调用LLM \| prompt长度={}", prompt.length());` |
| pass | 将第 125 行日志改为 `log.info("推荐节点LLM响应 \| prompt长度={} \| 响应长度={} \| 耗时={}ms", prompt.length(), response.length(), latencyMs);` |
| pass | 编译验证 |

---

### 3.9 PortfolioManagerNode

**文件**：`ai-agent-study-trading/ai-agent-study-trading-domain/src/main/java/denny/ai/agent/trading/domain/node/PortfolioManagerNode.java`

**改动位置**：`generateDecision()` 方法

| status | 任务项 |
|--------|--------|
| pass | 在 `call()` 前插入 `log.info("组合经理调用LLM \| prompt长度={}", prompt.length());` |
| pass | 将响应日志改为 `log.info("组合经理LLM响应 \| prompt长度={} \| 响应长度={} \| 耗时={}ms", prompt.length(), response.length(), latencyMs);` |
| pass | 编译验证 |

---

### 3.10 AggressiveRiskAnalystNode

**文件**：`ai-agent-study-trading/ai-agent-study-trading-domain/src/main/java/denny/ai/agent/trading/domain/node/AggressiveRiskAnalystNode.java`

**改动位置**：`generateRiskReport()` 方法

| status | 任务项 |
|--------|--------|
| pass | 在 `call()` 前插入 `log.info("激进风控分析师调用LLM \| prompt长度={}", prompt.length());` |
| pass | 将响应日志改为 `log.info("激进风控分析师LLM响应 \| prompt长度={} \| 响应长度={} \| 耗时={}ms", prompt.length(), response.length(), latencyMs);` |
| pass | 编译验证 |

---

### 3.11 ConservativeRiskAnalystNode

**文件**：`ai-agent-study-trading/ai-agent-study-trading-domain/src/main/java/denny/ai/agent/trading/domain/node/ConservativeRiskAnalystNode.java`

**改动位置**：`generateRiskReport()` 方法

| status | 任务项 |
|--------|--------|
| pass | 在 `call()` 前插入 `log.info("保守风控分析师调用LLM \| prompt长度={}", prompt.length());` |
| pass | 将响应日志改为 `log.info("保守风控分析师LLM响应 \| prompt长度={} \| 响应长度={} \| 耗时={}ms", prompt.length(), response.length(), latencyMs);` |
| pass | 编译验证 |

---

### 3.12 NeutralRiskAnalystNode

**文件**：`ai-agent-study-trading/ai-agent-study-trading-domain/src/main/java/denny/ai/agent/trading/domain/node/NeutralRiskAnalystNode.java`

**改动位置**：`generateRiskReport()` 方法

| status | 任务项 |
|--------|--------|
| pass | 在 `call()` 前插入 `log.info("中性风控分析师调用LLM \| prompt长度={}", prompt.length());` |
| pass | 将响应日志改为 `log.info("中性风控分析师LLM响应 \| prompt长度={} \| 响应长度={} \| 耗时={}ms", prompt.length(), response.length(), latencyMs);` |
| pass | 编译验证 |

---

## 4. 日志输出示例

改动后，单次 LLM 调用的日志输出如下：

```
INFO  多头研究员调用LLM | prompt长度=2048
INFO  多头研究员LLM响应 | prompt长度=2048 | 响应长度=512 | 耗时=1234ms
```

---

## 5. 回滚方案

如需回滚，逐一还原 12 个文件中被修改的日志行即可，无新增文件，无结构变更。
