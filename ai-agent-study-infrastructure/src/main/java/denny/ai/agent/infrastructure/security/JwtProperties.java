package denny.ai.agent.infrastructure.security;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Data
@ConfigurationProperties(prefix = "auth.jwt")
public class JwtProperties {

    private String secret = "dev-only-change-me";
    private long expiresInSeconds = 86400;
}
