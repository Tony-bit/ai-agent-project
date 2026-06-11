package denny.ai.agent.test.eval.routing;

import denny.ai.agent.domain.model.valobj.AiAgentClientFlowConfigVO;
import denny.ai.agent.domain.model.valobj.MultiIntentRoutingResult;
import denny.ai.agent.domain.model.valobj.SubTask;
import denny.ai.agent.domain.model.valobj.enums.IntentTypeEnum;
import denny.ai.agent.domain.service.auto.step.routing.IntentRoutingService;
import denny.ai.agent.domain.service.observability.ObservabilityService;
import denny.ai.agent.test.eval.routing.IntentRoutingOnlineEvalReportWriter.WrittenReports;
import denny.ai.agent.test.eval.routing.IntentRoutingOnlineEvaluator.EvalReport;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class IntentRoutingOnlineEvaluatorTest {

    @TempDir
    Path tempDirectory;

    @Test
    public void shouldSeparateFormatAndInfrastructureErrorsAndWriteReports() throws Exception {
        StubRoutingService service = new StubRoutingService(List.of(
                route(false, IntentTypeEnum.GENERAL_CHAT),
                fallback("JSON\u89e3\u6790\u5931\u8d25: invalid response"),
                fallback("LLM\u8c03\u7528\u5f02\u5e38: timeout")
        ));
        IntentRoutingOnlineEvalCase evalCase = routingCase(
                "error-classification", "single-task", List.of("GENERAL_CHAT"), false, 3);

        EvalReport report = new IntentRoutingOnlineEvaluator(service, "3201")
                .evaluate(List.of(evalCase), null, null);

        assertEquals(1, report.getMetrics().getPassedRunCount());
        assertEquals(2, report.getMetrics().getEffectiveRunCount());
        assertEquals(1, report.getMetrics().getFormatErrorCount());
        assertEquals(1, report.getMetrics().getInfrastructureErrorCount());
        assertEquals(0.5, report.getMetrics().getRunAccuracy());
        assertFalse(report.getCases().get(0).isPassed());

        WrittenReports written = new IntentRoutingOnlineEvalReportWriter(tempDirectory).write(report);
        assertTrue(Files.exists(written.getJsonPath()));
        assertTrue(Files.exists(written.getMarkdownPath()));
        assertTrue(Files.readString(written.getMarkdownPath()).contains("error-classification"));
    }

    @Test
    public void shouldMatchClarificationAndOrderInsensitiveMultiTask() {
        MultiIntentRoutingResult clarification = MultiIntentRoutingResult.builder()
                .multiTask(false)
                .needsClarification(true)
                .missingInfo(List.of("stockCode"))
                .taskList(List.of())
                .reasoning("missing stock")
                .build();
        MultiIntentRoutingResult reversedMultiTask = MultiIntentRoutingResult.builder()
                .multiTask(true)
                .needsClarification(false)
                .missingInfo(List.of())
                .taskList(List.of(
                        task(IntentTypeEnum.PE_REASONING),
                        task(IntentTypeEnum.PE_RETRIEVAL)))
                .reasoning("two tasks")
                .build();
        StubRoutingService service = new StubRoutingService(List.of(clarification, reversedMultiTask));

        IntentRoutingOnlineEvalCase clarificationCase = clarificationCase();
        IntentRoutingOnlineEvalCase multiCase = routingCase(
                "unordered-multi", "multi-task", List.of("PE_RETRIEVAL", "PE_REASONING"), true, 1);
        multiCase.getExpected().setOrderSensitive(false);

        EvalReport report = new IntentRoutingOnlineEvaluator(service, "3201")
                .evaluate(List.of(clarificationCase, multiCase), null, null);

        assertTrue(report.getCases().stream().allMatch(c -> c.isPassed()));
        assertEquals(1.0, report.getMetrics().getCasePassRate());
        assertEquals(1.0, report.getMetrics().getClarificationAccuracy());
        assertEquals(1.0, report.getMetrics().getMultiTaskExactMatch());
    }

    @Test
    public void shouldLinkEvaluationRunToTraceAndScores() {
        StubRoutingService service = new StubRoutingService(List.of(route(false, IntentTypeEnum.GENERAL_CHAT)));
        RecordingObservabilityService observability = new RecordingObservabilityService();
        IntentRoutingOnlineEvalCase evalCase = routingCase(
                "trace-case", "single-task", List.of("GENERAL_CHAT"), false, 1);

        EvalReport report = new IntentRoutingOnlineEvaluator(service, observability, "3201")
                .evaluate(List.of(evalCase), "smoke", null);

        assertFalse(report.getEvalRunId().isBlank());
        assertFalse(report.getBatchTraceId().isBlank());
        assertEquals(2, observability.startedTraces.size());
        assertEquals(report.getCases().get(0).getRuns().get(0).getTraceId(),
                service.observationContexts.get(0).get("trace_id"));
        assertEquals(report.getEvalRunId(), service.observationContexts.get(0).get("eval_run_id"));
        assertTrue(observability.scores.stream().anyMatch(score ->
                "routing_correct".equals(score.name) && score.value == 1.0));
        assertTrue(observability.scores.stream().anyMatch(score ->
                "run_accuracy".equals(score.name) && score.value == 1.0));
    }

    @Test
    public void shouldCompareWithPreviousBaselineAndDetectRegression() throws Exception {
        IntentRoutingOnlineEvalCase evalCase = routingCase(
                "baseline-case", "single-task", List.of("GENERAL_CHAT"), false, 1);

        EvalReport baseline = new IntentRoutingOnlineEvaluator(
                new StubRoutingService(List.of(route(false, IntentTypeEnum.GENERAL_CHAT))), "3201")
                .evaluate(List.of(evalCase), null, null);
        new IntentRoutingOnlineEvalReportWriter(tempDirectory).write(baseline);

        EvalReport current = new IntentRoutingOnlineEvaluator(
                new StubRoutingService(List.of(route(false, IntentTypeEnum.PE_REASONING))), "3201")
                .evaluate(List.of(evalCase), null, null);
        WrittenReports written = new IntentRoutingOnlineEvalReportWriter(tempDirectory).write(current);

        assertEquals(baseline.getEvalRunId(), current.getBaselineEvalRunId());
        assertEquals(List.of("baseline-case"), current.getComparison().getRegressedCaseIds());
        assertEquals(-1.0, current.getComparison().getMetricDeltas().get("runAccuracy"));
        assertTrue(Files.exists(written.getHistoryPath()));
        assertTrue(Files.exists(written.getLatestPath()));
        assertTrue(Files.readString(written.getMarkdownPath()).contains("Regressed cases: `baseline-case`"));
    }

    private IntentRoutingOnlineEvalCase routingCase(String id,
                                                    String category,
                                                    List<String> intents,
                                                    boolean multiTask,
                                                    int runs) {
        IntentRoutingOnlineEvalCase c = baseCase(id, category, runs);
        c.getExpected().setMultiTask(multiTask);
        c.getExpected().setNeedsClarification(false);
        c.getExpected().setTaskIntents(intents);
        return c;
    }

    private IntentRoutingOnlineEvalCase clarificationCase() {
        IntentRoutingOnlineEvalCase c = baseCase("clarification", "clarification", 1);
        c.getExpected().setMultiTask(false);
        c.getExpected().setNeedsClarification(true);
        c.getExpected().setTaskIntents(List.of());
        c.getExpected().setMissingInfoContains(List.of("stockCode"));
        c.getExpected().setMissingInfoNotEmpty(true);
        return c;
    }

    private IntentRoutingOnlineEvalCase baseCase(String id, String category, int runs) {
        IntentRoutingOnlineEvalCase c = new IntentRoutingOnlineEvalCase();
        c.setCaseId(id);
        c.setEnabled(true);
        c.setSuite("test");
        c.setCategory(category);
        c.setDescription(id);
        IntentRoutingOnlineEvalCase.Input input = new IntentRoutingOnlineEvalCase.Input();
        input.setQuery("query");
        input.setHistoryMessages(List.of());
        c.setInput(input);
        c.setExpected(new IntentRoutingOnlineEvalCase.Expected());
        IntentRoutingOnlineEvalCase.Evaluation evaluation = new IntentRoutingOnlineEvalCase.Evaluation();
        evaluation.setRuns(runs);
        evaluation.setMinPassRate(1.0);
        evaluation.setMinConsistencyRate(1.0);
        c.setEvaluation(evaluation);
        return c;
    }

    private static MultiIntentRoutingResult route(boolean multiTask, IntentTypeEnum... intents) {
        List<SubTask> tasks = new ArrayList<>();
        for (IntentTypeEnum intent : intents) {
            tasks.add(task(intent));
        }
        return MultiIntentRoutingResult.builder()
                .multiTask(multiTask)
                .needsClarification(false)
                .missingInfo(List.of())
                .taskList(tasks)
                .reasoning("normal route")
                .build();
    }

    private static SubTask task(IntentTypeEnum intent) {
        return SubTask.builder().intent(intent).build();
    }

    private static MultiIntentRoutingResult fallback(String reasoning) {
        return MultiIntentRoutingResult.builder()
                .multiTask(false)
                .needsClarification(false)
                .missingInfo(List.of())
                .taskList(List.of(task(IntentTypeEnum.GENERAL_CHAT)))
                .reasoning(reasoning)
                .build();
    }

    private static class StubRoutingService extends IntentRoutingService {
        private final Queue<MultiIntentRoutingResult> results;
        private final List<Map<String, Object>> observationContexts = new ArrayList<>();

        private StubRoutingService(List<MultiIntentRoutingResult> results) {
            this.results = new ArrayDeque<>(results);
        }

        @Override
        public MultiIntentRoutingResult routeUnified(String userMessage,
                                                     List<String> historyMessages,
                                                     AiAgentClientFlowConfigVO configVO) {
            return results.remove();
        }

        @Override
        public MultiIntentRoutingResult routeUnified(String userMessage,
                                                     List<String> historyMessages,
                                                     AiAgentClientFlowConfigVO configVO,
                                                     Map<String, Object> observationContext) {
            observationContexts.add(new HashMap<>(observationContext));
            return results.remove();
        }
    }

    private static class RecordingObservabilityService implements ObservabilityService {
        private final List<String> startedTraces = new ArrayList<>();
        private final List<RecordedScore> scores = new ArrayList<>();

        @Override
        public String startTrace(String sessionId, String input, Map<String, Object> metadata) {
            String traceId = UUID.randomUUID().toString();
            startedTraces.add(traceId);
            return traceId;
        }

        @Override
        public String startSpan(String traceId, String spanName, Map<String, Object> metadata) {
            return UUID.randomUUID().toString();
        }

        @Override
        public void logGeneration(String traceId,
                                  String spanId,
                                  String model,
                                  String prompt,
                                  String output,
                                  Map<String, Object> metadata,
                                  Map<String, Object> tokenUsage) {
        }

        @Override
        public void logScore(String traceId,
                             String scoreName,
                             Double value,
                             String comment,
                             Map<String, Object> metadata) {
            scores.add(new RecordedScore(traceId, scoreName, value));
        }

        @Override
        public void endSpan(String spanId, boolean success, String errorMessage) {
        }

        @Override
        public void endTrace(String traceId, String output, Map<String, Object> metadata) {
        }
    }

    private record RecordedScore(String traceId, String name, double value) {
    }
}
