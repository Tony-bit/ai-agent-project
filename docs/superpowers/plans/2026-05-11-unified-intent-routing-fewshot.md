# Intent Routing + 动态 Few-Shot + 切槽 Story

> **创建时间:** 2026-05-11
> **所属 epic:** 统一意图路由 + RAG Few-Shot + 切槽能力
> **优先级:** P0
> **状态:** pending

---

## 1. 背景与目标

### 1.1 现有问题

- **意图识别延迟高**：Zero-Shot 意图识别准确率依赖 LLM 能力，边界场景误识别率高
- **切槽能力缺失**：STOCK_ANALYSIS 链路无结构化槽位提取，TradingNode 需二次解析
- **STOCK_ANALYSIS 未实现**：设计文档标记 pending（IR-2）

### 1.2 目标

1. 意图识别 + 切槽 **一次 LLM 调用**完成
2. 动态 Few-Shot 注入，意图识别准确率提升
3. STOCK_ANALYSIS 链路支持结构化切槽（baseSlot + intentSpecificSlots）
4. 各链路自行解析消费 slots，不侵入其他链路

---

## 2. 架构总览

```
用户请求 → RootNode
              ├─ 有 aiAgentId → 直接路由对应 Agent
              └─ 无 aiAgentId → IntentRoutingNode
                                   │
                    ┌──────────────┴──────────────┐
                    │      一次 LLM 调用返回        │
                    │  intent + confidence        │
                    │  + baseSlot                 │
                    │  + intentSpecificSlots       │
                    └──────────────┬──────────────┘
                                   │
              ┌──────────┬─────────┼─────────┬──────────┐
              ▼          ▼         ▼         ▼          ▼
        STOCK_ANALYSIS  PE_*   INSPECTION GENERAL_CHAT  UNKNOWN
              │          │         │          │          │
          TradingNode   ↓         ↓          ↓          ↓
        (切槽生效)   Step1Analyzer  Intelligent  GeneralChat
                          Node      Inspection      Node
```

---

## 3. 决策记录

| 编号 | 决策 | 确认时间 |
|------|------|---------|
| P0-1 | 意图识别 + 切槽一次 LLM 调用完成 | 2026-05-11 |
| P0-2 | 切槽仅 STOCK_ANALYSIS 链路触发，其他意图忽略 | 2026-05-11 |
| P0-3 | 槽位 Schema：baseSlot（通用）+ intentSpecificSlots（意图专属） | 2026-05-11 |
| P1-1 | 动态 Few-Shot，每次请求前从 PGvector 检索 Top-5 | 2026-05-11 |
| P1-2 | 独立管理：意图样本独立表 + 独立 vector_store 表，与业务 RAG 隔离 | 2026-05-11 |
| P1-3 | 复用现有 Embedding 模型（qwen3-vl-embedding / text-embedding-v4） | 2026-05-11 |
| P1-4 | 各链路自行解析 slots，不侵入其他链路 | 2026-05-11 |
| P1-5 | 意图样本 CRUD 通过 Mapper 管理 | 2026-05-11 |

---

## 4. LLM 返回 Schema

```json
{
  "intent": "STOCK_ANALYSIS",
  "confidence": "HIGH",
  "reasoning": "用户明确询问平安银行股票走势",
  "baseSlot": {
    "topic": "股票分析",
    "sentiment": "neutral"
  },
  "intentSpecificSlots": {
    "stockCode": "平安银行",
    "stockQueryType": "走势分析",
    "timeRange": "近一年",
    "exchange": "SZ"
  }
}
```

### 意图类型

| 枚举值 | 说明 | 路由目标 | 切槽 |
|--------|------|---------|------|
| `STOCK_ANALYSIS` | 股票/市场分析 | TradingNode | ✅ |
| `PE_REASONING` | 逻辑推理 | Step1AnalyzerNode | ❌ |
| `PE_CALCULATION` | 数学计算 | Step1AnalyzerNode | ❌ |
| `PE_RETRIEVAL` | 知识检索 | Step1AnalyzerNode | ❌ |
| `INSPECTION` | 系统巡检 | IntelligentInspection | ❌ |
| `GENERAL_CHAT` | 闲聊 | GeneralChatNode | ❌ |
| `AMBIGUOUS` | 模糊意图 | GeneralChatNode（引导澄清） | ❌ |
| `UNKNOWN` | 未知意图 | GeneralChatNode（降级） | ❌ |

---

## 5. 动态 Few-Shot 方案

### 5.1 样本管理（独立管理）

**表结构：**

```sql
CREATE TABLE intent_fewshot_sample (
    id            BIGINT PRIMARY KEY AUTO_INCREMENT,
    query_text    VARCHAR(500) NOT NULL COMMENT '用户query原文',
    intent_code   VARCHAR(50)  NOT NULL COMMENT '意图编码',
    example_json  TEXT         NOT NULL COMMENT 'LLM应返回的完整JSON示例',
    dimension     INT          DEFAULT 768 COMMENT 'embedding维度',
    embedding     VECTOR(768)  COMMENT '向量',
    status        TINYINT      DEFAULT 1 COMMENT '状态：1启用 0禁用',
    created_at    DATETIME     DEFAULT CURRENT_TIMESTAMP,
    updated_at    DATETIME     DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
);
```

- 独立 `intent_fewshot_sample` 表，与业务 `vector_store` 完全隔离
- 向量存储：复用 PGvector provider，单独 `intent_fewshot_vector_store` 表

### 5.2 Few-Shot 注入流程

```
意图识别请求
    │
    ▼
① Query Embedding（复用现有 qwen3-vl-embedding / text-embedding-v4）
    │
    ▼
② PGvector Top-K 检索（intent_fewshot_vector_store 表）
    │  SELECT * FROM intent_fewshot_vector_store
    │  WHERE status = 1
    │  ORDER BY embedding <=> embedding_query
    │  LIMIT 5;
    │
    ▼
③ 动态组装 Few-Shot Prompt
    │  system: 分类定义 + 置信度说明
    │  examples: Top-K 样本（query → 完整JSON示例）
    │  user: 当前用户消息
    │
    ▼
④ LLM 调用（一次完成意图识别 + 切槽）
    │
    ▼
⑤ 返回结果，各链路自行解析 slots
```

### 5.3 样本 CRUD

| 方法 | 说明 |
|------|------|
| `addSample(queryText, intentCode, exampleJson)` | 新增样本，自动生成 embedding |
| `deleteSample(id)` | 软删除（status=0） |
| `updateSample(id, exampleJson)` | 更新样本 |
| `retrieveTopK(query, k)` | PGvector Top-K 检索 |

---

## 6. 文件变更清单

### 6.1 新增文件（7个）

| # | 文件 | 职责 | status |
|---|------|------|--------|
| 1 | `model/valobj/IntentRoutingResult.java` | 意图识别结果（含 slots） | pending |
| 2 | `model/valobj/BaseSlot.java` | 通用槽位 VO | pending |
| 3 | `model/valobj/StockSlot.java` | 股票切槽 VO | pending |
| 4 | `model/entity/IntentFewshotSample.java` | 样本实体 | pending |
| 5 | `repository/IntentFewshotSampleRepository.java` | 样本 CRUD | pending |
| 6 | `service/intent/IntentFewshotService.java` | Few-Shot 管理 + PGvector 检索 | pending |
| 7 | `mybatis/mapper/IntentFewshotSampleMapper.xml` | 样本 CRUD SQL | pending |

### 6.2 修改文件（3个）

| # | 文件 | 改动 | status |
|---|------|------|--------|
| 8 | `IntentRoutingPrompt.java` | 支持动态 Few-Shot 注入 | pending |
| 9 | `IntentRoutingService.java` | 集成 Few-Shot + 切槽解析 | pending |
| 10 | `IntentRoutingNode.java` | 解析 slots 到 context | pending |

### 6.3 不需修改

- `RootNode.java`
- `AiAgentController.java`
- `AutoAgentExecuteStrategy.java`
- PE / React 各节点
- `AiAgentConfig.java`（复用现有 PGvector 配置）

---

## 7. 任务列表

### P0 任务

| Task | 内容 | 文件 | status |
|------|------|------|--------|
| P0-1 | 新增 `IntentRoutingResult.java`（含 slots 字段） | `domain/model/valobj/` | pending |
| P0-2 | 新增 `BaseSlot.java`（通用槽位） | `domain/model/valobj/` | pending |
| P0-3 | 新增 `StockSlot.java`（股票切槽） | `domain/model/valobj/` | pending |
| P0-4 | 修改 `IntentRoutingPrompt.java`（支持动态 Few-Shot） | `domain/service/auto/step/routing/` | pending |
| P0-5 | 修改 `IntentRoutingService.java`（集成 Few-Shot + 切槽解析） | `domain/service/auto/step/routing/` | pending |
| P0-6 | 修改 `IntentRoutingNode.java`（解析 slots 到 context） | `domain/service/auto/step/routing/` | pending |

### P1 任务

| Task | 内容 | 文件 | status |
|------|------|------|--------|
| P1-1 | 新增 `IntentFewshotSample.java`（样本实体） | `domain/model/entity/` | pending |
| P1-2 | 新增 `IntentFewshotSampleMapper.xml`（CRUD SQL） | `resources/mybatis/mapper/` | pending |
| P1-3 | 新增 `IntentFewshotSampleRepository.java`（CRUD） | `domain/repository/` | pending |
| P1-4 | 新增 `IntentFewshotService.java`（Few-Shot 管理 + PGvector 检索） | `domain/service/intent/` | pending |

### P2 任务

| Task | 内容 | 文件 | status |
|------|------|------|--------|
| P2-1 | 修复 IR-2：STOCK_ANALYSIS → TradingNode 实际路由 | `IntentRoutingNode.java` | pending |
| P2-2 | 修复 IR-4：`getRecentMessages` → `getRecentHistoryMessages` | `IntentRoutingNode.java` | pending |
| P2-3 | 编译验证 | — | pending |

---

## 8. 核心实现

### 8.1 IntentRoutingResult（含切槽字段）

```java
@Data
@Builder
public class IntentRoutingResult {
    private IntentTypeEnum intent;
    private ConfidenceEnum confidence;
    private String reasoning;
    private BaseSlot baseSlot;
    private Map<String, Object> intentSpecificSlots;
}
```

### 8.2 StockSlot（仅 STOCK_ANALYSIS 使用）

```java
@Data @Builder
public class StockSlot {
    private String stockCode;
    private String stockQueryType;
    private String timeRange;
    private String exchange;
}
```

### 8.3 IntentRoutingPrompt（支持动态 Few-Shot）

```java
public class IntentRoutingPrompt {
    private static final String SYSTEM_PROMPT_TEMPLATE = """
        ## 意图分类（共 8 种）
        1. STOCK_ANALYSIS: 股票/市场分析
        2. PE_REASONING: 逻辑推理、问题分析
        3. PE_CALCULATION: 数学计算、数据处理
        4. PE_RETRIEVAL: 知识检索、信息查询
        5. INSPECTION: 系统巡检、健康检查
        6. GENERAL_CHAT: 闲聊或其他无法归类
        7. AMBIGUOUS: 意图模糊或复合语义
        8. UNKNOWN: 无法明确判断

        ## 置信度
        HIGH: 意图非常明确 | MEDIUM: 较明确 | LOW: 信号较弱

        ## 槽位说明
        - baseSlot: 所有意图通用槽位（topic, sentiment）
        - intentSpecificSlots: 意图专属槽位
          - STOCK_ANALYSIS: {stockCode, stockQueryType, timeRange, exchange}

        ## 输出格式
        {"intent": "...", "confidence": "HIGH|MEDIUM|LOW", "reasoning": "...",
         "baseSlot": {"topic": "...", "sentiment": "..."},
         "intentSpecificSlots": {...}}
        """;

    public static String buildPrompt(String userMessage, List<IntentFewshotSample> fewshotSamples) {
        StringBuilder sb = new StringBuilder();
        sb.append(SYSTEM_PROMPT_TEMPLATE);
        if (fewshotSamples != null && !fewshotSamples.isEmpty()) {
            sb.append("\n## 参考示例\n");
            for (IntentFewshotSample sample : fewshotSamples) {
                sb.append(String.format("用户: %s\n输出: %s\n\n",
                        sample.getQueryText(), sample.getExampleJson()));
            }
        }
        sb.append(String.format("用户: %s\n输出:", userMessage));
        return sb.toString();
    }
}
```

### 8.4 IntentRoutingService（集成 Few-Shot + 切槽解析）

```java
@Service
public class IntentRoutingService {
    @Resource
    private ChatModel chatModel;
    @Resource
    private IntentFewshotService intentFewshotService;

    public IntentRoutingResult route(String userMessage, List<String> historyMessages) {
        List<IntentFewshotSample> samples = intentFewshotService.retrieveTopK(userMessage, 5);
        String prompt = IntentRoutingPrompt.buildPrompt(userMessage, samples);
        String response = chatModel.call(prompt);
        return parseResponse(response);
    }

    @SuppressWarnings("unchecked")
    private IntentRoutingResult parseResponse(String response) {
        try {
            JSONObject json = JSONObject.parseObject(response);
            BaseSlot baseSlot = json.containsKey("baseSlot")
                    ? json.getObject("baseSlot", BaseSlot.class) : null;
            Map<String, Object> intentSpecificSlots = null;
            if (json.containsKey("intentSpecificSlots")) {
                intentSpecificSlots = json.getJSONObject("intentSpecificSlots").getInnerMap();
            }
            IntentTypeEnum intent = IntentTypeEnum.fromCode(json.getString("intent"));
            if (intent == STOCK_ANALYSIS && intentSpecificSlots != null) {
                StockSlot stockSlot = StockSlot.builder()
                        .stockCode((String) intentSpecificSlots.get("stockCode"))
                        .stockQueryType((String) intentSpecificSlots.get("stockQueryType"))
                        .timeRange((String) intentSpecificSlots.get("timeRange"))
                        .exchange((String) intentSpecificSlots.get("exchange"))
                        .build();
                intentSpecificSlots = Map.of("stockSlot", stockSlot);
            }
            return IntentRoutingResult.builder()
                    .intent(intent)
                    .confidence(ConfidenceEnum.fromCode(json.getString("confidence")))
                    .reasoning(json.getString("reasoning"))
                    .baseSlot(baseSlot)
                    .intentSpecificSlots(intentSpecificSlots)
                    .build();
        } catch (Exception e) {
            log.warn("LLM返回格式异常，降级为UNKNOWN: {}", e.getMessage());
            return IntentRoutingResult.builder()
                    .intent(UNKNOWN).confidence(LOW).reasoning("解析失败").build();
        }
    }
}
```

### 8.5 IntentRoutingNode（解析 slots，链路自行消费）

```java
@Service("intentRoutingNode")
public class IntentRoutingNode extends AbstractExecuteSupport {
    @Resource
    private IntentRoutingService intentRoutingService;
    @Resource
    private TradingNode tradingNode;

    @Override
    protected String doApply(ExecuteCommandEntity request, DynamicContext dynamicContext) {
        List<String> history = intentRoutingService.getRecentHistoryMessages(request.getSessionId());
        IntentRoutingResult result = intentRoutingService.route(request.getMessage(), history);
        dynamicContext.setValue("intentRoutingResult", result);
        dynamicContext.setValue("recognizedIntent", result.getIntent());
        dynamicContext.setValue("baseSlot", result.getBaseSlot());
        dynamicContext.setValue("intentSpecificSlots", result.getIntentSpecificSlots());
        if (result.getIntent() == STOCK_ANALYSIS) {
            StockSlot stockSlot = extractStockSlot(result.getIntentSpecificSlots());
            dynamicContext.setValue("stockSlot", stockSlot);
            log.info("STOCK_ANALYSIS 切槽完成: stockCode={}, queryType={}",
                    stockSlot.getStockCode(), stockSlot.getStockQueryType());
        }
        return router(request, dynamicContext);
    }

    @Override
    public StrategyHandler<...> get(ExecuteCommandEntity request, DynamicContext dynamicContext) {
        IntentTypeEnum intent = dynamicContext.getValue("recognizedIntent");
        return switch (intent) {
            case STOCK_ANALYSIS -> tradingNode;
            case PE_REASONING, PE_CALCULATION, PE_RETRIEVAL -> step1AnalyzerNode;
            case INSPECTION -> intelligentInspection;
            default -> generalChatNode;
        };
    }

    private StockSlot extractStockSlot(Map<String, Object> intentSpecificSlots) {
        if (intentSpecificSlots == null) return null;
        Object stockSlotObj = intentSpecificSlots.get("stockSlot");
        if (stockSlotObj instanceof StockSlot) return (StockSlot) stockSlotObj;
        return null;
    }
}
```

---

## 9. SQL 脚本

```sql
-- 独立意图样本表
CREATE TABLE intent_fewshot_sample (
    id            BIGINT PRIMARY KEY AUTO_INCREMENT,
    query_text    VARCHAR(500) NOT NULL COMMENT '用户query原文',
    intent_code   VARCHAR(50)  NOT NULL COMMENT '意图编码',
    example_json  TEXT         NOT NULL COMMENT 'LLM应返回的完整JSON示例',
    dimension     INT          DEFAULT 768 COMMENT 'embedding维度',
    embedding     VECTOR(768)  COMMENT '向量',
    status        TINYINT      DEFAULT 1 COMMENT '状态：1启用 0禁用',
    created_at    DATETIME     DEFAULT CURRENT_TIMESTAMP,
    updated_at    DATETIME     DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX idx_intent_code (intent_code),
    INDEX idx_status (status)
);

-- 独立 vector 表（复用 PGvector provider）
-- 由 Spring AI PgVectorStore 自动管理，无需手动创建
-- Spring AI 会创建 intent_fewshot_vector_store 表
```

---

## 10. 测试用例（见 `2026-05-11-unified-intent-routing-fewshot-test-cases.md`）

---

## 11. 风险与注意事项

1. **LLM 返回格式异常**：降级为 UNKNOWN + LOW，不阻断流程
2. **PGvector 检索失败**：降级为空 Few-Shot，继续 Zero-Shot 识别
3. **STOCK_ANALYSIS 切槽为空**：降级为默认槽值，打 warn 日志
4. **Embedding 模型维度不匹配**：校验 dimension 与 PGvector 表一致
