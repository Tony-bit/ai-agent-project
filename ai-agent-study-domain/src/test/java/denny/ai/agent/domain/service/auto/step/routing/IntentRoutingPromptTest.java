package denny.ai.agent.domain.service.auto.step.routing;

import denny.ai.agent.domain.model.entity.IntentFewshotSample;
import org.junit.Test;

import java.util.Collections;
import java.util.List;

import static org.junit.Assert.*;

/**
 * IntentRoutingPrompt 单元测试
 * <p>
 * 测试覆盖：
 * 1. TC-IRP-001: 用户消息被嵌入 Prompt
 * 2. TC-IRP-002: Prompt 包含合法意图
 * 3. TC-IRP-003: Prompt 禁止输出运行期字段
 * 4. TC-IRP-004: Prompt 包含多任务分解规则
 * 5. TC-IRP-005: Prompt 包含应该/不应该触发多任务的场景
 * </p>
 *
 * @author denny
 * 2026/05/31
 */
public class IntentRoutingPromptTest {

    @Test
    public void should_constrain_split_task_routing_to_standard_intent_codes() {
        String slotPrompt = IntentRoutingPrompt.buildTaskRoutingSlotPrompt("search RAG documents", List.of());

        assertTrue(slotPrompt.contains("合法 intent 取值严格限定"));
        assertTrue(slotPrompt.contains("PE_RETRIEVAL"));
        assertTrue(slotPrompt.contains("PE_REASONING"));
        assertTrue(slotPrompt.contains("GENERAL_CHAT"));
        assertTrue(slotPrompt.contains("FINANCIAL_GENERAL"));
        assertTrue(slotPrompt.contains("intent 字段必须严格等于上述 7 个合法值之一"));
        assertTrue(slotPrompt.contains("禁止输出语义标签或自造标签"));
        assertTrue(slotPrompt.contains("TECHNICAL_CONSULTING"));
        assertTrue(slotPrompt.contains("INFORMATION_PROVISION"));
    }

    @Test
    public void should_append_fewshot_examples_to_split_task_routing_prompt() {
        String slotPrompt = IntentRoutingPrompt.buildTaskRoutingSlotPrompt(
                "search RAG documents",
                List.of(),
                List.of(IntentFewshotSample.builder()
                        .queryText("检索 RAG 架构资料")
                        .exampleJson("{\"intent\":\"PE_RETRIEVAL\"}")
                        .build())
        );

        assertTrue(slotPrompt.contains("## 参考示例"));
        assertTrue(slotPrompt.contains("示例仅用于学习 intent 边界和合法枚举"));
        assertTrue(slotPrompt.contains("检索 RAG 架构资料"));
        assertTrue(slotPrompt.contains("{\"intent\":\"PE_RETRIEVAL\"}"));
        assertTrue(slotPrompt.contains("Task:\nsearch RAG documents"));
    }

    /**
     * TC-IRP-001: 用户消息被嵌入 Prompt
     */
    @Test
    public void testBuildMultiTaskDecomposePrompt_containsUserMessage() {
        String userMessage = "分析贵州茅台、比亚迪走势";

        String prompt = IntentRoutingPrompt.buildMultiTaskDecomposePrompt(userMessage, Collections.emptyList());

        assertTrue("Prompt 应包含用户消息", prompt.contains(userMessage));
    }

    /**
     * TC-IRP-002: Prompt 包含合法意图
     */
    @Test
    public void testBuildMultiTaskDecomposePrompt_containsIntentCodes() {
        String prompt = IntentRoutingPrompt.buildMultiTaskDecomposePrompt("测试消息", Collections.emptyList());

        assertTrue("Prompt 应包含 STOCK_ANALYSIS", prompt.contains("STOCK_ANALYSIS"));
        assertTrue("Prompt 应包含 GENERAL_CHAT", prompt.contains("GENERAL_CHAT"));
        assertTrue("Prompt 应包含 PE_REASONING", prompt.contains("PE_REASONING"));
        assertTrue("Prompt 应包含 INSPECTION", prompt.contains("INSPECTION"));
    }

    /**
     * TC-IRP-003: Prompt 禁止输出运行期字段
     */
    @Test
    public void testBuildMultiTaskDecomposePrompt_rejectsRuntimeFields() {
        String prompt = IntentRoutingPrompt.buildMultiTaskDecomposePrompt("测试消息", Collections.emptyList());

        assertTrue("Prompt 应说明不要输出 executorNode", prompt.contains("不要输出 executorNode"));
        assertTrue("Prompt 应说明运行期字段由服务端生成", prompt.contains("运行期字段由服务端生成"));
    }

    /**
     * TC-IRP-004: Prompt 包含多任务分解规则
     */
    @Test
    public void testBuildMultiTaskDecomposePrompt_containsDecomposeRules() {
        String prompt = IntentRoutingPrompt.buildMultiTaskDecomposePrompt("测试消息", Collections.emptyList());

        assertTrue("Prompt 应包含分解规则", prompt.contains("分解规则"));
        assertTrue("Prompt 应包含按实体粒度分解规则", prompt.contains("实体粒度"));
    }

    /**
     * TC-IRP-005: Prompt 包含应该/不应该触发多任务的场景
     */
    @Test
    public void testBuildMultiTaskDecomposePrompt_containsTriggerScenarios() {
        String prompt = IntentRoutingPrompt.buildMultiTaskDecomposePrompt("测试消息", Collections.emptyList());

        assertTrue("Prompt 应包含应该触发场景", prompt.contains("应该触发多任务分解"));
        assertTrue("Prompt 应包含不应触发场景", prompt.contains("不应触发多任务分解"));
    }

    /**
     * TC-IRP-006: 历史消息为空时，Prompt 包含"无历史对话"
     */
    @Test
    public void testBuildMultiTaskDecomposePrompt_emptyHistory() {
        String prompt = IntentRoutingPrompt.buildMultiTaskDecomposePrompt("测试消息", Collections.emptyList());

        assertTrue("历史为空时应包含提示", prompt.contains("无历史对话") || prompt.contains("历史上下文"));
    }

    /**
     * TC-IRP-007: 历史消息非空时，Prompt 包含历史内容
     */
    @Test
    public void testBuildMultiTaskDecomposePrompt_withHistory() {
        List<String> history = List.of("user: 分析茅台", "assistant: 茅台分析完成");

        String prompt = IntentRoutingPrompt.buildMultiTaskDecomposePrompt("分析比亚迪", history);

        assertTrue("Prompt 应包含历史消息", prompt.contains("分析茅台") && prompt.contains("茅台分析完成"));
    }

    /**
     * TC-IRP-008: Prompt 包含 JSON 输出格式说明
     */
    @Test
    public void testBuildMultiTaskDecomposePrompt_containsJsonFormat() {
        String prompt = IntentRoutingPrompt.buildMultiTaskDecomposePrompt("测试消息", Collections.emptyList());

        assertTrue("Prompt 应包含 multiTask 字段", prompt.contains("multiTask"));
        assertTrue("Prompt 应包含 taskList 字段", prompt.contains("taskList"));
        assertTrue("Prompt 应包含 JSON 输出要求", prompt.contains("JSON格式"));
    }

    /**
     * TC-IRP-009: 统一路由 Prompt 应将概念解释类知识问答归入 GENERAL_CHAT
     */
    @Test
    public void testBuildUnifiedRoutingPrompt_prefersGeneralChatForConceptExplanation() {
        String prompt = IntentRoutingPrompt.buildUnifiedRoutingPrompt("什么是向量数据库", Collections.emptyList(), Collections.emptyList());

        assertTrue("GENERAL_CHAT 描述应包含概念解释和简单知识问答", prompt.contains("概念解释、简单知识问答、普通信息查询"));
        assertTrue("PE_RETRIEVAL 描述应收缩到重型检索场景", prompt.contains("知识库检索、多文档汇总、外部资料整合"));
    }

    @Test
    public void should_build_both_stage_prompts_when_history_is_empty() {
        String decompositionPrompt = IntentRoutingPrompt.buildQueryDecompositionPrompt("分析贵州茅台", List.of());
        String slotPrompt = IntentRoutingPrompt.buildTaskRoutingSlotPrompt("分析贵州茅台", List.of());

        assertTrue(decompositionPrompt.contains("（无历史对话）"));
        assertTrue(slotPrompt.contains("（无历史对话）"));
        assertTrue(decompositionPrompt.contains("Do not output intent"));
        assertTrue(slotPrompt.contains("Do not output multiTask"));
    }

    @Test
    public void should_define_financial_boundaries_in_every_intent_producing_prompt() {
        List<String> intentPrompts = List.of(
                IntentRoutingPrompt.buildPrompt("查询茅台市盈率", List.of()),
                IntentRoutingPrompt.buildUnifiedRoutingPrompt("查询茅台市盈率", List.of(), List.of()),
                IntentRoutingPrompt.buildTaskRoutingSlotPrompt("查询茅台市盈率", List.of()),
                IntentRoutingPrompt.buildMultiTaskDecomposePrompt("查询茅台市盈率", List.of())
        );

        for (String prompt : intentPrompts) {
            assertTrue("Prompt 应包含 FINANCIAL_GENERAL", prompt.contains("FINANCIAL_GENERAL"));
            assertTrue("Prompt 应收窄 STOCK_ANALYSIS 到投资决策", prompt.contains("买入、卖出、持有"));
            assertTrue("Prompt 应包含分析深度澄清字段", prompt.contains("analysisDepth"));
            assertTrue("Prompt 应包含固定澄清问题", prompt.contains("你需要快速了解，还是进行完整投资分析？"));
            assertTrue("Prompt 应声明分析关键词不是充分条件", prompt.contains("不能单独作为 STOCK_ANALYSIS"));
            assertTrue("Prompt 应声明否定表达优先", prompt.contains("否定表达优先"));
        }
    }

    @Test
    public void unified_prompt_should_include_contrastive_financial_examples() {
        String prompt = IntentRoutingPrompt.buildUnifiedRoutingPrompt("贵州茅台最近怎么样", List.of(), List.of());

        assertTrue(prompt.contains("查询贵州茅台当前股价和市盈率"));
        assertTrue(prompt.contains("贵州茅台当前估值是否适合买入"));
        assertTrue(prompt.contains("帮我看看贵州茅台最近怎么样"));
        assertTrue(prompt.contains("当前输入和历史上下文优先"));
    }

    @Test
    public void financial_clarification_reply_should_reuse_history_context() {
        List<String> history = List.of(
                "user: 贵州茅台最近怎么样",
                "assistant: 你需要快速了解，还是进行完整投资分析？");

        String quickPrompt = IntentRoutingPrompt.buildUnifiedRoutingPrompt("快速了解", history, List.of());
        String fullPrompt = IntentRoutingPrompt.buildUnifiedRoutingPrompt("完整投资分析", history, List.of());

        for (String prompt : List.of(quickPrompt, fullPrompt)) {
            assertTrue(prompt.contains("快速了解"));
            assertTrue(prompt.contains("FINANCIAL_GENERAL"));
            assertTrue(prompt.contains("完整投资分析"));
            assertTrue(prompt.contains("STOCK_ANALYSIS"));
            assertTrue(prompt.contains("task content 必须结合历史恢复原金融对象"));
            assertTrue(prompt.contains("无法识别选项时安全生成 FINANCIAL_GENERAL"));
            assertTrue(prompt.contains("user: 贵州茅台最近怎么样"));
        }
    }

    @Test
    public void should_define_authoritative_stock_slot_contract_in_unified_prompt() {
        String prompt = IntentRoutingPrompt.buildUnifiedRoutingPrompt(
                "对药明康德进行完整投资分析", List.of(), List.of());

        assertTrue(prompt.contains("search_stock_by_name"));
        assertTrue(prompt.contains("stockName"));
        assertTrue(prompt.contains("ALL|FUNDAMENTAL|TECHNICAL|SENTIMENT|NEWS"));
        assertTrue(prompt.contains("不得凭记忆生成"));
        assertFalse(prompt.contains("stockQueryType, timeRange, exchange"));
    }
}
