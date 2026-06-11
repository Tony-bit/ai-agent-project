package denny.ai.agent.test.eval.routing;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.serializer.SerializerFeature;
import denny.ai.agent.test.eval.routing.IntentRoutingOnlineEvaluator.AccuracyMetric;
import denny.ai.agent.test.eval.routing.IntentRoutingOnlineEvaluator.CaseResult;
import denny.ai.agent.test.eval.routing.IntentRoutingOnlineEvaluator.EvalComparison;
import denny.ai.agent.test.eval.routing.IntentRoutingOnlineEvaluator.EvalReport;
import denny.ai.agent.test.eval.routing.IntentRoutingOnlineEvaluator.GlobalMetrics;
import denny.ai.agent.test.eval.routing.IntentRoutingOnlineEvaluator.RunResult;
import lombok.AllArgsConstructor;
import lombok.Data;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

public class IntentRoutingOnlineEvalReportWriter {

    private static final DateTimeFormatter FILE_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HHmmss");
    private final Path outputDirectory;

    public IntentRoutingOnlineEvalReportWriter(Path outputDirectory) {
        this.outputDirectory = outputDirectory;
    }

    public WrittenReports write(EvalReport report) {
        try {
            Files.createDirectories(outputDirectory);
            EvalReport baseline = readBaseline();
            applyComparison(report, baseline);

            String prefix = report.getEvalRunId() == null || report.getEvalRunId().isBlank()
                    ? LocalDateTime.now().format(FILE_TIME)
                    : report.getEvalRunId();
            Path jsonPath = outputDirectory.resolve(prefix + "-summary.json");
            Path markdownPath = outputDirectory.resolve(prefix + "-report.md");
            Path historyDirectory = outputDirectory.resolve("history");
            Path historyPath = historyDirectory.resolve(prefix + ".json");
            Path latestPath = outputDirectory.resolve("latest.json");
            Files.createDirectories(historyDirectory);

            String reportJson = JSON.toJSONString(
                    report, SerializerFeature.PrettyFormat, SerializerFeature.WriteMapNullValue);
            Files.writeString(jsonPath, reportJson, StandardCharsets.UTF_8);
            Files.writeString(historyPath, reportJson, StandardCharsets.UTF_8);
            Files.writeString(markdownPath, toMarkdown(report), StandardCharsets.UTF_8);
            if (report.getMetrics().getInfrastructureErrorCount() == 0) {
                Files.writeString(latestPath, reportJson, StandardCharsets.UTF_8);
            }
            return new WrittenReports(
                    jsonPath.toAbsolutePath(),
                    markdownPath.toAbsolutePath(),
                    historyPath.toAbsolutePath(),
                    latestPath.toAbsolutePath());
        } catch (IOException e) {
            throw new IllegalStateException("Failed to write online eval reports to " + outputDirectory, e);
        }
    }

    private EvalReport readBaseline() {
        Path latestPath = outputDirectory.resolve("latest.json");
        if (!Files.exists(latestPath)) {
            return null;
        }
        try {
            return JSON.parseObject(Files.readString(latestPath, StandardCharsets.UTF_8), EvalReport.class);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to read baseline report " + latestPath, e);
        }
    }

    private void applyComparison(EvalReport current, EvalReport baseline) {
        EvalComparison comparison = new EvalComparison();
        current.setComparison(comparison);
        if (baseline == null || baseline.getMetrics() == null) {
            return;
        }

        current.setBaselineEvalRunId(baseline.getEvalRunId());
        GlobalMetrics now = current.getMetrics();
        GlobalMetrics before = baseline.getMetrics();
        comparison.getMetricDeltas().put("casePassRate", now.getCasePassRate() - before.getCasePassRate());
        comparison.getMetricDeltas().put("runAccuracy", now.getRunAccuracy() - before.getRunAccuracy());
        comparison.getMetricDeltas().put("formatErrorRate", now.getFormatErrorRate() - before.getFormatErrorRate());
        comparison.getMetricDeltas().put("infrastructureErrorRate",
                now.getInfrastructureErrorRate() - before.getInfrastructureErrorRate());

        Map<String, CaseResult> previousCases = baseline.getCases().stream()
                .collect(Collectors.toMap(CaseResult::getCaseId, Function.identity(), (left, right) -> left, LinkedHashMap::new));
        Map<String, CaseResult> currentCases = current.getCases().stream()
                .collect(Collectors.toMap(CaseResult::getCaseId, Function.identity(), (left, right) -> left, LinkedHashMap::new));

        for (CaseResult currentCase : current.getCases()) {
            CaseResult previousCase = previousCases.get(currentCase.getCaseId());
            if (previousCase == null) {
                comparison.getNewCaseIds().add(currentCase.getCaseId());
            } else if (isRegressed(previousCase, currentCase)) {
                comparison.getRegressedCaseIds().add(currentCase.getCaseId());
            } else if (isImproved(previousCase, currentCase)) {
                comparison.getImprovedCaseIds().add(currentCase.getCaseId());
            }
        }
        for (String previousCaseId : previousCases.keySet()) {
            if (!currentCases.containsKey(previousCaseId)) {
                comparison.getRemovedCaseIds().add(previousCaseId);
            }
        }
    }

    private boolean isRegressed(CaseResult previous, CaseResult current) {
        return previous.isPassed() && !current.isPassed()
                || current.getPassRate() < previous.getPassRate()
                || current.getConsistencyRate() < previous.getConsistencyRate();
    }

    private boolean isImproved(CaseResult previous, CaseResult current) {
        return !previous.isPassed() && current.isPassed()
                || current.getPassRate() > previous.getPassRate()
                || current.getConsistencyRate() > previous.getConsistencyRate();
    }

    private String toMarkdown(EvalReport report) {
        GlobalMetrics metrics = report.getMetrics();
        StringBuilder markdown = new StringBuilder();
        markdown.append("# Intent Routing Online Evaluation\n\n");
        markdown.append("- Started: ").append(report.getStartedAt()).append("\n");
        markdown.append("- Finished: ").append(report.getFinishedAt()).append("\n");
        markdown.append("- Client ID: `").append(report.getClientId()).append("`\n");
        markdown.append("- Eval run ID: `").append(report.getEvalRunId()).append("`\n");
        markdown.append("- Baseline run ID: ").append(display(report.getBaselineEvalRunId())).append("\n");
        markdown.append("- Suite: ").append(display(report.getSuite())).append("\n");
        markdown.append("- Tag: ").append(display(report.getTag())).append("\n\n");

        markdown.append("## Global Metrics\n\n");
        markdown.append("| Metric | Value |\n|---|---:|\n");
        metric(markdown, "Case pass rate", metrics.getCasePassRate());
        metric(markdown, "Run accuracy", metrics.getRunAccuracy());
        metric(markdown, "Clarification accuracy", metrics.getClarificationAccuracy());
        metric(markdown, "Multi-task exact match", metrics.getMultiTaskExactMatch());
        metric(markdown, "Format error rate", metrics.getFormatErrorRate());
        metric(markdown, "Infrastructure error rate", metrics.getInfrastructureErrorRate());
        markdown.append("| Cases | ").append(metrics.getPassedCaseCount()).append(" / ").append(metrics.getCaseCount()).append(" |\n");
        markdown.append("| Runs | ").append(metrics.getPassedRunCount()).append(" / ").append(metrics.getEffectiveRunCount()).append(" effective |\n\n");

        appendComparison(markdown, report);

        markdown.append("## Per-Intent Accuracy\n\n");
        markdown.append("| Intent | Passed | Runs | Accuracy |\n|---|---:|---:|---:|\n");
        for (Map.Entry<String, AccuracyMetric> entry : metrics.getPerIntentAccuracy().entrySet()) {
            AccuracyMetric value = entry.getValue();
            markdown.append("| ").append(entry.getKey()).append(" | ")
                    .append(value.getPassedRunCount()).append(" | ")
                    .append(value.getRunCount()).append(" | ")
                    .append(percent(value.getAccuracy())).append(" |\n");
        }
        markdown.append("\n");

        markdown.append("## Confusion Matrix\n\n");
        markdown.append("| Expected | Actual | Count |\n|---|---|---:|\n");
        for (Map.Entry<String, Map<String, Integer>> expected : metrics.getConfusionMatrix().entrySet()) {
            for (Map.Entry<String, Integer> actual : expected.getValue().entrySet()) {
                markdown.append("| ").append(expected.getKey()).append(" | ")
                        .append(actual.getKey()).append(" | ")
                        .append(actual.getValue()).append(" |\n");
            }
        }
        markdown.append("\n");

        markdown.append("## Failed Cases\n\n");
        boolean hasFailure = false;
        for (CaseResult c : report.getCases()) {
            if (!c.isPassed()) {
                hasFailure = true;
                markdown.append("- `").append(c.getCaseId()).append("`: pass=")
                        .append(percent(c.getPassRate())).append(", consistency=")
                        .append(percent(c.getConsistencyRate())).append(", formatErrors=")
                        .append(c.getFormatErrorCount()).append(", infraErrors=")
                        .append(c.getInfrastructureErrorCount()).append("\n");
            }
        }
        if (!hasFailure) {
            markdown.append("None.\n");
        }
        markdown.append("\n");

        markdown.append("## Case Details\n\n");
        for (CaseResult c : report.getCases()) {
            markdown.append("### ").append(c.getCaseId()).append("\n\n");
            markdown.append("- Result: ").append(c.isPassed() ? "PASS" : "FAIL").append("\n");
            markdown.append("- Query: ").append(escape(c.getQuery())).append("\n");
            markdown.append("- Expected: `").append(String.join(",", c.getExpectedIntents())).append("`\n");
            markdown.append("- Pass rate: ").append(percent(c.getPassRate())).append("\n");
            markdown.append("- Consistency: ").append(percent(c.getConsistencyRate())).append("\n\n");
            markdown.append("| Run | Outcome | Signature | Passed | Latency ms | Reasoning |\n");
            markdown.append("|---:|---|---|---|---:|---|\n");
            for (RunResult run : c.getRuns()) {
                markdown.append("| ").append(run.getRunIndex()).append(" | ")
                        .append(run.getOutcomeType()).append(" | ")
                        .append(escape(run.getSignature())).append(" | ")
                        .append(run.isPassed()).append(" | ")
                        .append(run.getLatencyMs()).append(" | ")
                        .append(escape(run.getReasoning())).append(" |\n");
            }
            markdown.append("\n");
        }
        return markdown.toString();
    }

    private void appendComparison(StringBuilder markdown, EvalReport report) {
        markdown.append("## Baseline Diff\n\n");
        if (report.getBaselineEvalRunId() == null || report.getComparison() == null) {
            markdown.append("No previous baseline. This run becomes the first baseline.\n\n");
            return;
        }
        markdown.append("| Metric | Delta |\n|---|---:|\n");
        for (Map.Entry<String, Double> entry : report.getComparison().getMetricDeltas().entrySet()) {
            markdown.append("| ").append(entry.getKey()).append(" | ")
                    .append(signedPercent(entry.getValue())).append(" |\n");
        }
        markdown.append("\n- Regressed cases: ").append(caseList(report.getComparison().getRegressedCaseIds())).append("\n");
        markdown.append("- Improved cases: ").append(caseList(report.getComparison().getImprovedCaseIds())).append("\n");
        markdown.append("- New cases: ").append(caseList(report.getComparison().getNewCaseIds())).append("\n");
        markdown.append("- Removed cases: ").append(caseList(report.getComparison().getRemovedCaseIds())).append("\n\n");
    }

    private void metric(StringBuilder markdown, String name, double value) {
        markdown.append("| ").append(name).append(" | ").append(percent(value)).append(" |\n");
    }

    private String percent(double value) {
        return String.format("%.2f%%", value * 100);
    }

    private String signedPercent(double value) {
        return String.format("%+.2f%%", value * 100);
    }

    private String caseList(List<String> caseIds) {
        return caseIds == null || caseIds.isEmpty() ? "none" : "`" + String.join("`, `", caseIds) + "`";
    }

    private String display(String value) {
        return value == null || value.isBlank() ? "all" : "`" + value + "`";
    }

    private String escape(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("|", "\\|").replace("\r", " ").replace("\n", " ");
    }

    @Data
    @AllArgsConstructor
    public static class WrittenReports {
        private Path jsonPath;
        private Path markdownPath;
        private Path historyPath;
        private Path latestPath;
    }
}
