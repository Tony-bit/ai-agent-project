package denny.ai.agent.domain.model.valobj;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 跨会话长期记忆配置属性
 * <p>
 * 在 domain 层定义配置接口，在 app 层通过 @Bean 注册实例，
 * 避免 domain 层直接依赖 app 层的配置类。
 * </p>
 *
 * @author denny
 */
@Data
@Component
@ConfigurationProperties(prefix = "chat.memory")
public class CrossSessionMemoryProperties {

    /**
     * 是否在会话初始化时注入跨会话记忆
     */
    private boolean injectCrossSessionMemory = true;

    /**
     * 跨会话记忆默认查询条数
     */
    private int crossSessionMemoryTopK = 5;
}
