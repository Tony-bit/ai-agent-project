package denny.ai.agent.domain.service.auto.step;

import cn.bugstack.wrench.design.framework.tree.StrategyHandler;
import denny.ai.agent.domain.model.entity.ExecuteCommandEntity;
import denny.ai.agent.domain.model.valobj.AiAgentClientFlowConfigVO;
import denny.ai.agent.domain.service.auto.step.factory.DefaultAutoAgentExecuteStrategyFactory;
import denny.ai.agent.domain.service.auto.step.routing.IntentRoutingNode;
import denny.ai.agent.domain.service.auto.step.pe.Step1AnalyzerNode;
import denny.ai.agent.domain.service.auto.step.react.IntelligentInspection;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;

import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.Map;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

/**
 * RootNode 单元测试
 * <p>
 * 测试覆盖：
 * 1. TC-Root-001: 显式巡检Agent (aiAgentId="5") → intelligentInspection
 * 2. TC-Root-002: 显式PE链路 (aiAgentId非空) → step1AnalyzerNode
 * 3. TC-Root-003: 无aiAgentId_null → intentRoutingNode
 * 4. TC-Root-004: 无aiAgentId_空字符串 → intentRoutingNode
 * 5. TC-Root-005: 无aiAgentId_空白字符串 → intentRoutingNode
 * </p>
 *
 * @author denny
 * 2026/5/11
 */
@RunWith(MockitoJUnitRunner.class)
public class RootNodeTest {

    @Mock
    private IntentRoutingNode intentRoutingNode;

    @Mock
    private Step1AnalyzerNode step1AnalyzerNode;

    @Mock
    private IntelligentInspection intelligentInspection;

    private RootNode rootNode;

    private DefaultAutoAgentExecuteStrategyFactory.DynamicContext dynamicContext;

    @Before
    public void setUp() throws Exception {
        rootNode = new RootNode();

        setField(rootNode, "intentRoutingNode", intentRoutingNode);
        setField(rootNode, "step1AnalyzerNode", step1AnalyzerNode);
        setField(rootNode, "intelligentInspection", intelligentInspection);

        dynamicContext = new DefaultAutoAgentExecuteStrategyFactory.DynamicContext();
    }

    private void setField(Object target, String fieldName, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(target, value);
    }

    // ========== TC-Root-001 ~ TC-Root-005: 三分支路由测试 ==========

    /**
     * TC-Root-001: 显式巡检Agent (aiAgentId="5") → intelligentInspection
     */
    @Test
    public void testInspectionAgent_routesToIntelligengInspection() throws Exception {
        ExecuteCommandEntity request = ExecuteCommandEntity.builder()
                .aiAgentId("5")
                .sessionId("test-session")
                .message("执行巡检")
                .build();

        StrategyHandler<ExecuteCommandEntity, DefaultAutoAgentExecuteStrategyFactory.DynamicContext, String> handler =
                rootNode.get(request, dynamicContext);

        assertEquals(intelligentInspection, handler);
    }

    /**
     * TC-Root-002: 显式PE链路 (aiAgentId非空) → step1AnalyzerNode
     */
    @Test
    public void testExplicitPEAgent_routesToStep1Analyzer() throws Exception {
        ExecuteCommandEntity request = ExecuteCommandEntity.builder()
                .aiAgentId("123")
                .sessionId("test-session")
                .message("PE任务")
                .build();

        StrategyHandler<ExecuteCommandEntity, DefaultAutoAgentExecuteStrategyFactory.DynamicContext, String> handler =
                rootNode.get(request, dynamicContext);

        assertEquals(step1AnalyzerNode, handler);
    }

    /**
     * TC-Root-003: 无aiAgentId (null) → intentRoutingNode
     */
    @Test
    public void testNullAiAgentId_routesToIntentRouting() throws Exception {
        ExecuteCommandEntity request = ExecuteCommandEntity.builder()
                .aiAgentId(null)
                .sessionId("test-session")
                .message("用户请求")
                .build();

        StrategyHandler<ExecuteCommandEntity, DefaultAutoAgentExecuteStrategyFactory.DynamicContext, String> handler =
                rootNode.get(request, dynamicContext);

        assertEquals(intentRoutingNode, handler);
    }

    /**
     * TC-Root-004: 无aiAgentId (空字符串) → intentRoutingNode
     */
    @Test
    public void testEmptyAiAgentId_routesToIntentRouting() throws Exception {
        ExecuteCommandEntity request = ExecuteCommandEntity.builder()
                .aiAgentId("")
                .sessionId("test-session")
                .message("用户请求")
                .build();

        StrategyHandler<ExecuteCommandEntity, DefaultAutoAgentExecuteStrategyFactory.DynamicContext, String> handler =
                rootNode.get(request, dynamicContext);

        assertEquals(intentRoutingNode, handler);
    }

    /**
     * TC-Root-005: 无aiAgentId (空白字符串) → intentRoutingNode
     */
    @Test
    public void testBlankAiAgentId_routesToIntentRouting() throws Exception {
        ExecuteCommandEntity request = ExecuteCommandEntity.builder()
                .aiAgentId("   ")
                .sessionId("test-session")
                .message("用户请求")
                .build();

        StrategyHandler<ExecuteCommandEntity, DefaultAutoAgentExecuteStrategyFactory.DynamicContext, String> handler =
                rootNode.get(request, dynamicContext);

        assertEquals(intentRoutingNode, handler);
    }

    /**
     * TC-Root-006: 验证 aiAgentId="5" 精确匹配，不是 "50" 或 "51"
     */
    @Test
    public void testInspectionAgentId_exactMatch() throws Exception {
        // aiAgentId="50" 应该走 PE 链路，不是巡检
        ExecuteCommandEntity request50 = ExecuteCommandEntity.builder()
                .aiAgentId("50")
                .sessionId("test-session")
                .message("任务")
                .build();

        StrategyHandler<ExecuteCommandEntity, DefaultAutoAgentExecuteStrategyFactory.DynamicContext, String> handler50 =
                rootNode.get(request50, dynamicContext);

        assertEquals(step1AnalyzerNode, handler50);
    }
}
