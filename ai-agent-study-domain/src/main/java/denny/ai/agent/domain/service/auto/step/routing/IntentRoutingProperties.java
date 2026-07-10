package denny.ai.agent.domain.service.auto.step.routing;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "intent.routing")
public class IntentRoutingProperties {
    private IntentRoutingMode mode = IntentRoutingMode.UNIFIED;
}
