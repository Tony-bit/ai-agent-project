package denny.ai.agent.trading.domain.prompt;

import denny.ai.agent.trading.api.vo.TargetContext;
import denny.ai.agent.trading.domain.support.TradingPromptFixtures;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class TradingPromptV3IntegrationTest {

    @Test
    void explicitV2AndV3FixturesMatchAllTwelveContracts() {
        TradingPromptRenderer renderer = new TradingPromptRenderer();
        assertEquals(12, TradingPromptFixtures.STRICT_V2.size());
        assertEquals(12, TradingPromptFixtures.RELAXED_V3.size());
        TradingPromptFixtures.STRICT_V2.forEach((id, template) ->
                renderer.validateTemplate(PromptContractMode.STRICT_V2, id, template));
        TradingPromptFixtures.RELAXED_V3.forEach((id, template) ->
                renderer.validateTemplate(PromptContractMode.RELAXED_V3, id, template));
        for (String id : List.of("6002", "6003", "6004", "6005", "6006", "6007",
                "6010", "6011", "6012")) {
            assertFalse(TradingPromptFixtures.RELAXED_V3.get(id).contains("Contract"));
        }
    }

    @Test
    void v3SnapshotFreezesAndPromptRollbackReactivatesCompleteV2Set() {
        SwitchingRepository repository = new SwitchingRepository();
        TradingPromptSnapshot snapshot = new TradingPromptSnapshotFactory(repository,
                new TradingPromptRenderer()).create(target());
        assertEquals(PromptContractMode.RELAXED_V3, snapshot.mode());
        assertEquals(3, snapshot.version());

        TradingPromptActivationService activation = new TradingPromptActivationService(
                repository, new TradingPromptRenderer());
        activation.activateCompleteVersion(3);
        activation.activateCompleteVersion(2);

        assertEquals(List.of(3, 2), repository.activatedVersions);
        assertEquals(2, repository.deactivateCount);
    }

    private TargetContext target() {
        return new TargetContext(UUID.randomUUID().toString(), "601318.SH",
                "中国平安", "保险", LocalDate.of(2026, 7, 28));
    }

    private static class SwitchingRepository implements TradingPromptRepository {
        private final List<Integer> activatedVersions = new ArrayList<>();
        private int deactivateCount;

        @Override
        public List<TradingPromptRecord> findVersionSet(Set<String> ids, int type, int version) {
            return TradingPromptFixtures.records(PromptContractMode.fromVersion(version), false);
        }

        @Override
        public List<TradingPromptRecord> findActiveSet(Set<String> ids, int type) {
            return TradingPromptFixtures.records(PromptContractMode.RELAXED_V3, true);
        }

        @Override
        public void deactivateAll(Set<String> ids, int type) {
            deactivateCount++;
        }

        @Override
        public int activateVersion(Set<String> ids, int type, int version) {
            activatedVersions.add(version);
            return 12;
        }
    }
}
