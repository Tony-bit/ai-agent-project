package denny.ai.agent.trading.domain.prompt;

import org.junit.jupiter.api.Test;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TradingPromptActivationServiceTest {

    @Test
    void activatesOnlyAfterCompleteSetValidation() {
        FakeRepository repository = new FakeRepository(validRecords(2));

        new TradingPromptActivationService(repository, new TradingPromptRenderer()).activateCompleteVersion(2);

        assertTrue(repository.deactivated);
        assertTrue(repository.activated);
        assertEquals(2, repository.requestedVersion);
    }

    @Test
    void rejectsIncompleteOrInvalidSetBeforeAnyUpdate() {
        List<TradingPromptRecord> incomplete = new ArrayList<>(validRecords(2));
        incomplete.remove(0);
        FakeRepository missingRepository = new FakeRepository(incomplete);

        assertThrows(IllegalStateException.class,
                () -> new TradingPromptActivationService(missingRepository, new TradingPromptRenderer())
                        .activateCompleteVersion(2));
        assertFalse(missingRepository.deactivated);

        List<TradingPromptRecord> invalid = new ArrayList<>(validRecords(2));
        TradingPromptRecord first = invalid.get(0);
        invalid.set(0, new TradingPromptRecord(first.id(), first.promptId(), 2, 2,
                "{{targetContext}} missing output contract", false));
        FakeRepository invalidRepository = new FakeRepository(invalid);

        assertThrows(IllegalStateException.class,
                () -> new TradingPromptActivationService(invalidRepository, new TradingPromptRenderer())
                        .activateCompleteVersion(2));
        assertFalse(invalidRepository.deactivated);
    }

    @Test
    void activationBoundaryIsTransactionalAndCountMismatchFails() throws Exception {
        Transactional transactional = TradingPromptActivationService.class
                .getMethod("activateCompleteVersion", int.class)
                .getAnnotation(Transactional.class);
        assertEquals(Exception.class, transactional.rollbackFor()[0]);

        FakeRepository repository = new FakeRepository(validRecords(2));
        repository.activatedCount = 11;
        assertThrows(IllegalStateException.class,
                () -> new TradingPromptActivationService(repository, new TradingPromptRenderer())
                        .activateCompleteVersion(2));
    }

    private List<TradingPromptRecord> validRecords(int version) {
        return TradingPromptSet.REQUIRED_PROMPT_IDS.stream()
                .map(id -> new TradingPromptRecord(Long.valueOf(id), id, 2, version,
                        validTemplate(id), false))
                .toList();
    }

    private String validTemplate(String promptId) {
        return new TradingPromptRenderer().requiredPlaceholders(promptId).stream()
                .map(name -> "{{" + name + "}}")
                .collect(java.util.stream.Collectors.joining("\n"));
    }

    private static class FakeRepository implements TradingPromptRepository {
        private final List<TradingPromptRecord> records;
        private boolean deactivated;
        private boolean activated;
        private int requestedVersion;
        private int activatedCount = 12;

        private FakeRepository(List<TradingPromptRecord> records) {
            this.records = records;
        }

        @Override
        public List<TradingPromptRecord> findVersionSet(Set<String> promptIds, int promptType, int version) {
            requestedVersion = version;
            return records;
        }

        @Override
        public List<TradingPromptRecord> findActiveSet(Set<String> promptIds, int promptType) {
            return records;
        }

        @Override
        public void deactivateAll(Set<String> promptIds, int promptType) {
            deactivated = true;
        }

        @Override
        public int activateVersion(Set<String> promptIds, int promptType, int version) {
            activated = true;
            return activatedCount;
        }
    }
}
