package denny.ai.agent.domain.service.auto.step.routing;

import org.junit.Test;
import org.springframework.boot.context.properties.bind.BindException;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

public class IntentRoutingPropertiesTest {

    @Test
    public void should_route_to_unified_when_mode_is_not_configured() {
        IntentRoutingProperties properties = new IntentRoutingProperties();

        assertEquals(IntentRoutingMode.UNIFIED, properties.getMode());
    }

    @Test
    public void should_bind_split_mode_when_configured() {
        IntentRoutingProperties properties = bind(Map.of("intent.routing.mode", "split"));

        assertEquals(IntentRoutingMode.SPLIT, properties.getMode());
    }

    @Test
    public void should_bind_fewshot_and_debug_settings() {
        IntentRoutingProperties properties = bind(Map.of(
                "intent.routing.fewshot.top-k", "3",
                "intent.routing.fewshot.similarity-threshold", "0.72",
                "intent.routing.debug.enabled", "true",
                "intent.routing.debug.include-query", "true",
                "intent.routing.debug.include-results", "true",
                "intent.routing.debug.include-final-prompt", "true",
                "intent.routing.debug.include-model-response", "true",
                "intent.routing.debug.max-content-length", "4"));

        assertEquals(3, properties.getFewshot().getTopK());
        assertEquals(0.72d, properties.getFewshot().getSimilarityThreshold(), 0.0001d);
        assertEquals(true, properties.getDebug().isEnabled());
        assertEquals(true, properties.getDebug().isIncludeQuery());
        assertEquals(true, properties.getDebug().isIncludeResults());
        assertEquals(true, properties.getDebug().isIncludeFinalPrompt());
        assertEquals(true, properties.getDebug().isIncludeModelResponse());
        assertEquals("abcd... [truncated, originalLength=6]", properties.getDebug().truncate("abcdef"));
    }

    @Test
    public void should_reject_invalid_fewshot_settings() {
        assertThrows(BindException.class,
                () -> bind(Map.of("intent.routing.fewshot.top-k", "0")));
        assertThrows(BindException.class,
                () -> bind(Map.of("intent.routing.fewshot.similarity-threshold", "1.1")));
    }

    @Test
    public void should_fail_application_binding_when_routing_mode_is_invalid() {
        Binder binder = new Binder(new MapConfigurationPropertySource(
                Map.of("intent.routing.mode", "unknown")));

        assertThrows(BindException.class,
                () -> binder.bind("intent.routing", IntentRoutingProperties.class).get());
    }

    private IntentRoutingProperties bind(Map<String, String> source) {
        return new Binder(new MapConfigurationPropertySource(source))
                .bind("intent.routing", IntentRoutingProperties.class)
                .get();
    }
}
