package denny.ai.agent.domain.service.auto.step.routing;

import org.junit.Test;

import java.util.Collections;
import java.util.List;

import static org.junit.Assert.*;

/**
 * IntentRoutingPrompt 单元测试
 * <p>
 * 测试覆盖：
 * 1. TC-IRP-001: 用户消息被嵌入 Prompt
 * 2. TC-IRP-002: Prompt 包含意图-执行节点映射
 * 3. TC-IRP-003: Prompt 包含 executorNode 字段说明
 * 4. TC-IRP-004: Prompt 包含多任务分解规则
 * 5. TC-IRP-005: Prompt 包含应该/不应该触发多任务的场景
 * </p>
 *
 * @author denny
 * 2026/05/31
 */
public class IntentRoutingPromptTest {

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
     * TC-IRP-002: Prompt 包含意图-执行节点映射
     */
    @Test
    public void testBuildMultiTaskDecomposePrompt_containsIntentMapping() {
        String prompt = IntentRoutingPrompt.buildMultiTaskDecomposePrompt("测试消息", Collections.emptyList());

        assertTrue("Prompt 应包含 tradingStarter 映射", prompt.contains("tradingStarter"));
        assertTrue("Prompt 应包含 generalChatNode 映射", prompt.contains("generalChatNode"));
        assertTrue("Prompt 应包含 step1AnalyzerNode 映射", prompt.contains("step1AnalyzerNode"));
        assertTrue("Prompt 应包含 intelligentInspection 映射", prompt.contains("intelligentInspection"));
    }

    /**
     * TC-IRP-003: Prompt 包含 executorNode 字段说明
     */
    @Test
    public void testBuildMultiTaskDecomposePrompt_containsExecutorNodeField() {
        String prompt = IntentRoutingPrompt.buildMultiTaskDecomposePrompt("测试消息", Collections.emptyList());

        assertTrue("Prompt 应包含 executorNode 字段说明", prompt.contains("executorNode"));
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
}
