package denny.ai.agent.config;

import denny.ai.agent.domain.service.auto.step.routing.AnalysisDepthFollowUpResolver;
import denny.ai.agent.domain.service.auto.step.routing.StockRequestResolver;
import denny.ai.agent.domain.service.stock.StockNameMetrics;
import denny.ai.agent.domain.service.stock.StockNameRefreshService;
import denny.ai.agent.domain.service.stock.StockNameResolutionService;
import denny.ai.agent.domain.service.stock.StockResolutionPendingRepository;
import denny.ai.agent.trading.infra.config.TradingDataSourceProperties;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.mock;

class StockNameIndexConfigurationTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(StockNameIndexConfiguration.class, StockNameIndexRefreshJob.class)
            .withBean(TradingDataSourceProperties.class, () -> {
                TradingDataSourceProperties properties = new TradingDataSourceProperties();
                properties.setTushareToken("test-token");
                return properties;
            })
            .withBean(SimpleMeterRegistry.class, SimpleMeterRegistry::new)
            .withBean(StockResolutionPendingRepository.class,
                    () -> mock(StockResolutionPendingRepository.class))
            .withBean(AnalysisDepthFollowUpResolver.class, AnalysisDepthFollowUpResolver::new)
            .withPropertyValues(
                    "spring.ai.trading.stock-name-index.refresh-cron=0 30 3 * * ?",
                    "spring.ai.trading.stock-name-index.refresh-zone=Asia/Shanghai",
                    "spring.ai.trading.stock-name-index.max-age=7d",
                    "spring.ai.trading.stock-name-index.max-candidates=10");

    @Test
    void shouldWireStockNameBeansWithoutCircularDependencies() {
        contextRunner.run(context -> {
            assertNotNull(context.getBean(StockNameMetrics.class));
            assertNotNull(context.getBean(StockNameRefreshService.class));
            assertNotNull(context.getBean(StockNameResolutionService.class));
            assertNotNull(context.getBean(StockRequestResolver.class));
            assertNotNull(context.getBean(StockNameIndexRefreshJob.class));
        });
    }
}
