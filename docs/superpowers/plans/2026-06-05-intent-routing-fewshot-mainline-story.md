# 2026-06-05 IntentRoutingNode 接入 Few-Shot 主链 Story

status: pass
owner: Cursor Agent
created_at: 2026-06-05

## 1. 背景

status: pass

当前仓库中，`IntentRoutingService` 已具备基于 `IntentFewshotService.retrieveTopK(...)` 的 Few-Shot 检索与 Prompt 拼装能力，但 `IntentRoutingNode` 主流程优先走 `buildMultiTaskDecomposePrompt(...)` 的多任务分解分支，未直接复用 Few-Shot 检索结果。

已验证事实：
- `intentFewshotVectorStore` 已切换到新表 `intent_fewshot_vector_store`
- `OpenAiTest.test_intent_fewshot_pgvector_recall()` 可成功写入并召回样本
- `IntentRoutingService.route(...)` 会先检索 Few-Shot，再构造意图识别 Prompt
- `IntentRoutingNode.doApply(...)` 当前调用 `doMultiTaskRouting(...)`，其 Prompt 未注入 Few-Shot 示例

这导致系统存在“Few-Shot 能力存在，但主路由链未真正使用”的能力断层。

## 2. 目标

status: pass

将 Few-Shot 检索正式接入 `IntentRoutingNode` 主链路，使用户消息在进入任务拆分 / 意图路由前，先完成 Few-Shot Top-K 检索，并将检索到的示例注入统一路由 Prompt，最终让模型一次性输出：
- 是否多任务（`multiTask`）
- 是否需要补全（`needsClarification`）
- 子任务列表（`taskList`）
- 单任务场景下的意图、置信度、基础槽位、意图专属槽位

## 3. 设计原则

status: pass

### 3.1 主流程原则

status: pass

推荐采用“**先 Few-Shot 检索，再统一路由判断**”的方式，而不是把 Few-Shot 仅挂在单任务支路上。

推荐主流程如下：

```text
用户消息
-> 查询最近历史消息
-> Few-Shot 向量检索 Top-K
-> 构造统一路由 Prompt（含 history + few-shot）
-> LLM 输出 unified routing JSON
-> 根据结果执行：补全 / 多任务分发 / 单任务路由
```

### 3.2 职责边界原则

status: pass

- `IntentRoutingNode` 负责流程控制、DynamicContext 写入、下游节点路由
- `IntentRoutingService` 负责 Few-Shot 检索、Prompt 构造、LLM 调用、响应解析与降级
- `IntentRoutingPrompt` 负责统一路由 Prompt 模板与 Few-Shot 示例拼装

### 3.3 兼容性原则

status: pass

- 保留现有 `IntentRoutingResult` / `MultiIntentRoutingResult` 作为兼容输出模型
- 尽量避免一次性删除现有单任务方法，优先新增统一路由能力后再迁移调用方
- Few-Shot 检索失败时必须安全降级为空样本，不影响主链可用性

## 4. 变更范围

status: pass

### 4.1 计划修改文件

status: pass

1. `ai-agent-study-domain/src/main/java/denny/ai/agent/domain/service/auto/step/routing/IntentRoutingPrompt.java`
2. `ai-agent-study-domain/src/main/java/denny/ai/agent/domain/service/auto/step/routing/IntentRoutingService.java`
3. `ai-agent-study-domain/src/main/java/denny/ai/agent/domain/service/auto/step/routing/IntentRoutingNode.java`
4. `ai-agent-study-domain/src/test/java/denny/ai/agent/domain/service/auto/step/routing/IntentRoutingServiceTest.java`
5. `ai-agent-study-domain/src/test/java/denny/ai/agent/domain/service/auto/step/routing/IntentRoutingNodeTest.java`

### 4.2 非目标范围

status: pass

以下内容不在本次 Story 范围内：
- 调整 `intent_fewshot_vector_store` 表结构
- 改造 `IntentFewshotService` 的 CRUD 能力
- 修改 `Step1AnalyzerNode` / `GeneralChatNode` / `IntelligentInspection` 下游执行逻辑
- 引入新的数据库表或新的远程依赖

## 5. 详细方案

status: pass

### 5.1 Prompt 层：新增统一路由 Prompt

status: pass

在 `IntentRoutingPrompt` 中新增统一方法，例如：

```java
public static String buildUnifiedRoutingPrompt(
        String userMessage,
        List<String> historyMessages,
        List<IntentFewshotSample> fewshotSamples)
```

该 Prompt 需要同时覆盖：
- 多任务识别
- 信息缺失判断
- 单任务意图分类
- 置信度输出
- 基础槽位输出
- 意图专属槽位输出

#### 输出建议

status: pass

优先建议使用**统一 `taskList` 输出模式**，避免额外新增 `singleTask` 根节点。

当 `multiTask=false` 时：
- `taskList` 仅保留 1 个任务
- 该任务包含 `intent`、`confidence`、`slots`

当 `multiTask=true` 时：
- `taskList` 包含多个子任务
- 每个子任务都明确 `executorNode`

建议输出结构示例：

```json
{
  "multiTask": false,
  "needsClarification": false,
  "missingInfo": [],
  "clarificationPrompt": "",
  "reasoning": "用户在询问向量数据库概念，属于知识检索类任务",
  "taskList": [
    {
      "taskId": "sub-1",
      "taskIndex": 1,
      "totalTasks": 1,
      "content": "解释向量数据库概念",
      "intent": "PE_RETRIEVAL",
      "executorNode": "step1AnalyzerNode",
      "confidence": "HIGH",
      "taskType": 0,
      "slots": {
        "topic": "向量数据库"
      }
    }
  ]
}
```

#### Few-Shot 注入方式

status: pass

在 Prompt 中增加 `## 参考示例` 段落，沿用现有 Few-Shot 样本格式：
- `用户: <queryText>`
- `输出: <exampleJson>`

示例应位于系统规则之后、用户输入之前，以便模型在统一路由判断前参考语义近邻样本。

### 5.2 Service 层：升级为统一路由服务

status: pass

在 `IntentRoutingService` 中新增统一路由方法，例如：

```java
public MultiIntentRoutingResult routeUnified(String userMessage, List<String> historyMessages)
```

该方法内部职责：
1. 调用 `retrieveFewshotSamples(userMessage)` 获取 Top-K 样本
2. 调用 `IntentRoutingPrompt.buildUnifiedRoutingPrompt(...)` 构造 Prompt
3. 使用 `chatClient` 发起 LLM 调用
4. 解析统一 JSON 响应
5. 异常时进行降级

#### 解析策略

status: pass

新增统一响应解析逻辑，例如：
- `parseUnifiedResponse(String response)`

解析结果映射到 `MultiIntentRoutingResult`：
- `multiTask`
- `needsClarification`
- `missingInfo`
- `clarificationPrompt`
- `reasoning`
- `taskList`

对于 `multiTask=false` 且 `taskList` 为空的异常情况，统一降级为单任务 `GENERAL_CHAT` fallback。

#### 兼容策略

status: pass

- 原有 `route(...)` / `doRoute(...)` / `parseResponse(...)` 暂不立即删除
- 新主流程优先切换到 `routeUnified(...)`
- 旧单任务方法可暂作为兼容能力保留，待主链稳定后再决定是否下线

### 5.3 Node 层：接入统一路由主链

status: pass

修改 `IntentRoutingNode.doApply(...)`：
- 不再直接在 Node 内部拼装 `buildMultiTaskDecomposePrompt(...)`
- 改为调用 `intentRoutingService.routeUnified(request.getMessage(), historyMessages)`

Node 继续保留以下职责：
- 获取历史消息
- 记录耗时与日志
- 根据 `needsClarification` 返回追问
- 根据 `multiTask` 决定是否进入 `MultiTaskExecutionNode`
- 单任务场景下将结果写入 `DynamicContext`
- 根据识别意图选择对应执行节点

#### 单任务上下文映射

status: pass

当 `multiTask=false` 时：
- 取 `taskList[0]` 作为单任务结果来源
- 将其映射为 `IntentRoutingResult`
- 写入：
  - `ROUTING_RESULT_KEY`
  - `RECOGNIZED_INTENT_KEY`
  - `BASE_SLOT_KEY`
  - `INTENT_SPECIFIC_SLOTS_KEY`

如果任务中存在股票类槽位，则继续填充 `STOCK_SLOT_KEY`，保持现有下游兼容性。

### 5.4 测试层：补齐主链覆盖

status: pass

#### `IntentRoutingServiceTest`

status: pass

新增测试覆盖：
1. 统一路由 Prompt 包含 Few-Shot 示例
2. 单任务 unified JSON 解析成功
3. 多任务 unified JSON 解析成功
4. `needsClarification=true` 场景解析成功
5. LLM 返回非法 JSON 时降级成功
6. Few-Shot 检索异常时降级为空样本仍可执行

#### `IntentRoutingNodeTest`

status: pass

新增测试覆盖：
1. `doApply()` 调用统一路由服务而非旧多任务 Prompt 分支
2. `multiTask=true` 时写入 `TASK_LIST_KEY`
3. `needsClarification=true` 时返回补全提示
4. 单任务 `PE_RETRIEVAL` / `STOCK_ANALYSIS` / `GENERAL_CHAT` 路由正确
5. 股票槽位映射仍正确写入 `STOCK_SLOT_KEY`

## 6. 风险与应对

status: pass

### 风险 1：统一 Prompt 输出格式不稳定

status: pass

应对：
- 保持输出 JSON 结构尽量简单
- 尽量复用现有 `taskList` 模式
- 解析时容忍字段缺失，并提供明确 fallback

### 风险 2：Few-Shot 示例干扰多任务拆分

status: pass

应对：
- Few-Shot 示例数量限制为 Top-K（默认 5）
- 示例内容聚焦“query -> structured output”模式
- 若实测发现噪声过大，可在 Prompt 中强调“示例仅供参考，仍以当前输入为准”

### 风险 3：Node / Service 职责继续耦合

status: pass

应对：
- 明确 Service 负责路由判断与解析
- Node 仅负责流程编排与上下文落盘
- 后续不再在 Node 中直接拼装 routing Prompt

## 7. 验收标准

status: pass

满足以下条件视为通过：

1. `IntentRoutingNode` 主链已通过统一路由服务执行 Few-Shot 检索增强
2. Few-Shot 检索结果已进入统一路由 Prompt
3. 单任务、 多任务、补全三类场景均可正确解析
4. 现有下游节点路由逻辑保持兼容
5. 新增/修改单测通过
6. `OpenAiTest.test_intent_fewshot_pgvector_recall()` 继续可通过，作为向量底座验证

## 8. 实施任务清单

status: pass

- [x] 任务 1：为统一路由设计 Prompt 结构并固化 JSON 契约
  - status: pass
- [x] 任务 2：在 `IntentRoutingPrompt` 中新增 Few-Shot 统一路由 Prompt 构造方法
  - status: pass
- [x] 任务 3：在 `IntentRoutingService` 中新增统一路由方法与解析逻辑
  - status: pass
- [x] 任务 4：将 `IntentRoutingNode` 主流程切换到统一路由服务
  - status: pass
- [x] 任务 5：补充 `IntentRoutingServiceTest` 统一路由测试
  - status: pass
- [x] 任务 6：补充 `IntentRoutingNodeTest` 主链测试
  - status: pass
- [x] 任务 7：执行编译与测试验证，修正问题后更新各任务状态
  - status: pass

## 9. 备注

status: pass

本 Story 聚焦于“让 Few-Shot 真正进入 `IntentRoutingNode` 主链”的结构性改造，不追求一次性完成所有路由模型优化。若本次统一主链稳定，可在后续 Story 中继续优化：
- 示例筛选策略
- Prompt 压缩策略
- 多任务与单任务不同模板的 A/B 对比
- 路由质量评估与可观测性埋点
