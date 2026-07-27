package denny.ai.agent.trading.migration;

import denny.ai.agent.trading.domain.prompt.AnalystPromptTemplate;
import denny.ai.agent.trading.domain.prompt.DebatePromptTemplate;
import denny.ai.agent.trading.domain.prompt.PortfolioManagerPromptTemplate;
import denny.ai.agent.trading.domain.prompt.RecommendationPromptTemplate;
import denny.ai.agent.trading.domain.prompt.RiskAnalystPromptTemplate;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TradingPromptV1MigrationTest {

    private static final String MIGRATION =
            "db/migration/V2028__trading_prompt_target_context.sql";

    @Test
    void archivesEveryJavaV1PromptWithoutTextChanges() throws IOException {
        String sql = readMigration();

        Map<String, String> expected = expectedPrompts();
        Map<String, String> archived = extractArchivedPrompts(sql);

        assertEquals(expected.keySet(), archived.keySet());
        expected.forEach((promptId, content) ->
                assertEquals(content, archived.get(promptId),
                        "V1 migration content differs for promptId=" + promptId));
    }

    @Test
    void deduplicatesLegacyVersionsBeforeAddingUniqueConstraint() throws IOException {
        String sql = readMigration();

        int cleanup = sql.indexOf("DELETE duplicate_prompt");
        int uniqueConstraint = sql.indexOf("ADD UNIQUE KEY uk_prompt_id_type_version");
        assertTrue(cleanup >= 0, "migration must clean up legacy duplicate versions");
        assertTrue(cleanup < uniqueConstraint,
                "duplicate versions must be removed before adding the unique constraint");
        assertTrue(sql.contains(
                        "retained_prompt.status = 1 AND duplicate_prompt.status <> 1"),
                "an active legacy version must be retained in preference to an inactive one");
        assertTrue(sql.contains("retained_prompt.id > duplicate_prompt.id"),
                "the newest row must win when duplicate statuses match");
    }

    private String readMigration() throws IOException {
        try (var stream = Thread.currentThread().getContextClassLoader()
                .getResourceAsStream(MIGRATION)) {
            assertNotNull(stream, "migration must be available on the classpath");
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8)
                    .replace("\r\n", "\n");
        }
    }

    private Map<String, String> extractArchivedPrompts(String sql) {
        Map<String, String> prompts = new LinkedHashMap<>();
        for (String promptId : expectedPrompts().keySet()) {
            int tupleStart = sql.indexOf("('" + promptId + "', '");
            if (tupleStart < 0) {
                continue;
            }
            int contentStartDelimiter = sql.indexOf("',\n'", tupleStart);
            if (contentStartDelimiter < 0) {
                continue;
            }
            int contentStart = contentStartDelimiter + 4;
            int contentEnd = sql.indexOf(
                    "',\n'Exact archive of the pre-V2 Java prompt constant'", contentStart);
            if (contentEnd < 0) {
                continue;
            }
            prompts.put(promptId, sql.substring(contentStart, contentEnd).replace("''", "'"));
        }
        return prompts;
    }

    private Map<String, String> expectedPrompts() {
        Map<String, String> prompts = new LinkedHashMap<>();
        prompts.put("6002", AnalystPromptTemplate.FUNDAMENTAL_ANALYST_PROMPT);
        prompts.put("6003", AnalystPromptTemplate.TECHNICAL_ANALYST_PROMPT);
        prompts.put("6004", AnalystPromptTemplate.SENTIMENT_ANALYST_PROMPT);
        prompts.put("6005", AnalystPromptTemplate.NEWS_ANALYST_STRUCTURED_PROMPT);
        prompts.put("6006", DebatePromptTemplate.BULL_RESEARCHER_PROMPT);
        prompts.put("6007", DebatePromptTemplate.BEAR_RESEARCHER_PROMPT);
        prompts.put("6008", DebatePromptTemplate.RESEARCH_MANAGER_PROMPT);
        prompts.put("6009", PortfolioManagerPromptTemplate.PORTFOLIO_MANAGER_PROMPT);
        prompts.put("6010", RiskAnalystPromptTemplate.NEUTRAL_ANALYST_PROMPT);
        prompts.put("6011", RiskAnalystPromptTemplate.CONSERVATIVE_ANALYST_PROMPT);
        prompts.put("6012", RiskAnalystPromptTemplate.AGGRESSIVE_ANALYST_PROMPT);
        prompts.put("6013", RecommendationPromptTemplate.RECOMMENDATION_PROMPT);
        return prompts;
    }
}
