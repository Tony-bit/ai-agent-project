package denny.ai.agent.infrastructure.tools;

import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 情景记忆搜索 Tool 注册配置
 * <p>
 * 将 search_episodic_memory Tool 注册为 Bean，注入到 ChatClient。
 * </p>
 *
 * @author denny
 */
@Slf4j
@Configuration
public class EpisodicMemoryToolCallbackProvider {

    @Resource
    private EpisodicMemoryToolCallbacks episodicMemoryToolCallbacks;

    @Bean
    public ToolCallback searchEpisodicMemoryCallback() {
        return episodicMemoryToolCallbacks.searchEpisodicMemoryCallback();
    }
}
