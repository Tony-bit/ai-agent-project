package denny.ai.agent.trading.infra.config;

import com.alibaba.cloud.ai.graph.agent.hook.skills.ReadSkillTool;
import com.alibaba.cloud.ai.graph.skills.registry.SkillRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallback;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProgressiveDisclosureTest {

    @Test
    void readSkillToolLoadsFullSkillContentOnDemand() {
        TradingSkillsConfig config = new TradingSkillsConfig();

        SkillRegistry skillRegistry = config.tradingSkillRegistry();
        ToolCallback readSkillTool = config.readTradingSkillToolCallback(skillRegistry);

        String content = readSkillTool.call("{\"skill_name\":\"get-stock-info\"}");

        assertTrue(content.contains("# 获取股票实时信息"));
        assertTrue(content.contains("ToolCallback (`TradingToolCallbacks`)"));
        assertTrue(content.contains("get_stock_info"));
    }

    @Test
    void readSkillToolReturnsErrorForMissingSkillName() {
        TradingSkillsConfig config = new TradingSkillsConfig();

        SkillRegistry skillRegistry = config.tradingSkillRegistry();
        ToolCallback readSkillTool = config.readTradingSkillToolCallback(skillRegistry);

        String content = readSkillTool.call("{}");

        assertEquals("\"Error: skill_name is required\"", content);
    }
}
