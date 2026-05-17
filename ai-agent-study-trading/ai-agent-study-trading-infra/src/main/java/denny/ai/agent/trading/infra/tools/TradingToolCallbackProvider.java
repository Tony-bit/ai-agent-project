package denny.ai.agent.trading.infra.tools;

import denny.ai.agent.trading.api.provider.IStockDataProvider;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

/**
 * Trading 领域 ToolCallback 配置提供者。
 * <p>
 * 将 {@link denny.ai.agent.trading.api.provider.IStockDataProvider} 的 6 个方法逐个包装为独立的 {@link ToolCallback} Bean，
 * 注入到 ChatClient.defaultToolCallbacks() 中，供 Agent 通过 Function Calling 调用。
 */
@Slf4j
@Configuration
public class TradingToolCallbackProvider {

    @Bean
    public TradingToolCallbacks tradingToolCallbacks(IStockDataProvider stockDataProvider) {
        return new TradingToolCallbacks(stockDataProvider);
    }

    @Bean
    public ToolCallback stockInfoCallback(TradingToolCallbacks callbacks) {
        return callbacks.getStockInfoCallback();
    }

    @Bean
    public ToolCallback historicalBarsCallback(TradingToolCallbacks callbacks) {
        return callbacks.getHistoricalBarsCallback();
    }

    @Bean
    public ToolCallback technicalIndicatorsCallback(TradingToolCallbacks callbacks) {
        return callbacks.getTechnicalIndicatorsCallback();
    }

    @Bean
    public ToolCallback fundamentalDataCallback(TradingToolCallbacks callbacks) {
        return callbacks.getFundamentalDataCallback();
    }

    @Bean
    public ToolCallback sentimentCallback(TradingToolCallbacks callbacks) {
        return callbacks.getSentimentCallback();
    }

    @Bean
    public ToolCallback stockNewsCallback(TradingToolCallbacks callbacks) {
        return callbacks.getStockNewsCallback();
    }

    @Bean
    public ToolCallback searchStockByNameCallback(TradingToolCallbacks callbacks) {
        return callbacks.searchStockByNameCallback();
    }

    @Bean
    public List<ToolCallback> tradingToolCallbackList(
            ToolCallback stockInfoCallback,
            ToolCallback historicalBarsCallback,
            ToolCallback technicalIndicatorsCallback,
            ToolCallback fundamentalDataCallback,
            ToolCallback sentimentCallback,
            ToolCallback stockNewsCallback,
            ToolCallback searchStockByNameCallback) {
        return List.of(
                stockInfoCallback,
                historicalBarsCallback,
                technicalIndicatorsCallback,
                fundamentalDataCallback,
                sentimentCallback,
                stockNewsCallback,
                searchStockByNameCallback
        );
    }
}
