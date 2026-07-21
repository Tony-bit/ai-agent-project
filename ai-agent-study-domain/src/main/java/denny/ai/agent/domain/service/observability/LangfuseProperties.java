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

    /**
     * 事件缓冲队列容量，达到此数量时强制 flush
     */
    private int queueCapacity = 500;

    /**
     * 定时 flush 间隔（毫秒），默认 2 秒
     */
    private long flushIntervalMs = 2000;

    /**
     * 单次批量 flush 的最大事件数
     */
    private int maxBatchSize = 50;

    /** Number of retries after the initial delivery attempt. */
    private int maxRetries = 2;

    /** Initial retry backoff in milliseconds. */
    private long retryBackoffMs = 200;

    /** Upper bound for exponential retry backoff in milliseconds. */
    private long maxRetryBackoffMs = 2000;

    /** Best-effort shutdown drain budget in milliseconds. */
    private long shutdownDrainTimeoutMs = 3000;
}
