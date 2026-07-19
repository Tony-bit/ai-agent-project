package denny.ai.agent.test.eval.routing;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class IntentRoutingOnlineEvalDatasetTest {

    private final IntentRoutingOnlineEvalCaseLoader loader = new IntentRoutingOnlineEvalCaseLoader();

    @Test
    public void datasetShouldBeValidAndMeetCoverageRequirements() {
        List<IntentRoutingOnlineEvalCase> cases = loader.loadAll();
        List<String> errors = loader.validate(cases);

        assertTrue(errors.isEmpty(), "Dataset validation failed:\n" + String.join("\n", errors));
        assertTrue(cases.size() >= 24, "At least 24 cases are required");
        assertTrue(countSuite(cases, "boundary") >= 8, "At least 8 boundary cases are required");
        assertTrue(countCategory(cases, "multi-task") >= 3, "At least 3 multi-task cases are required");
        assertTrue(countCategory(cases, "clarification") >= 3, "At least 3 clarification cases are required");
        assertTrue(countSuite(cases, "history") >= 4, "At least 4 history cases are required");
        assertTrue(countSuite(cases, "challenge") >= 8, "At least 8 challenge cases are required");

        Set<String> coveredIntents = cases.stream()
                .flatMap(c -> c.getExpected().getTaskIntents().stream())
                .collect(Collectors.toSet());
        assertTrue(coveredIntents.containsAll(Set.of(
                "GENERAL_CHAT", "PE_RETRIEVAL", "PE_REASONING",
                "PE_CALCULATION", "FINANCIAL_GENERAL", "STOCK_ANALYSIS", "INSPECTION")));

        assertTrue(cases.stream().anyMatch(c -> c.getCaseId().equals("online-financial-negation-001")
                && c.getExpected().getTaskIntents().equals(List.of("FINANCIAL_GENERAL"))));
        assertTrue(cases.stream().anyMatch(c -> c.getCaseId().equals("online-financial-clarification-depth-001")
                && Boolean.TRUE.equals(c.getExpected().getNeedsClarification())
                && c.getExpected().getMissingInfoContains().contains("analysisDepth")));
        assertTrue(cases.stream().anyMatch(c -> c.getCaseId().equals("online-multi-financial-investment-001")
                && c.getExpected().getTaskIntents().equals(
                List.of("FINANCIAL_GENERAL", "STOCK_ANALYSIS"))));

        int expectedRuns = cases.stream().mapToInt(c -> c.getEvaluation().getRuns()).sum();
        assertEquals(46, cases.size(), "Dataset should contain financial boundary cases");
        assertEquals(126, expectedRuns, "Dataset should include financial boundary runs");
    }

    private long countSuite(List<IntentRoutingOnlineEvalCase> cases, String suite) {
        return cases.stream().filter(c -> suite.equals(c.getSuite())).count();
    }

    private long countCategory(List<IntentRoutingOnlineEvalCase> cases, String category) {
        return cases.stream().filter(c -> category.equals(c.getCategory())).count();
    }
}
