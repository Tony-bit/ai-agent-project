package denny.ai.agent.domain.service.observability;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "langfuse")
public class LangfuseProperties {

    private boolean enabled = false;

    private String host;

    private String publicKey;

    private String secretKey;

    private int timeoutMs = 3000;
}
