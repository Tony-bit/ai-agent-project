# 统一意图路由 (Intent Routing) 功能开发

> **创建时间:** 2026-05-10
> **功能概述:** 扩展 RootNode 为统一入口，通过意图识别兜底，自动路由到 Trading / PE / React / 通用对话四条链路
> **架构决策:** 保留用户显式指定 aiAgentId + 无 aiAgentId 时意图识别兜底

---

## 1. 背景

**当前架构:**
- `RootNode` 硬编码判断：aiAgentId="5" 走 React 巡检，否则走 PE
- Trading 股票分析有独立端点，与主入口完全分离

**目标架构:**

```
用户请求 → RootNode
              ├─ 有 aiAgentId → 直接路由对应 Agent
              └─ 无 aiAgentId → IntentRoutingNode（意图识别）
                                   ├─ STOCK_ANALYSIS → TradingNode（内部路由）
                                   ├─ PE_* → Step1AnalyzerNode
                                   ├─ INSPECTION → IntelligentInspection
                                   └─ GENERAL_CHAT/AMBIGUOUS/UNKNOWN → GeneralChatNode
```

---

## 2. 意图分类定义

| 枚举值 | 说明 | 路由目标 |
|--------|------|---------|
| `STOCK_ANALYSIS` | 股票/市场分析 | TradingNode |
| `PE_REASONING` | 逻辑推理、问题分析 | Step1AnalyzerNode |
| `PE_CALCULATION` | 数学计算、数据处理 | Step1AnalyzerNode |
| `PE_RETRIEVAL` | 知识检索、信息查询 | Step1AnalyzerNode |
| `INSPECTION` | 系统巡检、健康检查 | IntelligentInspection |
| `GENERAL_CHAT` | 闲聊或其他无法归类 | GeneralChatNode |
| `AMBIGUOUS` | 意图模糊、需澄清 | GeneralChatNode（引导澄清） |
| `UNKNOWN` | 无法明确判断 | GeneralChatNode（降级） |

**置信度:** HIGH / MEDIUM / LOW，低置信度时记录 warn 日志但不阻断流程。

---

## 3. 文件变更清单

### 新增文件 (5个)

| # | 文件 | 职责 |
|---|------|------|
| 1 | `domain/.../model/valobj/enums/IntentTypeEnum.java` | 意图分类枚举 |
| 2 | `domain/.../service/auto/step/routing/IntentRoutingPrompt.java` | 意图识别 Prompt（含历史上下文注入） |
| 3 | `domain/.../service/auto/step/routing/IntentRoutingService.java` | 意图识别服务，调用 LLM |
| 4 | `domain/.../service/auto/step/routing/IntentRoutingNode.java` | 意图路由节点，继承 AbstractExecuteSupport |
| 5 | `domain/.../service/auto/step/chat/GeneralChatNode.java` | 通用对话节点 |

### 修改文件 (1个)

| # | 文件 | 改动 |
|---|------|------|
| 6 | `domain/.../service/auto/step/RootNode.java` | get() 添加意图识别兜底分支 |

### 不需修改

- `AiAgentController.java` — Controller 层无需改动
- `AutoAgentExecuteStrategy.java` — 框架层无需改动
- PE / React 各节点 — 链路内部无需改动

---

## 4. 核心实现

### 4.1 IntentTypeEnum

```java
// domain/.../model/valobj/enums/IntentTypeEnum.java
public enum IntentTypeEnum {
    STOCK_ANALYSIS("STOCK_ANALYSIS", "股票分析"),
    PE_REASONING("PE_REASONING", "PE推理任务"),
    PE_CALCULATION("PE_CALCULATION", "PE计算任务"),
    PE_RETRIEVAL("PE_RETRIEVAL", "PE知识检索"),
    INSPECTION("INSPECTION", "系统巡检"),
    GENERAL_CHAT("GENERAL_CHAT", "通用对话"),
    AMBIGUOUS("AMBIGUOUS", "模糊意图需澄清"),
    UNKNOWN("UNKNOWN", "未知意图");
    // ...
}
```

### 4.2 IntentRoutingPrompt

```java
// domain/.../service/auto/step/routing/IntentRoutingPrompt.java
public class IntentRoutingPrompt {
    public static final String SYSTEM_PROMPT_TEMPLATE = """
        ## 意图分类（共 6 种）
        1. STOCK_ANALYSIS: 股票/市场分析
        2. PE_REASONING: 逻辑推理、问题分析
        3. PE_CALCULATION: 数学计算、数据处理
        4. PE_RETRIEVAL: 知识检索、信息查询
        5. INSPECTION: 系统巡检、健康检查
        6. GENERAL_CHAT: 闲聊或其他无法归类
        7. AMBIGUOUS: 意图模糊或复合语义

        ## 置信度
        HIGH: 意图非常明确 | MEDIUM: 较明确 | LOW: 信号较弱

        ## 输出格式
        {"intent": "...", "confidence": "HIGH|MEDIUM|LOW", "reasoning": "..."}
        """;

    // 支持历史上下文注入（最近N条对话）
    public static String buildPrompt(String userMessage, List<String> historyMessages) {...}
}
```

### 4.3 IntentRoutingService

```java
// domain/.../service/auto/step/routing/IntentRoutingService.java
@Service
public class IntentRoutingService {
    @Resource
    private ChatModel chatModel;

    // 调用 LLM 进行意图识别
    public IntentRoutingResult route(String userMessage, List<String> historyMessages) {...}

    // 解析 LLM 返回的 JSON，失败时降级为 UNKNOWN + LOW
    public IntentRoutingResult parseResponse(String response) {...}

    @Data @Builder
    public static class IntentRoutingResult {
        private IntentTypeEnum intent;
        private ConfidenceEnum confidence;
        private String reasoning;
        // 预留 RagConfig 字段（后续迭代实现 RAG Few-Shot）
    }
}
```

### 4.4 IntentRoutingNode

```java
// domain/.../service/auto/step/routing/IntentRoutingNode.java
@Service("intentRoutingNode")
public class IntentRoutingNode extends AbstractExecuteSupport {
    @Override
    protected String doApply(ExecuteCommandEntity request, DynamicContext dynamicContext) {
        // 1. 调用 LLM 识别意图（传入最近3条历史消息）
        List<String> historyMessages = dynamicContext.getRecentMessages(3);
        IntentRoutingResult result = intentRoutingService.route(request.getMessage(), historyMessages);
        
        // 2. 保存识别结果到上下文
        dynamicContext.setValue("recognizedIntent", result.getIntent());
        
        // 3. STOCK_ANALYSIS 内部路由（其他意图走 router()）
        if (result.getIntent() == IntentTypeEnum.STOCK_ANALYSIS) {
            // 发送 system 事件，内部路由到 TradingNode
            return null;
        }
        return router(request, dynamicContext);
    }

    @Override
    public StrategyHandler<...> get(ExecuteCommandEntity request, DynamicContext dynamicContext) {
        IntentTypeEnum intent = dynamicContext.getValue("recognizedIntent");
        return switch (intent) {
            case PE_REASONING, PE_CALCULATION, PE_RETRIEVAL -> step1AnalyzerNode;
            case INSPECTION -> intelligentInspection;
            default -> generalChatNode;
        };
    }
}
```

### 4.5 GeneralChatNode

```java
// domain/.../service/auto/step/chat/GeneralChatNode.java
@Service("generalChatNode")
public class GeneralChatNode extends AbstractExecuteSupport {
    @Override
    protected String doApply(ExecuteCommandEntity request, DynamicContext dynamicContext) {
        // 1. 发送 system 开始事件
        sendSseResult(dynamicContext, AutoAgentExecuteResultEntity.builder()
                .type("system").subType("general_chat_start").content("正在思考...").build());

        // 2. 构建 Prompt（AMBIGUOUS 时引导澄清）
        String prompt = buildGeneralChatPrompt(request.getMessage(), dynamicContext);

        // 3. 调用 LLM（使用 getChatClientByClientId("default", 0)）
        ChatClient chatClient = getChatClientByClientId("default", 0);
        String response = chatClient.prompt(prompt).call().content();

        // 4. 发送 content 结果事件
        sendSseResult(dynamicContext, AutoAgentExecuteResultEntity.builder()
                .type("content").subType("general_chat_response").content(response).build());

        dynamicContext.setCompleted(true);
        return response;
    }
}
```

### 4.6 RootNode 修改

```java
// domain/.../service/auto/step/RootNode.java
@Service("executeRootNode")
public class RootNode extends AbstractExecuteSupport {
    @Resource
    private IntentRoutingNode intentRoutingNode;
    @Resource
    private Step1AnalyzerNode step1AnalyzerNode;
    @Resource
    private IntelligentInspection intelligentInspection;

    @Override
    public StrategyHandler<...> get(ExecuteCommandEntity request, DynamicContext dynamicContext) {
        // 1. aiAgentId="5" → React 巡检
        if (Objects.equals(request.getAiAgentId(), "5")) {
            return intelligentInspection;
        }
        // 2. 有显式 aiAgentId → PE 链路
        if (request.getAiAgentId() != null && !request.getAiAgentId().isBlank()) {
            return step1AnalyzerNode;
        }
        // 3. 无 aiAgentId → 意图识别兜底
        return intentRoutingNode;
    }
}
```

---

## 5. 路由决策矩阵

| aiAgentId | 识别意图 | 路由目标 | 置信度低处理 |
|-----------|---------|---------|-------------|
| "5"（显式） | — | IntelligentInspection（React） | — |
| 非空（显式） | — | Step1AnalyzerNode（PE） | — |
| null / 空 | STOCK_ANALYSIS | TradingNode（内部路由） | warn 日志 |
| null / 空 | PE_* | Step1AnalyzerNode | warn 日志 |
| null / 空 | INSPECTION | IntelligentInspection | warn 日志 |
| null / 空 | GENERAL_CHAT / AMBIGUOUS / UNKNOWN | GeneralChatNode | warn 日志 |

---

## 6. SSE 事件规范

| type | subType | 说明 |
|------|---------|------|
| `system` | `general_chat_start` / `trading_analysis` | 系统事件 |
| `content` | `general_chat_response` | 内容事件 |

---

## 7. 执行清单

| Task | 内容 | status |
|------|------|--------|
| Task 1 | 新增 `IntentTypeEnum.java` | pending |
| Task 2 | 新增 `IntentRoutingPrompt.java` | pending |
| Task 3 | 新增 `IntentRoutingService.java` | pending |
| Task 4 | 新增 `GeneralChatNode.java` | pending |
| Task 5 | 新增 `IntentRoutingNode.java` | pending |
| Task 6 | ~~修改 `AiClientTypeEnumVO.java`~~ | cancel |
| Task 7 | 修改 `RootNode.java` | pending |
| Task 8 | 编译验证 | pending |

---

## 8. Review 确认记录 (P0-P1)

| 编号 | 确认结果 |
|------|----------|
| P0-1 | RootNode 三分支：aiAgentId="5"→巡检，aiAgentId非空→PE链路，aiAgentId空→意图识别兜底 |
| P0-2 | STOCK_ANALYSIS 暂不做切槽，后端内部路由到 TradingNode |
| P0-3 | SSE 事件统一为 `content` + `system` 两种类型 |
| P1-2 | 置信度 HIGH/MEDIUM/LOW，低置信度记录 warn 但不阻断 |
| P1-4 | 意图识别传入历史消息（最近3条） |
| P1-7 | AMBIGUOUS 枚举 + GeneralChatNode 澄清引导 |
| P1-8 | 先硬编码 Prompt，后续优化到数据库 |
| P1-14 | 统一 SSE 事件规范（`content`/`system`） |
| P1-17 | IntentRoutingNode 保持独立节点 |
| P1-19 | GeneralChatNode 使用 `getChatClientByClientId("default", 0)` |
| P1-22 | 同意命名：`IntentTypeEnum` + `IntentRoutingService` |
| P1-23 | 同意 6 种意图类型定义 |
| P1-24 | 澄清场景直接落到 GeneralChatNode，后续在 Chat 历史中做 |
| P1-28 | 实施优先级：P0=枚举 → P1=路由表 → P2=PE适配 → P3=LLM策略 → P4=流式 |
| P1-30 | 无需识别器优先级机制，LLM 返回单一意图 |

---

## 8.1 代码检视问题（Review Issues）

### IR-1. route() 方法签名与设计文档不一致【重要】

**位置:** `IntentRoutingService.java`

**问题描述:** 设计文档中 `route()` 接收 `List<String> historyMessages` 参数，实际实现中 Prompt 构建被移到 `IntentRoutingNode`，`route()` 只接收已构建好的 prompt 字符串。

**当前代码:**
```java
public IntentRoutingResult route(String userMessage, String prompt) {
    String response = chatClient.prompt(prompt).call().content();
    ...
}
```

**建议:** 更新设计文档第 4.3 节的代码示例以匹配实际实现，或调整实现以匹配文档。

**状态:** pending

---

### IR-2. STOCK_ANALYSIS 路由未实现【严重】

**位置:** `IntentRoutingNode.java` 第 81-84 行

**问题描述:** 根据设计文档 P0-2，STOCK_ANALYSIS 应路由到 TradingNode，但当前只是打 warn 日志后继续流转，没有实际路由逻辑。

**当前代码:**
```java
if (result.getIntent() == IntentTypeEnum.STOCK_ANALYSIS) {
    log.warn("STOCK_ANALYSIS 暂不支持内部路由，打日志后流转到下一节点: sessionId={}",
            request.getSessionId());
}
```

**建议:** 实现 STOCK_ANALYSIS 到 TradingNode 的路由，或明确标记为 TODO 并设置占位。

**状态:** pending

---

### IR-3. RootNode 中历史消息被覆盖【重要】

**位置:** `RootNode.java` 第 47-49 行

**问题描述:** `oldHistory` 保存后立即被覆盖，原有历史信息丢失。如果后续流程需要复用历史上下文，会出现问题。

**当前代码:**
```java
StringBuilder oldHistory = dynamicContext.getExecutionHistory();
dynamicContext.setExecutionHistory(new StringBuilder());
```

**建议:** 如果 `oldHistory` 不需要后续使用，直接删除保存逻辑；如果需要保留，应持久化或传递到其他地方。

**状态:** ~~pending~~ **pass（已修复：删除无意义的 oldHistory 变量）**

---

### IR-4. 设计文档与实现不一致【重要】

**位置:** 设计文档第 4.4 节 vs `IntentRoutingNode.java`

**问题描述:** 设计文档描述 `dynamicContext.getRecentMessages(3)`，但实际实现中 `IntentRoutingNode` 自己调用 `getRecentHistoryMessages(sessionId)` 获取历史。`DynamicContext` 实际没有 `getRecentMessages()` 方法。

**状态:** pending

---

### IR-5. route() 中 userMessage 参数未被使用【一般】

**位置:** `IntentRoutingService.java` 第 37 行

**问题描述:** `userMessage` 参数在方法体中未使用。如果后续需要日志记录或调试，这个参数是必需的。

**建议:** 添加日志记录 userMessage，或在 Prompt 中注入 userMessage 以便追踪。

**状态:** ~~pending~~ **pass（已修复：在 log.debug 中添加 userMessage）**

---

### IR-6. confidence 为 null 时降级行为【一般】

**位置:** `IntentRoutingService.java` 第 68 行

**问题描述:** `ConfidenceEnum.fromCode(null)` 会返回 `LOW`，但这种隐式降级行为没有显式文档说明。

**建议:** 在 `parseResponse()` 中添加显式的 null 检查，或在设计文档中说明降级规则。

**状态:** pending

---

### IR-7. 缺少单元测试【重要】

**位置:** 测试文档 vs 实际代码

**问题描述:** 设计文档中列出了 5 个测试类，但实际项目中这些测试文件都不存在。

**建议:** 优先实现核心测试类：`IntentRoutingServiceTest`、`IntentRoutingNodeTest`、`RootNodeTest`。

**状态:** pending

---

### IR-8. RootNode 中调试日志需清理【一般】

**位置:** `RootNode.java` 第 55-61 行

**问题描述:** 调试性质的日志在生产环境中可能产生噪音。

**建议:** 移除或降级为 debug 级别。

**状态:** ~~pending~~ **pass（已修复：调试日志已降级为 debug 级别并移除无用的 emitter 检查）**

---

### IR-9. reasoning 字段可能为 null【一般】

**位置:** `IntentRoutingService.java` 第 65 行

**问题描述:** 如果 JSON 中缺少 `reasoning` 字段，构建 `IntentRoutingResult` 时会传入 null，在后续日志中可能出现 `null` 字符串。

**建议:** 添加 `reasoning = reasoning != null ? reasoning : "无推理过程"` 的处理。

**状态:** ~~pending~~ **pass（已修复：添加 null 检查）**

---

## 9. 遗漏项（暂不实施）

- **切槽能力（Chunking Strategy）**: 长文档场景下召回精度受限。后续新增 `ChunkingService` 支持多策略切槽 — pending
- **RAG Few-Shot 意图增强**: Zero-Shot 升级为 RAG Few-Shot，提升识别准确率 — pending
- **Trading 重构为 TradingNode**: 纳入 Node 体系 — pending

---

## 10. 风险与注意事项

1. **意图识别延迟**: 额外增加一次 LLM 调用（约 500ms-2s）
2. **解析降级**: LLM 返回格式异常时降级为 UNKNOWN，不阻断流程
3. **PE 三种子类型**: 统一路由到 Step1AnalyzerNode，内部根据任务类型动态选择工具
