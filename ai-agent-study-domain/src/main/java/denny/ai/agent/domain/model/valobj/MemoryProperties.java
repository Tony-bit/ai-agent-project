package denny.ai.agent.domain.model.valobj;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 记忆配置属性
 * <p>
 * 统一管理所有 memory 相关配置：
 * - persona: 用户画像（跨会话长期记忆）
 * - episodic: 情景记忆（按需搜索）
 * </p>
 *
 * @author denny
 */
@Data
@Component
@ConfigurationProperties(prefix = "ai.memory")
public class MemoryProperties {

    /**
     * 是否在会话初始化时注入用户画像
     */
    private boolean injectPersona = true;

    /**
     * 用户画像默认查询条数
     */
    private int personaTopK = 5;

    /**
     * 用户画像 Redis 缓存 TTL（分钟），默认 5 分钟
     */
    private int personaTtlMinutes = 5;

    /**
     * 情景记忆搜索结果上限
     */
    private int episodicMemoryLimit = 5;
}
