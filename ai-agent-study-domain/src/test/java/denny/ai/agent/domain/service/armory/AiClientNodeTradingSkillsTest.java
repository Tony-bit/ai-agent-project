package denny.ai.agent.domain.service.armory;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.advisor.api.Advisor;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.context.ApplicationContext;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AiClientNodeTradingSkillsTest {

    @Test
    void enabledClientIdGetsReadSkillToolAndAdvisor() {
        AiClientNode node = new AiClientNode();
        ApplicationContext context = mock(ApplicationContext.class);
        ToolCallback readSkillTool = mock(ToolCallback.class);
        Advisor tradingSkillAdvisor = mock(Advisor.class);
        ToolCallback verboseTool = new TestToolCallback(
                "get_stock_info",
                "获取A股股票的实时行情信息，包括当前价格、52周高低、日成交量、市盈率、市净率等。适用场景：需要查询股票当前价格、涨跌幅、市值等基本信息时调用。注意：这是原始长描述。",
                "executed");

        MockEnvironment environment = new MockEnvironment()
                .withProperty("spring.ai.trading.skills.enabled-client-ids[0]", "6001");

        when(context.containsBean("readTradingSkillToolCallback")).thenReturn(true);
        when(context.containsBean("tradingSkillAdvisor")).thenReturn(true);
        when(context.getBean("readTradingSkillToolCallback", ToolCallback.class)).thenReturn(readSkillTool);
        when(context.getBean("tradingSkillAdvisor", Advisor.class)).thenReturn(tradingSkillAdvisor);

        ReflectionTestUtils.setField(node, "applicationContext", context);
        ReflectionTestUtils.setField(node, "environment", environment);

        ToolCallback[] mergedToolCallbacks = node.appendTradingSkillToolCallbacks("6001", new ToolCallback[]{verboseTool});
        List<Advisor> advisors = new ArrayList<>();
        node.appendTradingSkillAdvisor("6001", advisors);

        assertEquals(2, mergedToolCallbacks.length);
        assertEquals("get_stock_info", mergedToolCallbacks[0].getToolDefinition().name());
        assertFalse(mergedToolCallbacks[0].getToolDefinition().description().contains("当前价格、52周高低"));
        assertTrue(mergedToolCallbacks[0].getToolDefinition().description().contains("read_skill"));
        assertEquals("executed", mergedToolCallbacks[0].call("{}"));
        assertSame(readSkillTool, mergedToolCallbacks[1]);
        assertEquals(1, advisors.size());
        assertSame(tradingSkillAdvisor, advisors.get(0));
    }

    @Test
    void unconfiguredClientIdSkipsTradingSkills() {
        AiClientNode node = new AiClientNode();
        ApplicationContext context = mock(ApplicationContext.class);
        ToolCallback baseTool = mock(ToolCallback.class);
        List<Advisor> advisors = new ArrayList<>();
        advisors.add(mock(Advisor.class));

        MockEnvironment environment = new MockEnvironment()
                .withProperty("spring.ai.trading.skills.enabled-client-ids[0]", "6001");

        ReflectionTestUtils.setField(node, "applicationContext", context);
        ReflectionTestUtils.setField(node, "environment", environment);

        ToolCallback[] mergedToolCallbacks = node.appendTradingSkillToolCallbacks("6002", new ToolCallback[]{baseTool});
        node.appendTradingSkillAdvisor("6002", advisors);

        assertEquals(1, mergedToolCallbacks.length);
        assertSame(baseTool, mergedToolCallbacks[0]);
        assertEquals(1, advisors.size());
        verify(context, never()).containsBean("readTradingSkillToolCallback");
        verify(context, never()).containsBean("tradingSkillAdvisor");
    }

    @Test
    void parsesConfiguredClientIdsFromYamlStyleProperties() {
        AiClientNode node = new AiClientNode();
        MockEnvironment environment = new MockEnvironment()
                .withProperty("spring.ai.trading.skills.enabled-client-ids[0]", "6001")
                .withProperty("spring.ai.trading.skills.enabled-client-ids[1]", "6013");

        ReflectionTestUtils.setField(node, "environment", environment);

        List<String> clientIds = node.getTradingSkillsEnabledClientIds();

        assertEquals(2, clientIds.size());
        assertTrue(clientIds.contains("6001"));
        assertTrue(clientIds.contains("6013"));
    }

    private record TestToolCallback(String name, String description, String result) implements ToolCallback {

        @Override
        public ToolDefinition getToolDefinition() {
            return ToolDefinition.builder()
                    .name(name)
                    .description(description)
                    .inputSchema("{}")
                    .build();
        }

        @Override
        public String call(String functionInput) {
            return result;
        }
    }
}
