# Intent Routing 真实 LLM 在线评测需求设计

> **创建时间:** 2026-06-11  
> **状态:** draft  
> **目标范围:** 建设 `query -> routeUnified -> LLM -> routing result` 在线评测，衡量路由准确性与稳定性

---

## 1. 背景

现有 `IntentRoutingEvalTest` 使用固定 LLM response 调用
`IntentRoutingService.parseUnifiedResponse`，能够稳定验证 JSON 解析、字段归一化、执行节点映射和降级行为。

该评测属于解析契约回归，不能回答以下问题：

- 一个真实 query 是否会被路由到正确意图
- 同一个 query 多次调用是否发生意图漂移
- Prompt 或 Few-Shot 修改后，准确率是否提高或下降
- `GENERAL_CHAT`、`PE_RETRIEVAL`、`PE_REASONING` 等边界是否容易混淆
- 带历史消息的追问能否正确继承上下文

因此需要新增第二层真实 LLM 在线评测。原解析评测继续保留，两者职责不同，不互相替代。

---

## 2. 评测分层

| 层级 | 输入 | 调用对象 | 目标 | 默认执行 |
|---|---|---|---|---|
| 解析契约评测 | 固定 response | `parseUnifiedResponse` | 解析、归一化、容错回归 | 是 |
| 在线路由评测 | query + history | `routeUnified` | LLM 路由准确率、稳定率 | 否，手动开启 |
| 节点编排测试 | mock routing result | `IntentRoutingNode` | DynamicContext 和节点分支 | 是 |

本需求只新增第二层。`IntentRoutingNode` 的真实端到端执行不纳入第一版，避免股票分析、PE 执行等下游逻辑干扰路由指标。

---

## 3. 目标与非目标

### 3.1 目标

1. 使用真实用户 query 调用 `IntentRoutingService.routeUnified`
2. 使用应用实际配置的 Prompt、Few-Shot 和 ChatClient
3. 支持单条 case 重复运行，识别随机漂移
4. 统计准确率、稳定率、case 通过率和意图混淆
5. 区分模型分类错误、模型格式错误和基础设施错误
6. 输出可阅读、可留档的 JSON 与 Markdown 报告
7. 默认不进入普通 `mvn test` 在线调用
8. 数据结构为后续 Langfuse Dataset / Experiment 留出映射空间

### 3.2 非目标

- 不评测最终回答质量
- 不执行股票分析、PE、巡检等后续节点
- 不使用另一个 LLM 作为裁判
- 不在第一版自动写入 Langfuse
- 不在第一版自动修改 Prompt 或 Few-Shot
- 不把在线随机结果作为每次 CI 构建的硬门禁

---

## 4. 评测入口

### 4.1 代码位置

在线评测需要 Spring、数据库中的流程配置和启动后装配的 ChatClient，建议放在 `ai-agent-study-app`：

```text
ai-agent-study-app/src/test/java/
  denny/ai/agent/test/eval/routing/
    IntentRoutingOnlineEvalTest.java
    IntentRoutingOnlineEvalCase.java
    IntentRoutingOnlineEvalCaseLoader.java
    IntentRoutingOnlineEvaluator.java
    IntentRoutingOnlineEvalReportWriter.java

ai-agent-study-app/src/test/resources/eval/
  intent-routing-online-cases.json
```

### 4.2 被测链路

```text
online case
  -> query + historyMessages
  -> IntentRoutingService.routeUnified
  -> IntentRoutingPrompt.buildUnifiedRoutingPrompt
  -> IntentFewshotService.retrieveTopK
  -> configured ChatClient
  -> parseUnifiedResponse
  -> MultiIntentRoutingResult
  -> evaluator
```

### 4.3 路由客户端配置

当前意图路由节点使用的 `clientId` 已明确为 `3201`。在线评测直接构造路由配置：

```java
AiAgentClientFlowConfigVO config = AiAgentClientFlowConfigVO.builder()
        .clientId(clientId)
        .clientType(AiClientTypeEnumVO.INTENT_ROUTING.getCode())
        .build();
```

默认值为 `3201`，同时允许通过系统属性或环境变量覆盖，便于后续做模型或 Prompt A/B：

```text
intent.routing.eval.client-id=3201
INTENT_ROUTING_EVAL_CLIENT_ID=3201
```

该设计不依赖 `agentId` 查询流程配置。Spring 应用启动后，`AiAgentAutoConfiguration` 已根据
`spring.ai.agent.auto-config.client-ids` 装配 `3201` 对应的 ChatClient，在线评测只需在执行前确认注册表中存在：

```text
ai_client_3201taskType0
```

---

## 5. 在线评测 Case Schema

### 5.1 推荐结构

```json
{
  "caseId": "online-general-boundary-001",
  "enabled": true,
  "suite": "boundary",
  "category": "single-task",
  "description": "普通知识问答不应误路由到 PE_RETRIEVAL",
  "input": {
    "query": "Java 里的 HashMap 为什么线程不安全？",
    "historyMessages": []
  },
  "expected": {
    "multiTask": false,
    "needsClarification": false,
    "taskIntents": ["GENERAL_CHAT"],
    "acceptableTaskIntents": [],
    "orderSensitive": true
  },
  "evaluation": {
    "runs": 5,
    "minPassRate": 0.8,
    "minConsistencyRate": 0.8
  },
  "tags": ["general-vs-retrieval", "boundary", "chinese"]
}
```

### 5.2 字段说明

| 字段 | 含义 |
|---|---|
| `caseId` | 全局唯一标识 |
| `enabled` | 是否参与执行 |
| `suite` | `smoke`、`mainline`、`boundary`、`history`、`multitask` 等执行分组 |
| `category` | `single-task`、`multi-task`、`clarification` |
| `input.query` | 真实用户输入 |
| `input.historyMessages` | 传给 `routeUnified` 的历史消息，保持 `role: content` 格式 |
| `expected.taskIntents` | 首选、严格期望的任务意图列表 |
| `acceptableTaskIntents` | 业务认可的备选结果，默认空；不能用于掩盖普通误判 |
| `orderSensitive` | 多任务是否要求任务顺序一致，默认 `true` |
| `missingInfoContains` | 澄清场景必须包含的稳定缺失槽位，可选 |
| `missingInfoNotEmpty` | 只要求缺失信息非空，适用于开放式模糊表达 |
| `evaluation.runs` | 本 case 重复调用次数 |
| `minPassRate` | 正确运行次数占比门槛 |
| `minConsistencyRate` | 众数结果占全部运行次数的比例门槛 |
| `tags` | 筛选、报告聚合和 Langfuse 映射标签 |

### 5.3 澄清场景

```json
{
  "expected": {
    "multiTask": false,
    "needsClarification": true,
    "taskIntents": [],
    "missingInfoContains": ["stockCode"]
  }
}
```

澄清文案不做完整字符串相等，只断言 `needsClarification` 和稳定的缺失槽位，避免自然语言变化造成脆弱失败。

---

## 6. 判定规则

### 6.1 单次运行正确条件

单任务 case：

1. `needsClarification` 与期望一致
2. `multiTask` 与期望一致
3. 实际 task intent 与 `taskIntents` 一致，或命中明确配置的 `acceptableTaskIntents`
4. task 数量一致

多任务 case：

- `orderSensitive=true`：按顺序完整匹配 intent 列表
- `orderSensitive=false`：按多重集合匹配，重复 intent 数量仍需一致

澄清 case：

- `needsClarification=true`
- `missingInfo` 包含 `missingInfoContains`
- 不要求 clarification 文案逐字一致

### 6.2 输出签名

每次结果转换为稳定签名，用于计算一致率：

```text
ROUTE|single|GENERAL_CHAT
ROUTE|multi|PE_RETRIEVAL,PE_REASONING
CLARIFICATION|stockCode
FORMAT_ERROR|JSON_PARSE
INFRA_ERROR|LLM_CALL
```

### 6.3 Case 指标

```text
passRate = 正确运行次数 / 有效运行次数
consistencyRate = 出现次数最多的结果签名数 / 有效运行次数
```

case 通过条件：

```text
passRate >= minPassRate
AND consistencyRate >= minConsistencyRate
AND infrastructureErrorCount = 0
```

典型主线 case 建议：`runs=3`、两个门槛均为 `1.0`。  
容易漂移的边界 case 建议：`runs=5`、两个门槛均为 `0.8`。

### 6.4 数据集指标

- `casePassRate`：通过 case 数 / 执行 case 数
- `runAccuracy`：正确运行数 / 有效运行数
- `perIntentAccuracy`：按首选 intent 分组的运行准确率
- `clarificationAccuracy`：澄清 case 正确率
- `multiTaskExactMatch`：多任务完整匹配率
- `formatErrorRate`：空响应、非法 JSON、空 taskList 等比例
- `infrastructureErrorRate`：网络、鉴权、ChatClient 未初始化等比例
- `confusionMatrix`：期望单意图与实际单意图的混淆矩阵

第一版全局建议门槛：

```text
casePassRate >= 0.90
runAccuracy >= 0.90
formatErrorRate <= 0.02
infrastructureErrorRate = 0
```

全局门槛可以通过启动参数覆盖，便于建立第一版基线后逐步收紧。

---

## 7. 错误分类

`routeUnified` 当前会把异常降级成 `GENERAL_CHAT`。在线评测不能将所有降级都当成普通分类结果。

| 类型 | 示例 | 统计方式 |
|---|---|---|
| 分类错误 | 期望 GENERAL_CHAT，实际 PE_REASONING | 计入错误与混淆矩阵 |
| 模型格式错误 | 空响应、JSON 解析失败、taskList 为空 | 计入错误和 `formatErrorRate` |
| 基础设施错误 | ChatClient 未装配、超时、鉴权失败、网络异常 | 单独计入 `infrastructureErrorRate`，本次评测失败 |

MVP 可先根据 `MultiIntentRoutingResult.reasoning` 中现有降级原因分类：

- `LLM调用异常`：基础设施错误
- `LLM返回为空`：模型格式错误
- `JSON解析失败`：模型格式错误
- `taskList为空`：模型格式错误

后续若需要原始响应和精确 token、latency、model 信息，再引入独立 observation 对象或从 Langfuse trace 获取，不建议第一版为测试修改生产返回契约。

---

## 8. 数据集覆盖设计

第一版建议 24 至 30 条 query，约 80 至 100 次真实模型调用。

| 分组 | 建议数量 | 重点 |
|---|---:|---|
| 主线单意图 | 12 | 6 类可执行意图每类至少 2 条 |
| 边界混淆 | 8 | GENERAL/RETRIEVAL、RETRIEVAL/REASONING、REASONING/CALCULATION、金融知识/股票分析 |
| 多任务 | 3 | 同类多任务、跨类多任务、任务顺序 |
| 澄清 | 3 | 股票标的缺失、检索主题缺失、模糊指代 |
| 历史上下文 | 4 | 追问、省略主语、上下文改变意图、无关历史干扰 |

每类数据应同时包含：

- 标准表达
- 口语表达
- 短句
- 错别字或不完整表达
- 否定表达
- 容易被关键词误导的反例

数据集不能只放 Prompt 中已经出现的同义句，避免形成“背题式评测”。

### 8.1 执行规模

第一版固定为 30 条 case，共 94 次真实模型调用：

| 分组 | Case 数 | 每条运行次数 | 调用数 |
|---|---:|---:|---:|
| 主线单意图 | 12 | 2 | 24 |
| 边界混淆 | 8 | 5 | 40 |
| 多任务 | 3 | 3 | 9 |
| 澄清 | 3 | 3 | 9 |
| 历史上下文 | 4 | 3 | 12 |
| **合计** | **30** | - | **94** |

以下 `pass/consistency` 表示 `minPassRate/minConsistencyRate`。

### 8.2 主线单意图用例（12 条）

本节所有 case 的 `category=single-task`。

| caseId | suite | Query | 期望结果 | runs | pass/consistency | 设计目的 |
|---|---|---|---|---:|---|---|
| `online-main-general-001` | smoke | `你好，今天过得怎么样？` | `GENERAL_CHAT`，单任务，不澄清 | 2 | `1.0/1.0` | 验证标准问候 |
| `online-main-general-002` | mainline | `用通俗的话解释一下什么是线程池。` | `GENERAL_CHAT`，单任务，不澄清 | 2 | `1.0/1.0` | 概念解释应进入普通问答，不误判为检索 |
| `online-main-retrieval-001` | smoke | `请从公司知识库检索 RAG 架构相关文档，并汇总核心设计原则。` | `PE_RETRIEVAL`，单任务，不澄清 | 2 | `1.0/1.0` | 明确知识库检索 |
| `online-main-retrieval-002` | mainline | `查找内部文档中关于向量数据库选型的资料，整理成对比摘要。` | `PE_RETRIEVAL`，单任务，不澄清 | 2 | `1.0/1.0` | 文档检索与汇总 |
| `online-main-reasoning-001` | smoke | `为一个日活十万的 SaaS 系统设计灰度发布方案，并说明关键取舍。` | `PE_REASONING`，单任务，不澄清 | 2 | `1.0/1.0` | 方案设计与权衡分析 |
| `online-main-reasoning-002` | mainline | `分析新用户次日留存突然下降的可能原因，并给出系统化排查框架。` | `PE_REASONING`，单任务，不澄清 | 2 | `1.0/1.0` | 原因分析与推理框架 |
| `online-main-calculation-001` | smoke | `本金 10 万元，年化收益率 8%，按年复利，5 年后本息是多少？` | `PE_CALCULATION`，单任务，不澄清 | 2 | `1.0/1.0` | 明确数值计算 |
| `online-main-calculation-002` | mainline | `访问用户 12000 人，注册 2400 人，付费 360 人，请计算注册率和付费转化率。` | `PE_CALCULATION`，单任务，不澄清 | 2 | `1.0/1.0` | 数据处理和比例计算 |
| `online-main-stock-001` | smoke | `分析一下贵州茅台 600519 最近三个月的技术走势。` | `STOCK_ANALYSIS`，单任务，不澄清 | 2 | `1.0/1.0` | A 股标的与技术分析 |
| `online-main-stock-002` | mainline | `从基本面角度看看腾讯控股 0700.HK 现在是否值得长期关注。` | `STOCK_ANALYSIS`，单任务，不澄清 | 2 | `1.0/1.0` | 港股标的与基本面分析 |
| `online-main-inspection-001` | smoke | `检查支付服务当前是否健康。` | `INSPECTION`，单任务，不澄清 | 2 | `1.0/1.0` | 明确的单目标健康检查 |
| `online-main-inspection-002` | mainline | `帮我检察一下生产环境，重点看看 CPU、内存、磁盘和接口错误率。` | `INSPECTION`，单任务，不澄清 | 2 | `1.0/1.0` | 口语和错别字下的运维巡检 |

### 8.3 边界混淆用例（8 条）

本节所有 case 的 `suite=boundary`、`category=single-task`。

| caseId | Query | 期望结果 | 主要防止的误判 | runs | pass/consistency |
|---|---|---|---|---:|---|
| `online-boundary-general-retrieval-001` | `什么是向量数据库，它适合解决什么问题？` | `GENERAL_CHAT` | 概念解释误判为 `PE_RETRIEVAL` | 5 | `0.8/0.8` |
| `online-boundary-general-retrieval-002` | `Java 里的 HashMap 为什么线程不安全？` | `GENERAL_CHAT` | 简单知识问答误判为检索或推理 | 5 | `0.8/0.8` |
| `online-boundary-retrieval-general-001` | `请从公司知识库中检索 HashMap 并发问题的技术规范和历史故障记录。` | `PE_RETRIEVAL` | 明确内部检索被当成普通问答 | 5 | `0.8/0.8` |
| `online-boundary-retrieval-reasoning-001` | `结合三份上传的故障复盘文档，汇总共同根因和已有改进措施。` | `PE_RETRIEVAL` | 多文档汇总误判为纯推理 | 5 | `0.8/0.8` |
| `online-boundary-reasoning-retrieval-001` | `假设缓存命中率下降到 40%，请推演它为什么可能引发数据库雪崩，并给出验证思路。` | `PE_REASONING` | 推演分析误判为知识检索 | 5 | `0.8/0.8` |
| `online-boundary-calculation-reasoning-001` | `订单量从 8 万增长到 12 万，服务器从 20 台增加到 25 台，请计算单机负载变化比例。` | `PE_CALCULATION` | 明确计算误判为推理 | 5 | `0.8/0.8` |
| `online-boundary-general-stock-001` | `我不是要分析某只股票，只想知道什么是市盈率，它高了通常意味着什么？` | `GENERAL_CHAT` | 否定限定下的金融概念解释误判为股票分析 | 5 | `0.8/0.8` |
| `online-boundary-stock-general-001` | `贵州茅台现在的估值是否偏高，结合它的基本面给出投资分析。` | `STOCK_ANALYSIS` | 个股投资分析误判为普通金融问答 | 5 | `0.8/0.8` |

### 8.4 多任务用例（3 条）

多任务 case 默认 `orderSensitive=true`，要求任务数量和意图顺序完整匹配。
本节所有 case 的 `suite=multitask`、`category=multi-task`。

| caseId | Query | 期望 taskIntents | runs | pass/consistency | 设计目的 |
|---|---|---|---:|---|---|
| `online-multi-stock-001` | `分别分析贵州茅台 600519 和比亚迪 002594 最近一个月的走势。` | `[STOCK_ANALYSIS, STOCK_ANALYSIS]` | 3 | `0.67/0.67` | 同类多实体应拆为两个股票任务 |
| `online-multi-retrieval-reasoning-001` | `先从知识库检索微服务限流方案，再结合我们日均百万请求的场景设计选型建议。` | `[PE_RETRIEVAL, PE_REASONING]` | 3 | `0.67/0.67` | 检索和方案推理的跨类拆分 |
| `online-multi-inspection-calculation-001` | `先检查支付服务当前健康状态，再根据过去 24 小时 120 次失败和 60000 次请求计算错误率。` | `[INSPECTION, PE_CALCULATION]` | 3 | `0.67/0.67` | 运维检查和数值计算的跨类拆分 |

### 8.5 澄清用例（3 条）

`missingInfoContains` 只用于 Prompt 已有稳定槽位名的场景。对于开放式模糊表达，使用
`missingInfoNotEmpty=true`，避免把 `topic`、`target`、`context` 等同义槽位差异当成路由错误。
本节所有 case 的 `suite=clarification`、`category=clarification`。

| caseId | Query | 期望结果 | 缺失信息断言 | runs | pass/consistency |
|---|---|---|---|---:|---|
| `online-clarification-stock-001` | `帮我分析一下这只股票最近的走势。` | `needsClarification=true`，无执行任务 | `missingInfoContains=[stockCode]` | 3 | `1.0/1.0` |
| `online-clarification-retrieval-001` | `帮我从知识库查一下相关资料。` | `needsClarification=true`，无执行任务 | `missingInfoContains=[topic]` | 3 | `0.67/0.67` |
| `online-clarification-ambiguous-001` | `帮我看看这个方案到底行不行。` | `needsClarification=true`，无执行任务 | `missingInfoNotEmpty=true` | 3 | `0.67/0.67` |

对应 schema 增加可选字段：

```json
{
  "expected": {
    "needsClarification": true,
    "missingInfoContains": ["stockCode"],
    "missingInfoNotEmpty": true
  }
}
```

### 8.6 历史上下文用例（4 条）

历史消息按生产代码实际格式传入：`role: content`。
本节所有 case 的 `suite=history`、`category=single-task`。

| caseId | historyMessages | 当前 Query | 期望结果 | runs | pass/consistency | 设计目的 |
|---|---|---|---|---:|---|---|
| `online-history-stock-followup-001` | `user: 分析一下贵州茅台最近的技术走势`<br>`assistant: 已完成贵州茅台的分析` | `那腾讯控股呢？` | `STOCK_ANALYSIS`，单任务 | 3 | `0.67/0.67` | 追问继承股票分析意图和新标的 |
| `online-history-retrieval-followup-001` | `user: 从知识库检索微服务治理相关文档`<br>`assistant: 已整理出相关文档摘要` | `再把这些文档里的风险点汇总一下。` | `PE_RETRIEVAL`，单任务 | 3 | `0.67/0.67` | 代词指向历史检索文档 |
| `online-history-reasoning-followup-001` | `user: 我们准备把单体系统拆成微服务，请给出初步方案`<br>`assistant: 可以从领域边界、数据拆分和发布方式三个方面设计` | `如果团队只有 3 个人，应该怎么取舍？` | `PE_REASONING`，单任务 | 3 | `0.67/0.67` | 当前追问依赖历史方案上下文 |
| `online-history-current-input-wins-001` | `user: 北京今天气温怎么样？`<br>`assistant: 今天气温约 25 度` | `现在帮我检查支付服务是否健康。` | `INSPECTION`，单任务 | 3 | `1.0/1.0` | 无关历史不应覆盖当前明确意图 |

### 8.7 完整 JSON 示例

用例表落盘时统一转换为以下结构：

```json
{
  "caseId": "online-boundary-general-retrieval-001",
  "enabled": true,
  "suite": "boundary",
  "category": "single-task",
  "description": "概念解释不应误路由到知识检索",
  "input": {
    "query": "什么是向量数据库，它适合解决什么问题？",
    "historyMessages": []
  },
  "expected": {
    "multiTask": false,
    "needsClarification": false,
    "taskIntents": ["GENERAL_CHAT"],
    "acceptableTaskIntents": [],
    "orderSensitive": true
  },
  "evaluation": {
    "runs": 5,
    "minPassRate": 0.8,
    "minConsistencyRate": 0.8
  },
  "tags": ["general-vs-retrieval", "boundary", "concept-explanation"]
}
```

### 8.8 首版 Baseline 规则

上述期望结果是业务标准，不因为模型首次运行结果而修改。首次真实执行后允许调整的只有：

- case 文字是否存在客观歧义
- 重复运行次数
- `minPassRate` 和 `minConsistencyRate`
- 是否将明确合理的第二种结果加入 `acceptableTaskIntents`

不得仅为了让评测通过，把明显误判加入 acceptable 列表。所有期望调整都需要在文档或提交记录中说明业务理由。

---

## 9. 执行控制

### 9.1 默认跳过

测试类启动时检查：

```text
intent.routing.online.eval.enabled=true
```

未开启时使用 JUnit Assume 跳过，不产生模型调用。这样测试类可以保留 `*Test` 命名，同时不会污染普通构建。

### 9.2 推荐命令

```powershell
mvn -pl ai-agent-study-app -am `
  "-Dtest=IntentRoutingOnlineEvalTest" `
  "-Dintent.routing.online.eval.enabled=true" `
  "-Dintent.routing.eval.client-id=3201" `
  test
```

筛选 smoke suite：

```powershell
mvn -pl ai-agent-study-app -am `
  "-Dtest=IntentRoutingOnlineEvalTest" `
  "-Dintent.routing.online.eval.enabled=true" `
  "-Dintent.routing.eval.suite=smoke" `
  test
```

### 9.3 并发与重试

- MVP 默认串行执行，避免限流和结果交错
- 基础设施错误不自动伪装成分类重试
- 可配置一次技术重试，但报告必须保留初次错误
- 模型分类错误不重试，否则会人为美化准确率

---

## 10. 前置检查

正式执行数据集前必须一次性验证：

1. 在线评测开关已开启
2. 数据集非空，caseId 唯一
3. `clientId` 非空，默认值为 `3201`
4. 自动装配配置包含 `3201`
5. `ArmoryObjectRegistry` 中存在 `ai_client_3201taskType0`
6. 每条 case 的 intent、runs 和阈值合法
7. `runs >= 1`
8. `minPassRate`、`minConsistencyRate` 位于 `[0, 1]`

任一前置条件失败时立即终止，不生成“模型准确率为 0”的误导报告。

---

## 11. 报告设计

输出目录：

```text
ai-agent-study-app/target/eval-reports/intent-routing/
  2026-06-11T120000-summary.json
  2026-06-11T120000-report.md
```

报告至少包含：

- 执行时间、clientId、suite
- case 数和真实调用次数
- 全局指标
- 各 intent 指标
- 混淆矩阵
- 每条 case 的所有运行签名、耗时、reasoning
- 未通过 case 汇总
- 格式错误与基础设施错误详情

控制台只输出摘要和失败 case，完整明细写入报告，避免日志难以阅读。

---

## 12. 测试组织方式

在线评测建议使用一个聚合测试方法执行完整数据集，而不是每个 run 一个 JUnit 断言：

```java
@Test
public void runIntentRoutingOnlineEvaluation() {
    assumeOnlineEvalEnabled();
    preflight();
    EvalReport report = evaluator.evaluate(cases);
    reportWriter.write(report);
    assertGlobalAndCaseThresholds(report);
}
```

原因：

- 即使前面的 case 失败，也要继续收集后续结果
- 可以统一生成混淆矩阵和完整报告
- 最后一次性给出所有失败 case，而不是首错即停

另建一个不调用 LLM 的 `IntentRoutingOnlineEvalDatasetTest`，默认执行并负责 schema、唯一性和覆盖度检查。

---

## 13. 验收标准

### 13.1 功能验收

- [ ] 普通测试默认不发起真实 LLM 调用
- [ ] 开启开关后可以读取在线评测集并执行 `routeUnified`
- [ ] 每条 case 支持独立 runs 和阈值
- [ ] 支持 single、multi、clarification 三类结果判定
- [ ] 能计算准确率、稳定率和混淆矩阵
- [ ] 能区分分类错误、格式错误、基础设施错误
- [ ] 失败后仍完成整套评测并生成报告
- [ ] 支持 suite/tag 筛选
- [ ] JSON 与 Markdown 报告均能生成

### 13.2 数据验收

- [ ] 第一版不少于 24 条 query
- [ ] 6 类可执行意图均有覆盖
- [ ] 至少 8 条边界混淆 case
- [ ] 至少 3 条多任务 case
- [ ] 至少 3 条澄清 case
- [ ] 至少 4 条历史上下文 case

### 13.3 质量验收

- [ ] 数据集结构自检全部通过
- [ ] 基础设施错误率为 0
- [ ] 输出报告包含全部运行明细
- [ ] 首次执行结果保存为 baseline，不在首次运行前武断收紧阈值

---

## 14. 实施阶段

### Phase 1：MVP

1. 新增在线 case schema、loader 和 dataset test
2. 新增 Spring Boot 在线 evaluator
3. 串行执行、重复运行、阈值判定
4. 输出 JSON/Markdown 报告
5. 建设首批 30 条 query
6. 执行并保存第一版 baseline

### Phase 2：诊断增强

1. 记录 prompt 版本、模型名、token、成本
2. 从失败结果生成可回填 case
3. 支持 baseline diff 和 Prompt A/B
4. 接入 Langfuse Dataset / Experiment
5. 从线上 trace 自动沉淀高价值失败样本

---

## 15. 核心决策总结

1. 当前解析评测保留，它负责代码契约，不负责模型质量
2. 在线评测直接调用 `routeUnified`，不执行下游业务节点
3. 同一 query 重复运行，同时衡量正确率和一致率
4. 在线评测默认跳过，只允许显式执行
5. 基础设施失败与模型判错分开统计
6. 第一版先建立可信 baseline，再根据实际结果收紧门槛
7. 本地 JSON 是数据源，结构保持与未来 Langfuse Dataset 可映射
