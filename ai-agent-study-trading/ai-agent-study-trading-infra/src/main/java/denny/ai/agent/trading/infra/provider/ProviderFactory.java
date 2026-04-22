package denny.ai.agent.trading.infra.provider;

import denny.ai.agent.trading.api.provider.IStockDataProvider;
import denny.ai.agent.trading.infra.config.TradingDataSourceProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.stereotype.Component;

/**
 * 数据 Provider 工厂类，根据配置创建对应的 {@link IStockDataProvider} 实例。
 * <p>
 * 策略模式实现，支持运行时切换数据源。
 */
@Component
public class ProviderFactory {

    private final TradingDataSourceProperties properties;

    public ProviderFactory(TradingDataSourceProperties properties) {
        this.properties = properties;
    }

    /**
     * 获取当前配置的数据 Provider 实例。
     *
     * @return IStockDataProvider 实现实例
     */
    @ConditionalOnMissingBean(IStockDataProvider.class)
    public IStockDataProvider getProvider() {
        String provider = properties.getProvider();
        if ("yahoo-finance".equalsIgnoreCase(provider)) {
            return createYahooFinanceProvider();
        }
        // 默认返回 Mock Provider（Phase 1-5）
        return createMockProvider();
    }

    private IStockDataProvider createMockProvider() {
        return new MockStockDataProvider();
    }

    private IStockDataProvider createYahooFinanceProvider() {
        // Phase 6 实现：return new YahooFinanceStockDataProvider();
        throw new UnsupportedOperationException(
                "YahooFinanceStockDataProvider 尚未实现，请先完成 Phase 6 T6-01 任务");
    }
}
