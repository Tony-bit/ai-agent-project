package denny.ai.agent.trading.infra.config;

import com.alibaba.cloud.ai.graph.agent.hook.skills.ReadSkillTool;
import com.alibaba.cloud.ai.graph.agent.hook.skills.SkillsAgentHook;
import com.alibaba.cloud.ai.graph.skills.SpringAiSkillAdvisor;
import com.alibaba.cloud.ai.graph.skills.registry.SkillRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallback;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TradingSkillsConfigTest {

    @Test
    void createsSkillRegistryAdvisorHookAndReadSkillTool() {
        TradingSkillsConfig config = new TradingSkillsConfig();

        SkillRegistry skillRegistry = config.tradingSkillRegistry();
        SpringAiSkillAdvisor advisor = config.tradingSkillAdvisor(skillRegistry);
        SkillsAgentHook hook = config.tradingSkillsAgentHook(skillRegistry);
        ToolCallback readSkillTool = config.readTradingSkillToolCallback(skillRegistry);

        assertNotNull(skillRegistry);
        assertNotNull(advisor);
        assertNotNull(hook);
        assertNotNull(readSkillTool);
        assertEquals("Classpath", skillRegistry.getRegistryType());
        assertEquals(7, skillRegistry.size(), "Trading skill registry should load all 7 skills");
        assertEquals(ReadSkillTool.READ_SKILL, readSkillTool.getToolDefinition().name());
        assertTrue(readSkillTool.getToolDefinition().description().contains("skill"));
    }

    @Test
    void registryContainsExpectedTradingSkills() {
        TradingSkillsConfig config = new TradingSkillsConfig();

        SkillRegistry skillRegistry = config.tradingSkillRegistry();

        assertTrue(skillRegistry.contains("get-stock-info"));
        assertTrue(skillRegistry.contains("get-historical-bars"));
        assertTrue(skillRegistry.contains("get-technical-indicators"));
        assertTrue(skillRegistry.contains("get-fundamental-data"));
        assertTrue(skillRegistry.contains("get-sentiment"));
        assertTrue(skillRegistry.contains("get-stock-news"));
        assertTrue(skillRegistry.contains("search-stock-by-name"));
    }
}
