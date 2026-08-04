package denny.ai.agent.domain.service.armory;

import com.alibaba.cloud.ai.graph.skills.SpringAiSkillAdvisor;
import org.junit.Before;
import org.junit.Test;
import org.springframework.ai.chat.client.advisor.api.Advisor;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.context.ApplicationContext;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class AiClientNodeToolIsolationTest {

    private AiClientNode node;

    @Before
    public void setUp() {
        node = new AiClientNode();
        MockEnvironment environment = new MockEnvironment()
                .withProperty("spring.ai.trading.tools.allowed-by-client.3201[0]", "read_skill")
                .withProperty("spring.ai.trading.tools.allowed-by-client.3201[1]", "search_stock_by_name")
                .withProperty("spring.ai.trading.tools.allowed-by-client.3201[2]", "search_stock_by_name")
                .withProperty("spring.ai.trading.tools.allowed-by-client.6002[0]", "get_stock_info");
        ReflectionTestUtils.setField(node, "environment", environment);
    }

    @Test
    public void should_hide_trading_tools_from_general_chat_client() {
        ToolCallback tradingTool = tool("get_stock_info");
        ToolCallback generalTool = tool("search_episodic_memory");

        assertFalse(shouldRegister("3001", tradingTool));
        assertTrue(shouldRegister("3001", generalTool));
    }

    @Test
    public void should_keep_trading_tools_for_enabled_trading_client() {
        assertTrue(shouldRegister("6002", tool("get_stock_info")));
        assertFalse(shouldRegister("6002", tool("get_stock_news")));
    }

    @Test
    public void trading_skill_advisor_should_not_bypass_explicit_allowlist() {
        Advisor skillAdvisor = mock(SpringAiSkillAdvisor.class);

        assertTrue(node.isTradingSkillsEnabled("3001", List.of(skillAdvisor)));
        assertFalse(shouldRegister("3001", tool("get_historical_bars"), true));
    }

    @Test
    public void configured_client_should_remain_skill_enabled_without_advisor() {
        MockEnvironment environment = new MockEnvironment()
                .withProperty("spring.ai.trading.skills.enabled-client-ids[0]", "3201");
        ReflectionTestUtils.setField(node, "environment", environment);

        assertTrue(node.isTradingSkillsEnabled("3201", List.of()));
    }

    @Test
    public void allowlisted_read_skill_should_keep_general_tools_and_register_read_skill() {
        ToolCallbackRegistry registry = new ToolCallbackRegistry();
        ToolCallback tradingTool = tool("get_historical_bars");
        ToolCallback generalTool = tool("search_episodic_memory");
        ToolCallback readSkill = tool("read_skill");
        registry.register(tradingTool, "test");
        registry.register(generalTool, "test");

        ApplicationContext applicationContext = mock(ApplicationContext.class);
        when(applicationContext.containsBean("readTradingSkillToolCallback")).thenReturn(true);
        when(applicationContext.getBean("readTradingSkillToolCallback", ToolCallback.class))
                .thenReturn(readSkill);
        ReflectionTestUtils.setField(node, "toolCallbackRegistry", registry);
        ReflectionTestUtils.setField(node, "applicationContext", applicationContext);

        ReflectionTestUtils.invokeMethod(
                node, "appendTradingSkillToolCallbacks", "3201", true);

        Set<String> names = registry.getAllToolNames();
        assertEquals(Set.of("search_episodic_memory", "read_skill"), names);
    }

    @Test
    public void missing_or_empty_client_allowlist_should_deny_all_trading_tools() {
        assertFalse(shouldRegister("9999", tool("get_stock_info")));

        MockEnvironment environment = new MockEnvironment()
                .withProperty("spring.ai.trading.tools.allowed-by-client.4101", "");
        ReflectionTestUtils.setField(node, "environment", environment);

        assertFalse(shouldRegister("4101", tool("search_stock_by_name")));
        assertTrue(shouldRegister("4101", tool("search_episodic_memory")));
    }

    @Test
    public void allowlist_should_be_immutable_and_deduplicated() {
        Map<String, Set<String>> allowlist = ReflectionTestUtils.invokeMethod(
                node, "getAllowedTradingToolsByClient");

        assertEquals(Set.of("read_skill", "search_stock_by_name"), allowlist.get("3201"));
        assertThrows(UnsupportedOperationException.class,
                () -> allowlist.get("3201").add("get_stock_info"));
        assertThrows(UnsupportedOperationException.class,
                () -> allowlist.put("6003", Set.of("get_stock_info")));
    }

    @Test
    public void unknown_trading_tool_name_should_fail_validation() {
        MockEnvironment environment = new MockEnvironment()
                .withProperty("spring.ai.trading.tools.allowed-by-client.3201[0]", "unknown_tool");
        ReflectionTestUtils.setField(node, "environment", environment);

        IllegalStateException thrown = assertThrows(IllegalStateException.class,
                () -> ReflectionTestUtils.invokeMethod(node, "validateTradingToolAllowlist"));

        assertTrue(thrown.getMessage().contains("unknown_tool"));
    }

    @Test
    public void lightweight_wrapper_should_forward_tool_context_to_delegate() {
        ToolCallback delegate = tool("get_stock_info");
        ToolCallback wrapper = ReflectionTestUtils.invokeMethod(
                node, "toLightweightTradingToolCallback", delegate);
        ToolContext toolContext = new ToolContext(Map.of("target_context", "target"));
        when(delegate.call("{}", toolContext)).thenReturn("ok");

        String result = wrapper.call("{}", toolContext);

        assertSame("ok", result);
        verify(delegate).call("{}", toolContext);
        verify(delegate, never()).call("{}");
    }

    private boolean shouldRegister(String clientId, ToolCallback callback) {
        return shouldRegister(clientId, callback, false);
    }

    private boolean shouldRegister(String clientId, ToolCallback callback, boolean tradingSkillsEnabled) {
        Boolean result = ReflectionTestUtils.invokeMethod(
                node, "shouldRegisterSpringToolCallback", clientId, callback, tradingSkillsEnabled);
        return Boolean.TRUE.equals(result);
    }

    private ToolCallback tool(String name) {
        ToolCallback callback = mock(ToolCallback.class);
        when(callback.getToolDefinition()).thenReturn(ToolDefinition.builder()
                .name(name)
                .description(name)
                .inputSchema("{\"type\":\"object\"}")
                .build());
        return callback;
    }
}
