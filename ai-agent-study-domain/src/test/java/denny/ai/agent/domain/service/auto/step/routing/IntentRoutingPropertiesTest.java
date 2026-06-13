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
