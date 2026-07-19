package denny.ai.agent.domain.service.auto.step.routing;

import denny.ai.agent.domain.model.valobj.MultiIntentRoutingResult;
import denny.ai.agent.domain.service.auto.step.routing.model.IntentRoutingEvalCase;
import denny.ai.agent.domain.service.auto.step.routing.support.IntentRoutingEvalCaseLoader;
import org.junit.Test;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
                "GENERAL_CHAT", "FINANCIAL_GENERAL", "STOCK_ANALYSIS", "PE_REASONING",
                "PE_CALCULATION", "PE_RETRIEVAL", "INSPECTION"
        )));
        assertTrue(cases.stream().anyMatch(aCase ->
                "intent-clarification-analysis-depth-001".equals(aCase.getCaseId())
                        && Boolean.TRUE.equals(aCase.getExpected().getNeedsClarification())
                        && aCase.getExpected().getMissingInfo().contains("analysisDepth")));
        assertTrue(cases.stream().anyMatch(aCase ->
                "intent-multi-financial-investment-001".equals(aCase.getCaseId())
                        && aCase.getExpected().getTaskIntents().equals(
                        List.of("FINANCIAL_GENERAL", "STOCK_ANALYSIS"))));
    }

    @Test
    public void shouldReportPerfectFinancialPrecisionAndRecallForStaticParserFixtures() {
        IntentRoutingService routingService = new IntentRoutingService();
        Map<String, int[]> counts = new LinkedHashMap<>();
        counts.put("FINANCIAL_GENERAL", new int[3]);
        counts.put("STOCK_ANALYSIS", new int[3]);

        for (IntentRoutingEvalCase aCase : loader.getRunnableCases()) {
            List<String> expectedIntents = aCase.getExpected().getTaskIntents();
            if (expectedIntents.size() != 1) {
                continue;
            }
            MultiIntentRoutingResult parsed = routingService.parseUnifiedResponse(aCase.getResponse());
            String expected = expectedIntents.get(0);
            String actual = parsed.getTaskList().size() == 1
                    ? parsed.getTaskList().get(0).getIntent().getCode()
                    : null;
            counts.forEach((target, values) -> {
                if (target.equals(expected) && target.equals(actual)) {
                    values[0]++;
                } else if (!target.equals(expected) && target.equals(actual)) {
                    values[1]++;
                } else if (target.equals(expected)) {
                    values[2]++;
                }
            });
        }

        counts.forEach((intent, values) -> {
            assertTrue(intent + " fixture coverage must be non-empty", values[0] + values[2] > 0);
            double precision = rate(values[0], values[0] + values[1]);
            double recall = rate(values[0], values[0] + values[2]);
            assertEquals(intent + " static parser fixture precision", 1.0, precision, 0.0);
            assertEquals(intent + " static parser fixture recall", 1.0, recall, 0.0);
        });
    }

    private double rate(int numerator, int denominator) {
        return denominator == 0 ? 0 : (double) numerator / denominator;
    }
}
