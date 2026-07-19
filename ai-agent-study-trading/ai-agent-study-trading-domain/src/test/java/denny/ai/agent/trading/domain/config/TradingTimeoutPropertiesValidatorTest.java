package denny.ai.agent.trading.domain.config;

import denny.ai.agent.domain.service.armory.AiStreamingProperties;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

class TradingTimeoutPropertiesValidatorTest {

    @Test
    void should_accept_default_timeout_budget() {
        TradingTimeoutPropertiesValidator validator = new TradingTimeoutPropertiesValidator(
                new TradingAgentProperties(), new AiStreamingProperties());

        assertDoesNotThrow(validator::afterPropertiesSet);
    }

    @Test
    void should_reject_node_timeout_not_greater_than_model_timeout() {
        TradingAgentProperties trading = new TradingAgentProperties();
        trading.setNodeTimeout(Duration.ofSeconds(150));
        TradingTimeoutPropertiesValidator validator = new TradingTimeoutPropertiesValidator(
                trading, new AiStreamingProperties());

        assertThrows(IllegalArgumentException.class, validator::afterPropertiesSet);
    }
}
