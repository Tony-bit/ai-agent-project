package denny.ai.agent.trading.infra.provider;

import denny.ai.agent.trading.api.provider.IStockDataProvider;
import denny.ai.agent.trading.infra.calculator.TechnicalIndicatorCalculator;
import denny.ai.agent.trading.infra.config.TradingDataSourceProperties;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.stereotype.Service;

/**
 * 数据 Provider 工厂类，根据配置创建对应的 {@link IStockDataProvider} 实例。
 * <p>
 * 策略模式实现，支持运行时切换数据源。
 */
@Service
public class ProviderFactory {

    private final TradingDataSourceProperties properties;
    private final TechnicalIndicatorCalculator indicatorCalculator;

    public ProviderFactory(TradingDataSourceProperties properties,
                           @Autowired(required = false) TechnicalIndicatorCalculator indicatorCalculator) {
        this.properties = properties;
        this.indicatorCalculator = indicatorCalculator != null
                ? indicatorCalculator
                : new TechnicalIndicatorCalculator();
    }

    /**
     * 获取当前配置的数据 Provider 实例。
     *
     * @return IStockDataProvider 实现实例
     */
    @ConditionalOnMissingBean(IStockDataProvider.class)
    @Bean
    public IStockDataProvider stockDataProvider() {
        return getProvider();
    }

    private IStockDataProvider getProvider() {
        String provider = properties.getProvider();
        if ("tushare".equalsIgnoreCase(provider)) {
            return createTushareProvider();
        }
        return createMockProvider();
    }

    // ======== 新增：新浪新闻 Provider ========
    @ConditionalOnMissingBean(INewsSearchProvider.class)
    @Bean
    public INewsSearchProvider sinaNewsSearchProvider() {
        return new SinaNewsDataProvider();
    }

    private IStockDataProvider createMockProvider() {
        return new MockStockDataProvider();
    }

    private IStockDataProvider createTushareProvider() {
        String token = properties.getTushareToken();
        if (token == null || token.isBlank()) {
            throw new IllegalStateException(
                    "Tushare Token 未配置，请设置 spring.ai.trading.data-source.tushare-token");
        }
        TushareApiClient apiClient = new TushareApiClient(token);
        INewsSearchProvider newsSearchProvider = new SinaNewsDataProvider();
        return new TushareStockDataProvider(apiClient, indicatorCalculator, newsSearchProvider);
    }
}
