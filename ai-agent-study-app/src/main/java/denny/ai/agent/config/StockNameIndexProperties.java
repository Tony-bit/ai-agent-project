package denny.ai.agent.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * Configuration for the in-memory stock name index lifecycle.
 */
@Data
@ConfigurationProperties(prefix = "spring.ai.trading.stock-name-index")
public class StockNameIndexProperties {

    private String refreshCron = "0 30 3 * * ?";

    private String refreshZone = "Asia/Shanghai";

    private Duration maxAge = Duration.ofDays(7);

    private int maxCandidates = 10;
}
