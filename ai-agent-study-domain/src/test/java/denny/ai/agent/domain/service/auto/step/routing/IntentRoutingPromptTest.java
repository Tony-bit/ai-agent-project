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
        assertTrue(slotPrompt.contains("intent 字段必须严格等于上述 6 个合法值之一"));
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
}
