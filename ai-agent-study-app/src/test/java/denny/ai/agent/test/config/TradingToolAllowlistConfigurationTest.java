package denny.ai.agent.test.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.MutablePropertySources;
import org.springframework.core.env.PropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class TradingToolAllowlistConfigurationTest {

    private static final Set<String> ANALYSIS_TOOLS = Set.of(
            "get_stock_info",
            "get_historical_bars",
            "get_technical_indicators",
            "get_fundamental_data",
            "get_sentiment",
            "get_stock_news",
            "search_stock_by_name");

    @Test
    void shouldBindExactTradingToolAllowlistFromApplicationYaml() throws IOException {
        StandardEnvironment environment = new StandardEnvironment();
        MutablePropertySources propertySources = environment.getPropertySources();
        List<PropertySource<?>> yamlSources = new YamlPropertySourceLoader()
                .load("application", new ClassPathResource("application.yml"));
        yamlSources.forEach(source -> propertySources.addFirst(source));

        Map<String, String[]> configured = Binder.get(environment)
                .bind("spring.ai.trading.tools.allowed-by-client",
                        Bindable.mapOf(String.class, String[].class))
                .orElseThrow(() -> new IllegalStateException("Trading Tool allowlist is missing"));

        assertEquals(Set.of("read_skill", "search_stock_by_name"), asSet(configured.get("3201")));
        assertFalse(configured.containsKey("6001"));
        assertFalse(Arrays.asList(environment.getProperty(
                "spring.ai.agent.auto-config.client-ids", "").split(","))
                .contains("6001"));
        for (int clientId = 6002; clientId <= 6013; clientId++) {
            assertEquals(ANALYSIS_TOOLS, asSet(configured.get(String.valueOf(clientId))));
        }
    }

    private Set<String> asSet(String[] values) {
        return Set.copyOf(Arrays.asList(values));
    }
}
