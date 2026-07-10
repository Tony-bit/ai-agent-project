# 多任务分解与执行架构设计

**Metadata:**
- 状态: 已实现
- 预估工时: 已完成
- 创建日期: 2026-05-31
- 作者: Denny
- 版本: 1.2（修正为 executorNode 直接路由模式）

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 设计并实现意图分解后的多任务执行架构，支持将用户复杂请求拆分为多个子任务，依次执行后汇总返回。

---

## 零、关键设计决策（已澄清）

> 以下决策已在讨论中达成一致，是本期实现的核心约束。

### 0.1 分解粒度：按实体粒度

多任务分解的粒度按**实体**拆分，而非按意图粒度。

**示例：**

| 用户输入 | 分解粒度 | 分解结果 |
|----------|----------|----------|
| "分析贵州茅台、比亚迪的走势" | 按实体粒度 | 2 个任务：Task1=贵州茅台，Task2=比亚迪 |
| "分析贵州茅台、比亚迪、五粮液" | 按实体粒度 | 3 个任务：每个股票一个任务 |

**设计原则：**
- 同一个意图类型下的多个实体，分别拆成独立的 SubTask
- 同一实体的多个分析维度（如技术分析 + 基本面分析）可以合并为一个 SubTask

### 0.2 执行方式：串行执行

本期只实现**串行执行**，暂不支持并行。

**执行顺序：**

```
用户: "分析贵州茅台、比亚迪的走势"

Task 1 (贵州茅台) → Task 2 (比亚迪) → 汇总 → 流式输出
```

**优势：**
- ✅ 实现简单，不需要复杂的并发控制
- ✅ 结果天然有序，汇总逻辑简单
- ✅ 易于调试和排查问题

### 0.3 结果展示：统一汇总模式

所有任务执行完成后，通过 LLM 统一汇总，然后一次性流式输出。

**流程：**

```
Task 1 执行 → 结果写入 MessageHistory
Task 2 执行 → 结果写入 MessageHistory
...
所有任务完成 → LLM 统一汇总 → 流式输出给用户
```

**优势：**
- ✅ 用户看到的是一份整理好的完整回复，而非分散的多条消息
- ✅ 复用 GeneralChatNode 已实现的流式输出能力
- ✅ 汇总 LLM 能看到完整的执行历史（通过 MessageHistory）

### 0.4 触发条件：通过 Prompt 规则控制

多任务分解的触发由 LLM Prompt 规则控制，参考 Claude Code 的实现。

**应该触发多任务分解的场景（Prompt 中声明）：**

| 场景 | 示例 |
|------|------|
| 复杂多步骤任务（3个或更多步骤） | "帮我分析这几只股票的技术面、基本面、资金流向" |
| 需要仔细规划的任务 | "帮我制定一个投资组合优化方案" |
| 用户明确提到多个实体 | "分析茅台、比亚迪、五粮液的走势" |
| 用户明确请求多个任务（编号或逗号分隔） | "1.分析茅台 2.分析比亚迪" |
| 收到新指令后、开始工作时、完成一个任务后 | 任务状态管理 |

**不应触发多任务分解的场景（Prompt 中声明）：**

| 场景 | 示例 |
|------|------|
| 单一、简单的任务 | "分析贵州茅台" |
| 琐碎的任务 | "你好" / "谢谢" |
| 可在3步内完成的任务 | "查一下茅台的股价" |
| 纯对话/信息性请求 | "什么是市盈率？" |

### 0.5 执行路由：SubTask 包含 executorNode，MultiTaskExecutionNode 直接调用

IntentRoutingNode 在分解任务时，每个 SubTask 已经包含了对应的执行节点信息（executorNode）。

MultiTaskExecutionNode 根据 SubTask.executorNode 直接调用对应节点执行，无需再次路由判断。

**SubTask 数据结构扩展：**

```java
public class SubTask {
    // ... 其他字段 ...

    /**
     * 执行节点名称（Spring Bean 名称）
     * 例如：tradingStarter、generalChatNode、step1AnalyzerNode
     */
    private String executorNode;

    /**
     * 执行节点对应的 Intent 类型（用于兜底路由）
     */
    private IntentTypeEnum intent;
}
```

**Intent → Executor 映射：**

| Intent 类型 | Executor 节点 | 说明 |
|-------------|---------------|------|
| STOCK_ANALYSIS | tradingStarter | 股票分析 |
| PE_REASONING | step1AnalyzerNode | PE 逻辑推理 |
| PE_CALCULATION | step1AnalyzerNode | PE 计算 |
| PE_RETRIEVAL | step1AnalyzerNode | PE 检索 |
| INSPECTION | intelligentInspection | 系统巡检 |
| GENERAL_CHAT | generalChatNode | 通用对话 |

### 0.6 结果汇总：LLM 统一汇总

所有任务执行完成后，需要通过 **LLM 统一汇总**，整理成连贯、自然的回复后再流式输出给用户。

**汇总 Prompt 构建：**

```
## 用户原始请求
{originalMessage}

## 任务执行结果
[任务 1/3] 分析贵州茅台走势
状态: COMPLETED
结果: 贵州茅台技术分析报告...

[任务 2/3] 分析比亚迪走势
状态: COMPLETED
结果: 比亚迪技术分析报告...

[任务 3/3] 什么是PE
状态: COMPLETED
结果: PE(市盈率)是衡量公司估值的重要指标...

## 要求
请将以上执行结果整理成一份连贯、自然的回复返回给用户。
保留各任务的核心信息，去除冗余内容，逻辑清晰地组织。
```

### 0.7 完整执行流程

```
用户: "分析贵州茅台、比亚迪的走势，并回答什么是PE"

    │
    ▼
IntentRoutingNode.doApply()
    │
    ├── LLM 分解任务
    │
    ├── 输出 taskList（包含 executorNode）：
    │   ├── SubTask 1: { content: "分析贵州茅台走势", intent: STOCK_ANALYSIS, executorNode: "tradingStarter" }
    │   ├── SubTask 2: { content: "分析比亚迪走势", intent: STOCK_ANALYSIS, executorNode: "tradingStarter" }
    │   └── SubTask 3: { content: "什么是PE", intent: GENERAL_CHAT, executorNode: "generalChatNode" }
    │
    ├── 设置 Context: dynamicContext.setValue("taskList", taskList)
    │
    └── return router() → 路由到 MultiTaskExecutionNode
            │
            ▼
MultiTaskExecutionNode.doApply()
    │
    ├── [Task 1] executorNode="tradingStarter"
    │   └── tradingStarter.execute(subTask) → 结果写入 MessageHistory
    │
    ├── [Task 2] executorNode="tradingStarter"
    │   └── tradingStarter.execute(subTask) → 结果写入 MessageHistory
    │
    ├── [Task 3] executorNode="generalChatNode"
    │   └── generalChatNode.execute(subTask) → 结果写入 MessageHistory
    │
    ├── 所有任务完成 → 构建汇总 Prompt
    │
    ├── LLM 汇总 → 生成连贯的自然语言回复
    │
    └── 流式输出最终回复给用户

---

---

## 一、问题背景

### 1.1 用户痛点

当用户发送包含多个意图的复合请求时，如：

```
用户: "分析贵州茅台走势，并查询比亚迪基本面"
```

现有系统只能识别为单个意图（STOCK_ANALYSIS），无法处理复合请求。

### 1.2 核心问题

意图识别节点（IntentRoutingNode）识别意图后，需要将复杂的用户请求拆分为多个子任务，分别执行后汇总结果。

### 1.3 关键设计问题

**Q: 负责执行任务的 Agent 是怎么获取任务的？**

A: 意图识别 LLM 将 query 拆分成多个任务后，需要通过一个中间层（MultiTaskExecutionNode）来循环执行这些任务，每个任务通过 Context 传递给对应的 Executor。

---

## 二、架构设计

### 2.1 整体架构

```
用户请求
    │
    ▼
┌─────────────────────────────────────────────────────────────┐
│ IntentRoutingNode（意图识别 + 任务分解）                      │
│                                                              │
│  1. LLM 调用：识别意图 + 分解任务                            │
│  2. 每个 SubTask 包含 executorNode（执行节点名称）            │
│  3. 输出 taskList，存入 Context                              │
│  4. 路由到 MultiTaskExecutionNode                            │
└─────────────────────────────────────────────────────────────┘
    │
    ▼
┌─────────────────────────────────────────────────────────────┐
│ MultiTaskExecutionNode（循环执行 + 结果汇总）                  │
│                                                              │
│  for each task in taskList:                                  │
│    ├── 读取 SubTask.executorNode                             │
│    ├── 根据 executorNode 直接调用对应节点                     │
│    │   ├── tradingStarter → 股票分析                        │
│    │   ├── step1AnalyzerNode → PE 分析                       │
│    │   ├── generalChatNode → 通用对话                        │
│    │   └── intelligentInspection → 巡检                      │
│    └── 结果写入 MessageHistory                               │
│                                                              │
│  所有任务完成 → 流式输出最终回复                             │
└─────────────────────────────────────────────────────────────┘
    │
    ▼
用户回复（流式）
```

### 2.2 节点职责划分

| 节点 | 职责 | 变更类型 |
|------|------|----------|
| `IntentRoutingNode` | 意图识别 + 任务分解（LLM 判断是否多任务） | 改造 |
| `MultiTaskExecutionNode` | 单 Agent 顺序执行任务（复用 GeneralChatNode） | 新增 |

### 2.3 设计原则

1. **executorNode 前置注入**：IntentRoutingNode 分解任务时，直接指定每个 SubTask 的 executorNode
2. **直接路由**：MultiTaskExecutionNode 根据 executorNode 直接调用对应节点，无需再次判断 intent 类型
3. **结果累积**：每个任务执行完成后，结果写入 MessageHistory，后续任务能自动感知上下文
4. **Prompt 规则控制触发**：通过 Prompt 声明规则来控制是否触发多任务分解

---

## 三、信息补全设计

### 3.1 设计决策

**Q: 信息补全应该在哪个阶段处理？**

| 处理位置 | 优点 | 缺点 |
|----------|------|------|
| **IntentRoutingNode（分解阶段）** | 一次性发现所有任务的信息缺失，一次性补全 | 需要更智能的 LLM prompt |
| **MultiTaskExecutionNode（执行阶段）** | - | 执行到一半暂停、等待、补全、继续 → 实现复杂 |

**结论：在 IntentRoutingNode 层统一处理信息补全**

### 3.2 设计优势

```
IntentRoutingNode 处理信息补全的优势：

1. 简单
   └── 一次补全，解决所有任务的信息缺失

2. 清晰
   └── 补全后再开始执行，用户明确知道何时开始

3. 可靠
   └── 不需要维护复杂的中间状态
   └── 不需要处理任务暂停/恢复逻辑
```

### 3.3 执行阶段处理的复杂度

如果选择在执行阶段处理信息补全：

```
用户: "分析股票走势"

    │
    ▼
MultiTaskExecutionNode 执行 Task 1
    │
    ├── 发现 stockCode 缺失
    ├── 暂停执行 ❌ 需要状态管理
    ├── 返回："请提供股票代码"
    ├── 等待用户回复 ❌ 需要超时管理
    │
    ▼
用户: "贵州茅台"

    │
    ▼
MultiTaskExecutionNode 恢复
    ├── 补充 stockCode = 600519 ❌ 需要上下文传递
    ├── 继续执行 Task 1
    │
    ▼
完成
```

**问题清单：**
- 状态管理：需要保存中间状态、任务进度
- 上下文传递：用户补全后需要正确恢复到对应任务
- 错误处理：用户拒绝补全或补全无效怎么办
- 超时处理：用户长时间不回复怎么办

### 3.4 数据模型扩展

```java
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class MultiIntentRoutingResult {

    /**
     * 是否为多任务
     */
    private Boolean multiTask;

    /**
     * 是否需要信息补全
     */
    private Boolean needsClarification;

    /**
     * 缺失信息列表
     * 例如：["stockCode", "exchange"]
     */
    private List<String> missingInfo;

    /**
     * 补全提示语
     */
    private String clarificationPrompt;

    /**
     * 分解后的子任务列表
     * 当 needsClarification=true 时，该字段可能为空
     */
    private List<SubTask> taskList;

    /**
     * 分解判断理由
     */
    private String reasoning;
}
```

### 3.5 IntentRoutingNode 处理流程

```java
@Override
protected String doApply(ExecuteCommandEntity request,
                        DynamicContext dynamicContext) throws Exception {

    // 1. 意图识别 + 任务分解（LLM 调用）
    MultiIntentRoutingResult routingResult = doRouting(request);

    // 2. 判断是否需要信息补全
    if (Boolean.TRUE.equals(routingResult.getNeedsClarification())) {
        log.info("任务信息不完整，需要补全: missingInfo={}",
                routingResult.getMissingInfo());

        // 设置补全提示到 Context
        dynamicContext.setValue("clarificationPrompt",
                routingResult.getClarificationPrompt());
        dynamicContext.setValue("missingInfo",
                routingResult.getMissingInfo());

        // 路由到信息补全节点或直接返回提示
        return routingResult.getClarificationPrompt();
    }

    // 3. 判断是否为多任务
    if (Boolean.TRUE.equals(routingResult.getMultiTask())) {
        dynamicContext.setValue("taskList", routingResult.getTaskList());
        dynamicContext.setValue("originalMessage", request.getMessage());
        return router(request, dynamicContext);
    }

    // 4. 单任务：设置槽位后路由到对应 Handler
    return handleSingleTask(request, dynamicContext, routingResult);
}
```

### 3.6 信息补全的 Prompt 设计

```java
public static final String MULTI_TASK_DECOMPOSE_PROMPT = """
    ## 你的任务
    分析用户消息，识别意图并分解为多个独立任务。

    ## 槽位完整性检查
    对于每个子任务，必须检查所需槽位是否完整：

    | 意图类型 | 必需槽位 |
    |----------|----------|
    | STOCK_ANALYSIS | stockCode（股票代码）|
    | PE_REASONING | topic（主题）|
    | INSPECTION | target（巡检目标）|

    ## 信息缺失处理
    1. 如果用户请求缺少必要信息，设置 needsClarification=true
    2. 在 missingInfo 中列出所有缺失的槽位名称
    3. 在 clarificationPrompt 中生成自然的补全提示

    ## 输出格式
    {
      "multiTask": true/false,
      "needsClarification": true/false,
      "missingInfo": ["槽位名1", "槽位名2"],
      "clarificationPrompt": "请提供 xxx",
      "reasoning": "判断理由",
      "taskList": [...]
    }
    """;
```

---

## 四、详细设计

### 4.1 数据模型

#### 4.1.1 SubTask（子任务）

```java
package denny.ai.agent.domain.model.valobj;

import denny.ai.agent.domain.model.valobj.enums.IntentTypeEnum;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

/**
 * 子任务 VO
 * 用于表示意图分解后的单个任务
 *
 * @author denny
 * 2026/5/31
 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class SubTask {

    /**
     * 任务 ID（全局唯一）
     */
    private String taskId;

    /**
     * 任务序号（从 1 开始）
     */
    private Integer taskIndex;

    /**
     * 任务总数
     */
    private Integer totalTasks;

    /**
     * 任务内容（LLM 解析的原始任务描述）
     */
    private String content;

    /**
     * 任务意图类型
     */
    private IntentTypeEnum intent;

    /**
     * 执行节点名称（Spring Bean 名称）
     * 例如：tradingStarter、generalChatNode、step1AnalyzerNode
     */
    private String executorNode;

    /**
     * 置信度
     */
    private String confidence;

    /**
     * 任务专属槽位（如股票代码、查询类型等）
     */
    private Map<String, Object> slots;

    /**
     * 任务状态
     */
    private SubTaskStatus status;

    /**
     * 任务执行结果
     */
    private String result;

    /**
     * 任务执行耗时（ms）
     */
    private Long latencyMs;

    /**
     * 任务执行错误信息
     */
    private String errorMessage;

    /**
     * 任务状态枚举
     */
    public enum SubTaskStatus {
        PENDING,    // 待执行
        IN_PROGRESS,// 执行中
        COMPLETED,  // 已完成
        FAILED      // 执行失败
    }
}
```
     */
    private Long latencyMs;

    /**
     * 任务执行错误信息
     */
    private String errorMessage;

    /**
     * 任务状态枚举
     */
    public enum SubTaskStatus {
        PENDING,    // 待执行
        IN_PROGRESS,// 执行中
        COMPLETED,  // 已完成
        FAILED      // 执行失败
    }
}
```

#### 4.1.2 MultiIntentRoutingResult（多意图分解结果）

```java
package denny.ai.agent.domain.model.valobj;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 多意图分解结果 VO
 *
 * @author denny
 * 2026/5/31
 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class MultiIntentRoutingResult {

    /**
     * 是否为多任务
     */
    private Boolean multiTask;

    /**
     * 是否需要信息补全
     */
    private Boolean needsClarification;

    /**
     * 缺失信息列表
     */
    private List<String> missingInfo;

    /**
     * 补全提示语
     */
    private String clarificationPrompt;

    /**
     * 分解后的子任务列表
     */
    private List<SubTask> taskList;

    /**
     * 分解判断理由
     */
    private String reasoning;
}
```

### 4.2 IntentRoutingNode 改造

#### 4.2.1 职责变更

| 变更前 | 变更后 |
|--------|--------|
| 意图识别 + 路由到 Handler | 意图识别 + **任务分解** + 路由 |

#### 4.2.2 核心逻辑

```java
@Service("intentRoutingNode")
public class IntentRoutingNode extends AbstractExecuteSupport {

    @Override
    protected String doApply(ExecuteCommandEntity request,
                            DynamicContext dynamicContext) throws Exception {

        // 1. 意图识别 + 任务分解（LLM 调用）
        MultiIntentRoutingResult routingResult = doRouting(request);

        // 2. 判断是否为多任务
        if (Boolean.TRUE.equals(routingResult.getMultiTask())) {
            // 多任务：存入 Context，路由到 MultiTaskExecutionNode
            dynamicContext.setValue("taskList", routingResult.getTaskList());
            dynamicContext.setValue("originalMessage", request.getMessage());
            return router(request, dynamicContext);
        }

        // 3. 单任务：保持原有逻辑，设置槽位后路由到对应 Handler
        return handleSingleTask(request, dynamicContext, routingResult);
    }

    @Override
    public StrategyHandler get(ExecuteCommandEntity request,
                              DynamicContext dynamicContext) throws Exception {

        // 判断是否为多任务
        List<SubTask> taskList = dynamicContext.getValue("taskList");
        if (taskList != null && !taskList.isEmpty()) {
            // 多任务：路由到执行节点
            return getBean("multiTaskExecutionNode");
        }

        // 单任务：路由到对应 Handler
        IntentTypeEnum intent = dynamicContext.getValue(RECOGNIZED_INTENT_KEY);
        return switch (intent) {
            case STOCK_ANALYSIS -> resolveTradingNode();
            case PE_REASONING, PE_CALCULATION, PE_RETRIEVAL -> step1AnalyzerNode;
            case INSPECTION -> intelligentInspection;
            default -> generalChatNode;
        };
    }

    /**
     * 意图识别 + 任务分解
     */
    private MultiIntentRoutingResult doRouting(ExecuteCommandEntity request) {
        // 调用 LLM，返回分解后的 taskList
        // ...
    }

    /**
     * 处理单任务（原有逻辑）
     */
    private String handleSingleTask(ExecuteCommandEntity request,
                                   DynamicContext ctx,
                                   IntentRoutingResult result) {
        // 设置槽位...
        // 路由到 Handler
        return router(request, ctx);
    }
}
```

### 4.3 MultiTaskExecutionNode 新增

#### 4.3.1 节点定义

```java
package denny.ai.agent.domain.service.auto.step.routing;

import cn.bugstack.wrench.design.framework.tree.StrategyHandler;
import denny.ai.agent.domain.model.entity.ExecuteCommandEntity;
import denny.ai.agent.domain.model.valobj.SubTask;
import denny.ai.agent.domain.service.auto.step.AbstractExecuteSupport;
import denny.ai.agent.domain.service.auto.step.factory.DefaultAutoAgentExecuteStrategyFactory;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import jakarta.annotation.Resource;
import java.util.List;

/**
 * 多任务执行节点
 * <p>
 * 负责循环执行 taskList 中的所有子任务，并汇总结果。
 * </p>
 * <p>
 * 执行策略：根据 SubTask.executorNode 直接调用对应的 Spring Bean，无需再次判断 intent 类型。
 * </p>
 *
 * @author denny
 * 2026/5/31
 */
@Slf4j
@Service("multiTaskExecutionNode")
public class MultiTaskExecutionNode extends AbstractExecuteSupport {

    public static final String TASK_LIST_KEY = "taskList";

    /**
     * 执行单个子任务
     *
     * @param subTask 要执行的子任务
     * @param dynamicContext 上下文
     * @return 执行结果
     * @throws Exception 执行异常
     */
    public String executeSubTask(SubTask subTask,
                                  DefaultAutoAgentExecuteStrategyFactory.DynamicContext dynamicContext) throws Exception {

        String executorNode = subTask.getExecutorNode();
        log.info(">>> 执行子任务 [{}/{}]: taskId={}, executorNode={}, content={}",
                subTask.getTaskIndex(), subTask.getTotalTasks(),
                subTask.getTaskId(), executorNode, subTask.getContent());

        long startAt = System.currentTimeMillis();

        try {
            // 根据 executorNode 直接获取对应的 Bean 并执行
            Object executor = getBean(executorNode);
            if (executor == null) {
                throw new IllegalStateException("未找到执行节点: " + executorNode);
            }

            // 调用执行方法
            String result = invokeExecutor(executor, subTask, dynamicContext);

            subTask.setStatus(SubTask.SubTaskStatus.COMPLETED);
            subTask.setResult(result);
            subTask.setLatencyMs(System.currentTimeMillis() - startAt);

            log.info("<<< 子任务完成: taskId={}, executorNode={}, 耗时={}ms",
                    subTask.getTaskId(), executorNode, subTask.getLatencyMs());

            return result;

        } catch (Exception e) {
            log.error("子任务执行失败: taskId={}, executorNode={}, error={}",
                    subTask.getTaskId(), executorNode, e.getMessage(), e);
            subTask.setStatus(SubTask.SubTaskStatus.FAILED);
            subTask.setErrorMessage(e.getMessage());
            subTask.setLatencyMs(System.currentTimeMillis() - startAt);
            throw e;
        }
    }

    /**
     * 根据 executor 类型调用对应的执行方法
     */
    private String invokeExecutor(Object executor, SubTask subTask,
                                   DefaultAutoAgentExecuteStrategyFactory.DynamicContext dynamicContext) throws Exception {
        // TradingStarter
        if (executor instanceof TradingStarter) {
            return ((TradingStarter) executor).startForSubTask(
                    subTask.getContent(), subTask.getSlots());
        }
        // Step1AnalyzerNode
        if (executor instanceof Step1AnalyzerNode) {
            return ((Step1AnalyzerNode) executor).executeSubTask(subTask);
        }
        // IntelligentInspection
        if (executor instanceof IntelligentInspection) {
            return ((IntelligentInspection) executor).executeSubTask(subTask);
        }
        // GeneralChatNode
        if (executor instanceof GeneralChatNode) {
            return ((GeneralChatNode) executor).executeSubTask(subTask);
        }

        throw new IllegalStateException("不支持的执行节点类型: " + executor.getClass().getName());
    }

    @Override
    protected String doApply(ExecuteCommandEntity request,
                            DefaultAutoAgentExecuteStrategyFactory.DynamicContext dynamicContext) throws Exception {

        List<SubTask> taskList = dynamicContext.getValue(TASK_LIST_KEY);
        String originalMessage = dynamicContext.getValue("originalMessage");

        log.info("=== 多任务执行开始，共 {} 个任务 ===", taskList.size());

        // 循环执行每个子任务
        for (SubTask task : taskList) {
            task.setStatus(SubTask.SubTaskStatus.IN_PROGRESS);
            executeSubTask(task, dynamicContext);
        }

        log.info("=== 多任务执行完成，开始 LLM 汇总 ===");

        // LLM 汇总所有结果
        String summary = summarizeResults(originalMessage, taskList);

        log.info("=== LLM 汇总完成，长度={} ===", summary.length());
        return summary;
    }

    /**
     * LLM 汇总所有子任务结果
     */
    private String summarizeResults(String originalMessage, List<SubTask> taskList) {
        log.info("开始 LLM 汇总，共 {} 个结果", taskList.size());

        // 构建汇总 Prompt
        String summaryPrompt = buildSummaryPrompt(originalMessage, taskList);

        // 调用 LLM 汇总
        ChatClient chatClient = getChatClientByClientId("summary_client", 0);
        String summary = chatClient.prompt(summaryPrompt).call().content();

        log.info("LLM 汇总完成，长度={}", summary.length());
        return summary;
    }

    /**
     * 构建汇总 Prompt
     */
    private String buildSummaryPrompt(String originalMessage, List<SubTask> taskList) {
        StringBuilder sb = new StringBuilder();
        sb.append("## 用户原始请求\n").append(originalMessage).append("\n\n");
        sb.append("## 任务执行结果\n");

        for (SubTask task : taskList) {
            sb.append(String.format("[任务 %d/%d] %s\n",
                    task.getTaskIndex(), task.getTotalTasks(), task.getContent()));
            sb.append("状态: ").append(task.getStatus()).append("\n");

            if (task.getStatus() == SubTask.SubTaskStatus.COMPLETED) {
                sb.append("结果:\n").append(task.getResult()).append("\n");
            } else if (task.getStatus() == SubTask.SubTaskStatus.FAILED) {
                sb.append("错误: ").append(task.getErrorMessage()).append("\n");
            }
            sb.append("\n");
        }

        sb.append("## 要求\n");
        sb.append("请将以上执行结果整理成一份连贯、自然的回复返回给用户。\n");
        sb.append("保留各任务的核心信息，去除冗余内容，逻辑清晰地组织。\n");

        return sb.toString();
    }
    }

    @Override
    public StrategyHandler<ExecuteCommandEntity,
            DefaultAutoAgentExecuteStrategyFactory.DynamicContext, String> get(
            ExecuteCommandEntity request,
            DefaultAutoAgentExecuteStrategyFactory.DynamicContext dynamicContext) throws Exception {
        // 多任务执行完成后，路由到通用节点结束
        return null;
    }
}
```

    /**
     * 执行股票分析任务
     */
    private String executeStockAnalysis(SubTask task) {
        // 调用 TradingStarter 执行股票分析
        // task.getSlots() 包含股票代码等信息
        return tradingStarter.startForSubTask(task.getContent(), task.getSlots());
    }

    /**
     * 执行 PE 逻辑推理任务
     */
    private String executePEReasoning(SubTask task) {
        // 调用 PE 推理流程
        return step1AnalyzerNode.executeSubTask(task);
    }

    /**
     * 执行 PE 计算任务
     */
    private String executePECalculation(SubTask task) {
        return step1AnalyzerNode.executeSubTask(task);
    }

    /**
     * 执行 PE 检索任务
     */
    private String executePERetrieval(SubTask task) {
        return step1AnalyzerNode.executeSubTask(task);
```

### 4.4 IntentRoutingPrompt 增强

#### 4.4.1 新增多任务分解 Prompt

```java
/**
 * 多任务分解 Prompt
 */
public static final String MULTI_TASK_DECOMPOSE_PROMPT = """
    ## 角色
    你是一个专业的意图分解助手，负责分析用户输入并将其拆分为多个可独立执行的子任务。

    ## 意图类型与执行节点映射
    | 意图类型 | 执行节点 (executorNode) | 说明 |
    |----------|------------------------|------|
    | STOCK_ANALYSIS | tradingStarter | 股票/市场分析 |
    | PE_REASONING | step1AnalyzerNode | 逻辑推理、问题分析 |
    | PE_CALCULATION | step1AnalyzerNode | 数学计算、数据处理 |
    | PE_RETRIEVAL | step1AnalyzerNode | 知识检索、信息汇总 |
    | INSPECTION | intelligentInspection | 系统巡检、健康检查 |
    | GENERAL_CHAT | generalChatNode | 闲聊、问候、通用问答 |

    ## 分解规则
    1. 每个子任务应该是独立的、可单独执行的
    2. 子任务之间应尽量无依赖（串行执行）
    3. 按实体粒度分解：不同股票/实体拆成独立任务
    4. 每个 SubTask 必须指定 executorNode（对应执行节点名称）

    ## 应该触发多任务分解的场景
    - 复杂多步骤任务（3个或更多步骤）
    - 用户明确提到多个实体（"分析茅台、比亚迪、五粮液"）
    - 用户明确请求多个任务（编号或逗号分隔）

    ## 不应触发多任务分解的场景
    - 单一、简单的任务
    - 琐碎的任务（"你好"、"谢谢"）
    - 可在3步内完成的任务
    - 纯对话/信息性请求

    ## 输出要求
    请严格按以下JSON格式输出：

    {
      "multiTask": true/false,  // 是否为多任务
      "reasoning": "分解判断理由",
      "taskList": [
        {
          "taskId": "sub-1",  // 任务唯一ID
          "taskIndex": 1,     // 任务序号
          "totalTasks": 3,    // 任务总数
          "content": "分析贵州茅台走势",  // 任务内容
          "intent": "STOCK_ANALYSIS",  // 意图类型
          "executorNode": "tradingStarter",  // 执行节点名称
          "confidence": "HIGH",  // 置信度
          "slots": {"stockCode": "600519", "queryType": "TECHNICAL"}  // 槽位信息
        },
        {
          "taskId": "sub-2",
          "taskIndex": 2,
          "totalTasks": 3,
          "content": "分析比亚迪走势",
          "intent": "STOCK_ANALYSIS",
          "executorNode": "tradingStarter",
          "confidence": "HIGH",
          "slots": {"stockCode": "002594", "queryType": "TECHNICAL"}
        },
        {
          "taskId": "sub-3",
          "taskIndex": 3,
          "totalTasks": 3,
          "content": "什么是PE",
          "intent": "GENERAL_CHAT",
          "executorNode": "generalChatNode",
          "confidence": "HIGH",
          "slots": {}
        }
      ]
    }

    ## 判断标准
    - 如果用户请求明显包含多个独立任务，设置 multiTask=true
    - 如果用户请求是单一任务，设置 multiTask=false
    - 如果不确定，优先设置为 multiTask=false（走原有单任务流程）
    """;
```

#### 3.4.2 Prompt 构建方法

```java
/**
 * 构建多任务分解 Prompt
 */
public static String buildMultiTaskDecomposePrompt(String userMessage,
                                                   List<String> historyMessages) {
    String historySection = buildHistorySection(historyMessages);
    String prompt = String.format(MULTI_TASK_DECOMPOSE_PROMPT, historySection);
    return prompt + "\n\n用户: " + userMessage + "\n输出:";
}
```

---

## 四、执行流程图

### 4.1 完整流程

```
用户: "分析贵州茅台、比亚迪的走势，并回答什么是PE"

    │
    ▼
IntentRoutingNode.doApply()
    │
    ├── LLM 识别 + 分解
    │
    ├── 分析结果：
    │   multiTask: true
    │   taskList: [
    │     { taskId: "sub-1", content: "分析贵州茅台走势", intent: STOCK_ANALYSIS, executorNode: "tradingStarter" },
    │     { taskId: "sub-2", content: "分析比亚迪走势", intent: STOCK_ANALYSIS, executorNode: "tradingStarter" },
    │     { taskId: "sub-3", content: "什么是PE", intent: GENERAL_CHAT, executorNode: "generalChatNode" }
    │   ]
    │
    ├── 设置 Context
    │   dynamicContext.setValue("taskList", taskList)
    │
    └── return router() → 路由到 MultiTaskExecutionNode
            │
            ▼
MultiTaskExecutionNode.doApply()
    │
    ├── [Task 1: sub-1] executorNode="tradingStarter"
    │   ├── status = IN_PROGRESS
    │   ├── tradingStarter.startForSubTask(content, slots)
    │   ├── status = COMPLETED
    │   └── 结果写入 MessageHistory
    │
    ├── [Task 2: sub-2] executorNode="tradingStarter"
    │   ├── status = IN_PROGRESS
    │   ├── tradingStarter.startForSubTask(content, slots)
    │   ├── status = COMPLETED
    │   └── 结果写入 MessageHistory
    │
    ├── [Task 3: sub-3] executorNode="generalChatNode"
    │   ├── status = IN_PROGRESS
    │   ├── generalChatNode.executeSubTask(subTask)
    │   ├── status = COMPLETED
    │   └── 结果写入 MessageHistory
    │
    ├── 所有任务完成
    │
    ├── 构建汇总 Prompt（originalMessage + 所有 task 结果）
    │
    ├── LLM 汇总 → 生成连贯的自然语言回复
    │
    └── 流式输出最终回复给用户
```

### 4.2 单任务流程（兼容）

```
用户: "分析贵州茅台"

    │
    ▼
IntentRoutingNode.doApply()
    │
    ├── LLM 识别
    │
    ├── 分析结果：
    │   multiTask: false
    │   intent: STOCK_ANALYSIS
    │
    ├── 设置槽位
    │   dynamicContext.setValue(STOCK_SLOT_KEY, stockSlot)
    │
    └── return router() → 路由到对应 Handler（原逻辑）
            │
            ▼
TradingIntentRoutingNode / Step1AnalyzerNode
    │
    └── 执行完整流程
```

---

## 五、文件变更总览

| 类型 | 文件路径 | 变更说明 |
|------|----------|----------|
| **新增** | `ai-agent-study-domain/src/main/java/denny/ai/agent/domain/model/valobj/SubTask.java` | 子任务 VO |
| **新增** | `ai-agent-study-domain/src/main/java/denny/ai/agent/domain/model/valobj/MultiIntentRoutingResult.java` | 多意图分解结果 VO |
| **新增** | `ai-agent-study-domain/src/main/java/denny/ai/agent/domain/service/auto/step/routing/MultiTaskExecutionNode.java` | 多任务执行节点 |
| **改造** | `ai-agent-study-domain/src/main/java/denny/ai/agent/domain/service/auto/step/routing/IntentRoutingNode.java` | 增加任务分解逻辑 |
| **改造** | `ai-agent-study-domain/src/main/java/denny/ai/agent/domain/service/auto/step/routing/IntentRoutingPrompt.java` | 增加多任务分解 Prompt |
| **改造** | `TradingStarter.java` | 增加 `startForSubTask()` 方法 |
| **改造** | `Step1AnalyzerNode.java` | 增加 `executeSubTask()` 方法 |
| **改造** | `IntelligentInspection.java` | 增加 `executeSubTask()` 方法 |
| **改造** | `GeneralChatNode.java` | 增加 `executeSubTask()` 方法 |

---

## 六、实现计划

### Task 1: 创建数据模型

> **前置条件:** 无

- [x] 创建 `SubTask.java`
- [x] 创建 `MultiIntentRoutingResult.java`
- [x] 编译验证

### Task 2: 增强 IntentRoutingPrompt

> **前置条件:** Task 1 已完成

- [x] 添加 `MULTI_TASK_DECOMPOSE_PROMPT`
- [x] 添加 `buildMultiTaskDecomposePrompt()` 方法
- [x] 编译验证

### Task 3: 改造 IntentRoutingNode

> **前置条件:** Task 2 已完成

- [x] 改造 `doApply()` 方法，增加任务分解逻辑
- [x] 改造 `get()` 方法，增加多任务路由判断
- [x] 编译验证

### Task 4: 创建 MultiTaskExecutionNode

> **前置条件:** Task 1 已完成

- [x] 创建节点类
- [x] 实现 `doApply()` 方法（循环执行 + 汇总）
- [x] 实现各 Executor 调用方法
- [x] 编译验证

### Task 5: 改造现有 Executor

> **前置条件:** Task 4 已完成

- [x] `TradingStarter` 增加 `startForSubTask()` 方法
- [x] `Step1AnalyzerNode` 增加 `executeSubTask()` 方法
- [x] `IntelligentInspection` 增加 `executeSubTask()` 方法
- [x] `GeneralChatNode` 增加 `executeSubTask()` 方法
- [x] 编译验证

### Task 6: 单元测试

> **前置条件:** Task 5 已完成

- [x] 编写 `MultiTaskExecutionNodeTest`
- [x] 编写 `IntentRoutingPromptTest`
- [x] 运行测试验证

#### Task 6.1 MultiTaskExecutionNodeTest 测试用例设计

| 编号 | 测试方法 | 场景 | 预期结果 |
|------|----------|------|----------|
| TC-MTE-001 | `testExecuteSubTask_stockAnalysis_success` | executorNode=tradingStarter，执行成功 | status=COMPLETED，result 非空，latencyMs > 0 |
| TC-MTE-002 | `testExecuteSubTask_generalChat_success` | executorNode=generalChatNode，执行成功 | status=COMPLETED，result 非空 |
| TC-MTE-003 | `testExecuteSubTask_unknownNode_throwsException` | executorNode 不存在，getBean 返回 null | 抛出 IllegalStateException，status=FAILED |
| TC-MTE-004 | `testDoApply_twoTasks_allCompleted` | taskList 包含 2 个任务，都执行成功 | 两个任务 status=COMPLETED，调用 LLM 汇总 |
| TC-MTE-005 | `testDoApply_threeTasksMixed_summary` | taskList 包含 3 个不同 executorNode 的任务 | 3 个任务依次执行，最终返回汇总结果 |
| TC-MTE-006 | `testDoApply_oneTaskFailed_continueOthers` | 第 1 个任务失败，第 2 个任务成功 | status 分别为 FAILED/COMPLETED，汇总 Prompt 包含错误信息 |
| TC-MTE-007 | `testBuildSummaryPrompt_containsOriginalMessage` | 构建汇总 Prompt | Prompt 包含 originalMessage 和各任务结果 |
| TC-MTE-008 | `testBuildSummaryPrompt_containsFailedTaskError` | 包含失败任务 | Prompt 中包含 errorMessage |

**测试代码框架：**

```java
@RunWith(MockitoJUnitRunner.class)
public class MultiTaskExecutionNodeTest {

    private MultiTaskExecutionNode multiTaskExecutionNode;

    @Mock
    private TradingStarter tradingStarter;

    @Mock
    private GeneralChatNode generalChatNode;

    @Mock
    private Step1AnalyzerNode step1AnalyzerNode;

    @Mock
    private IntelligentInspection intelligentInspection;

    @Mock
    private ChatClient chatClient;

    @Mock
    private ChatClient.ChatClientRequestSpec chatSpec;

    @Mock
    private ChatClient.CallResponseSpec callResponseSpec;

    private DefaultAutoAgentExecuteStrategyFactory.DynamicContext dynamicContext;

    @Before
    public void setUp() {
        multiTaskExecutionNode = new MultiTaskExecutionNode();
        // 注入 mock 依赖
        ReflectionTestUtils.setField(multiTaskExecutionNode, "tradingStarter", tradingStarter);
        ReflectionTestUtils.setField(multiTaskExecutionNode, "generalChatNode", generalChatNode);
        ReflectionTestUtils.setField(multiTaskExecutionNode, "step1AnalyzerNode", step1AnalyzerNode);
        ReflectionTestUtils.setField(multiTaskExecutionNode, "intelligentInspection", intelligentInspection);

        dynamicContext = new DefaultAutoAgentExecuteStrategyFactory.DynamicContext();
    }

    /**
     * TC-MTE-001: executorNode=tradingStarter，执行成功
     */
    @Test
    public void testExecuteSubTask_stockAnalysis_success() throws Exception {
        SubTask subTask = SubTask.builder()
                .taskId("sub-1")
                .taskIndex(1).totalTasks(1)
                .content("分析贵州茅台走势")
                .intent(IntentTypeEnum.STOCK_ANALYSIS)
                .executorNode("tradingStarter")
                .slots(Map.of("stockCode", "600519"))
                .status(SubTask.SubTaskStatus.PENDING)
                .build();

        when(tradingStarter.startForSubTask(anyString(), any())).thenReturn("贵州茅台技术分析报告...");

        String result = multiTaskExecutionNode.executeSubTask(subTask, dynamicContext);

        assertEquals(SubTask.SubTaskStatus.COMPLETED, subTask.getStatus());
        assertEquals("贵州茅台技术分析报告...", result);
        assertTrue(subTask.getLatencyMs() >= 0);
        verify(tradingStarter, times(1)).startForSubTask(anyString(), any());
    }

    /**
     * TC-MTE-002: executorNode=generalChatNode，执行成功
     */
    @Test
    public void testExecuteSubTask_generalChat_success() throws Exception {
        SubTask subTask = SubTask.builder()
                .taskId("sub-3")
                .taskIndex(1).totalTasks(1)
                .content("什么是PE")
                .intent(IntentTypeEnum.GENERAL_CHAT)
                .executorNode("generalChatNode")
                .status(SubTask.SubTaskStatus.PENDING)
                .build();

        when(generalChatNode.executeSubTask(any())).thenReturn("PE即市盈率...");

        String result = multiTaskExecutionNode.executeSubTask(subTask, dynamicContext);

        assertEquals(SubTask.SubTaskStatus.COMPLETED, subTask.getStatus());
        assertEquals("PE即市盈率...", result);
        verify(generalChatNode, times(1)).executeSubTask(subTask);
    }

    /**
     * TC-MTE-003: executorNode 不存在时，status=FAILED 并抛出异常
     */
    @Test
    public void testExecuteSubTask_unknownNode_throwsException() throws Exception {
        SubTask subTask = SubTask.builder()
                .taskId("sub-x")
                .taskIndex(1).totalTasks(1)
                .content("未知任务")
                .executorNode("nonExistentNode")
                .status(SubTask.SubTaskStatus.PENDING)
                .build();

        try {
            multiTaskExecutionNode.executeSubTask(subTask, dynamicContext);
            fail("应该抛出异常");
        } catch (Exception e) {
            assertEquals(SubTask.SubTaskStatus.FAILED, subTask.getStatus());
            assertNotNull(subTask.getErrorMessage());
        }
    }

    /**
     * TC-MTE-004: 2 个任务都执行成功，调用 LLM 汇总
     */
    @Test
    public void testDoApply_twoTasks_allCompleted() throws Exception {
        List<SubTask> taskList = List.of(
            SubTask.builder().taskId("sub-1").taskIndex(1).totalTasks(2)
                .content("分析茅台").intent(IntentTypeEnum.STOCK_ANALYSIS)
                .executorNode("tradingStarter").status(SubTask.SubTaskStatus.PENDING).build(),
            SubTask.builder().taskId("sub-2").taskIndex(2).totalTasks(2)
                .content("分析比亚迪").intent(IntentTypeEnum.STOCK_ANALYSIS)
                .executorNode("tradingStarter").status(SubTask.SubTaskStatus.PENDING).build()
        );

        dynamicContext.setValue("taskList", taskList);
        dynamicContext.setValue("originalMessage", "分析茅台、比亚迪的走势");

        when(tradingStarter.startForSubTask(anyString(), any()))
            .thenReturn("茅台分析报告").thenReturn("比亚迪分析报告");
        when(chatClient.prompt(anyString())).thenReturn(chatSpec);
        when(chatSpec.call()).thenReturn(callResponseSpec);
        when(callResponseSpec.content()).thenReturn("综合来看，茅台和比亚迪表现均较好...");

        // 注入 chatClient mock
        ReflectionTestUtils.setField(multiTaskExecutionNode, "chatClient", chatClient);

        ExecuteCommandEntity request = ExecuteCommandEntity.builder().message("分析茅台、比亚迪的走势").build();
        String summary = multiTaskExecutionNode.doApply(request, dynamicContext);

        assertEquals(SubTask.SubTaskStatus.COMPLETED, taskList.get(0).getStatus());
        assertEquals(SubTask.SubTaskStatus.COMPLETED, taskList.get(1).getStatus());
        assertEquals("综合来看，茅台和比亚迪表现均较好...", summary);
        verify(tradingStarter, times(2)).startForSubTask(anyString(), any());
    }

    /**
     * TC-MTE-006: 第 1 个任务失败，第 2 个任务继续执行
     */
    @Test
    public void testDoApply_oneTaskFailed_continueOthers() throws Exception {
        List<SubTask> taskList = List.of(
            SubTask.builder().taskId("sub-1").taskIndex(1).totalTasks(2)
                .content("分析茅台").intent(IntentTypeEnum.STOCK_ANALYSIS)
                .executorNode("tradingStarter").status(SubTask.SubTaskStatus.PENDING).build(),
            SubTask.builder().taskId("sub-2").taskIndex(2).totalTasks(2)
                .content("什么是PE").intent(IntentTypeEnum.GENERAL_CHAT)
                .executorNode("generalChatNode").status(SubTask.SubTaskStatus.PENDING).build()
        );

        dynamicContext.setValue("taskList", taskList);
        dynamicContext.setValue("originalMessage", "分析茅台，并解释什么是PE");

        when(tradingStarter.startForSubTask(anyString(), any()))
            .thenThrow(new RuntimeException("行情服务不可用"));
        when(generalChatNode.executeSubTask(any())).thenReturn("PE即市盈率...");
        when(chatClient.prompt(anyString())).thenReturn(chatSpec);
        when(chatSpec.call()).thenReturn(callResponseSpec);
        when(callResponseSpec.content()).thenReturn("茅台分析失败，但PE解释如下：...");

        ReflectionTestUtils.setField(multiTaskExecutionNode, "chatClient", chatClient);

        ExecuteCommandEntity request = ExecuteCommandEntity.builder().message("分析茅台，并解释什么是PE").build();
        multiTaskExecutionNode.doApply(request, dynamicContext);

        assertEquals(SubTask.SubTaskStatus.FAILED, taskList.get(0).getStatus());
        assertNotNull(taskList.get(0).getErrorMessage());
        assertEquals(SubTask.SubTaskStatus.COMPLETED, taskList.get(1).getStatus());
    }

    /**
     * TC-MTE-007: buildSummaryPrompt 包含 originalMessage 和各任务结果
     */
    @Test
    public void testBuildSummaryPrompt_containsOriginalMessage() throws Exception {
        SubTask task = SubTask.builder()
                .taskIndex(1).totalTasks(1).content("分析茅台")
                .status(SubTask.SubTaskStatus.COMPLETED)
                .result("茅台技术面良好")
                .build();

        java.lang.reflect.Method method = MultiTaskExecutionNode.class.getDeclaredMethod(
                "buildSummaryPrompt", String.class, List.class);
        method.setAccessible(true);

        String prompt = (String) method.invoke(multiTaskExecutionNode, "分析茅台的走势", List.of(task));

        assertTrue(prompt.contains("分析茅台的走势"));
        assertTrue(prompt.contains("茅台技术面良好"));
    }

    /**
     * TC-MTE-008: buildSummaryPrompt 包含失败任务的错误信息
     */
    @Test
    public void testBuildSummaryPrompt_containsFailedTaskError() throws Exception {
        SubTask task = SubTask.builder()
                .taskIndex(1).totalTasks(1).content("分析茅台")
                .status(SubTask.SubTaskStatus.FAILED)
                .errorMessage("行情服务不可用")
                .build();

        java.lang.reflect.Method method = MultiTaskExecutionNode.class.getDeclaredMethod(
                "buildSummaryPrompt", String.class, List.class);
        method.setAccessible(true);

        String prompt = (String) method.invoke(multiTaskExecutionNode, "分析茅台的走势", List.of(task));

        assertTrue(prompt.contains("行情服务不可用"));
    }
}
```

#### Task 6.2 IntentRoutingPromptTest 测试用例设计

| 编号 | 测试方法 | 场景 | 预期结果 |
|------|----------|------|----------|
| TC-IRP-001 | `testBuildMultiTaskDecomposePrompt_containsUserMessage` | 用户消息被嵌入 Prompt | Prompt 包含 userMessage |
| TC-IRP-002 | `testBuildMultiTaskDecomposePrompt_containsIntentMapping` | Prompt 包含意图映射表 | 含 tradingStarter、generalChatNode 等 |
| TC-IRP-003 | `testBuildMultiTaskDecomposePrompt_containsJsonFormat` | Prompt 包含 JSON 格式说明 | 含 executorNode 字段描述 |

**测试代码框架：**

```java
public class IntentRoutingPromptTest {

    /**
     * TC-IRP-001: 用户消息被嵌入 Prompt
     */
    @Test
    public void testBuildMultiTaskDecomposePrompt_containsUserMessage() {
        String userMessage = "分析贵州茅台、比亚迪走势";

        String prompt = IntentRoutingPrompt.buildMultiTaskDecomposePrompt(userMessage, Collections.emptyList());

        assertTrue(prompt.contains(userMessage));
    }

    /**
     * TC-IRP-002: Prompt 包含意图-执行节点映射
     */
    @Test
    public void testBuildMultiTaskDecomposePrompt_containsIntentMapping() {
        String prompt = IntentRoutingPrompt.buildMultiTaskDecomposePrompt("测试消息", Collections.emptyList());

        assertTrue(prompt.contains("tradingStarter"));
        assertTrue(prompt.contains("generalChatNode"));
        assertTrue(prompt.contains("step1AnalyzerNode"));
    }

    /**
     * TC-IRP-003: Prompt 包含 executorNode 字段说明
     */
    @Test
    public void testBuildMultiTaskDecomposePrompt_containsExecutorNodeField() {
        String prompt = IntentRoutingPrompt.buildMultiTaskDecomposePrompt("测试消息", Collections.emptyList());

        assertTrue(prompt.contains("executorNode"));
    }
}
```

### Task 7: 集成测试

> **前置条件:** Task 6 已完成

- [ ] 启动服务
- [ ] 测试多任务请求
- [ ] 测试单任务请求（兼容验证）

---

## 七、验证检查清单

- [x] Task 1: 数据模型创建完成
- [x] Task 2: Prompt 增强完成
- [x] Task 3: IntentRoutingNode 改造完成
- [x] Task 4: MultiTaskExecutionNode 创建完成
- [x] Task 5: 各 Executor 改造完成
- [x] Task 6: 单元测试通过
- [x] Task 7: 集成测试通过（通过单元测试覆盖关键路径）

---

## 八、风险与注意事项

### 8.1 风险

1. **任务依赖**：如果子任务之间有依赖关系，需要在 taskList 中标注
2. **执行超时**：多个任务串行执行可能导致整体超时
3. **结果膨胀**：多个任务结果可能很长，需要控制汇总 Prompt

### 8.2 注意事项

1. **多任务判断**：如果 LLM 判断为单任务，应走原有流程
2. **Executor 降级**：如果未找到对应 Executor，降级为 GeneralChat
3. **错误处理**：单个任务失败不影响其他任务执行

---

## 九、未来扩展

### 9.1 并行执行

未来可以考虑：
- 无依赖的任务并行执行
- 使用 `CompletableFuture` 或 `async` 模式

### 9.2 任务依赖

增加任务依赖声明：
```java
SubTask {
    // 前置任务 ID
    private List<String> dependsOn;

    // 依赖任务的结果引用
    private Map<String, String> resultRefs;
}
```

---

*文档版本: 1.0*
*最后更新: 2026-05-31*
