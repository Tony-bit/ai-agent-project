package denny.ai.agent.trading.infra.config;

import com.alibaba.cloud.ai.graph.agent.hook.skills.ReadSkillTool;
import com.alibaba.cloud.ai.graph.agent.hook.skills.SkillsAgentHook;
import com.alibaba.cloud.ai.graph.skills.SpringAiSkillAdvisor;
import com.alibaba.cloud.ai.graph.skills.registry.SkillRegistry;
import com.alibaba.cloud.ai.graph.skills.registry.classpath.ClasspathSkillRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@Slf4j
public class TradingSkillsConfig {

    @Bean
    public SkillRegistry tradingSkillRegistry() {
        return ClasspathSkillRegistry.builder()
                .classpathPath(".claude/skills/trading")
                .autoLoad(true)
                .build();
    }

    @Bean
    public SpringAiSkillAdvisor tradingSkillAdvisor(SkillRegistry tradingSkillRegistry) {
        return SpringAiSkillAdvisor.builder()
                .skillRegistry(tradingSkillRegistry)
                .lazyLoad(true)
                .build();
    }

    @Bean
    public SkillsAgentHook tradingSkillsAgentHook(SkillRegistry tradingSkillRegistry) {
        return SkillsAgentHook.builder()
                .skillRegistry(tradingSkillRegistry)
                .autoReload(true)
                .build();
    }

    @Bean
    public ToolCallback readTradingSkillToolCallback(SkillRegistry tradingSkillRegistry) {
        return ReadSkillTool.createReadSkillToolCallback(tradingSkillRegistry, ReadSkillTool.DESCRIPTION);
    }
}
