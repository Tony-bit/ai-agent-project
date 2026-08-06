package denny.ai.agent.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.core.env.MutablePropertySources;
import org.springframework.core.env.PropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class StockNameIndexPropertiesBindingTest {

    @Test
    void shouldBindStockNameIndexPropertiesFromApplicationYaml() throws IOException {
        StandardEnvironment environment = new StandardEnvironment();
        MutablePropertySources propertySources = environment.getPropertySources();
        List<PropertySource<?>> yamlSources = new YamlPropertySourceLoader()
                .load("application", new ClassPathResource("application.yml"));
        yamlSources.forEach(source -> propertySources.addFirst(source));

        StockNameIndexProperties properties = Binder.get(environment)
                .bind("spring.ai.trading.stock-name-index", StockNameIndexProperties.class)
                .orElseThrow(() -> new IllegalStateException("stock-name-index config is missing"));

        assertEquals("0 30 3 * * ?", properties.getRefreshCron());
        assertEquals("Asia/Shanghai", properties.getRefreshZone());
        assertEquals(Duration.ofDays(7), properties.getMaxAge());
        assertEquals(10, properties.getMaxCandidates());
    }
}
