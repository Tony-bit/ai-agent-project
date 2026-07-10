package denny.ai.agent.domain.service.auto.step.routing;

import denny.ai.agent.domain.service.auto.step.routing.model.IntentRoutingEvalCase;
import denny.ai.agent.domain.service.auto.step.routing.support.IntentRoutingEvalCaseLoader;
import org.junit.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Guards the eval dataset itself against accidental structural or coverage regressions.
 */
public class IntentRoutingEvalDatasetTest {

    private final IntentRoutingEvalCaseLoader loader = new IntentRoutingEvalCaseLoader();

    @Test
    public void shouldLoadNonEmptyDatasetWithUniqueCaseIds() {
        List<IntentRoutingEvalCase> cases = loader.getAll();

        assertFalse("评测数据集不能为空", cases.isEmpty());
        Set<String> caseIds = cases.stream()
                .map(IntentRoutingEvalCase::getCaseId)
                .collect(Collectors.toSet());
        assertEquals("caseId 必须唯一", cases.size(), caseIds.size());
    }

    @Test
    public void shouldKeepRequiredCategoryAndIntentCoverage() {
        List<IntentRoutingEvalCase> cases = loader.getAll();
        Set<String> categories = cases.stream()
                .map(IntentRoutingEvalCase::getCategory)
                .collect(Collectors.toSet());
        Set<String> intents = cases.stream()
                .flatMap(aCase -> aCase.getExpected().getTaskIntents().stream())
                .collect(Collectors.toCollection(HashSet::new));

        assertTrue(categories.containsAll(Set.of(
                "single-task", "multi-task", "clarification", "fallback"
        )));
        assertTrue(intents.containsAll(Set.of(
                "GENERAL_CHAT", "STOCK_ANALYSIS", "PE_REASONING",
                "PE_CALCULATION", "PE_RETRIEVAL", "INSPECTION"
        )));
    }
}
