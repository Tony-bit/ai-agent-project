# Story: 多任务场景上下文隔离与按需依赖设计

## 1. 背景与问题

### 1.1 当前问题

在 `MultiTaskExecutionNode` 执行多任务时，存在以下问题：

1. **上下文污染**：每个 SubTask 的执行结果都会累积到对话历史中，后续 SubTask 继承了不必要的上下文
2. **Prompt 超长**：`summarizeResults()` 汇总所有 SubTask 结果时，Prompt 超过模型限制（1261 错误）
3. **缺乏依赖表达**：无法表达 SubTask 之间的依赖关系，导致不必要的上下文传递

### 1.2 场景示例

```
用户请求：
"解释向量数据库的概念、介绍Spring AI框架、对比RAG和微调"

当前行为：
┌─────────────────────────────────────────────────────────────┐
│ MultiTaskExecutionNode.doApply()                           │
├─────────────────────────────────────────────────────────────┤
│ 1. 执行 sub-1: 解释向量数据库                              │
│    → 结果存入 task.result = "向量数据库是..."              │
│    → 结果同时追加到对话历史上下文                          │
│                                                             │
│ 2. 执行 sub-2: 介绍Spring AI框架                          │
│    → 对话历史已包含 sub-1 的结果                          │
│    → 上下文膨胀，不必要的依赖                            │
│                                                             │
│ 3. 执行 sub-3: 对比RAG和微调                               │
│    → 对话历史包含 sub-1, sub-2 的结果                     │
│    → 上下文继续膨胀                                        │
│                                                             │
│ 4. 汇总时:                                                 │
│    → 所有结果拼接 → Prompt 超长 → 1261 错误               │
└─────────────────────────────────────────────────────────────┘
```

---

## 2. 设计目标

| 目标 | 描述 |
|------|------|
| **上下文隔离** | 每个 SubTask 独立执行，不继承上一个 SubTask 的结果（除非明确依赖） |
| **按需依赖** | 通过 `dependsOn` 字段表达依赖关系，有依赖才传递结果 |
| **统一汇总** | 所有结果存入 `DynamicContext`，由汇总节点统一整理输出 |
| **压缩前置** | 汇总节点负责压缩，确保最终 Prompt 不超限 |

---

## 3. 架构设计

### 3.1 数据模型

```
SubTask
├── taskId: String                    // 任务唯一标识
├── dependsOn: List<String>           // 【新增】依赖的 SubTask ID 列表
├── executorNode: String               // 执行节点
├── content: String                   // 任务内容
├── result: String                    // 任务执行结果
└── status: SubTaskStatus             // 任务状态
```

### 3.2 DynamicContext 路径说明

> **重要说明**：项目中存在两个 DynamicContext，本需求使用 `auto/step` 层的内部类 DynamicContext。

| DynamicContext | 路径 | 使用场景 |
|----------------|------|----------|
| `DefaultAutoAgentExecuteStrategyFactory.DynamicContext` | `factory/DefaultAutoAgentExecuteStrategyFactory.java` 内部类 | `MultiTaskExecutionNode`、`IntentRoutingNode` 等 auto/step 层节点 |
| `DynamicContext` | `armory/factory/DynamicContext.java` | `AiClientNode`、`CompressionContextNode` 等 armory 层节点 |

**本需求修改的是内部类 DynamicContext**，即 `DefaultAutoAgentExecuteStrategyFactory.DynamicContext`。

### 3.3 执行流程

```
┌─────────────────────────────────────────────────────────────────────┐
│                  MultiTaskExecutionNode.doApply()                   │
├─────────────────────────────────────────────────────────────────────┤
│                                                                      │
│  ┌──────────┐    ┌──────────┐    ┌──────────┐                     │
│  │ sub-1    │ -> │ sub-2    │ -> │ sub-3    │                     │
│  │ no deps  │    │ deps:    │    │ no deps  │                     │
│  └────┬─────┘    │ [sub-1]  │    └────┬─────┘                     │
│       │          └────┬─────┘         │                              │
│       v               v               v                              │
│  ┌─────────────────────────────────────────────────────────┐        │
│  │  DefaultAutoAgentExecuteStrategyFactory.DynamicContext  │        │
│  │  ├── taskList: List<SubTask>                          │        │
│  │  └── subTaskResults: Map<String, SubTask>             │        │
│  │  {                                                      │        │
│  │    "sub-1": { result, latency, status },               │        │
│  │    "sub-2": { result, latency, status, dependsOn },   │        │
│  │    "sub-3": { result, latency, status }                │        │
│  │  }                                                      │        │
│  └─────────────────────────────────────────────────────────┘        │
│                              │                                       │
│                              v                                       │
│  ┌─────────────────────────────────────────────────────────┐        │
│  │  SummarizeNode (汇总节点)                                │        │
│  │  1. 从 DynamicContext 收集所有结果                        │        │
│  │  2. 根据依赖关系构建上下文                               │        │
│  │  3. 压缩后汇总输出                                       │        │
│  └─────────────────────────────────────────────────────────┘        │
│                              │                                       │
│                              v                                       │
│  ┌─────────────────────────────────────────────────────────┐        │
│  │  【异步清理】异步触发清理（不阻塞返回）                    │        │
│  │  - dynamicContext.clearSubTaskResults()                │        │
│  │  - dynamicContext.clearMultiTaskContext()                 │        │
│  └─────────────────────────────────────────────────────────┘        │
│                                                                      │
└─────────────────────────────────────────────────────────────────────┘
```

### 3.4 依赖表达示例

```yaml
# 意图解析返回的 SubTask 列表
taskList:
  - taskId: "sub-1"
    content: "解释向量数据库的概念"
    dependsOn: []                    # 无依赖，干净上下文

  - taskId: "sub-2"
    content: "基于向量数据库，介绍RAG应用"
    dependsOn: ["sub-1"]             # 依赖 sub-1，可获取其结果

  - taskId: "sub-3"
    content: "对比RAG和微调两种技术"
    dependsOn: []                    # 无依赖，干净上下文
```

### 3.5 清理策略与 sessionHistory 设计

#### 3.5.1 清理时机

| 场景 | 清理时机 | 说明 |
|------|----------|------|
| **非流式响应** | doApply() 返回后，异步触发清理 | 使用 CompletableFuture 异步执行，不阻塞返回 |
| **流式响应** | StreamingComplete 回调中清理 | 整个 stream 完成后异步清理 |

#### 3.5.2 清理范围

| 清理项 | 说明 | 是否清理 |
|--------|------|----------|
| **subTaskResults** | DynamicContext 中存储的 SubTask 执行结果 | ✅ 清理 |
| **taskList / originalMessage 等多任务 key** | DynamicContext.dataObjects 中存储的当前 Query 多任务数据 | ✅ 清理 |
| **sessionHistory** | 用户级别对话历史（多轮对话） | ❌ 保留 |
| **taskList** | 当前 query 拆分出的 SubTask 列表（本地变量） | ✅ 自然回收 |

#### 3.5.3 异步清理实现

```java
/**
 * 执行完成后异步清理（不阻塞返回）
 */
private void cleanupAfterQueryComplete(DefaultAutoAgentExecuteStrategyFactory.DynamicContext dynamicContext) {
    CompletableFuture.runAsync(() -> {
        log.info("异步清理执行上下文开始");
        try {
            dynamicContext.clearSubTaskResults();
            dynamicContext.clearMultiTaskContext();
            log.info("异步清理执行上下文完成");
        } catch (Exception e) {
            log.error("异步清理失败: {}", e.getMessage(), e);
        }
    });
}
```

#### 3.5.4 sessionHistory 保留策略

```
┌─────────────────────────────────────────────────────────────────────┐
│  用户会话生命周期                                                      │
├─────────────────────────────────────────────────────────────────────┤
│                                                                      │
│  Query 1 → Query 2 → Query 3 → ...                                 │
│      ↓         ↓         ↓                                          │
│  清理 task   清理 task  清理 task                                   │
│  相关数据    相关数据   相关数据                                      │
│      ↓         ↓         ↓                                          │
│  sessionHistory 保留（用户级别对话历史，支持多轮对话）                    │
│                                                                      │
└─────────────────────────────────────────────────────────────────────┘
```

**设计原则**：
- 每次 Query 执行完成后，清理 SubTask 相关数据
- sessionHistory 保留用户级别对话历史，支持多轮对话上下文
- DynamicContext 中的 dataObjects 只清理 MultiTask 相关的 key

### 3.6 依赖传递逻辑

```java
// 执行 SubTask 前
if (task.getDependsOn() != null && !task.getDependsOn().isEmpty()) {
    // 有依赖：从 DynamicContext 获取依赖任务的结果
    for (String depTaskId : task.getDependsOn()) {
        SubTask depTask = dynamicContext.getSubTaskResult(depTaskId);
        if (depTask != null) {
            // 将依赖结果注入到当前任务 content 的占位符位置
            executableContent = injectDependencyResult(executableContent, depTask);
        }
    }
} else {
    // 无依赖：直接使用原始 content 执行，不额外注入前置任务结果
    executableContent = task.getContent();
}
```

---

## 4. 代码修改

### 4.1 SubTask.java - 添加依赖字段

**文件**: `ai-agent-study-domain/src/main/java/denny/ai/agent/domain/model/valobj/SubTask.java`

```java
// 在 slots 字段后添加

/**
 * 依赖的子任务 ID 列表
 * <p>
 * 用于表达 SubTask 之间的依赖关系。
 * - 为空或 null：表示不依赖任何任务，使用干净上下文执行
 * - 有值：表示依赖指定任务的结果，动态构建上下文时包含依赖结果
 * </p>
 * <p>示例：</p>
 * <ul>
 *   <li>[] - 不依赖任何任务</li>
 *   <li>["sub-1"] - 依赖 sub-1 的执行结果</li>
 *   <li>["sub-1", "sub-2"] - 依赖 sub-1 和 sub-2 的结果</li>
 * </ul>
 */
private List<String> dependsOn;
```

### 4.2 DefaultAutoAgentExecuteStrategyFactory.DynamicContext - 添加 SubTask 结果存储

> **文件路径**: `ai-agent-study-domain/src/main/java/denny/ai/agent/domain/service/auto/step/factory/DefaultAutoAgentExecuteStrategyFactory.java`（内部类 `DynamicContext`）

**注意**：本需求修改的是 `DefaultAutoAgentExecuteStrategyFactory.DynamicContext`（内部类），不是 `armory/factory/DynamicContext.java`（独立类）。

```java
// 新增字段
private Map<String, SubTask> subTaskResults = new HashMap<>();

// 新增方法
public void putSubTaskResult(String taskId, SubTask subTask) {
    subTaskResults.put(taskId, subTask);
}

public SubTask getSubTaskResult(String taskId) {
    return subTaskResults.get(taskId);
}

public Map<String, SubTask> getAllSubTaskResults() {
    return Collections.unmodifiableMap(subTaskResults);
}

/**
 * 清理所有 SubTask 结果
 */
public void clearSubTaskResults() {
    log.info("清理 subTaskResults，当前大小: {}", subTaskResults.size());
    subTaskResults.clear();
}

/**
 * 清理当前 Query 的多任务上下文数据
 */
public void clearMultiTaskContext() {
    log.info("清理多任务上下文 key");
    dataObjects.remove(MultiTaskExecutionNode.TASK_LIST_KEY);
    dataObjects.remove(MultiTaskExecutionNode.ORIGINAL_MESSAGE_KEY);
}
```

### 4.3 MultiTaskExecutionNode.java - 修改执行逻辑

**文件**: `ai-agent-study-domain/src/main/java/denny/ai/agent/domain/service/auto/step/routing/MultiTaskExecutionNode.java`

#### 4.3.1 修改执行前逻辑

```java
public String executeSubTask(SubTask subTask,
                            DefaultAutoAgentExecuteStrategyFactory.DynamicContext dynamicContext) throws Exception {

    String executableContent = buildExecutionContent(subTask, dynamicContext);
    SubTask taskToExecute = SubTask.builder()
            .taskId(subTask.getTaskId())
            .taskIndex(subTask.getTaskIndex())
            .totalTasks(subTask.getTotalTasks())
            .content(executableContent)
            .intent(subTask.getIntent())
            .executorNode(subTask.getExecutorNode())
            .confidence(subTask.getConfidence())
            .slots(subTask.getSlots())
            .dependsOn(subTask.getDependsOn())
            .taskType(subTask.getTaskType())
            .build();

    ExecutorAdapter executor = resolveExecutor(taskToExecute.getExecutorNode());
    String result = executor.executeSubTask(taskToExecute, dynamicContext);

    subTask.setResult(result);
    dynamicContext.putSubTaskResult(subTask.getTaskId(), subTask);
    return result;
}
```

#### 4.3.2 依赖结果注入逻辑（核心）

```java
private static final Pattern DEPENDENCY_PLACEHOLDER_PATTERN =
        Pattern.compile("<\\$DEPENDENCY\\$ taskId=\"([^\"]+)\" />");

/**
 * 构建可执行的任务内容
 * <p>
 * 如果任务有依赖，将依赖任务的结果注入到占位符位置。
 * </p>
 *
 * 示例：
 * - LLM 分解的原始 content: "基于 <$DEPENDENCY$ taskId="sub-1" /> 的分析，进行深入对比"
 * - 注入后: "基于 【sub-1 的执行结果内容】 的分析，进行深入对比"
 */
private String buildExecutionContext(SubTask task, DefaultAutoAgentExecuteStrategyFactory.DynamicContext dynamicContext) {
    List<String> dependsOn = task.getDependsOn();
    if (dependsOn == null || dependsOn.isEmpty()) {
        log.info("SubTask {} 无依赖，使用原始内容执行", task.getTaskId());
        return task.getContent();
    }

    String content = task.getContent();
    log.info("SubTask {} 依赖任务: {}, 开始注入依赖结果", task.getTaskId(), dependsOn);

    // 查找所有占位符并替换
    Matcher matcher = DEPENDENCY_PLACEHOLDER_PATTERN.matcher(content);
    StringBuffer sb = new StringBuffer();

    while (matcher.find()) {
        String depTaskId = matcher.group(1);
        SubTask depTask = dynamicContext.getSubTaskResult(depTaskId);

        if (depTask != null && depTask.getResult() != null) {
            String replacement = "【" + depTask.getTaskId() + " 的结果】\n" + depTask.getResult();
            matcher.appendReplacement(sb, Matcher.quoteReplacement(replacement));
            log.info("注入依赖结果: taskId={}, resultLength={}", depTaskId, depTask.getResult().length());
        } else {
            log.warn("依赖任务 {} 的结果不存在，跳过注入", depTaskId);
            matcher.appendReplacement(sb, "【依赖任务 " + depTaskId + " 结果不可用】");
        }
    }
    matcher.appendTail(sb);

    return sb.toString();
}
```

#### 4.3.3 占位符格式说明

LLM 在分解任务时，应使用以下占位符标记需要注入依赖结果的位置：

```
<$DEPENDENCY$ taskId="sub-1" />
```

**示例场景**：

| 用户请求 | LLM 分解结果 |
|----------|--------------|
| "先查 A 股票的走势，再分析是否值得买入" | sub-1: `查 A 股票的走势`（无依赖）<br>sub-2: `基于 <$DEPENDENCY$ taskId="sub-1" /> 的分析，分析是否值得买入`（依赖 sub-1） |
| "分析茅台、比亚迪，对比哪个更值得投资" | sub-1: `分析茅台走势`（无依赖）<br>sub-2: `分析比亚迪走势`（无依赖）<br>sub-3: `基于 <$DEPENDENCY$ taskId="sub-1" /> 和 <$DEPENDENCY$ taskId="sub-2" />，对比哪个更值得投资`（依赖 sub-1, sub-2） |

#### 4.3.4 Prompt 模板更新

在 `IntentRoutingPrompt.java` 的 `MULTI_TASK_DECOMPOSE_PROMPT` 中增加占位符说明：

```java
## dependsOn 字段说明
- dependsOn 必须为数组，表示该子任务依赖哪些前置任务的执行结果
- 如果子任务不需要依赖前置结果，设置为空数组 []
- **【重要】如果任务需要依赖前置结果，必须在 content 中使用占位符标记注入位置**
- 占位符格式：`<$DEPENDENCY$ taskId="sub-1" />`
- 示例：
  - "基于 <$DEPENDENCY$ taskId="sub-1" /> 的分析，进行深入对比"
  - "综合 <$DEPENDENCY$ taskId="sub-1" /> 和 <$DEPENDENCY$ taskId="sub-2" /> 的结果，得出结论"
```

#### 4.3.5 依赖注入执行流程图

```
┌─────────────────────────────────────────────────────────────────────┐
│                    依赖注入执行流程                                    │
├─────────────────────────────────────────────────────────────────────┤
│                                                                      │
│  IntentRoutingNode 解析用户请求                                       │
│         │                                                           │
│         ▼                                                           │
│  ┌─────────────────────────────────────┐                           │
│  │ taskList:                            │                           │
│  │   sub-1: content="查A股票走势"        │                           │
│  │         dependsOn=[]                 │                           │
│  │   sub-2: content="基于 <$DEPENDENCY$ │                           │
│  │                taskId="sub-1" />    │                           │
│  │         dependsOn=["sub-1"]         │                           │
│  └─────────────────────────────────────┘                           │
│         │                                                           │
│         ▼                                                           │
│  MultiTaskExecutionNode 顺序执行                                      │
│         │                                                           │
│         ├──► 执行 sub-1                                             │
│         │      content="查A股票走势"（无依赖，原样执行）              │
│         │      result="A股票今日上涨2%..."                           │
│         │      ↓ 存入 DynamicContext                                 │
│         │                                                           │
│         ├──► 执行 sub-2                                             │
│         │      检测到 dependsOn=["sub-1"]                           │
│         │      识别占位符: <$DEPENDENCY$ taskId="sub-1" />          │
│         │      注入依赖结果:                                         │
│         │      "基于 【sub-1 的结果】\nA股票今日上涨2%..."          │
│         │      执行注入后的内容                                      │
│         │      result="基于A股票走势分析..."                         │
│         │                                                           │
│         ▼                                                           │
│  SummarizeNode 汇总所有结果                                          │
│                                                                      │
└─────────────────────────────────────────────────────────────────────┘
```
```

#### 4.3.6 修改汇总逻辑

**核心原则**：
- 所有任务结果都传入汇总 Prompt，由 LLM 根据用户 Query 自行判断如何组织回复
- 不预设模板，让 LLM 灵活处理不同场景
- 中间结果如果不适合直接展示，可以提炼后呈现

```java
private String summarizeResults(String originalMessage, List<SubTask> taskList,
                              DefaultAutoAgentExecuteStrategyFactory.DynamicContext dynamicContext) {
    log.info("开始 LLM 汇总，共 {} 个结果", taskList.size());

    // 构建汇总 Prompt
    String summaryPrompt = buildSummaryPrompt(originalMessage, taskList);

    // 调用 LLM 汇总
    ChatClient chatClient = getChatClientByClientId("3001", 0);

    String fullContent = chatClient.prompt(summaryPrompt).call().content();
    return fullContent;
}

private String buildSummaryPrompt(String originalMessage, List<SubTask> taskList) {
    StringBuilder results = new StringBuilder();
    for (SubTask task : taskList) {
        results.append(String.format("[%s] %s\n状态: %s\n",
                task.getTaskId(), task.getContent(), task.getStatus()));
        if (task.getStatus() == SubTask.SubTaskStatus.COMPLETED) {
            results.append("结果:\n").append(task.getResult()).append("\n");
        } else if (task.getStatus() == SubTask.SubTaskStatus.FAILED) {
            results.append("错误: ").append(task.getErrorMessage()).append("\n");
        }
        results.append("\n");
    }

    return """
        ## 用户原始请求
        {originalMessage}

        ## 任务执行结果
        {taskResults}

        ## 回复要求
        请根据用户原始请求，将以上任务结果整理成最终的自然语言回复。
        原则：
        1. 直接回答用户的问题，不要列举中间过程
        2. 如果有多个任务结果，按逻辑顺序组织
        3. 回复简洁、清晰、有条理
        4. 如果某个任务失败，在回复中说明即可
        """.replace("{originalMessage}", originalMessage)
            .replace("{taskResults}", results.toString());
}
```

#### 4.3.7 汇总完成后异步清理执行上下文（核心）

**设计原则**：每个 Query 任务完成后异步清理，避免上下文污染下一个 Query，且不阻塞返回。

**清理时机**：
- 非流式响应：doApply() 返回后，异步触发清理
- 流式响应：StreamingComplete 回调中异步清理

**清理范围**：
| 清理项 | 说明 |
|--------|------|
| **subTaskResults** | DynamicContext 中存储的 SubTask 执行结果 |
| **taskList / originalMessage 等多任务 key** | DynamicContext.dataObjects 中存储的当前 Query 多任务数据 |

**保留项**：
| 保留项 | 说明 |
|--------|------|
| **sessionHistory** | 用户级别对话历史，支持多轮对话 |

```java
/**
 * 执行完成后异步清理（不阻塞返回）
 */
private void cleanupAfterQueryComplete(DefaultAutoAgentExecuteStrategyFactory.DynamicContext dynamicContext) {
    CompletableFuture.runAsync(() -> {
        log.info("异步清理执行上下文开始");
        try {
            dynamicContext.clearSubTaskResults();
            dynamicContext.clearMultiTaskContext();
            log.info("异步清理执行上下文完成");
        } catch (Exception e) {
            log.error("异步清理失败: {}", e.getMessage(), e);
        }
    });
}
```

**调用位置**（doApply 方法末尾）：

```java
@Override
protected String doApply(ExecuteCommandEntity request,
                        DefaultAutoAgentExecuteStrategyFactory.DynamicContext dynamicContext) throws Exception {

    // 1. 解析用户请求，确定是否多任务
    List<SubTask> taskList = dynamicContext.getValue(TASK_LIST_KEY);
    String originalMessage = dynamicContext.getValue(ORIGINAL_MESSAGE_KEY);

    // 2. 执行多任务
    for (SubTask task : taskList) {
        executeSubTask(task, dynamicContext);
    }

    // 3. 汇总结果
    String result = summarizeResults(originalMessage, taskList, dynamicContext);

    // 4. 【核心】异步清理执行上下文（不阻塞返回）
    cleanupAfterQueryComplete(dynamicContext);

    return result;
}
```

**流程图**：

```
Query 1
    ↓
doApply()
    ↓
执行 sub-1, sub-2, sub-3...
    ↓
存入 DynamicContext
    ↓
summarizeResults() 汇总
    ↓
return 汇总结果给前端
    ↓
【清理】cleanupAfterQueryComplete()
    ↓
- dynamicContext.clearSubTaskResults()
- dynamicContext.clearMultiTaskContext()
↓
Query 2（sessionHistory 保留，task 相关数据已清理）
```

---

**场景示例**：

| 场景 | 清理时机 | 清理内容 | 保留内容 |
|------|--------|---------|---------|
| **单轮对话** | 任务完成 → 返回结果 → 异步清理 | subTaskResults、taskList key | sessionHistory |
| **多轮对话** | 每轮汇总后异步清理 | 本轮 subTaskResults、taskList key | sessionHistory（多轮上下文） |
| **流式响应** | StreamingComplete 回调中异步清理 | 同上 | sessionHistory |

---

**场景示例**：

| 用户 Query | 任务列表 | 最终回复 |
|-----------|---------|---------|
| "解释向量数据库、Spring AI、RAG对比" | 3个查询任务 | 返回整理后的完整解释 |
| "生成***分析报告" | 查询→写文档→生成文件 | 返回文档路径 + 使用的数据源 |
| "分析茅台、比亚迪走势" | 2个股票分析任务 | 返回两个股票的分析结果 |

### 4.4 IntentRoutingPrompt.java - 更新 Prompt 模板

**文件**: `ai-agent-study-domain/src/main/java/denny/ai/agent/domain/service/auto/step/routing/IntentRoutingPrompt.java`

在 `MULTI_TASK_DECOMPOSE_PROMPT` 的输出格式中，增加 `dependsOn` 字段与占位符说明：

```java
public static final String MULTI_TASK_DECOMPOSE_PROMPT = """
    ...
    ## 输出要求
    请严格按以下JSON格式输出，不要包含任何额外内容：
    {
      "multiTask": true/false,
      "needsClarification": true/false,
      "missingInfo": ["槽位名1"],
      "clarificationPrompt": "请提供 xxx",
      "reasoning": "分解判断理由",
      "taskList": [
        {
          "taskId": "sub-1",
          "taskIndex": 1,
          "totalTasks": 2,
          "content": "分析贵州茅台走势",
          "intent": "STOCK_ANALYSIS",
          "executorNode": "tradingStarter",
          "confidence": "HIGH",
          "taskType": 0,
          "dependsOn": [],
          "slots": {"stockCode": "600519", "stockQueryType": "TECHNICAL"}
        }
      ]
    }

    ## dependsOn 字段说明
    - dependsOn 必须为数组，表示该子任务依赖哪些前置任务的执行结果
    - 如果子任务不需要依赖前置结果，设置为空数组 []
    - 如果任务需要依赖前置结果，必须在 content 中使用占位符标记注入位置
    - 占位符格式：`<$DEPENDENCY$ taskId="sub-1" />`
    - 依赖规则：
      - 只能依赖已定义的前置 taskId
      - 禁止循环依赖（A 依赖 B，B 依赖 A）
      - 依赖的任务必须已完成才能执行当前任务
    ...
    """;
```

### 4.5 IntentRoutingNode.java - 解析 dependsOn 字段

**文件**: `ai-agent-study-domain/src/main/java/denny/ai/agent/domain/service/auto/step/routing/IntentRoutingNode.java`

`SubTask` 使用 Fastjson `toJavaObject(SubTask.class)` 反序列化时即可自动承接 `dependsOn` 字段，因此此处重点是：
- 确保 `SubTask` 新增了 `dependsOn` 字段
- 保持 `taskList = jsonArray.toJavaList(SubTask.class)` 的解析方式即可
- 在 fallback 场景中显式设置 `dependsOn = List.of()`，避免空语义不一致

```java
private MultiIntentRoutingResult buildSingleTaskFallback(String reason) {
    return MultiIntentRoutingResult.builder()
            .multiTask(false)
            .needsClarification(false)
            .reasoning(reason)
            .taskList(List.of(
                    SubTask.builder()
                            .taskId("fallback-1")
                            .taskIndex(1)
                            .totalTasks(1)
                            .content("通用对话")
                            .intent(IntentTypeEnum.GENERAL_CHAT)
                            .executorNode("generalChatNode")
                            .confidence(ConfidenceEnum.MEDIUM)
                            .dependsOn(List.of())
                            .status(SubTask.SubTaskStatus.PENDING)
                            .build()
            ))
            .build();
}
```

### 4.6 IntentRoutingNode.java - 依赖关系透传说明

确保解析后的 `dependsOn` 字段不在路由阶段丢失，并原样传递到 `MultiTaskExecutionNode` 执行阶段。

---

## 5. 测试用例

### 5.1 单元测试

| 测试ID | 测试场景 | 输入 | 预期结果 |
|--------|----------|------|----------|
| TC-MTI-001 | 无依赖任务执行 | task.dependsOn = [] | 使用原始 content 执行 |
| TC-MTI-002 | 有依赖任务执行 | task.dependsOn = ["sub-1"] | 占位符被替换为依赖结果 |
| TC-MTI-003 | 多依赖任务执行 | task.dependsOn = ["sub-1", "sub-2"] | 所有占位符被替换 |
| TC-MTI-004 | 依赖结果不存在 | task.dependsOn = ["non-existent"] | 占位符替换为"结果不可用" |
| TC-MTI-005 | 占位符格式验证 | content 含 `<$DEPENDENCY$ taskId="sub-1" />` | 正确解析并替换 |
| TC-MTI-006 | 多个占位符 | content 含 2 个占位符 | 按顺序替换 |
| TC-MTI-007 | 汇总节点收集结果 | 3个任务完成 | 从 DynamicContext 收集所有结果 |
| TC-MTI-008 | 汇总 Prompt 构建 | 包含依赖信息 | Prompt 包含依赖关系描述 |

### 5.2 循环依赖检测测试

| 测试ID | 测试场景 | 输入 | 预期结果 |
|--------|----------|------|----------|
| TC-MTI-DEP-001 | 正常依赖 | sub-1 → sub-2 → sub-3 | 正常执行 |
| TC-MTI-DEP-002 | 简单循环依赖 | sub-1 → sub-2 → sub-1 | LLM 检测到，返回 clarificationPrompt |
| TC-MTI-DEP-003 | 复杂循环依赖 | sub-1 → sub-2 → sub-3 → sub-1 | LLM 检测到，返回 clarificationPrompt |
| TC-MTI-DEP-004 | 自依赖 | sub-1 dependsOn: ["sub-1"] | LLM 检测到并修复 |
| TC-MTI-DEP-005 | 依赖不存在任务 | dependsOn: ["non-existent"] | LLM 检测到并修复 |

### 5.3 集成测试

| 测试ID | 测试场景 | 预期结果 |
|--------|----------|----------|
| TC-MTI-INT-001 | 3个独立任务顺序执行 | 每个任务使用干净上下文，汇总正常 |
| TC-MTI-INT-002 | 任务链式依赖 | sub-2 可获取 sub-1 结果，sub-3 可获取 sub-1, sub-2 结果 |
| TC-MTI-INT-003 | 混合依赖场景 | 部分任务有依赖，部分无依赖，都能正确执行 |
| TC-MTI-INT-004 | 循环依赖用户澄清 | 检测到循环后返回 clarificationPrompt，前端展示澄清 |

### 5.4 上下文隔离测试（核心）

**设计原则**：每个 Query 完成后，上下文必须完全隔离。

| 测试ID | 测试场景 | 输入 | 预期结果 |
|--------|----------|------|----------|
| TC-MTI-ISO-001 | 单 Query 清理验证 | Query1 执行完成 | subTaskResults 和多任务 key 已被清空 |
| TC-MTI-ISO-002 | 多 Query 隔离验证 | Query1 → Query2 | Query2 看不到 Query1 的 task 数据，sessionHistory 保留 |
| TC-MTI-ISO-003 | 并发 Query 隔离 | Query1 和 Query2 并发执行 | 各自的 DynamicContext 独立，互不影响 |
| TC-MTI-ISO-004 | 汇总后异步清理 | 汇总完成返回结果 | 返回后已触发异步清理，不残留 task 数据 |
| TC-MTI-ISO-005 | 异常场景清理 | 执行过程中抛异常 | 异常也被清理，不影响下一个 Query |

**验证方法**：
```java
@Test
void testQueryIsolation() {
    // 执行 Query1
    node.doApply(query1Request);
    assertTrue(dynamicContext.getAllSubTaskResults().isEmpty());
    assertNull(dynamicContext.getValue(MultiTaskExecutionNode.TASK_LIST_KEY));
    assertNull(dynamicContext.getValue(MultiTaskExecutionNode.ORIGINAL_MESSAGE_KEY));

    // 执行 Query2
    node.doApply(query2Request);
    // Query2 应该看到干净的 task 环境，但 sessionHistory 仍可用于多轮对话
}
```

### 5.5 占位符替换边界测试

| 测试ID | 测试场景 | 输入 | 预期结果 |
|--------|----------|------|----------|
| TC-MTI-PH-001 | 空占位符 | `content = "分析<$DEPENDENCY$ taskId=\"sub-1\" />"` | 替换为依赖结果 |
| TC-MTI-PH-002 | 占位符在开头 | `"<$DEPENDENCY$ taskId=\"sub-1\" />然后..."` | 结果在开头 |
| TC-MTI-PH-003 | 占位符在结尾 | `"先分析<$DEPENDENCY$ taskId=\"sub-1\" />"` | 结果在结尾 |
| TC-MTI-PH-004 | 连续占位符 | `"<$DEPENDENCY$ taskId=\"sub-1\" /><$DEPENDENCY$ taskId=\"sub-2\" />"` | 两个结果依次拼接 |
| TC-MTI-PH-005 | 占位符在嵌套结构 | `"结论：<引用><$DEPENDENCY$ taskId=\"sub-1\" /></引用>"` | 整体替换 |
| TC-MTI-PH-006 | 无效占位符格式 | `<$DEPENDENCY sub-1 />` | 不被替换，保持原样 |
| TC-MTI-PH-007 | 占位符含转义字符 | `taskId="sub-1\" />注入内容` | 正确处理转义 |

### 5.6 错误处理与降级测试

| 测试ID | 测试场景 | 输入 | 预期结果 |
|--------|----------|------|----------|
| TC-MTI-ERR-001 | 依赖任务执行失败 | sub-1 FAILED | sub-2 仍执行，占位符替换为"任务执行失败" |
| TC-MTI-ERR-002 | 所有任务失败 | taskList 全 FAILED | 汇总时返回"所有任务执行失败" |
| TC-MTI-ERR-003 | LLM 汇总失败 | ChatClient 抛异常 | 降级返回原始结果拼接 |
| TC-MTI-ERR-004 | 空 taskList | taskList = [] | 返回空或友好提示 |
| TC-MTI-ERR-005 | taskList 为 null | parse 返回 null | Fallback 到单任务处理 |

### 5.7 性能与安全测试

| 测试ID | 测试场景 | 输入 | 预期结果 |
|--------|----------|------|----------|
| TC-MTI-PERF-001 | 大结果注入 | sub-1 result = 100KB | 注入后 content 长度正常，无 OOM |
| TC-MTI-PERF-002 | 大量任务执行 | taskList = 100 个 | 顺序执行，每个任务上下文隔离 |
| TC-MTI-PERF-003 | 长依赖链 | sub-1→sub-2→...→sub-10 | 每步正确注入，无状态泄漏 |
| TC-MTI-SEC-001 | Prompt 注入攻击 | content 含恶意 prompt | 占位符替换不执行注入内容，仅作文本替换 |

---

## 6. 任务清单

| 任务ID | 任务描述 | 状态 |
|--------|----------|------|
| TASK-001 | SubTask.java 添加 dependsOn 字段 | pending |
| TASK-002 | DynamicContext.java 添加 subTaskResults 存储 | pending |
| TASK-003 | IntentRoutingPrompt.java 更新 Prompt 模板，增加 dependsOn 字段说明 | pending |
| TASK-004 | IntentRoutingPrompt.java 增加循环依赖检测验证规则 | pending |
| TASK-005 | IntentRoutingNode.java 解析 dependsOn 字段 | pending |
| TASK-006 | IntentRoutingNode.java 处理 dependencyError 响应 | pending |
| TASK-007 | IntentRoutingNode.java 更新 Fallback 逻辑 | pending |
| TASK-008 | MultiTaskExecutionNode.java 修改执行前逻辑（依赖结果注入） | pending |
| TASK-009 | MultiTaskExecutionNode.java 修改汇总逻辑 | pending |
| TASK-010 | MultiTaskExecutionNode.java 汇总完成后清理 subTaskResults | pending |
| TASK-011 | 编写单元测试 | 覆盖以下模块：<br>• 5.1 单元测试（8个）<br>• 5.2 循环依赖检测测试（5个）<br>• 5.4 上下文隔离测试（5个）<br>• 5.5 占位符替换边界测试（7个）<br>• 5.6 错误处理测试（5个）<br>• 5.7 性能与安全测试（4个） | pending |
| TASK-012 | 编译验证 | pending |

---

## 7. 循环依赖检测（LLM 检测方案）

**方案选择**：借助 IntentRoutingNode 的 LLM 能力检测循环依赖，改动最小且灵活性强。

### 7.1 设计思路

```
用户请求包含循环依赖 → LLM 分解任务时自动检测 → 发现循环返回错误提示 → 用户重新输入
```

### 7.2 IntentRoutingPrompt.java - 更新 Prompt 模板

在 `MULTI_TASK_DECOMPOSE_PROMPT` 中增加验证规则：

```java
public static final String MULTI_TASK_DECOMPOSE_PROMPT = """
    // ... 保持现有内容不变 ...

    ## 【新增】依赖验证规则
    在输出最终 JSON 之前，你必须完成以下验证：

    1. **依赖有效性检查**：
       - 每个 task 的 dependsOn 中的 taskId 必须存在于 taskList 中
       - 任务不能依赖自己（禁止 taskId="sub-1", dependsOn=["sub-1"]）

    2. **循环依赖检测**：
       - 检查是否存在 A→B→...→A 的循环
       - 如果检测到循环依赖，必须解决：
         - 方案A：移除导致循环的依赖边（通常是后置任务的错误依赖）
         - 方案B：将循环中的任务合并为单个任务
       - 禁止返回存在循环依赖的 taskList

    3. **验证通过后输出**：只有在所有验证通过后，才输出最终的 JSON

    ## 输出格式
    如果检测到循环依赖，请在 JSON 中增加错误提示：
    {
      "multiTask": true,
      "dependencyError": true,
      "dependencyErrorReason": "检测到循环依赖：sub-1 → sub-2 → sub-1",
      "clarificationPrompt": "您的请求中存在循环依赖，请重新描述任务顺序，例如：先做A再做B"
      ...
    }
    """;
```

### 7.3 IntentRoutingNode.java - 处理循环依赖响应

```java
@SuppressWarnings("unchecked")
private MultiIntentRoutingResult parseMultiTaskResponse(String response) {
    // ... 现有代码 ...

    // 【新增】检查是否检测到循环依赖
    if (json.containsKey("dependencyError") && Boolean.TRUE.equals(json.getBoolean("dependencyError"))) {
        String reason = json.getString("dependencyErrorReason");
        String clarificationPrompt = json.getString("clarificationPrompt");

        log.warn("LLM 检测到循环依赖: reason={}", reason);

        return MultiIntentRoutingResult.builder()
                .multiTask(false)  // 降级为单任务
                .needsClarification(true)  // 需要用户澄清
                .reasoning("依赖关系错误: " + reason)
                .missingInfo(List.of("taskDependency"))
                .clarificationPrompt(clarificationPrompt != null
                        ? clarificationPrompt
                        : "任务之间存在循环依赖，请重新描述任务顺序")
                .build();
    }

    // ... 继续正常解析 ...
}
```

### 7.4 执行流程

```
┌─────────────────────────────────────────────────────────────┐
│  用户请求："先分析A，基于A的结果分析B，再基于B的结果分析A"    │
└─────────────────────────────────────────────────────────────┘
                          │
                          ▼
┌─────────────────────────────────────────────────────────────┐
│  IntentRoutingNode.doMultiTaskRouting()                     │
│  调用 LLM 分解任务 + 验证依赖                               │
│                                                              │
│  LLM 检测到：                                               │
│    sub-1 → sub-2 → sub-3 → sub-1（循环）                  │
│  → 发现循环依赖                                              │
│  → 在 JSON 中返回 dependencyError: true                     │
└─────────────────────────────────────────────────────────────┘
                          │
                          ▼
┌─────────────────────────────────────────────────────────────┐
│  IntentRoutingNode.parseMultiTaskResponse()                  │
│  检测到 dependencyError=true                                │
│  → 构建 needsClarification=true 的响应                      │
│  → 返回给用户："任务之间存在循环依赖，请重新描述任务顺序"      │
└─────────────────────────────────────────────────────────────┘
                          │
                          ▼
┌─────────────────────────────────────────────────────────────┐
│  前端展示澄清提示，引导用户重新输入                           │
└─────────────────────────────────────────────────────────────┘
```

### 7.5 方案对比

| 对比项 | 代码实现拓扑排序 | LLM 检测（采用） |
|--------|-----------------|-----------------|
| 代码改动 | 需要新增图算法代码 | 只改 Prompt + 解析逻辑 |
| 复杂场景 | 简单环可检测，复杂 DAG 可能漏报 | LLM 推理能力强，可处理复杂情况 |
| 可维护性 | 需要维护图算法代码 | Prompt 调整即可 |
| 灵活性 | 固定逻辑 | LLM 可智能判断修复方案 |
| 依赖准确性 | 可能与 LLM 理解不一致 | LLM 自己分解，自己验证，一致性强 |

---

## 8. 后续优化

1. **压缩前置到汇总节点**：汇总节点可根据结果大小自动触发压缩
2. **依赖可视化**：在管理界面展示任务依赖关系图

---

## 9. 参考资料

- Claude Code Auto-Compact 设计
- 当前 `CompressionContextNode` 实现
- 当前 `MultiTaskExecutionNode` 实现
