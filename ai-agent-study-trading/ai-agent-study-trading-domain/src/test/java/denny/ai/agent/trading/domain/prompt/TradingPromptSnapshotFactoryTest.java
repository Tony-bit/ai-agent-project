package denny.ai.agent.trading.domain.prompt;

import denny.ai.agent.trading.api.vo.TargetContext;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TradingPromptSnapshotFactoryTest {

    @Test
    void freezesCompleteActiveSetWithContentHashesInOneQuery() {
        MutableRepository repository = new MutableRepository(activeRecords(1, "v1"));
        TradingPromptSnapshotFactory factory = new TradingPromptSnapshotFactory(
                repository, new TradingPromptRenderer());
        TargetContext target = target();

        TradingPromptSnapshot first = factory.create(target);
        repository.records = activeRecords(2, "v2");
        TradingPromptSnapshot second = factory.create(target);

        assertEquals(1, first.require("6002").version());
        assertTrue(first.require("6002").content().startsWith("v1-6002"));
        assertEquals(64, first.require("6002").contentHash().length());
        assertEquals(2, second.require("6002").version());
        assertNotEquals(first.require("6002").contentHash(), second.require("6002").contentHash());
        assertEquals(2, repository.queryCount);
        assertThrows(UnsupportedOperationException.class,
                () -> first.prompts().put("other", first.require("6002")));
    }

    @Test
    void rejectsMissingAndDuplicateActivePrompts() {
        List<TradingPromptRecord> missing = new ArrayList<>(activeRecords(2, "v2"));
        missing.remove(0);
        assertThrows(IllegalStateException.class,
                () -> new TradingPromptSnapshotFactory(
                        new MutableRepository(missing), new TradingPromptRenderer()).create(target()));

        List<TradingPromptRecord> duplicate = new ArrayList<>(activeRecords(2, "v2"));
        duplicate.set(0, duplicate.get(1));
        assertThrows(IllegalStateException.class,
                () -> new TradingPromptSnapshotFactory(
                        new MutableRepository(duplicate), new TradingPromptRenderer()).create(target()));
    }

    private TargetContext target() {
        return new TargetContext(UUID.randomUUID().toString(), "601318.SH",
                "中国平安", "保险", LocalDate.of(2026, 7, 22));
    }

    private List<TradingPromptRecord> activeRecords(int version, String prefix) {
        return TradingPromptSet.REQUIRED_PROMPT_IDS.stream()
                .map(id -> new TradingPromptRecord(Long.valueOf(id), id, 2, version,
                        prefix + "-" + id + "\n" + validTemplate(id), true))
                .toList();
    }

    private String validTemplate(String promptId) {
        return new TradingPromptRenderer().requiredPlaceholders(promptId).stream()
                .map(name -> "{{" + name + "}}")
                .collect(java.util.stream.Collectors.joining("\n"));
    }

    private static class MutableRepository implements TradingPromptRepository {
        private List<TradingPromptRecord> records;
        private int queryCount;

        private MutableRepository(List<TradingPromptRecord> records) {
            this.records = records;
        }

        @Override
        public List<TradingPromptRecord> findVersionSet(Set<String> promptIds, int promptType, int version) {
            return List.of();
        }

        @Override
        public List<TradingPromptRecord> findActiveSet(Set<String> promptIds, int promptType) {
            queryCount++;
            return records;
        }

        @Override public void deactivateAll(Set<String> promptIds, int promptType) { }
        @Override public int activateVersion(Set<String> promptIds, int promptType, int version) { return 0; }
    }
}
