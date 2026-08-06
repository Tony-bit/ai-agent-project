package denny.ai.agent.config;

import denny.ai.agent.domain.service.auto.step.routing.AnalysisDepthFollowUpResolver;
import denny.ai.agent.domain.service.auto.step.routing.StockRequestResolver;
import denny.ai.agent.domain.service.stock.StockNameIndexHolder;
import denny.ai.agent.domain.service.stock.StockNameMetrics;
import denny.ai.agent.domain.service.stock.StockNameRefreshService;
import denny.ai.agent.domain.service.stock.StockNameResolutionService;
import denny.ai.agent.domain.service.stock.StockNameSource;
import denny.ai.agent.domain.service.stock.StockResolutionPendingRepository;
import io.micrometer.core.instrument.MeterRegistry;
import denny.ai.agent.trading.infra.config.TradingDataSourceProperties;
import denny.ai.agent.trading.infra.provider.TushareApiClient;
import denny.ai.agent.trading.infra.provider.TushareStockNameSource;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

@Configuration
@EnableConfigurationProperties(StockNameIndexProperties.class)
public class StockNameIndexConfiguration {

    @Bean
    public Clock stockNameIndexClock() {
        return Clock.systemDefaultZone();
    }

    @Bean
    public StockNameSource stockNameSource(TradingDataSourceProperties tradingDataSourceProperties) {
        String token = tradingDataSourceProperties.getTushareToken();
        if (token == null || token.isBlank()) {
            throw new IllegalStateException(
                    "Tushare token is required for stock name index refresh");
        }
        return new TushareStockNameSource(new TushareApiClient(token));
    }

    @Bean
    public StockNameIndexHolder stockNameIndexHolder(Clock stockNameIndexClock) {
        return new StockNameIndexHolder(stockNameIndexClock);
    }

    @Bean
    public StockNameMetrics stockNameMetrics(StockNameIndexHolder stockNameIndexHolder,
                                             ObjectProvider<MeterRegistry> meterRegistryProvider) {
        StockNameMetrics metrics = new StockNameMetrics(meterRegistryProvider.getIfAvailable());
        metrics.registerIndexHolder(stockNameIndexHolder);
        return metrics;
    }

    @Bean
    public StockNameRefreshService stockNameRefreshService(StockNameSource stockNameSource,
                                                           StockNameIndexHolder stockNameIndexHolder,
                                                           Clock stockNameIndexClock,
                                                           StockNameIndexProperties stockNameIndexProperties,
                                                           StockNameMetrics stockNameMetrics) {
        return new StockNameRefreshService(
                stockNameSource,
                stockNameIndexHolder,
                stockNameIndexClock,
                stockNameIndexProperties.getMaxAge(),
                stockNameMetrics);
    }

    @Bean
    public StockNameResolutionService stockNameResolutionService(StockNameIndexProperties stockNameIndexProperties,
                                                                 StockNameMetrics stockNameMetrics) {
        return new StockNameResolutionService(stockNameIndexProperties.getMaxCandidates(), stockNameMetrics);
    }

    @Bean
    public StockRequestResolver stockRequestResolver(StockNameIndexHolder stockNameIndexHolder,
                                                     StockNameResolutionService stockNameResolutionService,
                                                     StockResolutionPendingRepository stockResolutionPendingRepository,
                                                     AnalysisDepthFollowUpResolver analysisDepthFollowUpResolver,
                                                     Clock stockNameIndexClock) {
        return new StockRequestResolver(
                stockNameIndexHolder,
                stockNameResolutionService,
                stockResolutionPendingRepository,
                analysisDepthFollowUpResolver,
                stockNameIndexClock);
    }
}
