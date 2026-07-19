package denny.ai.agent.trading.domain.config;

import denny.ai.agent.domain.service.armory.AiStreamingProperties;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.stereotype.Component;

@Component
public class TradingTimeoutPropertiesValidator implements InitializingBean {

    private final TradingAgentProperties tradingProperties;
    private final AiStreamingProperties streamingProperties;

    public TradingTimeoutPropertiesValidator(TradingAgentProperties tradingProperties,
                                             AiStreamingProperties streamingProperties) {
        this.tradingProperties = tradingProperties;
        this.streamingProperties = streamingProperties;
    }

    @Override
    public void afterPropertiesSet() {
        tradingProperties.validateAgainstModelTimeout(
                streamingProperties.resolve(null).totalTimeout());
    }
}
