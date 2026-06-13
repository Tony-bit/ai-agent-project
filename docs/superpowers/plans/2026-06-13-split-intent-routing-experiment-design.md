# Query 拆解与意图切槽分离路由实验设计

> **创建时间:** 2026-06-13  
> **修订时间:** 2026-06-13  
> **状态:** reviewed draft  
> **目标范围:** 在现有统一意图路由链路旁新增一条两阶段拆分链路，用于后续对比路由准确率、耗时与 token 消耗。本期先完成可切换的功能链路、原始指标采集和单元测试，不建设完整评测体系。

---

## 1. 背景

当前自动意图路由采用统一路由节点。当用户未显式传入 aiAgentId 时，RootNode 进入 IntentRoutingNode，由 IntentRoutingService.routeUnified 通过一次 LLM 调用完成：

1. 判断 query 是否需要拆解为多任务。
2. 识别每个任务的意图。
3. 抽取关键槽位。
4. 判断是否需要澄清。
5. 选择 executorNode。

统一调用链路短、调用次数少，但拆解、意图识别和槽位填充集中在同一个 prompt 中，不便于独立观察各阶段的准确性、耗时和 token 消耗。

本需求新增一条拆分式实验链路：

1. 第一阶段只负责 query 拆解。
2. 第二阶段对拆解后的每个任务串行执行意图识别和槽位填充。
3. 最终组装为与现有链路兼容的 MultiIntentRoutingResult。
4. 通过配置开关选择统一链路或拆分链路。

---

## 2. 目标与非目标

### 2.1 本期目标

1. 新增 QueryDecompositionNode 和 TaskRoutingSlotNode，将 query 拆解与意图切槽拆成两个节点。
2. 新增独立的阶段间模型，避免第一阶段复用包含最终执行语义的 SubTask。
3. 由 RootNode.get() 根据配置选择统一链路或拆分链路。
4. 两条链路最终输出统一的 MultiIntentRoutingResult 和 DynamicContext 数据。
5. 抽取 RoutingResultHandler，共享最终结果落地和下游分发逻辑。
6. 新增 TaskGraphValidator，同时校验 unified 和 split 产生的任务图。
7. 采集统一口径的总耗时、阶段耗时和 token 使用量。
8. 保持现有统一链路为默认链路。

### 2.2 本期非目标

1. split 链路不处理澄清，不针对缺少必要信息的不完整 query 做特殊处理。
2. 不回退到 unified 链路，避免实验结果被另一条链路修正。
3. 不引入并行任务切槽、线程池或异步编排。
4. 不修改 IntentRoutingOnlineEvaluator、评测数据集或报告 writer。
5. 不新增拆解准确率、槽位准确率等评测指标。
6. 不改变 PE、通用对话、巡检、交易等业务执行节点的业务语义。
7. 不重构 MultiTaskExecutionNode 的任务执行方式。
8. 不引入线上 shadow 双跑。

本期实验数据应优先使用完整、可直接执行的 query。准确率评测体系在后续独立需求中建设。

---

## 3. 总体架构

### 3.1 统一链路

    RootNode
      -> IntentRoutingNode
      -> IntentRoutingService.routeUnified
      -> TaskGraphValidator.validateSubTasks
      -> RoutingResultHandler
      -> single task downstream / MultiTaskExecutionNode

### 3.2 拆分链路

    RootNode
      -> QueryDecompositionNode
      -> IntentRoutingService.decomposeQuery
      -> QueryDecompositionResult
      -> TaskGraphValidator.validateDecomposedTasks
      -> TaskRoutingSlotNode
      -> IntentRoutingService.routeTaskIntentSlots (逐任务串行调用)
      -> MultiIntentRoutingResult
      -> TaskGraphValidator.validateSubTasks
      -> RoutingResultHandler
      -> single task downstream / MultiTaskExecutionNode

### 3.3 RootNode 选择逻辑

RootNode 继续负责自动路由、显式 PE 和巡检三类入口选择。只有未传 aiAgentId 的自动意图路由场景读取 intent.routing.mode：

    aiAgentId 为空
      -> mode=UNIFIED -> IntentRoutingNode
      -> mode=SPLIT   -> QueryDecompositionNode

    aiAgentId=5
      -> IntelligentInspection

    其它显式 aiAgentId
      -> Step1AnalyzerNode

不新增 Gateway 节点，也不通过两个 IntentRoutingService 实现类隐藏节点差异。

---

## 4. 阶段间领域模型

### 4.1 QueryDecompositionResult

新增文件：

    ai-agent-study-domain/src/main/java/denny/ai/agent/domain/model/valobj/QueryDecompositionResult.java

建议字段：

    public class QueryDecompositionResult {
        private Boolean multiTask;
        private String reasoning;
        private List<DecomposedTask> taskList;
    }

职责：

- 表示第一阶段 query 拆解结果。
- 不包含 intent、confidence、slots、executorNode 等第二阶段字段。
- 不包含 needsClarification、missingInfo、clarificationPrompt。
- 单任务也必须包含一个 DecomposedTask。
- multiTask 最终由 taskList.size() > 1 归一化，避免依赖 LLM 的布尔值。

### 4.2 DecomposedTask

新增文件：

    ai-agent-study-domain/src/main/java/denny/ai/agent/domain/model/valobj/DecomposedTask.java

建议字段：

    public class DecomposedTask {
        private String taskId;
        private Integer taskIndex;
        private Integer totalTasks;
        private String content;
        private List<String> dependsOn;
    }

职责：

- 只描述任务边界和任务依赖。
- 不复用 SubTask，避免阶段间模型混入最终执行状态。
- 不包含 taskType；组装最终 SubTask 时统一默认设置为 0。
- dependsOn 为空时归一化为空列表。

### 4.3 最终模型

MultiIntentRoutingResult 和 SubTask 继续作为最终路由结果模型：

- unified 直接产生最终模型。
- split 在第二阶段完成后才产生最终模型。
- MultiIntentRoutingResult 增加 RoutingExecutionMetrics metrics 字段。
- split 链路固定输出 needsClarification=false、missingInfo=[]、clarificationPrompt=""。

---

## 5. 配置设计

### 5.1 强类型配置

新增：

    IntentRoutingProperties
    IntentRoutingMode

IntentRoutingProperties：

    @Component
    @ConfigurationProperties(prefix = "intent.routing")
    public class IntentRoutingProperties {
        private IntentRoutingMode mode = IntentRoutingMode.UNIFIED;
    }

IntentRoutingMode：

    public enum IntentRoutingMode {
        UNIFIED,
        SPLIT
    }

不在 RootNode 中使用 @Value String，避免拼写错误被静默接受。非法枚举值应在应用启动时失败。

### 5.2 配置示例

默认统一链路：

    intent:
      routing:
        mode: unified

启用拆分链路：

    intent:
      routing:
        mode: split

本方案只约定通过 application.yml 中的 intent.routing.mode 配置链路。unified 和 split 是同一个配置项的两个可选值，不是两个独立变量。

### 5.3 使用规则

1. 未配置时默认 UNIFIED，现有行为不变。
2. 配置为 split 后，所有未显式传入 aiAgentId 的请求进入拆分链路。
3. 显式 aiAgentId、巡检和其它业务入口不受该开关影响。
4. 修改配置后需要重启应用，不支持单请求动态切换。
5. 非法值应导致启动失败，不能静默回退。
6. 线上默认保持 unified；实验环境或指定部署实例显式开启 split。

---

## 6. 节点设计

### 6.1 QueryDecompositionNode

新增文件：

    ai-agent-study-domain/src/main/java/denny/ai/agent/domain/service/auto/step/routing/QueryDecompositionNode.java

Spring Bean：

    @Service("queryDecompositionNode")

职责：

1. 获取 INTENT_ROUTING client 配置。
2. 获取最近历史消息。
3. 调用 IntentRoutingService.decomposeQuery。
4. 调用 TaskGraphValidator.validateDecomposedTasks。
5. 将 QueryDecompositionResult 写入 DynamicContext。
6. 将第一阶段指标写入路由指标对象。
7. 路由到 TaskRoutingSlotNode。

上下文仅写入：

| key | value |
|---|---|
| queryDecompositionResult | QueryDecompositionResult |
| intentRoutingMetrics | RoutingExecutionMetrics |

不再额外写入 queryDecomposedTaskList，避免同一数据存在两个上下文副本。

### 6.2 TaskRoutingSlotNode

新增文件：

    ai-agent-study-domain/src/main/java/denny/ai/agent/domain/service/auto/step/routing/TaskRoutingSlotNode.java

Spring Bean：

    @Service("taskRoutingSlotNode")

职责：

1. 从 DynamicContext 读取 QueryDecompositionResult。
2. 按 taskIndex 顺序遍历 DecomposedTask。
3. 对每个 content 串行调用 IntentRoutingService.routeTaskIntentSlots。
4. 将 DecomposedTask 与 IntentRoutingResult 组装为 SubTask。
5. 将 taskType 默认设置为 0。
6. 组装最终 MultiIntentRoutingResult。
7. 调用 TaskGraphValidator.validateSubTasks 做最终一致性校验。
8. 将 RoutingExecutionMetrics 写入最终结果。
9. 调用 RoutingResultHandler 写上下文并进入下游。

第二阶段不做并行调用，不创建线程池，不增加异步状态。

### 6.3 IntentRoutingNode 调整

IntentRoutingNode 继续调用 routeUnified，但不再自行处理单任务、多任务和下游节点选择：

1. 调用 IntentRoutingService.routeUnified。
2. 调用 TaskGraphValidator.validateSubTasks。
3. 将指标挂载到结果。
4. 调用 RoutingResultHandler。

统一链路现有澄清能力继续保留；split 链路不产生澄清结果。

---

## 7. 服务层设计

### 7.1 统一路由

保留：

    MultiIntentRoutingResult routeUnified(
            String userMessage,
            List<String> historyMessages,
            AiAgentClientFlowConfigVO configVO);

统一路由解析流程调整为：

    解析原始 SubTask
      -> TaskGraphValidator.validateSubTasks
      -> normalizeTask
      -> MultiIntentRoutingResult

任务图校验应发生在执行节点归一化之前。

### 7.2 Query 拆解

新增：

    QueryDecompositionResult decomposeQuery(
            String userMessage,
            List<String> historyMessages,
            AiAgentClientFlowConfigVO configVO);

职责：

- 构建 query decomposition prompt。
- 调用 LLM。
- 解析 multiTask、reasoning 和 taskList。
- 生成 QueryDecompositionResult。
- 不识别 intent，不生成槽位，不判断澄清。
- 拆解调用失败、返回空或解析失败时，使用原始 query 构造单任务结果。

拆解降级结果：

    multiTask = false
    reasoning = 失败原因
    taskList = [
      {
        taskId = "fallback-1",
        taskIndex = 1,
        totalTasks = 1,
        content = userMessage,
        dependsOn = []
      }
    ]

### 7.3 单任务意图识别和槽位填充

新增：

    IntentRoutingResult routeTaskIntentSlots(
            String taskContent,
            List<String> historyMessages,
            AiAgentClientFlowConfigVO configVO);

职责：

- 构建单任务意图识别和槽位填充 prompt。
- 明确禁止继续拆解任务。
- 调用 LLM 并返回 IntentRoutingResult。
- 复用现有 parseResponse 和股票槽位标准化逻辑。
- 不判断 needsClarification。
- 槽位缺失或不完整时仍返回已识别结果。

调用失败时返回：

    intent = GENERAL_CHAT
    confidence = LOW
    reasoning = 失败原因
    baseSlot = null
    intentSpecificSlots = {}

### 7.4 Split 聚合入口

新增：

    MultiIntentRoutingResult routeSplit(
            String userMessage,
            List<String> historyMessages,
            AiAgentClientFlowConfigVO configVO);

职责：

1. 调用 decomposeQuery。
2. 校验 QueryDecompositionResult。
3. 逐任务串行调用 routeTaskIntentSlots。
4. 组装并校验最终 MultiIntentRoutingResult。
5. 返回包含 RoutingExecutionMetrics 的最终结果。

该方法供单元测试和后续评测体系直接调用。线上节点链路仍使用 QueryDecompositionNode -> TaskRoutingSlotNode。

### 7.5 LLM 调用与指标封装

IntentRoutingService 内部封装统一的路由模型调用方法，负责：

- 取得完整 ChatResponse。
- 从 metadata usage 获取真实 token。
- 缺少 usage 时使用 TokenCountUtils 估算。
- 记录调用耗时、成功状态和错误信息。
- 将 content 交给对应 parser。

该封装服务于 unified-routing、query-decomposition 和 task-routing-slot 三种阶段，不改变业务解析规则。

---

## 8. 公共组件设计

### 8.1 RoutingResultHandler

新增文件：

    ai-agent-study-domain/src/main/java/denny/ai/agent/domain/service/auto/step/routing/RoutingResultHandler.java

职责：

1. 接收最终 MultiIntentRoutingResult。
2. 处理 unified 链路已有的澄清结果。
3. 多任务时写入 taskList 和 originalMessage。
4. 单任务时转换为 IntentRoutingResult。
5. 写入 recognizedIntent、baseSlot、intentSpecificSlots 和 stockSlot。
6. 根据最终结果返回对应下游节点。
7. 统一处理 trading 节点不存在时降级到 generalChatNode。

统一链路和拆分链路均调用该组件，避免两个节点复制上下文写入和下游选择逻辑。

本期不继续拆分 RoutingResultAssembler、RoutingContextWriter 和 RoutingResultDispatcher。

### 8.2 TaskGraphValidator

新增文件：

    ai-agent-study-domain/src/main/java/denny/ai/agent/domain/service/auto/step/routing/TaskGraphValidator.java

提供两个入口：

    void validateDecomposedTasks(List<DecomposedTask> tasks);
    void validateSubTasks(List<SubTask> tasks);

共享的校验规则：

1. taskId 非空且唯一。
2. content 非空。
3. taskIndex 从 1 开始、不可重复，并与列表顺序一致。
4. totalTasks 与实际任务数量一致。
5. dependsOn 中的 taskId 必须存在。
6. 禁止任务依赖自身。
7. 禁止循环依赖。
8. 被依赖任务必须排在当前任务之前。

校验失败抛出 TaskGraphValidationException。

降级规则：

- unified 校验失败：降级为现有 GENERAL_CHAT 最终结果。
- split 第一阶段校验失败：使用原始 query 重建单个 DecomposedTask，继续第二阶段。
- split 最终 SubTask 校验失败：降级为单个 GENERAL_CHAT 最终结果。
- 校验失败应记录 success=false 和 errorMessage，但本期不细分错误类型。

---

## 9. Prompt 设计

### 9.1 Query Decomposition Prompt

新增：

    QUERY_DECOMPOSITION_PROMPT_TEMPLATE
    buildQueryDecompositionPrompt(...)

核心约束：

1. 只判断任务边界和任务依赖。
2. 不输出 intent、confidence、executorNode、slots、taskType。
3. 不输出 needsClarification、missingInfo、clarificationPrompt。
4. 单任务也必须输出一个 task。
5. 任务 content 应尽量自包含。
6. dependsOn 只能引用前置任务。
7. 输出必须为 JSON。

建议格式：

    {
      "multiTask": true,
      "reasoning": "用户要求分别分析两只股票",
      "taskList": [
        {
          "taskId": "sub-1",
          "taskIndex": 1,
          "totalTasks": 2,
          "content": "分析贵州茅台最近一个月走势",
          "dependsOn": []
        },
        {
          "taskId": "sub-2",
          "taskIndex": 2,
          "totalTasks": 2,
          "content": "分析比亚迪最近一个月走势",
          "dependsOn": []
        }
      ]
    }

### 9.2 Task Routing Slot Prompt

新增：

    TASK_ROUTING_SLOT_PROMPT_TEMPLATE
    buildTaskRoutingSlotPrompt(...)

核心约束：

1. 输入已经是单个任务，禁止继续拆解。
2. 只输出 intent、confidence、reasoning、baseSlot 和 intentSpecificSlots。
3. 不输出 multiTask、taskList、executorNode 或澄清字段。
4. STOCK_ANALYSIS 尽量抽取 stockCode、stockQueryType、timeRange、exchange。
5. 即使部分槽位缺失，也返回当前能够识别的结果。

建议格式：

    {
      "intent": "STOCK_ANALYSIS",
      "confidence": "HIGH",
      "reasoning": "任务明确要求分析贵州茅台走势",
      "baseSlot": {
        "topic": "贵州茅台走势",
        "sentiment": "neutral"
      },
      "intentSpecificSlots": {
        "stockCode": "600519",
        "stockQueryType": "TECHNICAL",
        "timeRange": "最近一个月",
        "exchange": "SH"
      }
    }

### 9.3 历史消息使用

两阶段可继续使用现有最近历史消息获取逻辑：

- 第一阶段用于理解追问、指代和任务边界。
- 第二阶段接收相同历史消息，用于识别依赖上下文的单任务意图。
- 本期不引入额外历史裁剪策略或按任务隔离历史的复杂设计。

---

## 10. 指标与可观测性

### 10.1 RoutingExecutionMetrics

新增字段：

| 字段 | 含义 |
|---|---|
| mode | UNIFIED 或 SPLIT |
| totalLatencyMs | 完整路由方法或完整节点链路的实际耗时 |
| totalPromptTokens | 所有 LLM 调用 prompt token 总和 |
| totalCompletionTokens | 所有 LLM 调用 completion token 总和 |
| totalTokens | 所有 LLM 调用 token 总和 |
| estimated | 任意一次调用使用估算 token 时为 true |
| stageMetrics | 按实际串行调用顺序记录的阶段指标 |

### 10.2 RoutingStageMetric

| 字段 | 含义 |
|---|---|
| stageName | unified-routing、query-decomposition 或 task-routing-slot |
| clientId | 使用的 clientId |
| taskId | task-routing-slot 对应任务 ID，其它阶段为空 |
| callIndex | 串行调用序号；统一和拆解阶段为 0，任务阶段从 1 开始 |
| latencyMs | 当前 LLM 调用实际耗时 |
| promptTokens | prompt tokens |
| completionTokens | completion tokens |
| totalTokens | total tokens |
| estimatedTokens | 当前调用 token 是否为估算 |
| success | 当前调用是否成功 |
| errorMessage | 当前调用错误信息 |

### 10.3 统计口径

1. 第二阶段严格串行调用。
2. 总 token 为全部阶段调用 token 求和。
3. totalLatencyMs 使用完整链路实际耗时。
4. stageMetrics 保留每次任务路由调用，不将多个任务合并成一条指标。
5. 优先读取 ChatResponse metadata usage。
6. usage 缺失时估算 prompt 和 response token，并显式标记。
7. 指标写入 MultiIntentRoutingResult.metrics、DynamicContext 和日志。
8. 本期不修改在线评测报告。

日志示例：

    Intent routing metrics: mode=SPLIT, totalLatencyMs=1820, totalTokens=1480,
    stages=[
      {stage=query-decomposition, callIndex=0, latencyMs=680, totalTokens=520},
      {stage=task-routing-slot, taskId=sub-1, callIndex=1, latencyMs=560, totalTokens=470},
      {stage=task-routing-slot, taskId=sub-2, callIndex=2, latencyMs=580, totalTokens=490}
    ]

---

## 11. 异常与降级

### 11.1 拆解阶段失败

以下情况使用原始 query 构建单个 DecomposedTask：

- LLM 调用异常。
- 返回为空。
- JSON 解析失败。
- taskList 为空。
- TaskGraphValidator 校验失败。

降级后仍进入 TaskRoutingSlotNode，不回退 unified。

### 11.2 单任务意图切槽失败

某个任务调用或解析失败时：

    intent = GENERAL_CHAT
    confidence = LOW
    executorNode = generalChatNode
    slots = {}

其它任务继续串行处理。

### 11.3 最终任务图失败

split 最终 SubTask 校验失败时，返回单个 GENERAL_CHAT 结果，避免将非法任务图交给 MultiTaskExecutionNode。

### 11.4 错误记录

本期只记录：

- success=false
- errorMessage
- 降级后的 reasoning

不新增 MODEL_ERROR、PARSE_ERROR、VALIDATION_ERROR 等错误枚举。

### 11.5 澄清

- unified 链路保持现有澄清行为。
- split 链路不判断、不生成、不执行澄清。
- split 最终固定 needsClarification=false。
- 不完整 query 不属于本期 split 实验重点数据集。

---

## 12. 上下文与下游兼容

RoutingResultHandler 保证最终 DynamicContext 与现有链路兼容：

| key | 场景 | 用途 |
|---|---|---|
| intentRoutingResult | 单任务 | 下游节点读取 |
| recognizedIntent | 单任务 | 下游节点选择 |
| baseSlot | 单任务 | 通用槽位 |
| intentSpecificSlots | 单任务 | 业务执行 |
| stockSlot | 单任务股票分析 | 交易 Agent |
| taskList | 多任务 | MultiTaskExecutionNode |
| originalMessage | 多任务 | 多任务结果汇总 |
| clarificationPrompt | unified 澄清 | 现有澄清输出 |
| missingInfo | unified 澄清 | 现有澄清信息 |
| intentRoutingMetrics | unified 和 split | 调试与指标读取 |

split 第一阶段只增加 queryDecompositionResult，第二阶段完成后由 RoutingResultHandler 写入最终兼容 key。

---

## 13. 实施清单

### 13.1 领域模型

| 动作 | 文件 |
|---|---|
| 新增 | QueryDecompositionResult.java |
| 新增 | DecomposedTask.java |
| 新增 | RoutingExecutionMetrics.java |
| 新增 | RoutingStageMetric.java |
| 修改 | MultiIntentRoutingResult.java |

### 13.2 配置

| 动作 | 文件 |
|---|---|
| 新增 | IntentRoutingProperties.java |
| 新增 | IntentRoutingMode.java |
| 修改 | application.yml |
| 修改 | RootNode.java |

### 13.3 公共组件

| 动作 | 文件 |
|---|---|
| 新增 | RoutingResultHandler.java |
| 新增 | TaskGraphValidator.java |
| 新增 | TaskGraphValidationException.java |

### 13.4 Prompt 和服务

| 动作 | 文件 |
|---|---|
| 修改 | IntentRoutingPrompt.java |
| 修改 | IntentRoutingService.java |

IntentRoutingService 新增：

- decomposeQuery
- routeTaskIntentSlots
- routeSplit
- query decomposition parser
- 路由 LLM 调用指标封装

### 13.5 节点

| 动作 | 文件 |
|---|---|
| 新增 | QueryDecompositionNode.java |
| 新增 | TaskRoutingSlotNode.java |
| 修改 | IntentRoutingNode.java |
| 修改 | RootNode.java |

### 13.6 本期不修改

- IntentRoutingOnlineEvaluator.java
- IntentRoutingOnlineEvalReportWriter.java
- intent-routing-online-cases.json
- MultiTaskExecutionNode 的执行方式
- 下游业务执行节点

---

## 14. 测试计划

### 14.1 模型和解析测试

- QueryDecompositionResult 正常解析。
- 单任务和多任务拆解解析。
- 不输出或忽略 intent、slots 等非第一阶段字段。
- 空响应、非法 JSON、空 taskList 降级为原始 query 单任务。
- routeTaskIntentSlots 正常解析和股票槽位标准化。
- 单任务意图调用失败降级 GENERAL_CHAT + LOW。

### 14.2 TaskGraphValidator 测试

对 DecomposedTask 和 SubTask 两种入口覆盖：

- 合法单任务。
- 合法多任务。
- taskId 为空。
- taskId 重复。
- content 为空。
- taskIndex 非法或重复。
- totalTasks 不一致。
- dependsOn 引用不存在。
- 自依赖。
- 循环依赖。
- 依赖后置任务。

### 14.3 节点测试

QueryDecompositionNodeTest：

- 获取配置和历史消息。
- 写入 queryDecompositionResult。
- 写入第一阶段指标。
- 正常路由到 TaskRoutingSlotNode。
- 校验失败后使用原始 query 单任务继续。

TaskRoutingSlotNodeTest：

- 单任务组装。
- 多任务串行组装。
- 调用顺序与 taskIndex 一致。
- taskType 默认值为 0。
- 某任务失败时其它任务继续。
- metrics 按调用顺序聚合。
- 最终调用 RoutingResultHandler。

IntentRoutingNodeTest：

- unified 正常结果交给 RoutingResultHandler。
- unified 澄清保持现有行为。
- unified 任务图非法时降级 GENERAL_CHAT。

RootNodeTest：

- 默认 UNIFIED 进入 IntentRoutingNode。
- SPLIT 进入 QueryDecompositionNode。
- 显式 aiAgentId 不受 mode 影响。
- 巡检 aiAgentId 不受 mode 影响。

RoutingResultHandlerTest：

- 单任务上下文写入。
- 股票 stockSlot 写入。
- 多任务 taskList 和 originalMessage 写入。
- unified 澄清上下文写入。
- 下游节点选择。
- trading 节点缺失降级。

### 14.4 Service 测试

IntentRoutingServiceTest：

- routeUnified 现有行为回归。
- decomposeQuery 正常与降级。
- routeTaskIntentSlots 正常与降级。
- routeSplit 单任务与多任务。
- routeSplit 第二阶段严格串行。
- stageMetrics、totalLatencyMs 和 token 聚合。
- usage 缺失时 token 估算。

本期不增加在线准确率评测测试。

---

## 15. 风险与应对

| 风险 | 影响 | 应对 |
|---|---|---|
| split 增加 LLM 调用次数 | 延迟和 token 上升 | 默认使用 unified，仅实验环境开启 split |
| 第一阶段拆解错误 | 后续任务边界错误 | 独立阶段模型、TaskGraphValidator、原始 query 单任务降级 |
| 第二阶段某任务失败 | 单个任务结果错误 | 该任务降级 GENERAL_CHAT，其它任务继续 |
| unified 与 split 上下文不一致 | 下游行为回归 | 共用 RoutingResultHandler |
| 非法任务依赖进入执行节点 | 执行顺序和结果错误 | 两条链路共用 TaskGraphValidator |
| token usage 缺失 | 对比数据不完整 | 使用 TokenCountUtils 估算并标记 |
| split 不处理澄清 | 不完整 query 结果不理想 | 本期数据集使用完整 query，澄清不纳入实验范围 |
| 配置错误 | 请求进入错误链路 | 强类型枚举绑定，非法值启动失败 |

---

## 16. 后续独立需求

以下能力不纳入本期：

1. 扩展在线评测器支持 UNIFIED 和 SPLIT 模式。
2. 建设 query 拆解准确率、槽位准确率和任务依赖准确率指标。
3. 生成 unified 与 split 的准确率、延迟和 token 对比报告。
4. 建设 mode 独立 baseline，避免不同模式报告相互覆盖。
5. 引入线上 shadow compare。
6. 处理 split 链路的澄清和不完整 query。
7. 根据业务类型动态选择路由模式。

本期保留 routeSplit 和 RoutingExecutionMetrics，作为后续评测体系的调用入口和数据来源。

---

## 17. 推荐实施顺序

1. 新增阶段间模型和强类型配置。
2. 新增 RoutingExecutionMetrics 和 RoutingStageMetric。
3. 新增 TaskGraphValidator 及其单元测试。
4. 抽取 RoutingResultHandler，并让 IntentRoutingNode 使用该组件。
5. 实现 query decomposition prompt、parser 和 decomposeQuery。
6. 实现 task routing slot prompt 和 routeTaskIntentSlots。
7. 实现 QueryDecompositionNode 和 TaskRoutingSlotNode。
8. 在 RootNode.get() 中接入配置选择。
9. 实现 routeSplit 和串行指标聚合。
10. 补齐服务、节点、配置和回归测试。

---

## 18. 验收标准

1. 未配置 intent.routing.mode 时，现有统一链路行为保持不变。
2. 配置 intent.routing.mode=split 并重启后，未传 aiAgentId 的请求进入 QueryDecompositionNode。
3. 显式 aiAgentId 和巡检请求不受该配置影响。
4. 第一阶段返回 QueryDecompositionResult，不复用 SubTask。
5. split 第一阶段不输出 intent、slots 或澄清字段。
6. split 第二阶段按 taskIndex 串行执行。
7. unified 和 split 都经过 TaskGraphValidator。
8. unified 和 split 都通过 RoutingResultHandler 写入最终上下文并选择下游。
9. split 对下游输出的 DynamicContext key 与现有 unified 链路兼容。
10. unified 和 split 都能输出 RoutingExecutionMetrics。
11. 每个第二阶段任务都有独立的 taskId、callIndex、耗时和 token 指标。
12. split 任一阶段失败时按本文定义降级，且不回退 unified。
13. split 固定 needsClarification=false，不处理不完整 query 的澄清。
14. 本期单元测试覆盖主要正常、校验和降级路径。
15. 本期不修改现有在线评测体系。
