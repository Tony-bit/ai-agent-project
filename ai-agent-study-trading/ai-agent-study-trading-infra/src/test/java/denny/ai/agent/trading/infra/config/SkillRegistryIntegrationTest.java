package denny.ai.agent.trading.infra.config;

import com.alibaba.cloud.ai.graph.agent.hook.skills.ReadSkillTool;
import com.alibaba.cloud.ai.graph.agent.hook.skills.SkillsAgentHook;
import com.alibaba.cloud.ai.graph.skills.SkillMetadata;
import com.alibaba.cloud.ai.graph.skills.registry.SkillRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallback;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SkillRegistryIntegrationTest {

    @Test
    void skillsAgentHookExposesReadSkillToolAndTradingMetadata() {
        TradingSkillsConfig config = new TradingSkillsConfig();

        SkillRegistry skillRegistry = config.tradingSkillRegistry();
        SkillsAgentHook hook = config.tradingSkillsAgentHook(skillRegistry);
        List<ToolCallback> tools = hook.getTools();
        List<SkillMetadata> skills = hook.listSkills();

        assertNotNull(tools);
        assertFalse(tools.isEmpty());
        assertTrue(tools.stream().anyMatch(tool -> ReadSkillTool.READ_SKILL.equals(tool.getToolDefinition().name())));
        assertEquals(7, hook.getSkillCount());
        assertEquals(7, skills.size());
        assertTrue(skills.stream().map(SkillMetadata::getName).anyMatch("get-stock-info"::equals));
    }
}
