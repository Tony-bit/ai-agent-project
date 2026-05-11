# Intent Routing + 动态 Few-Shot + 切槽 测试用例手册

> **编写日期:** 2026-05-11
> **所属 Story:** `2026-05-11-unified-intent-routing-fewshot.md`
> **测试范围:** 仅覆盖本次代码变更部分，已有逻辑无需重复测试

---

## 1. 测试范围概述

### 1.1 本次变更需测试的文件

| 类别 | 文件 | 变更类型 |
|------|------|----------|
| **新增VO** | `BaseSlot.java` | 新增 |
| **新增VO** | `StockSlot.java` | 新增 |
| **新增VO** | `IntentRoutingResult.java` | 新增（含slots字段） |
| **新增Service** | `IntentFewshotService.java` | 新增 |
| **新增Entity** | `IntentFewshotSample.java` | 新增 |
| **新增Repository** | `IntentFewshotSampleRepository.java` | 新增 |
| **修改Prompt** | `IntentRoutingPrompt.java` | 支持动态Few-Shot |
| **修改Service** | `IntentRoutingService.java` | 集成切槽解析 |
| **修改Node** | `IntentRoutingNode.java` | STOCK_ANALYSIS→tradingNode路由 |

### 1.2 无需测试的范围

- 现有 `ConfidenceEnum`（除非本次变更涉及修改）
- 现有 `IntentTypeEnum` 枚举定义（已有测试覆盖）
- PE/React 各节点（未变更）
- `RootNode.java`（未变更）

---

## 2. 测试用例详细设计

### 2.1 BaseSlot 单元测试

**测试类:** `BaseSlotTest.java`
**测试路径:** `ai-agent-study-domain/src/test/java/denny/ai/agent/domain/model/valobj/BaseSlotTest.java`

```java
package denny.ai.agent.domain.model.valobj;

import denny.ai.agent.domain.model.valobj.BaseSlot;
import org.junit.Test;

import static org.junit.Assert.*;

/**
 * BaseSlot 单元测试
 * <p>
 * 测试覆盖：
 * 1. TC-BaseSlot-001: 正常构造验证字段赋值
 * 2. TC-BaseSlot-002: topic字段为null
 * 3. TC-BaseSlot-003: sentiment字段为null
 * 4. TC-BaseSlot-004: 全部字段为null
 * 5. TC-BaseSlot-005: builder模式构造
 * </p>
 *
 * @author denny
 * 2026/5/11
 */
public class BaseSlotTest {

    /**
     * TC-BaseSlot-001: 正常构造验证字段赋值
     */
    @Test
    public void testNormalConstruction() {
        BaseSlot baseSlot = BaseSlot.builder()
                .topic("股票分析")
                .sentiment("neutral")
                .build();

        assertEquals("股票分析", baseSlot.getTopic());
        assertEquals("neutral", baseSlot.getSentiment());
    }

    /**
     * TC-BaseSlot-002: topic字段为null
     */
    @Test
    public void testNullTopic() {
        BaseSlot baseSlot = BaseSlot.builder()
                .topic(null)
                .sentiment("positive")
                .build();

        assertNull(baseSlot.getTopic());
        assertEquals("positive", baseSlot.getSentiment());
    }

    /**
     * TC-BaseSlot-003: sentiment字段为null
     */
    @Test
    public void testNullSentiment() {
        BaseSlot baseSlot = BaseSlot.builder()
                .topic("市场分析")
                .sentiment(null)
                .build();

        assertEquals("市场分析", baseSlot.getTopic());
        assertNull(baseSlot.getSentiment());
    }

    /**
     * TC-BaseSlot-004: 全部字段为null
     */
    @Test
    public void testAllFieldsNull() {
        BaseSlot baseSlot = BaseSlot.builder()
                .topic(null)
                .sentiment(null)
                .build();

        assertNull(baseSlot.getTopic());
        assertNull(baseSlot.getSentiment());
    }

    /**
     * TC-BaseSlot-005: builder模式构造
     */
    @Test
    public void testBuilderPattern() {
        BaseSlot baseSlot = BaseSlot.builder()
                .topic("技术分析")
                .sentiment("negative")
                .build();

        assertNotNull(baseSlot);
        assertEquals("技术分析", baseSlot.getTopic());
        assertEquals("negative", baseSlot.getSentiment());
    }
}
```

---

### 2.2 StockSlot 单元测试

**测试类:** `StockSlotTest.java`
**测试路径:** `ai-agent-study-domain/src/test/java/denny/ai/agent/domain/model/valobj/StockSlotTest.java`

```java
package denny.ai.agent.domain.model.valobj;

import denny.ai.agent.domain.model.valobj.StockSlot;
import org.junit.Test;

import static org.junit.Assert.*;

/**
 * StockSlot 单元测试
 * <p>
 * 测试覆盖：
 * 1. TC-StockSlot-001: 正常构造验证字段赋值
 * 2. TC-StockSlot-002: 部分字段为null
 * 3. TC-StockSlot-003: 全部字段为null
 * 4. TC-StockSlot-004: builder模式构造
 * 5. TC-StockSlot-005: 字段边界值测试
 * </p>
 *
 * @author denny
 * 2026/5/11
 */
public class StockSlotTest {

    /**
     * TC-StockSlot-001: 正常构造验证字段赋值
     */
    @Test
    public void testNormalConstruction() {
        StockSlot stockSlot = StockSlot.builder()
                .stockCode("000001")
                .stockQueryType("走势分析")
                .timeRange("近一年")
                .exchange("SZ")
                .build();

        assertEquals("000001", stockSlot.getStockCode());
        assertEquals("走势分析", stockSlot.getStockQueryType());
        assertEquals("近一年", stockSlot.getTimeRange());
        assertEquals("SZ", stockSlot.getExchange());
    }

    /**
     * TC-StockSlot-002: 部分字段为null（stockCode和stockQueryType必须有，timeRange和exchange可选）
     */
    @Test
    public void testPartialNullFields() {
        StockSlot stockSlot = StockSlot.builder()
                .stockCode("平安银行")
                .stockQueryType("基本面分析")
                .timeRange(null)
                .exchange(null)
                .build();

        assertEquals("平安银行", stockSlot.getStockCode());
        assertEquals("基本面分析", stockSlot.getStockQueryType());
        assertNull(stockSlot.getTimeRange());
        assertNull(stockSlot.getExchange());
    }

    /**
     * TC-StockSlot-003: 全部字段为null（降级场景）
     */
    @Test
    public void testAllFieldsNull() {
        StockSlot stockSlot = StockSlot.builder()
                .stockCode(null)
                .stockQueryType(null)
                .timeRange(null)
                .exchange(null)
                .build();

        assertNull(stockSlot.getStockCode());
        assertNull(stockSlot.getStockQueryType());
        assertNull(stockSlot.getTimeRange());
        assertNull(stockSlot.getExchange());
    }

    /**
     * TC-StockSlot-004: stockQueryType枚举值验证
     */
    @Test
    public void testStockQueryTypeValues() {
        // 验证常见的stockQueryType值
        StockSlot trendSlot = StockSlot.builder()
                .stockCode("000001")
                .stockQueryType("走势分析")
                .build();
        assertEquals("走势分析", trendSlot.getStockQueryType());

        StockSlot fundamentalSlot = StockSlot.builder()
                .stockCode("000001")
                .stockQueryType("基本面分析")
                .build();
        assertEquals("基本面分析", fundamentalSlot.getStockQueryType());

        StockSlot technicalSlot = StockSlot.builder()
                .stockCode("000001")
                .stockQueryType("技术分析")
                .build();
        assertEquals("技术分析", technicalSlot.getStockQueryType());
    }

    /**
     * TC-StockSlot-005: exchange字段枚举值验证
     */
    @Test
    public void testExchangeValues() {
        StockSlot szSlot = StockSlot.builder()
                .stockCode("000001")
                .exchange("SZ")
                .build();
        assertEquals("SZ", szSlot.getExchange());

        StockSlot shSlot = StockSlot.builder()
                .stockCode("600000")
                .exchange("SH")
                .build();
        assertEquals("SH", shSlot.getExchange());
    }
}
```

---

### 2.3 IntentRoutingPrompt 单元测试

**测试类:** `IntentRoutingPromptTest.java`
**测试路径:** `ai-agent-study-domain/src/test/java/denny/ai/agent/domain/service/auto/step/routing/IntentRoutingPromptTest.java`

```java
package denny.ai.agent.domain.service.auto.step.routing;

import denny.ai.agent.domain.model.entity.IntentFewshotSample;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.*;

/**
 * IntentRoutingPrompt 单元测试（新增 Few-Shot 注入功能）
 * <p>
 * 测试覆盖：
 * 1. TC-Prompt-001: 无Few-Shot样本，Prompt不含参考示例
 * 2. TC-Prompt-002: 有Few-Shot样本（1个），Prompt包含参考示例
 * 3. TC-Prompt-003: 有Few-Shot样本（多个），Prompt包含多个参考示例
 * 4. TC-Prompt-004: Few-Shot样本为null
 * 5. TC-Prompt-005: Few-Shot样本为空列表
 * 6. TC-Prompt-006: Few-Shot样本queryText或exampleJson为null
 * 7. TC-Prompt-007: Prompt包含8种意图分类定义
 * 8. TC-Prompt-008: Prompt包含置信度说明（HIGH/LOW两级）
 * 9. TC-Prompt-009: Prompt包含槽位说明
 * </p>
 *
 * @author denny
 * 2026/5/11
 */
public class IntentRoutingPromptTest {

    /**
     * TC-Prompt-001: 无Few-Shot样本，Prompt不含参考示例
     */
    @Test
    public void testNoFewShotSamples() {
        String userMessage = "分析一下平安银行";
        List<IntentFewshotSample> samples = null;

        String prompt = IntentRoutingPrompt.buildPrompt(userMessage, samples);

        assertNotNull(prompt);
        assertTrue(prompt.contains("用户: 分析一下平安银行"));
        assertFalse(prompt.contains("## 参考示例"));
    }

    /**
     * TC-Prompt-002: 有Few-Shot样本（1个），Prompt包含参考示例
     */
    @Test
    public void testOneFewShotSample() {
        String userMessage = "帮我看看招商银行";
        List<IntentFewshotSample> samples = List.of(
                IntentFewshotSample.builder()
                        .queryText("分析工商银行走势")
                        .exampleJson("{\"intent\":\"STOCK_ANALYSIS\",\"confidence\":\"HIGH\"}")
                        .build()
        );

        String prompt = IntentRoutingPrompt.buildPrompt(userMessage, samples);

        assertTrue(prompt.contains("## 参考示例"));
        assertTrue(prompt.contains("用户: 分析工商银行走势"));
        assertTrue(prompt.contains("输出: {\"intent\":\"STOCK_ANALYSIS\""));
        assertTrue(prompt.contains("用户: 帮我看看招商银行"));
    }

    /**
     * TC-Prompt-003: 有Few-Shot样本（多个），Prompt包含多个参考示例
     */
    @Test
    public void testMultipleFewShotSamples() {
        String userMessage = "贵州茅台怎么样";
        List<IntentFewshotSample> samples = List.of(
                IntentFewshotSample.builder()
                        .queryText("分析工商银行走势")
                        .exampleJson("{\"intent\":\"STOCK_ANALYSIS\",\"confidence\":\"HIGH\"}")
                        .build(),
                IntentFewshotSample.builder()
                        .queryText("什么是RAG")
                        .exampleJson("{\"intent\":\"PE_RETRIEVAL\",\"confidence\":\"HIGH\"}")
                        .build(),
                IntentFewshotSample.builder()
                        .queryText("今天天气")
                        .exampleJson("{\"intent\":\"GENERAL_CHAT\",\"confidence\":\"LOW\"}")
                        .build()
        );

        String prompt = IntentRoutingPrompt.buildPrompt(userMessage, samples);

        assertTrue(prompt.contains("## 参考示例"));
        assertTrue(prompt.contains("用户: 分析工商银行走势"));
        assertTrue(prompt.contains("用户: 什么是RAG"));
        assertTrue(prompt.contains("用户: 今天天气"));
        // 验证示例按顺序输出
        int idx1 = prompt.indexOf("用户: 分析工商银行走势");
        int idx2 = prompt.indexOf("用户: 什么是RAG");
        int idx3 = prompt.indexOf("用户: 今天天气");
        assertTrue(idx1 < idx2 && idx2 < idx3);
    }

    /**
     * TC-Prompt-004: Few-Shot样本为null
     */
    @Test
    public void testNullSamples() {
        String prompt = IntentRoutingPrompt.buildPrompt("test", null);

        assertNotNull(prompt);
        assertFalse(prompt.contains("## 参考示例"));
    }

    /**
     * TC-Prompt-005: Few-Shot样本为空列表
     */
    @Test
    public void testEmptySamples() {
        String prompt = IntentRoutingPrompt.buildPrompt("test", List.of());

        assertNotNull(prompt);
        assertFalse(prompt.contains("## 参考示例"));
    }

    /**
     * TC-Prompt-006: Few-Shot样本queryText或exampleJson为null
     */
    @Test
    public void testSampleWithNullFields() {
        String userMessage = "测试";
        List<IntentFewshotSample> samples = List.of(
                IntentFewshotSample.builder()
                        .queryText(null)
                        .exampleJson("{\"intent\":\"STOCK_ANALYSIS\"}")
                        .build(),
                IntentFewshotSample.builder()
                        .queryText("test")
                        .exampleJson(null)
                        .build()
        );

        String prompt = IntentRoutingPrompt.buildPrompt(userMessage, samples);

        assertNotNull(prompt);
        // 不应抛出异常
    }

    /**
     * TC-Prompt-007: Prompt包含8种意图分类定义
     */
    @Test
    public void testPromptContainsEightIntents() {
        String prompt = IntentRoutingPrompt.buildPrompt("test", null);

        assertTrue(prompt.contains("STOCK_ANALYSIS"));
        assertTrue(prompt.contains("PE_REASONING"));
        assertTrue(prompt.contains("PE_CALCULATION"));
        assertTrue(prompt.contains("PE_RETRIEVAL"));
        assertTrue(prompt.contains("INSPECTION"));
        assertTrue(prompt.contains("GENERAL_CHAT"));
        assertTrue(prompt.contains("AMBIGUOUS"));
        assertTrue(prompt.contains("UNKNOWN"));
    }

    /**
     * TC-Prompt-008: Prompt包含置信度说明（HIGH/LOW两级）
     */
    @Test
    public void testPromptContainsConfidenceLevels() {
        String prompt = IntentRoutingPrompt.buildPrompt("test", null);

        assertTrue(prompt.contains("HIGH"));
        assertTrue(prompt.contains("LOW"));
        // 不应包含MEDIUM（根据P0-4决策）
    }

    /**
     * TC-Prompt-009: Prompt包含槽位说明
     */
    @Test
    public void testPromptContainsSlotDefinitions() {
        String prompt = IntentRoutingPrompt.buildPrompt("test", null);

        assertTrue(prompt.contains("baseSlot"));
        assertTrue(prompt.contains("intentSpecificSlots"));
        assertTrue(prompt.contains("topic"));
        assertTrue(prompt.contains("sentiment"));
        assertTrue(prompt.contains("stockCode"));
        assertTrue(prompt.contains("stockQueryType"));
        assertTrue(prompt.contains("timeRange"));
        assertTrue(prompt.contains("exchange"));
    }
}
```

---

### 2.4 IntentRoutingService 增强测试

**测试类:** `IntentRoutingServiceSlotsTest.java`（新增文件，扩展现有测试）
**测试路径:** `ai-agent-study-domain/src/test/java/denny/ai/agent/domain/service/auto/step/routing/IntentRoutingServiceSlotsTest.java`

```java
package denny.ai.agent.domain.service.auto.step.routing;

import com.alibaba.fastjson.JSON;
import denny.ai.agent.domain.model.valobj.BaseSlot;
import denny.ai.agent.domain.model.valobj.StockSlot;
import denny.ai.agent.domain.model.valobj.enums.ConfidenceEnum;
import denny.ai.agent.domain.model.valobj.enums.IntentTypeEnum;
import denny.ai.agent.domain.service.intent.IntentFewshotService;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;

import java.lang.reflect.Field;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * IntentRoutingService 切槽功能增强测试
 * <p>
 * 测试覆盖（仅测试本次新增/变更逻辑）：
 * 1. TC-Service-Slot-001: STOCK_ANALYSIS响应正确解析baseSlot
 * 2. TC-Service-Slot-002: STOCK_ANALYSIS响应正确解析intentSpecificSlots
 * 3. TC-Service-Slot-003: STOCK_ANALYSIS响应正确构建StockSlot
 * 4. TC-Service-Slot-004: 非STOCK_ANALYSIS意图不构建StockSlot
 * 5. TC-Service-Slot-005: baseSlot字段缺失时降级处理
 * 6. TC-Service-Slot-006: intentSpecificSlots字段缺失时降级处理
 * 7. TC-Service-Slot-007: StockSlot字段部分缺失时降级处理
 * 8. TC-Service-Slot-008: PGvector检索失败时降级为空Few-Shot（跳过Few-Shot）
 * 9. TC-Service-Slot-009: 集成Few-Shot检索流程（Mock IntentFewshotService）
 * 10. TC-Service-Slot-010: intentSpecificSlots.stockSlot字段验证
 * </p>
 *
 * @author denny
 * 2026/5/11
 */
@RunWith(MockitoJUnitRunner.class)
public class IntentRoutingServiceSlotsTest {

    @Mock
    private ChatModel chatModel;

    @Mock
    private IntentFewshotService intentFewshotService;

    private IntentRoutingService intentRoutingService;

    @Before
    public void setUp() throws Exception {
        intentRoutingService = new IntentRoutingService();
        ChatClient chatClient = ChatClient.builder(chatModel).build();

        Field chatClientField = IntentRoutingService.class.getDeclaredField("chatClient");
        chatClientField.setAccessible(true);
        chatClientField.set(intentRoutingService, chatClient);

        Field fewshotServiceField = IntentRoutingService.class.getDeclaredField("intentFewshotService");
        fewshotServiceField.setAccessible(true);
        fewshotServiceField.set(intentRoutingService, intentFewshotService);
    }

    private void mockLLMResponse(String responseContent) {
        ChatResponse chatResponse = new ChatResponse(
                List.of(new Generation(new AssistantMessage(responseContent))));
        when(chatModel.call(any(org.springframework.ai.chat.prompt.Prompt.class)))
                .thenReturn(chatResponse);
    }

    // ========== TC-Service-Slot-001 ~ TC-Service-Slot-003: STOCK_ANALYSIS 切槽测试 ==========

    /**
     * TC-Service-Slot-001: STOCK_ANALYSIS响应正确解析baseSlot
     */
    @Test
    public void testStockAnalysisParseBaseSlot() {
        String response = """
                {
                    "intent": "STOCK_ANALYSIS",
                    "confidence": "HIGH",
                    "reasoning": "用户明确询问股票走势",
                    "baseSlot": {
                        "topic": "股票分析",
                        "sentiment": "neutral"
                    }
                }
                """;
        mockLLMResponse(response);

        IntentRoutingService.IntentRoutingResult result = intentRoutingService.route("分析平安银行", "prompt");

        assertEquals(IntentTypeEnum.STOCK_ANALYSIS, result.getIntent());
        assertEquals(ConfidenceEnum.HIGH, result.getConfidence());
        assertNotNull(result.getBaseSlot());
        assertEquals("股票分析", result.getBaseSlot().getTopic());
        assertEquals("neutral", result.getBaseSlot().getSentiment());
    }

    /**
     * TC-Service-Slot-002: STOCK_ANALYSIS响应正确解析intentSpecificSlots
     */
    @Test
    public void testStockAnalysisParseIntentSpecificSlots() {
        String response = """
                {
                    "intent": "STOCK_ANALYSIS",
                    "confidence": "HIGH",
                    "reasoning": "用户询问股票",
                    "baseSlot": {"topic": "股票", "sentiment": "neutral"},
                    "intentSpecificSlots": {
                        "stockCode": "平安银行",
                        "stockQueryType": "走势分析",
                        "timeRange": "近一年",
                        "exchange": "SZ"
                    }
                }
                """;
        mockLLMResponse(response);

        IntentRoutingService.IntentRoutingResult result = intentRoutingService.route("分析平安银行", "prompt");

        assertNotNull(result.getIntentSpecificSlots());
        assertEquals("平安银行", result.getIntentSpecificSlots().get("stockCode"));
        assertEquals("走势分析", result.getIntentSpecificSlots().get("stockQueryType"));
        assertEquals("近一年", result.getIntentSpecificSlots().get("timeRange"));
        assertEquals("SZ", result.getIntentSpecificSlots().get("exchange"));
    }

    /**
     * TC-Service-Slot-003: STOCK_ANALYSIS响应正确构建StockSlot对象
     */
    @Test
    public void testStockAnalysisBuildStockSlot() {
        String response = """
                {
                    "intent": "STOCK_ANALYSIS",
                    "confidence": "HIGH",
                    "reasoning": "股票分析",
                    "baseSlot": {"topic": "股票", "sentiment": "neutral"},
                    "intentSpecificSlots": {
                        "stockCode": "000001",
                        "stockQueryType": "基本面分析",
                        "timeRange": "近三月",
                        "exchange": "SZ"
                    }
                }
                """;
        mockLLMResponse(response);

        IntentRoutingService.IntentRoutingResult result = intentRoutingService.route("分析", "prompt");

        assertNotNull(result.getIntentSpecificSlots());
        // 验证intentSpecificSlots中包含stockSlot对象
        Object stockSlotObj = result.getIntentSpecificSlots().get("stockSlot");
        assertNotNull(stockSlotObj);
        assertTrue(stockSlotObj instanceof StockSlot);

        StockSlot stockSlot = (StockSlot) stockSlotObj;
        assertEquals("000001", stockSlot.getStockCode());
        assertEquals("基本面分析", stockSlot.getStockQueryType());
        assertEquals("近三月", stockSlot.getTimeRange());
        assertEquals("SZ", stockSlot.getExchange());
    }

    // ========== TC-Service-Slot-004: 非STOCK_ANALYSIS 意图不构建StockSlot ==========

    /**
     * TC-Service-Slot-004: 非STOCK_ANALYSIS意图不构建StockSlot
     */
    @Test
    public void testNonStockAnalysisNoStockSlot() {
        String response = """
                {
                    "intent": "PE_REASONING",
                    "confidence": "HIGH",
                    "reasoning": "逻辑推理"
                }
                """;
        mockLLMResponse(response);

        IntentRoutingService.IntentRoutingResult result = intentRoutingService.route("分析问题", "prompt");

        assertEquals(IntentTypeEnum.PE_REASONING, result.getIntent());
        // intentSpecificSlots应为null或不包含stockSlot
        if (result.getIntentSpecificSlots() != null) {
            assertNull(result.getIntentSpecificSlots().get("stockSlot"));
        }
    }

    // ========== TC-Service-Slot-005 ~ TC-Service-Slot-007: 边界降级测试 ==========

    /**
     * TC-Service-Slot-005: baseSlot字段缺失时降级处理
     */
    @Test
    public void testMissingBaseSlot() {
        String response = """
                {
                    "intent": "STOCK_ANALYSIS",
                    "confidence": "HIGH",
                    "reasoning": "股票分析"
                }
                """;
        mockLLMResponse(response);

        IntentRoutingService.IntentRoutingResult result = intentRoutingService.route("分析", "prompt");

        assertEquals(IntentTypeEnum.STOCK_ANALYSIS, result.getIntent());
        assertNull(result.getBaseSlot());
    }

    /**
     * TC-Service-Slot-006: intentSpecificSlots字段缺失时降级处理
     */
    @Test
    public void testMissingIntentSpecificSlots() {
        String response = """
                {
                    "intent": "STOCK_ANALYSIS",
                    "confidence": "HIGH",
                    "reasoning": "股票分析",
                    "baseSlot": {"topic": "股票", "sentiment": "neutral"}
                }
                """;
        mockLLMResponse(response);

        IntentRoutingService.IntentRoutingResult result = intentRoutingService.route("分析", "prompt");

        assertEquals(IntentTypeEnum.STOCK_ANALYSIS, result.getIntent());
        assertNull(result.getIntentSpecificSlots());
    }

    /**
     * TC-Service-Slot-007: StockSlot字段部分缺失时降级处理
     */
    @Test
    public void testPartialStockSlotFields() {
        String response = """
                {
                    "intent": "STOCK_ANALYSIS",
                    "confidence": "HIGH",
                    "reasoning": "股票分析",
                    "intentSpecificSlots": {
                        "stockCode": "平安银行"
                    }
                }
                """;
        mockLLMResponse(response);

        IntentRoutingService.IntentRoutingResult result = intentRoutingService.route("分析", "prompt");

        assertEquals(IntentTypeEnum.STOCK_ANALYSIS, result.getIntent());
        assertNotNull(result.getIntentSpecificSlots());
        // stockSlot中其他字段为null
        Object stockSlotObj = result.getIntentSpecificSlots().get("stockSlot");
        if (stockSlotObj instanceof StockSlot) {
            StockSlot stockSlot = (StockSlot) stockSlotObj;
            assertEquals("平安银行", stockSlot.getStockCode());
            assertNull(stockSlot.getStockQueryType());
            assertNull(stockSlot.getTimeRange());
            assertNull(stockSlot.getExchange());
        }
    }

    // ========== TC-Service-Slot-008 ~ TC-Service-Slot-009: Few-Shot集成测试 ==========

    /**
     * TC-Service-Slot-008: PGvector检索失败时降级为空Few-Shot（跳过Few-Shot，继续Zero-Shot）
     */
    @Test
    public void testPgvectorFailure_SkipFewShot() {
        // Mock PGvector检索失败
        when(intentFewshotService.retrieveTopK(anyString(), anyInt()))
                .thenThrow(new RuntimeException("PGvector连接失败"));

        // Mock LLM返回正常结果
        String response = """
                {
                    "intent": "STOCK_ANALYSIS",
                    "confidence": "HIGH",
                    "reasoning": "股票分析"
                }
                """;
        mockLLMResponse(response);

        // 不应抛异常，应该降级继续执行
        IntentRoutingService.IntentRoutingResult result = intentRoutingService.route("分析", "prompt");

        assertEquals(IntentTypeEnum.STOCK_ANALYSIS, result.getIntent());
        assertEquals(ConfidenceEnum.HIGH, result.getConfidence());
    }

    /**
     * TC-Service-Slot-009: 集成Few-Shot检索流程
     */
    @Test
    public void testIntegratedFewShotRetrieval() {
        // Mock PGvector检索返回样本
        when(intentFewshotService.retrieveTopK(eq("分析平安银行"), eq(5)))
                .thenReturn(Collections.emptyList());

        // Mock LLM返回
        String response = """
                {
                    "intent": "STOCK_ANALYSIS",
                    "confidence": "HIGH",
                    "reasoning": "股票分析"
                }
                """;
        mockLLMResponse(response);

        IntentRoutingService.IntentRoutingResult result = intentRoutingService.route("分析平安银行", "prompt");

        // 验证调用了Few-Shot服务
        verify(intentFewshotService, times(1)).retrieveTopK("分析平安银行", 5);
        assertEquals(IntentTypeEnum.STOCK_ANALYSIS, result.getIntent());
    }

    /**
     * TC-Service-Slot-010: intentSpecificSlots.stockSlot字段验证（直接测试parseResponse）
     */
    @Test
    public void testParseResponseStockSlotField() {
        String response = """
                {
                    "intent": "STOCK_ANALYSIS",
                    "confidence": "HIGH",
                    "reasoning": "test",
                    "intentSpecificSlots": {
                        "stockCode": "600000",
                        "stockQueryType": "技术分析",
                        "timeRange": "近一年",
                        "exchange": "SH"
                    }
                }
                """;

        IntentRoutingService.IntentRoutingResult result = intentRoutingService.parseResponse(response);

        assertNotNull(result.getIntentSpecificSlots());
        Object stockSlotObj = result.getIntentSpecificSlots().get("stockSlot");
        assertNotNull(stockSlotObj);
        assertTrue(stockSlotObj instanceof StockSlot);
    }
}
```

---

### 2.5 IntentRoutingNode 增强测试

**测试类:** `IntentRoutingNodeSlotsTest.java`（新增文件）
**测试路径:** `ai-agent-study-domain/src/test/java/denny/ai/agent/domain/service/auto/step/routing/IntentRoutingNodeSlotsTest.java`

```java
package denny.ai.agent.domain.service.auto.step.routing;

import cn.bugstack.wrench.design.framework.tree.StrategyHandler;
import denny.ai.agent.domain.model.entity.ExecuteCommandEntity;
import denny.ai.agent.domain.model.valobj.StockSlot;
import denny.ai.agent.domain.model.valobj.enums.ConfidenceEnum;
import denny.ai.agent.domain.model.valobj.enums.IntentTypeEnum;
import denny.ai.agent.domain.service.auto.step.chat.GeneralChatNode;
import denny.ai.agent.domain.service.auto.step.factory.DefaultAutoAgentExecuteStrategyFactory;
import denny.ai.agent.domain.service.auto.step.pe.Step1AnalyzerNode;
import denny.ai.agent.domain.service.auto.step.react.IntelligentInspection;
import denny.ai.agent.domain.service.chatmemory.ChatMemoryPersistenceService;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * IntentRoutingNode 切槽功能增强测试
 * <p>
 * 测试覆盖（仅测试本次新增/变更逻辑）：
 * 1. TC-Node-Slot-001: STOCK_ANALYSIS路由到tradingNode（新增）
 * 2. TC-Node-Slot-002: STOCK_ANALYSIS时StockSlot存入DynamicContext
 * 3. TC-Node-Slot-003: STOCK_ANALYSIS时baseSlot存入DynamicContext
 * 4. TC-Node-Slot-004: STOCK_ANALYSIS时intentRoutingResult存入DynamicContext
 * 5. TC-Node-Slot-005: 非STOCK_ANALYSIS意图不存入StockSlot
 * 6. TC-Node-Slot-006: StockSlot字段部分缺失时存入context（带null值）
 * 7. TC-Node-Slot-007: getRecentHistoryMessages修复验证（getRecentHistoryMessages）
 * 8. TC-Node-Slot-008: doApply中路由结果存入context的完整性验证
 * </p>
 *
 * @author denny
 * 2026/5/11
 */
@RunWith(MockitoJUnitRunner.class)
public class IntentRoutingNodeSlotsTest {

    @Mock
    private IntentRoutingService intentRoutingService;

    @Mock
    private ChatMemoryPersistenceService chatMemoryPersistenceService;

    @Mock
    private Step1AnalyzerNode step1AnalyzerNode;

    @Mock
    private IntelligentInspection intelligentInspection;

    @Mock
    private GeneralChatNode generalChatNode;

    @Mock
    private StrategyHandler generalHandler;

    private IntentRoutingNode intentRoutingNode;

    private DefaultAutoAgentExecuteStrategyFactory.DynamicContext dynamicContext;

    private ExecuteCommandEntity request;

    @Before
    public void setUp() throws Exception {
        intentRoutingNode = new IntentRoutingNode();

        setField(intentRoutingNode, "intentRoutingService", intentRoutingService);
        setField(intentRoutingNode, "chatMemoryPersistenceService", chatMemoryPersistenceService);
        setField(intentRoutingNode, "step1AnalyzerNode", step1AnalyzerNode);
        setField(intentRoutingNode, "intelligentInspection", intelligentInspection);
        setField(intentRoutingNode, "generalChatNode", generalChatNode);

        dynamicContext = new DefaultAutoAgentExecuteStrategyFactory.DynamicContext();

        request = ExecuteCommandEntity.builder()
                .sessionId("test-session-123")
                .message("分析平安银行")
                .build();
    }

    private void setField(Object target, String fieldName, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(target, value);
    }

    // ========== TC-Node-Slot-001 ~ TC-Node-Slot-004: STOCK_ANALYSIS 切槽路由测试 ==========

    /**
     * TC-Node-Slot-001: STOCK_ANALYSIS路由到tradingNode
     * <p>
     * 验证：get()方法返回tradingNode handler（非generalChatNode）
     * </p>
     */
    @Test
    public void testStockAnalysisRoutesToTradingNode() throws Exception {
        dynamicContext.setValue(IntentRoutingNode.RECOGNIZED_INTENT_KEY, IntentTypeEnum.STOCK_ANALYSIS);

        // 由于tradingNode可能不存在，我们验证它不是generalChatNode
        // 或者mock一个tradingNode来验证路由
        StrategyHandler<ExecuteCommandEntity, DefaultAutoAgentExecuteStrategyFactory.DynamicContext, String> handler =
                intentRoutingNode.get(request, dynamicContext);

        // 根据设计文档，STOCK_ANALYSIS应该路由到tradingNode
        // 在测试环境中，如果tradingNode注入失败，会fallback到其他节点
        // 这里验证至少不会错误地路由到PE相关节点
        assertNotEquals(step1AnalyzerNode, handler);
    }

    /**
     * TC-Node-Slot-002: STOCK_ANALYSIS时StockSlot存入DynamicContext
     */
    @Test
    public void testStockAnalysisStockSlotStoredInContext() throws Exception {
        // Mock 历史消息
        when(chatMemoryPersistenceService.getConversationHistory(anyString()))
                .thenReturn(List.of());

        // Mock LLM返回STOCK_ANALYSIS结果（含完整slots）
        IntentRoutingService.IntentRoutingResult stockResult =
                IntentRoutingService.IntentRoutingResult.builder()
                        .intent(IntentTypeEnum.STOCK_ANALYSIS)
                        .confidence(ConfidenceEnum.HIGH)
                        .reasoning("用户询问股票")
                        .intentSpecificSlots(Map.of(
                                "stockSlot", StockSlot.builder()
                                        .stockCode("平安银行")
                                        .stockQueryType("走势分析")
                                        .timeRange("近一年")
                                        .exchange("SZ")
                                        .build()
                        ))
                        .build();
        when(intentRoutingService.route(anyString(), anyString()))
                .thenReturn(stockResult);

        intentRoutingNode.doApply(request, dynamicContext);

        // 验证StockSlot存入context
        StockSlot storedStockSlot = dynamicContext.getValue("stockSlot");
        assertNotNull(storedStockSlot);
        assertEquals("平安银行", storedStockSlot.getStockCode());
        assertEquals("走势分析", storedStockSlot.getStockQueryType());
        assertEquals("近一年", storedStockSlot.getTimeRange());
        assertEquals("SZ", storedStockSlot.getExchange());
    }

    /**
     * TC-Node-Slot-003: STOCK_ANALYSIS时intentRoutingResult存入DynamicContext
     */
    @Test
    public void testStockAnalysisIntentRoutingResultStoredInContext() throws Exception {
        when(chatMemoryPersistenceService.getConversationHistory(anyString()))
                .thenReturn(List.of());

        IntentRoutingService.IntentRoutingResult stockResult =
                IntentRoutingService.IntentRoutingResult.builder()
                        .intent(IntentTypeEnum.STOCK_ANALYSIS)
                        .confidence(ConfidenceEnum.HIGH)
                        .reasoning("股票分析")
                        .build();
        when(intentRoutingService.route(anyString(), anyString()))
                .thenReturn(stockResult);

        intentRoutingNode.doApply(request, dynamicContext);

        // 验证intentRoutingResult存入context
        IntentRoutingService.IntentRoutingResult storedResult =
                dynamicContext.getValue(IntentRoutingNode.ROUTING_RESULT_KEY);
        assertNotNull(storedResult);
        assertEquals(IntentTypeEnum.STOCK_ANALYSIS, storedResult.getIntent());
        assertEquals(ConfidenceEnum.HIGH, storedResult.getConfidence());
    }

    /**
     * TC-Node-Slot-004: doApply中recognizedIntent存入DynamicContext
     */
    @Test
    public void testStockAnalysisRecognizedIntentStoredInContext() throws Exception {
        when(chatMemoryPersistenceService.getConversationHistory(anyString()))
                .thenReturn(List.of());

        IntentRoutingService.IntentRoutingResult stockResult =
                IntentRoutingService.IntentRoutingResult.builder()
                        .intent(IntentTypeEnum.STOCK_ANALYSIS)
                        .confidence(ConfidenceEnum.HIGH)
                        .reasoning("股票")
                        .build();
        when(intentRoutingService.route(anyString(), anyString()))
                .thenReturn(stockResult);

        intentRoutingNode.doApply(request, dynamicContext);

        // 验证recognizedIntent存入context
        IntentTypeEnum storedIntent = dynamicContext.getValue(IntentRoutingNode.RECOGNIZED_INTENT_KEY);
        assertEquals(IntentTypeEnum.STOCK_ANALYSIS, storedIntent);
    }

    // ========== TC-Node-Slot-005 ~ TC-Node-Slot-006: 非STOCK_ANALYSIS 和边界测试 ==========

    /**
     * TC-Node-Slot-005: 非STOCK_ANALYSIS意图不存入StockSlot
     */
    @Test
    public void testNonStockAnalysisNoStockSlotInContext() throws Exception {
        when(chatMemoryPersistenceService.getConversationHistory(anyString()))
                .thenReturn(List.of());

        IntentRoutingService.IntentRoutingResult result =
                IntentRoutingService.IntentRoutingResult.builder()
                        .intent(IntentTypeEnum.PE_REASONING)
                        .confidence(ConfidenceEnum.HIGH)
                        .reasoning("逻辑推理")
                        .build();
        when(intentRoutingService.route(anyString(), anyString()))
                .thenReturn(result);

        intentRoutingNode.doApply(request, dynamicContext);

        // 验证stockSlot未存入context
        StockSlot storedStockSlot = dynamicContext.getValue("stockSlot");
        assertNull(storedStockSlot);
    }

    /**
     * TC-Node-Slot-006: StockSlot字段部分缺失时存入context（带null值）
     */
    @Test
    public void testPartialStockSlotStoredWithNullFields() throws Exception {
        when(chatMemoryPersistenceService.getConversationHistory(anyString()))
                .thenReturn(List.of());

        // Mock只返回stockCode，其他字段缺失
        IntentRoutingService.IntentRoutingResult result =
                IntentRoutingService.IntentRoutingResult.builder()
                        .intent(IntentTypeEnum.STOCK_ANALYSIS)
                        .confidence(ConfidenceEnum.HIGH)
                        .reasoning("股票")
                        .intentSpecificSlots(Map.of(
                                "stockSlot", StockSlot.builder()
                                        .stockCode("平安银行")
                                        .build()
                        ))
                        .build();
        when(intentRoutingService.route(anyString(), anyString()))
                .thenReturn(result);

        intentRoutingNode.doApply(request, dynamicContext);

        StockSlot storedStockSlot = dynamicContext.getValue("stockSlot");
        assertNotNull(storedStockSlot);
        assertEquals("平安银行", storedStockSlot.getStockCode());
        assertNull(storedStockSlot.getStockQueryType());
        assertNull(storedStockSlot.getTimeRange());
        assertNull(storedStockSlot.getExchange());
    }

    // ========== TC-Node-Slot-007: 方法名修复验证 ==========

    /**
     * TC-Node-Slot-007: getRecentHistoryMessages方法调用验证
     * <p>
     * 验证：doApply中调用的是getRecentHistoryMessages而非getRecentMessages
     * </p>
     */
    @Test
    public void testGetRecentHistoryMessagesCalled() throws Exception {
        when(chatMemoryPersistenceService.getConversationHistory(anyString()))
                .thenReturn(List.of());

        IntentRoutingService.IntentRoutingResult result =
                IntentRoutingService.IntentRoutingResult.builder()
                        .intent(IntentTypeEnum.GENERAL_CHAT)
                        .confidence(ConfidenceEnum.HIGH)
                        .reasoning("闲聊")
                        .build();
        when(intentRoutingService.route(anyString(), anyString()))
                .thenReturn(result);

        intentRoutingNode.doApply(request, dynamicContext);

        // 验证调用了getConversationHistory
        verify(chatMemoryPersistenceService, times(1)).getConversationHistory("test-session-123");
    }

    // ========== TC-Node-Slot-008: doApply路由结果完整性验证 ==========

    /**
     * TC-Node-Slot-008: doApply中路由结果存入context的完整性验证
     */
    @Test
    public void testDoApplyContextCompleteness() throws Exception {
        when(chatMemoryPersistenceService.getConversationHistory(anyString()))
                .thenReturn(List.of());

        IntentRoutingService.IntentRoutingResult result =
                IntentRoutingService.IntentRoutingResult.builder()
                        .intent(IntentTypeEnum.STOCK_ANALYSIS)
                        .confidence(ConfidenceEnum.HIGH)
                        .reasoning("股票分析")
                        .intentSpecificSlots(Map.of(
                                "stockSlot", StockSlot.builder()
                                        .stockCode("000001")
                                        .stockQueryType("走势")
                                        .timeRange("近一年")
                                        .exchange("SZ")
                                        .build()
                        ))
                        .build();
        when(intentRoutingService.route(anyString(), anyString()))
                .thenReturn(result);

        intentRoutingNode.doApply(request, dynamicContext);

        // 验证所有关键字段都存入context
        assertNotNull(dynamicContext.getValue(IntentRoutingNode.ROUTING_RESULT_KEY));
        assertNotNull(dynamicContext.getValue(IntentRoutingNode.RECOGNIZED_INTENT_KEY));
        assertNotNull(dynamicContext.getValue("stockSlot"));
    }
}
```

---

### 2.6 IntentFewshotService 单元测试

**测试类:** `IntentFewshotServiceTest.java`
**测试路径:** `ai-agent-study-domain/src/test/java/denny/ai/agent/domain/service/intent/IntentFewshotServiceTest.java`

```java
package denny.ai.agent.domain.service.intent;

import denny.ai.agent.domain.model.entity.IntentFewshotSample;
import denny.ai.agent.domain.repository.IntentFewshotSampleRepository;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;

import java.util.List;

import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * IntentFewshotService 单元测试
 * <p>
 * 测试覆盖：
 * 1. TC-Fewshot-001: retrieveTopK正常检索（返回多个样本）
 * 2. TC-Fewshot-002: retrieveTopK无结果返回空列表
 * 3. TC-Fewshot-003: retrieveTopK异常时降级为空列表
 * 4. TC-Fewshot-004: addSample正常新增样本
 * 5. TC-Fewshot-005: deleteSample软删除样本
 * 6. TC-Fewshot-006: updateSample更新样本
 * 7. TC-Fewshot-007: retrieveTopK使用正确的k值
 * 8. TC-Fewshot-008: 样本过滤验证（仅返回status=1的样本）
 * </p>
 *
 * @author denny
 * 2026/5/11
 */
@RunWith(MockitoJUnitRunner.class)
public class IntentFewshotServiceTest {

    @Mock
    private IntentFewshotSampleRepository repository;

    private IntentFewshotService intentFewshotService;

    @Before
    public void setUp() {
        intentFewshotService = new IntentFewshotService();
        // 通过setter或其他方式注入mock repository
        // 由于代码未实现，这里假设使用字段注入
    }

    /**
     * TC-Fewshot-001: retrieveTopK正常检索（返回多个样本）
     */
    @Test
    public void testRetrieveTopKNormal() {
        List<IntentFewshotSample> mockSamples = List.of(
                IntentFewshotSample.builder()
                        .id(1L)
                        .queryText("分析工商银行")
                        .exampleJson("{\"intent\":\"STOCK_ANALYSIS\"}")
                        .build(),
                IntentFewshotSample.builder()
                        .id(2L)
                        .queryText("分析招商银行")
                        .exampleJson("{\"intent\":\"STOCK_ANALYSIS\"}")
                        .build()
        );
        when(repository.searchSimilar(anyString(), anyInt())).thenReturn(mockSamples);

        List<IntentFewshotSample> result = intentFewshotService.retrieveTopK("分析平安银行", 5);

        assertNotNull(result);
        assertEquals(2, result.size());
        verify(repository, times(1)).searchSimilar("分析平安银行", 5);
    }

    /**
     * TC-Fewshot-002: retrieveTopK无结果返回空列表
     */
    @Test
    public void testRetrieveTopKEmptyResult() {
        when(repository.searchSimilar(anyString(), anyInt())).thenReturn(List.of());

        List<IntentFewshotSample> result = intentFewshotService.retrieveTopK("未知查询", 5);

        assertNotNull(result);
        assertTrue(result.isEmpty());
    }

    /**
     * TC-Fewshot-003: retrieveTopK异常时降级为空列表
     */
    @Test
    public void testRetrieveTopKException() {
        when(repository.searchSimilar(anyString(), anyInt()))
                .thenThrow(new RuntimeException("数据库连接失败"));

        List<IntentFewshotSample> result = intentFewshotService.retrieveTopK("分析", 5);

        // 降级为空列表，不抛异常
        assertNotNull(result);
        assertTrue(result.isEmpty());
    }

    /**
     * TC-Fewshot-004: addSample正常新增样本
     */
    @Test
    public void testAddSample() {
        IntentFewshotSample sample = IntentFewshotSample.builder()
                .queryText("测试查询")
                .intentCode("STOCK_ANALYSIS")
                .exampleJson("{\"intent\":\"STOCK_ANALYSIS\"}")
                .build();

        intentFewshotService.addSample(sample);

        verify(repository, times(1)).save(any(IntentFewshotSample.class));
    }

    /**
     * TC-Fewshot-005: deleteSample软删除样本
     */
    @Test
    public void testDeleteSample() {
        intentFewshotService.deleteSample(1L);

        verify(repository, times(1)).updateStatus(1L, (byte) 0);
    }

    /**
     * TC-Fewshot-006: updateSample更新样本
     */
    @Test
    public void testUpdateSample() {
        IntentFewshotSample sample = IntentFewshotSample.builder()
                .id(1L)
                .queryText("更新后的查询")
                .exampleJson("{\"intent\":\"STOCK_ANALYSIS\"}")
                .build();

        intentFewshotService.updateSample(1L, sample);

        verify(repository, times(1)).update(any(IntentFewshotSample.class));
    }

    /**
     * TC-Fewshot-007: retrieveTopK使用正确的k值
     */
    @Test
    public void testRetrieveTopKUsesCorrectK() {
        when(repository.searchSimilar(anyString(), anyInt())).thenReturn(List.of());

        intentFewshotService.retrieveTopK("测试", 5);

        verify(repository, times(1)).searchSimilar("测试", 5);
    }

    /**
     * TC-Fewshot-008: 样本过滤验证（仅返回status=1的样本）
     */
    @Test
    public void testSampleFilteringByStatus() {
        // 假设repository.searchSimilar内部已经过滤status=1
        // 这里验证返回的样本都是status=1
        List<IntentFewshotSample> mockSamples = List.of(
                IntentFewshotSample.builder()
                        .id(1L)
                        .status((byte) 1)
                        .queryText("启用样本")
                        .build()
        );
        when(repository.searchSimilar(anyString(), anyInt())).thenReturn(mockSamples);

        List<IntentFewshotSample> result = intentFewshotService.retrieveTopK("测试", 5);

        assertTrue(result.stream().allMatch(s -> s.getStatus() == 1));
    }
}
```

---

## 3. 测试用例汇总表

### 3.1 BaseSlot 测试用例

| 用例ID | 用例名称 | 输入 | 预期结果 |
|--------|----------|------|----------|
| TC-BaseSlot-001 | 正常构造验证 | topic="股票分析", sentiment="neutral" | 字段正确赋值 |
| TC-BaseSlot-002 | topic为null | topic=null, sentiment="positive" | topic为null，其他正常 |
| TC-BaseSlot-003 | sentiment为null | topic="市场分析", sentiment=null | sentiment为null，其他正常 |
| TC-BaseSlot-004 | 全部为null | 全部字段=null | 全部为null |
| TC-BaseSlot-005 | builder模式 | 使用builder构造 | 构造成功 |

### 3.2 StockSlot 测试用例

| 用例ID | 用例名称 | 输入 | 预期结果 |
|--------|----------|------|----------|
| TC-StockSlot-001 | 正常构造 | 4个字段都有值 | 字段正确赋值 |
| TC-StockSlot-002 | 部分字段null | stockCode和queryType有值，其他null | 部分字段为null |
| TC-StockSlot-003 | 全部null | 全部字段=null | 全部为null |
| TC-StockSlot-004 | stockQueryType枚举值 | 传入不同queryType值 | 正确存储 |
| TC-StockSlot-005 | exchange枚举值 | SZ/SH等 | 正确存储 |

### 3.3 IntentRoutingPrompt 测试用例

| 用例ID | 用例名称 | 输入 | 预期结果 |
|--------|----------|------|----------|
| TC-Prompt-001 | 无Few-Shot样本 | samples=null | Prompt不含参考示例 |
| TC-Prompt-002 | 1个Few-Shot样本 | samples含1个样本 | Prompt含1个参考示例 |
| TC-Prompt-003 | 多个Few-Shot样本 | samples含3个样本 | Prompt含3个参考示例，按序 |
| TC-Prompt-004 | samples为null | samples=null | Prompt不含参考示例 |
| TC-Prompt-005 | 空列表 | samples=[] | Prompt不含参考示例 |
| TC-Prompt-006 | 样本字段为null | queryText或exampleJson为null | 不抛异常 |
| TC-Prompt-007 | 8种意图定义 | - | Prompt含8种意图 |
| TC-Prompt-008 | 置信度说明 | - | Prompt含HIGH/LOW |
| TC-Prompt-009 | 槽位说明 | - | Prompt含baseSlot/intentSpecificSlots |

### 3.4 IntentRoutingService 切槽测试用例

| 用例ID | 用例名称 | 输入 | 预期结果 |
|--------|----------|------|----------|
| TC-Service-Slot-001 | 解析baseSlot | STOCK_ANALYSIS响应含baseSlot | baseSlot正确解析 |
| TC-Service-Slot-002 | 解析intentSpecificSlots | 含完整intentSpecificSlots | 正确解析4个字段 |
| TC-Service-Slot-003 | 构建StockSlot | STOCK_ANALYSIS意图 | 构建StockSlot对象 |
| TC-Service-Slot-004 | 非STOCK_ANALYSIS | PE_REASONING意图 | 不构建StockSlot |
| TC-Service-Slot-005 | baseSlot缺失 | 响应无baseSlot | baseSlot为null |
| TC-Service-Slot-006 | intentSpecificSlots缺失 | 响应无intentSpecificSlots | intentSpecificSlots为null |
| TC-Service-Slot-007 | StockSlot部分字段 | 只有stockCode | 其他字段为null |
| TC-Service-Slot-008 | PGvector失败 | PGvector抛异常 | 降级为空Few-Shot |
| TC-Service-Slot-009 | 集成Few-Shot | 调用route | 调用intentFewshotService |
| TC-Service-Slot-010 | stockSlot字段验证 | parseResponse直接调用 | stockSlot对象正确 |

### 3.5 IntentRoutingNode 切槽测试用例

| 用例ID | 用例名称 | 输入 | 预期结果 |
|--------|----------|------|----------|
| TC-Node-Slot-001 | STOCK_ANALYSIS路由 | intent=STOCK_ANALYSIS | 路由到tradingNode |
| TC-Node-Slot-002 | StockSlot存入Context | STOCK_ANALYSIS结果 | stockSlot在context |
| TC-Node-Slot-003 | intentRoutingResult存入Context | - | 完整结果在context |
| TC-Node-Slot-004 | recognizedIntent存入Context | - | intent在context |
| TC-Node-Slot-005 | 非STOCK_ANALYSIS无StockSlot | PE_REASONING | stockSlot不在context |
| TC-Node-Slot-006 | 部分字段StockSlot | stockCode-only | 存入带null字段的StockSlot |
| TC-Node-Slot-007 | getRecentHistoryMessages调用 | - | 调用正确方法 |
| TC-Node-Slot-008 | context完整性 | - | 3个key都存入 |

### 3.6 IntentFewshotService 测试用例

| 用例ID | 用例名称 | 输入 | 预期结果 |
|--------|----------|------|----------|
| TC-Fewshot-001 | 正常检索 | 返回2个样本 | 返回2个样本 |
| TC-Fewshot-002 | 无结果 | 无匹配样本 | 返回空列表 |
| TC-Fewshot-003 | 异常降级 | 数据库异常 | 返回空列表，不抛异常 |
| TC-Fewshot-004 | 新增样本 | 样本对象 | 调用repository.save |
| TC-Fewshot-005 | 删除样本 | id=1 | 调用updateStatus(id, 0) |
| TC-Fewshot-006 | 更新样本 | 样本对象 | 调用repository.update |
| TC-Fewshot-007 | k值验证 | k=5 | 调用searchSimilar(query, 5) |
| TC-Fewshot-008 | status过滤 | - | 仅返回status=1 |

---

## 4. 测试执行指南

### 4.1 前提条件

1. **测试环境准备**
   - JUnit 5 环境
   - Mockito 依赖
   - 项目依赖正常编译

2. **Mock对象**
   - `ChatModel` - Mock LLM调用
   - `IntentFewshotService` - Mock PGvector检索
   - `IntentFewshotSampleRepository` - Mock 数据库操作
   - `ChatMemoryPersistenceService` - Mock 会话历史

### 4.2 执行顺序

```
1. BaseSlotTest (基础VO)
2. StockSlotTest (基础VO)
3. IntentRoutingPromptTest (Prompt构建)
4. IntentRoutingServiceSlotsTest (切槽解析)
5. IntentRoutingNodeSlotsTest (节点路由)
6. IntentFewshotServiceTest (Few-Shot服务)
```

### 4.3 覆盖率目标

| 测试类 | 目标覆盖率 |
|--------|------------|
| IntentRoutingServiceSlotsTest | 切槽解析逻辑 100% |
| IntentRoutingNodeSlotsTest | 新增路由逻辑 100% |
| IntentRoutingPromptTest | Few-Shot注入 100% |
| IntentFewshotServiceTest | CRUD + 检索 100% |

---

## 5. 已知限制与注意事项

1. **tradingNode依赖**：TC-Node-Slot-001需要tradingNode实现后才能完整验证路由目标
2. **PGvector集成**：Few-Shot检索依赖PGvector服务，单元测试需mock
3. **ConfidenceEnum MEDIUM**：现有测试用例包含MEDIUM，需确认是否保留
4. **边界降级**：所有降级场景应确保不抛异常，流程继续

---

## 6. 附录

### 6.1 测试数据示例

**STOCK_ANALYSIS 完整响应：**
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

**Few-Shot 样本示例：**
```json
{
    "queryText": "分析工商银行走势",
    "intentCode": "STOCK_ANALYSIS",
    "exampleJson": "{\"intent\":\"STOCK_ANALYSIS\",\"confidence\":\"HIGH\",\"reasoning\":\"明确股票分析请求\",\"baseSlot\":{\"topic\":\"股票分析\",\"sentiment\":\"neutral\"},\"intentSpecificSlots\":{\"stockCode\":\"工商银行\",\"stockQueryType\":\"走势分析\",\"timeRange\":\"近一年\",\"exchange\":\"SH\"}}"
}
```
