package denny.ai.agent.trading.migration;

import denny.ai.agent.trading.domain.prompt.TradingPromptRenderer;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TradingPromptV2MigrationTest {

    @Test
    void containsCompleteInactiveTargetLockedV2Set() throws IOException {
        String resource = "db/migration/V2029__trading_prompt_v2_templates.sql";
        String sql;
        try (var stream = Thread.currentThread().getContextClassLoader().getResourceAsStream(resource)) {
            assertNotNull(stream);
            sql = new String(stream.readAllBytes(), StandardCharsets.UTF_8)
                    .replace("\r\n", "\n")
                    .replace('\r', '\n');
        }

        int records = 0;
        for (int promptId = 6002; promptId <= 6013; promptId++) {
            assertTrue(sql.contains("('" + promptId + "',"), "missing promptId=" + promptId);
            records++;
        }
        assertEquals(12, records);
        assertEquals(12, occurrences(sql, "{{targetContext}}"));
        assertEquals(12, occurrences(sql, "{{outputContract}}"));
        assertEquals(12, occurrences(sql, ", 0, 2, 2,"));

        TradingPromptRenderer renderer = new TradingPromptRenderer();
        for (int promptId = 6002; promptId <= 6013; promptId++) {
            renderer.validateTemplate(String.valueOf(promptId), extractContent(sql, String.valueOf(promptId)));
        }
    }

    private int occurrences(String text, String token) {
        int count = 0;
        int offset = 0;
        while ((offset = text.indexOf(token, offset)) >= 0) {
            count++;
            offset += token.length();
        }
        return count;
    }

    private String extractContent(String sql, String promptId) {
        int tupleStart = sql.indexOf("('" + promptId + "', '");
        int contentStartDelimiter = sql.indexOf("',\n'", tupleStart);
        int contentStart = contentStartDelimiter + 4;
        int contentEnd = sql.indexOf("',\n'Target-locked", contentStart);
        assertTrue(tupleStart >= 0 && contentStartDelimiter >= 0 && contentEnd >= 0);
        return sql.substring(contentStart, contentEnd).replace("''", "'");
    }
}
