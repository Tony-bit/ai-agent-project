package denny.ai.agent.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 会话记忆配置
 *
 * @author denny
 */
@Data
@Component
@ConfigurationProperties(prefix = "chat.memory")
public class ChatMemoryProperties {

    /**
     * 是否启用持久化
     */
    private boolean enabled = true;

    /**
     * Redis 缓存 TTL（小时）
     */
    private int redisTtlHours = 24;

    /**
     * Redis 最大缓存消息条数
     */
    private int maxCacheSize = 20;
}
