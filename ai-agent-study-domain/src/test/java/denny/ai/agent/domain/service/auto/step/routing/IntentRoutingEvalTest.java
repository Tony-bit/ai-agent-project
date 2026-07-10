package denny.ai.agent.domain.service.auto.step.routing;

import denny.ai.agent.domain.model.valobj.MultiIntentRoutingResult;
import denny.ai.agent.domain.model.valobj.SubTask;
import denny.ai.agent.domain.model.valobj.enums.ConfidenceEnum;
import denny.ai.agent.domain.model.valobj.enums.IntentTypeEnum;
import denny.ai.agent.domain.service.auto.step.routing.model.IntentRoutingEvalCase;
import denny.ai.agent.domain.service.auto.step.routing.support.IntentRoutingEvalCaseLoader;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import static org.junit.Assert.*;

/**
 * Intent Routing 可评测集参数化测试
 * <p>
 * 遍历 intent-routing-cases.json 中的所有 case，
 * 对每条 case 调用 parseUnifiedResponse，验证输出符合 expected 断言。
 * <p>
 * 断言策略（第一版强断言）：
 * <ul>
 *   <li>multiTask 与 expected.multiTask 比对</li>
 *   <li>needsClarification 与 expected.needsClarification 比对</li>
 *   <li>taskList.size() 与 expected.taskCount 比对</li>
 *   <li>每个 SubTask 的 intent 与 expected.taskIntents[i] 比对</li>
 *   <li>每个 SubTask 的 executorNode 与 expected.executorNodes[i] 比对</li>
 *   <li>clarification 场景：missingInfo 与 expected.missingInfo 比对</li>
 * </ul>
 *
 * @author denny
 * 2026/06/08
 */
@RunWith(Parameterized.class)
public class IntentRoutingEvalTest {

    private IntentRoutingService intentRoutingService;

    @Parameterized.Parameter(0)
    public String caseId;

    @Parameterized.Parameter(1)
    public IntentRoutingEvalCase aCase;

    @Parameterized.Parameter(2)
    public String caseDescription;

    @Parameterized.Parameters(name = "{0}: {2}")
    public static Collection<Object[]> data() {
        IntentRoutingEvalCaseLoader loader = new IntentRoutingEvalCaseLoader();
        List<IntentRoutingEvalCase> allCases = loader.getRunnableCases();
        List<Object[]> parameters = new ArrayList<>(allCases.size());
        for (IntentRoutingEvalCase c : allCases) {
            parameters.add(new Object[]{c.getCaseId(), c, c.getDescription()});
        }
        return parameters;
    }

    @Before
    public void setUp() {
        intentRoutingService = new IntentRoutingService();
    }

    @Test
    public void testParseUnifiedResponse() {
        String response = aCase.getResponse();
        IntentRoutingEvalCase.ExpectedResult expected = aCase.getExpected();

        MultiIntentRoutingResult result = intentRoutingService.parseUnifiedResponse(response);

        assertNotNull("parseUnifiedResponse 不应返回 null, caseId=" + caseId, result);

        if (expected.getMultiTask() != null) {
            assertEquals(
                    "multiTask 断言失败, caseId=" + caseId,
                    expected.getMultiTask(),
                    result.getMultiTask()
            );
        }

        if (expected.getNeedsClarification() != null) {
            assertEquals(
                    "needsClarification 断言失败, caseId=" + caseId,
                    expected.getNeedsClarification(),
                    result.getNeedsClarification()
            );
        }

        if (expected.getTaskCount() != null) {
            assertEquals(
                    "taskCount 断言失败, caseId=" + caseId,
                    expected.getTaskCount().intValue(),
                    result.getTaskList().size()
            );
        }

        if (expected.getTaskIntents() != null && !expected.getTaskIntents().isEmpty()) {
            List<SubTask> taskList = result.getTaskList();
            List<String> expectedIntents = expected.getTaskIntents();
            assertEquals(
                    "taskIntents 长度不匹配, caseId=" + caseId,
                    expectedIntents.size(),
                    taskList.size()
            );
            for (int i = 0; i < expectedIntents.size(); i++) {
                String expectedIntentStr = expectedIntents.get(i);
                IntentTypeEnum expectedIntent = IntentTypeEnum.fromCode(expectedIntentStr);
                IntentTypeEnum actualIntent = taskList.get(i).getIntent();
                assertEquals(
                        "taskIntents[" + i + "] 断言失败, caseId=" + caseId,
                        expectedIntent,
                        actualIntent
                );
            }
        }

        if (expected.getExecutorNodes() != null && !expected.getExecutorNodes().isEmpty()) {
            List<SubTask> taskList = result.getTaskList();
            List<String> expectedNodes = expected.getExecutorNodes();
            assertEquals(
                    "executorNodes 长度不匹配, caseId=" + caseId,
                    expectedNodes.size(),
                    taskList.size()
            );
            for (int i = 0; i < expectedNodes.size(); i++) {
                assertEquals(
                        "executorNodes[" + i + "] 断言失败, caseId=" + caseId,
                        expectedNodes.get(i),
                        taskList.get(i).getExecutorNode()
                );
            }
        }

        if (expected.getConfidences() != null && !expected.getConfidences().isEmpty()) {
            List<SubTask> taskList = result.getTaskList();
            List<String> expectedConfidences = expected.getConfidences();
            assertEquals(
                    "confidences 长度不匹配, caseId=" + caseId,
                    expectedConfidences.size(),
                    taskList.size()
            );
            for (int i = 0; i < expectedConfidences.size(); i++) {
                assertEquals(
                        "confidences[" + i + "] 断言失败, caseId=" + caseId,
                        ConfidenceEnum.fromCode(expectedConfidences.get(i)),
                        taskList.get(i).getConfidence()
                );
            }
        }

        if (expected.getTaskTypes() != null && !expected.getTaskTypes().isEmpty()) {
            List<SubTask> taskList = result.getTaskList();
            assertEquals("taskTypes 长度不匹配, caseId=" + caseId,
                    expected.getTaskTypes().size(), taskList.size());
            for (int i = 0; i < expected.getTaskTypes().size(); i++) {
                assertEquals(
                        "taskTypes[" + i + "] 断言失败, caseId=" + caseId,
                        expected.getTaskTypes().get(i),
                        taskList.get(i).getTaskType()
                );
            }
        }

        if (expected.getTaskStatuses() != null && !expected.getTaskStatuses().isEmpty()) {
            List<SubTask> taskList = result.getTaskList();
            assertEquals("taskStatuses 长度不匹配, caseId=" + caseId,
                    expected.getTaskStatuses().size(), taskList.size());
            for (int i = 0; i < expected.getTaskStatuses().size(); i++) {
                assertEquals(
                        "taskStatuses[" + i + "] 断言失败, caseId=" + caseId,
                        SubTask.SubTaskStatus.valueOf(expected.getTaskStatuses().get(i)),
                        taskList.get(i).getStatus()
                );
            }
        }

        if (expected.getMissingInfo() != null && !expected.getMissingInfo().isEmpty()) {
            List<String> actualMissingInfo = result.getMissingInfo();
            assertNotNull(
                    "missingInfo 不应为 null, caseId=" + caseId,
                    actualMissingInfo
            );
            assertEquals(
                    "missingInfo 长度不匹配, caseId=" + caseId,
                    expected.getMissingInfo().size(),
                    actualMissingInfo.size()
            );
            for (String mi : expected.getMissingInfo()) {
                assertTrue(
                        "missingInfo 应包含 '" + mi + "', caseId=" + caseId,
                        actualMissingInfo.contains(mi)
                );
            }
        }

        if (expected.getClarificationPrompt() != null) {
            assertEquals(
                    "clarificationPrompt 断言失败, caseId=" + caseId,
                    expected.getClarificationPrompt(),
                    result.getClarificationPrompt()
            );
        }

        if (expected.getReasoningContains() != null) {
            assertNotNull("reasoning 不应为 null, caseId=" + caseId, result.getReasoning());
            assertTrue(
                    "reasoning 应包含 '" + expected.getReasoningContains() + "', caseId=" + caseId,
                    result.getReasoning().contains(expected.getReasoningContains())
            );
        }
    }
}
