package denny.ai.agent.test.eval.routing;

import com.alibaba.fastjson.JSON;
import denny.ai.agent.domain.model.valobj.AiAgentClientFlowConfigVO;
import denny.ai.agent.domain.model.valobj.MultiIntentRoutingResult;
import denny.ai.agent.domain.model.valobj.RoutingExecutionMetrics;
import denny.ai.agent.domain.model.valobj.RoutingStageMetric;
import denny.ai.agent.domain.model.valobj.SubTask;
import denny.ai.agent.domain.model.valobj.enums.ConfidenceEnum;
import denny.ai.agent.domain.model.valobj.enums.IntentTypeEnum;
import denny.ai.agent.domain.service.auto.step.routing.IntentRoutingMode;
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
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
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
        assertEquals(report.getEvalRunId() + "/trace-case/1",
                service.observationContexts.get(0).get("chat_memory_conversation_id"));
        assertTrue(observability.scores.stream().anyMatch(score ->
                "routing_correct".equals(score.name) && score.value == 1.0));
        assertTrue(observability.scores.stream().anyMatch(score ->
                "run_accuracy".equals(score.name) && score.value == 1.0));
        assertTrue(observability.endedTraces.stream().anyMatch(trace ->
                List.of("HIGH").equals(trace.metadata.get("routingConfidences"))
                        && "HIGH".equals(trace.metadata.get("routingMinConfidence"))
                        && Boolean.FALSE.equals(trace.metadata.get("routingHasLowConfidence"))));
    }

    @Test
    public void shouldEvaluateSplitModeAndIncludeRoutingMetricsInReports() throws Exception {
        StubRoutingService service = new StubRoutingService(List.of(
                routeWithMetrics(EvalRoutingMode.SPLIT, 120L, 15, 5, 20, 2)
        ));
        IntentRoutingOnlineEvalCase evalCase = routingCase(
                "split-metrics", "multi-task", List.of("STOCK_ANALYSIS", "STOCK_ANALYSIS"), true, 1);

        EvalReport report = new IntentRoutingOnlineEvaluator(
                service, null, "3201", EvalRoutingMode.SPLIT)
                .evaluate(List.of(evalCase), "multitask", null);

        assertEquals(EvalRoutingMode.SPLIT.name(), report.getRoutingMode());
        assertEquals(1, service.splitCalls);
        assertEquals(0, service.unifiedCalls);
        assertEquals(20.0, report.getMetrics().getAvgTokens());
        assertEquals(2.0, report.getMetrics().getAvgStageCount());
        assertEquals(1.0, report.getMetrics().getStageSuccessRate());
        assertEquals(Long.valueOf(120L), report.getCases().get(0).getRuns().get(0).getRoutingLatencyMs());

        WrittenReports written = new IntentRoutingOnlineEvalReportWriter(tempDirectory).write(report);
        String markdown = Files.readString(written.getMarkdownPath());
        assertTrue(markdown.contains("Routing mode: `SPLIT`"));
        assertTrue(markdown.contains("Avg tokens"));
        assertTrue(markdown.contains("Routing latency ms"));
    }

    @Test
    public void shouldOverrideCaseRunsForLowCostSampling() {
        StubRoutingService service = new StubRoutingService(List.of(route(false, IntentTypeEnum.GENERAL_CHAT)));
        IntentRoutingOnlineEvalCase evalCase = routingCase(
                "runs-override", "single-task", List.of("GENERAL_CHAT"), false, 5);

        EvalReport report = new IntentRoutingOnlineEvaluator(
                service, null, "3201", EvalRoutingMode.UNIFIED, 1)
                .evaluate(List.of(evalCase), "smoke", null);

        assertEquals(1, service.unifiedCalls);
        assertEquals(1, report.getMetrics().getRunCount());
        assertEquals(1, report.getCases().get(0).getRuns().size());
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

    @Test
    public void shouldQuarantineInvalidBaselineAndWriteParseableReport() throws Exception {
        Files.writeString(tempDirectory.resolve("latest.json"), "{invalid-json");
        IntentRoutingOnlineEvalCase evalCase = routingCase(
                "parseable-report", "single-task", List.of("GENERAL_CHAT"), false, 2);
        EvalReport report = new IntentRoutingOnlineEvaluator(
                new StubRoutingService(List.of(
                        route(false, IntentTypeEnum.GENERAL_CHAT),
                        route(false, IntentTypeEnum.GENERAL_CHAT))), "3201")
                .evaluate(List.of(evalCase), null, null);

        WrittenReports written = new IntentRoutingOnlineEvalReportWriter(tempDirectory).write(report);

        assertNull(report.getBaselineEvalRunId());
        assertEquals(report.getEvalRunId(),
                JSON.parseObject(Files.readString(written.getLatestPath()), EvalReport.class).getEvalRunId());
        try (Stream<Path> files = Files.list(tempDirectory)) {
            assertEquals(1, files
                    .filter(path -> path.getFileName().toString().startsWith("latest.invalid-"))
                    .count());
        }
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

    private static MultiIntentRoutingResult routeWithMetrics(EvalRoutingMode mode,
                                                             long latencyMs,
                                                             int promptTokens,
                                                             int completionTokens,
                                                             int totalTokens,
                                                             int stageCount) {
        List<SubTask> tasks = new ArrayList<>();
        for (int i = 0; i < stageCount; i++) {
            tasks.add(task(IntentTypeEnum.STOCK_ANALYSIS));
        }
        RoutingExecutionMetrics metrics = RoutingExecutionMetrics.builder()
                .mode(mode == EvalRoutingMode.SPLIT ? IntentRoutingMode.SPLIT : IntentRoutingMode.UNIFIED)
                .totalLatencyMs(latencyMs)
                .totalPromptTokens(promptTokens)
                .totalCompletionTokens(completionTokens)
                .totalTokens(totalTokens)
                .estimated(false)
                .stageMetrics(new ArrayList<>())
                .build();
        for (int i = 0; i < stageCount; i++) {
            metrics.getStageMetrics().add(RoutingStageMetric.builder()
                    .stageName(i == 0 ? "query-decomposition" : "task-routing-slot")
                    .callIndex(i)
                    .taskId(i == 0 ? null : "sub-" + i)
                    .totalTokens(totalTokens / stageCount)
                    .success(true)
                    .build());
        }
        return MultiIntentRoutingResult.builder()
                .multiTask(stageCount > 1)
                .needsClarification(false)
                .missingInfo(List.of())
                .taskList(tasks)
                .reasoning("normal route")
                .metrics(metrics)
                .build();
    }

    private static SubTask task(IntentTypeEnum intent) {
        return SubTask.builder().intent(intent).confidence(ConfidenceEnum.HIGH).build();
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
        private int unifiedCalls;
        private int splitCalls;

        private StubRoutingService(List<MultiIntentRoutingResult> results) {
            this.results = new ArrayDeque<>(results);
        }

        @Override
        public MultiIntentRoutingResult routeUnified(String userMessage,
                                                     List<String> historyMessages,
                                                     AiAgentClientFlowConfigVO configVO) {
            unifiedCalls++;
            return results.remove();
        }

        @Override
        public MultiIntentRoutingResult routeUnified(String userMessage,
                                                     List<String> historyMessages,
                                                     AiAgentClientFlowConfigVO configVO,
                                                     Map<String, Object> observationContext) {
            unifiedCalls++;
            observationContexts.add(new HashMap<>(observationContext));
            return results.remove();
        }

        @Override
        public MultiIntentRoutingResult routeSplit(String userMessage,
                                                   List<String> historyMessages,
                                                   AiAgentClientFlowConfigVO configVO) {
            splitCalls++;
            return results.remove();
        }
    }

    private static class RecordingObservabilityService implements ObservabilityService {
        private final List<String> startedTraces = new ArrayList<>();
        private final List<RecordedScore> scores = new ArrayList<>();
        private final List<RecordedTrace> endedTraces = new ArrayList<>();

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
        public void updateTraceMetadata(String traceId, Map<String, Object> metadata) {
        }

        @Override
        public void endSpan(String spanId, boolean success, String errorMessage) {
        }

        @Override
        public void endTrace(String traceId, String output, Map<String, Object> metadata) {
            endedTraces.add(new RecordedTrace(traceId, output, metadata == null ? Map.of() : new HashMap<>(metadata)));
        }
    }

    private record RecordedScore(String traceId, String name, double value) {
    }

    private record RecordedTrace(String traceId, String output, Map<String, Object> metadata) {
    }
}
