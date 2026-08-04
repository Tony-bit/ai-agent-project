package denny.ai.agent.test.config;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertEquals;

class TradingClientRemovalMigrationTest {

    @Test
    void shouldRemoveOnlyTypedTradingClient6001References() throws IOException {
        String sql = migrationSql();

        int flowDelete = sql.indexOf("DELETE FROM ai_agent_flow_config");
        int relationDelete = sql.indexOf("DELETE FROM ai_client_config");
        int clientDelete = sql.indexOf("DELETE FROM ai_client\nWHERE client_id");
        assertTrue(flowDelete >= 0 && flowDelete < relationDelete && relationDelete < clientDelete);
        assertTrue(sql.contains("client_id = '6001'"));
        assertTrue(sql.contains("source_type = 'client' AND source_id = '6001'"));
        assertTrue(sql.contains("target_type = 'client' AND target_id = '6001'"));
        assertFalse(sql.contains("DELETE FROM ai_client_system_prompt"));
        assertFalse(sql.contains("source_id = '6001' OR target_id = '6001'"));
    }

    @Test
    void migrationShouldRemainIdempotentAndPreservePrompt6001() throws IOException {
        String sql = migrationSql();

        assertFalse(sql.toUpperCase().contains("INSERT "));
        assertFalse(sql.toUpperCase().contains("UPDATE "));
        assertFalse(sql.contains("prompt_id = '6001'"));
        assertFalse(sql.contains("source_id = '3001'"));
    }

    @Test
    void removedTradingIntentClassesShouldNotBeLoadable() {
        assertThrows(ClassNotFoundException.class, () -> Class.forName(
                "denny.ai.agent.trading.domain.node.IntentRoutingNode"));
        assertThrows(ClassNotFoundException.class, () -> Class.forName(
                "denny.ai.agent.trading.domain.service.TradingIntentRoutingService"));
        assertThrows(ClassNotFoundException.class, () -> Class.forName(
                "denny.ai.agent.trading.domain.prompt.IntentRoutingPrompt"));
    }

    @Test
    void upgradedAndFreshDatabaseModelsShouldReachSameFinalState() {
        MigrationModel upgraded = legacyModel();
        MigrationModel fresh = legacyModel();

        upgraded.applyV2030();
        upgraded.applyV2030();
        fresh.applyV2030();

        assertEquals(fresh, upgraded);
        assertFalse(upgraded.clients.contains("6001"));
        assertFalse(upgraded.flows.contains("6001"));
        assertTrue(upgraded.prompts.contains("6001"));
        assertTrue(upgraded.relations.contains(new Relation("client", "3001", "prompt", "6001")));
        assertTrue(upgraded.clients.contains("3201"));
        for (int clientId = 6002; clientId <= 6013; clientId++) {
            assertTrue(upgraded.clients.contains(String.valueOf(clientId)));
        }
    }

    private String migrationSql() throws IOException {
        return new ClassPathResource(
                "db/migration/V2030__remove_trading_intent_client_6001.sql")
                .getContentAsString(StandardCharsets.UTF_8);
    }

    private MigrationModel legacyModel() {
        Set<String> clients = new HashSet<>(Set.of("3201", "6001"));
        for (int clientId = 6002; clientId <= 6013; clientId++) {
            clients.add(String.valueOf(clientId));
        }
        return new MigrationModel(
                clients,
                new HashSet<>(Set.of("3201", "6001", "6002")),
                new HashSet<>(Set.of("6001", "6002")),
                new HashSet<>(Set.of(
                        new Relation("client", "6001", "model", "1001"),
                        new Relation("model", "1001", "client", "6001"),
                        new Relation("client", "3001", "prompt", "6001"),
                        new Relation("client", "6002", "prompt", "6002"))));
    }

    private record Relation(String sourceType, String sourceId, String targetType, String targetId) {
    }

    private record MigrationModel(Set<String> clients, Set<String> flows,
                                  Set<String> prompts, Set<Relation> relations) {
        private void applyV2030() {
            flows.remove("6001");
            relations.removeIf(relation ->
                    ("client".equals(relation.sourceType()) && "6001".equals(relation.sourceId()))
                            || ("client".equals(relation.targetType()) && "6001".equals(relation.targetId())));
            clients.remove("6001");
        }
    }
}
