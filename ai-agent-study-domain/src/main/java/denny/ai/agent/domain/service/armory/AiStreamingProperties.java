package denny.ai.agent.domain.service.armory;

import denny.ai.agent.domain.model.valobj.AiClientModelVO.StreamingTimeoutConfig;
import lombok.Data;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@Data
@ConfigurationProperties(prefix = "ai.client.streaming")
public class AiStreamingProperties implements InitializingBean {

    private Duration connectTimeout = Duration.ofSeconds(10);
    private Duration firstContentTimeout = Duration.ofSeconds(45);
    private Duration idleTimeout = Duration.ofSeconds(30);
    private Duration totalTimeout = Duration.ofSeconds(150);

    @Override
    public void afterPropertiesSet() {
        validate();
    }

    public void validate() {
        requirePositive(connectTimeout, "ai.client.streaming.connect-timeout");
        requirePositive(firstContentTimeout, "ai.client.streaming.first-content-timeout");
        requirePositive(idleTimeout, "ai.client.streaming.idle-timeout");
        requirePositive(totalTimeout, "ai.client.streaming.total-timeout");
    }

    public StreamingTimeouts resolve(StreamingTimeoutConfig override) {
        validate();
        Duration effectiveFirstContent = durationOrDefault(
                override == null ? null : override.getFirstContentTimeoutMs(),
                firstContentTimeout, "streamingTimeout.firstContentTimeoutMs");
        Duration effectiveIdle = durationOrDefault(
                override == null ? null : override.getIdleTimeoutMs(),
                idleTimeout, "streamingTimeout.idleTimeoutMs");
        Duration effectiveTotal = durationOrDefault(
                override == null ? null : override.getTotalTimeoutMs(),
                totalTimeout, "streamingTimeout.totalTimeoutMs");
        return new StreamingTimeouts(connectTimeout, effectiveFirstContent, effectiveIdle, effectiveTotal);
    }

    private Duration durationOrDefault(Long millis, Duration defaultValue, String property) {
        if (millis == null) {
            return defaultValue;
        }
        if (millis <= 0) {
            throw new IllegalArgumentException(property + " must be positive");
        }
        return Duration.ofMillis(millis);
    }

    private void requirePositive(Duration value, String property) {
        if (value == null || value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException(property + " must be positive");
        }
    }

    public record StreamingTimeouts(Duration connectTimeout,
                                    Duration firstContentTimeout,
                                    Duration idleTimeout,
                                    Duration totalTimeout) {
    }
}
