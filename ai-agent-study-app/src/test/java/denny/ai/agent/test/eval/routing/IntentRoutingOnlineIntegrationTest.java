package denny.ai.agent.test.eval.routing;

import denny.ai.agent.Application;
import denny.ai.agent.config.AiAgentAutoConfigProperties;
import denny.ai.agent.domain.model.valobj.enums.AiAgentEnumVO;
import denny.ai.agent.domain.service.armory.factory.ArmoryObjectRegistry;
import denny.ai.agent.domain.service.auto.step.routing.IntentRoutingService;
import denny.ai.agent.domain.service.observability.ObservabilityService;
import denny.ai.agent.test.eval.routing.IntentRoutingOnlineEvalReportWriter.WrittenReports;
import denny.ai.agent.test.eval.routing.IntentRoutingOnlineEvaluator.EvalReport;
import denny.ai.agent.test.eval.routing.IntentRoutingOnlineEvaluator.GlobalMetrics;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.context.SpringBootTest;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

@Slf4j
@ExtendWith(IntentRoutingOnlineEvalEnabledCondition.class)
@SpringBootTest(classes = Application.class)
public class IntentRoutingOnlineIntegrationTest {

    @Resource
    private IntentRoutingService routingService;

    @Resource
    private ArmoryObjectRegistry armoryObjectRegistry;

    @Resource
    private AiAgentAutoConfigProperties autoConfigProperties;

    @Resource
    private ObservabilityService observabilityService;

    @Test
    public void runIntentRoutingOnlineEvaluation() {
        String clientId = setting("intent.routing.eval.client-id", "INTENT_ROUTING_EVAL_CLIENT_ID", "3201");
        EvalRoutingMode routingMode = EvalRoutingMode.from(setting(
                "intent.routing.eval.mode", "INTENT_ROUTING_EVAL_MODE", EvalRoutingMode.UNIFIED.name()));
        String suite = setting("intent.routing.eval.suite", "INTENT_ROUTING_EVAL_SUITE", null);
        String tag = setting("intent.routing.eval.tag", "INTENT_ROUTING_EVAL_TAG", null);
        Integer runsOverride = integerSetting("intent.routing.eval.runs", "INTENT_ROUTING_EVAL_RUNS");
        Integer maxCases = integerSetting("intent.routing.eval.max-cases", "INTENT_ROUTING_EVAL_MAX_CASES");

        IntentRoutingOnlineEvalCaseLoader loader = new IntentRoutingOnlineEvalCaseLoader();
        List<IntentRoutingOnlineEvalCase> allCases = loader.loadAll();
        List<String> validationErrors = loader.validate(allCases);
        List<IntentRoutingOnlineEvalCase> selectedCases = loader.loadRunnable(suite, tag);
        if (maxCases != null) {
            selectedCases = selectedCases.stream().limit(maxCases).toList();
        }
        preflight(clientId, selectedCases, validationErrors);

        IntentRoutingOnlineEvaluator evaluator = new IntentRoutingOnlineEvaluator(
                routingService, observabilityService, clientId, routingMode, runsOverride);
        EvalReport report = evaluator.evaluate(selectedCases, suite, tag);
        Path reportDirectory = resolveReportDirectory(setting(
                "intent.routing.eval.report-dir",
                "INTENT_ROUTING_EVAL_REPORT_DIR",
                null), routingMode);
        WrittenReports written = new IntentRoutingOnlineEvalReportWriter(reportDirectory).write(report);

        GlobalMetrics metrics = report.getMetrics();
        log.info("Intent routing online eval completed: mode={}, evalRunId={}, baselineRunId={}, "
                        + "suite={}, tag={}, runsOverride={}, maxCases={}, cases={}/{}, "
                        + "runs={}/{}, formatErrorRate={}, infraErrorRate={}, failedCases={}, regressedCases={}, "
                        + "jsonReport={}, markdownReport={}",
                report.getRoutingMode(), report.getEvalRunId(), report.getBaselineEvalRunId(),
                suite, tag, runsOverride, maxCases,
                metrics.getPassedCaseCount(), metrics.getCaseCount(),
                metrics.getPassedRunCount(), metrics.getEffectiveRunCount(),
                metrics.getFormatErrorRate(), metrics.getInfrastructureErrorRate(),
                report.failedCaseIds(), report.getComparison().getRegressedCaseIds(),
                written.getJsonPath(), written.getMarkdownPath());

        assertThresholds(report);
    }

    private void preflight(String clientId,
                           List<IntentRoutingOnlineEvalCase> selectedCases,
                           List<String> validationErrors) {
        List<String> errors = new ArrayList<>(validationErrors);
        if (clientId == null || clientId.isBlank()) {
            errors.add("clientId must not be blank");
        }
        if (selectedCases.isEmpty()) {
            errors.add("No enabled cases matched the suite/tag filters");
        }
        if (!configuredClientIds().contains(clientId)) {
            errors.add("spring.ai.agent.auto-config.client-ids does not contain " + clientId);
        }
        String registryKey = AiAgentEnumVO.AI_CLIENT.getBeanName(clientId) + "taskType0";
        if (!armoryObjectRegistry.contains(registryKey)) {
            errors.add("ArmoryObjectRegistry does not contain " + registryKey);
        }
        assertTrue(errors.isEmpty(), "Online eval preflight failed:\n" + String.join("\n", errors));
    }

    private List<String> configuredClientIds() {
        if (autoConfigProperties.getClientIds() == null) {
            return List.of();
        }
        return autoConfigProperties.getClientIds().stream()
                .flatMap(value -> Arrays.stream(value.split(",")))
                .map(String::trim)
                .filter(value -> !value.isBlank())
                .toList();
    }

    void assertThresholds(EvalReport report) {
        GlobalMetrics metrics = report.getMetrics();
        double minCasePassRate = doubleSetting("intent.routing.eval.min-case-pass-rate", 0.90);
        double minRunAccuracy = doubleSetting("intent.routing.eval.min-run-accuracy", 0.90);
        double maxFormatErrorRate = doubleSetting("intent.routing.eval.max-format-error-rate", 0.02);
        double maxInfrastructureErrorRate = doubleSetting("intent.routing.eval.max-infrastructure-error-rate", 0.0);
        double maxFinancialGeneralToStockRate = doubleSetting(
                "intent.routing.eval.max-financial-general-to-stock-rate", 0.0);

        List<String> failures = new ArrayList<>();
        if (metrics.getCasePassRate() < minCasePassRate) {
            failures.add("casePassRate=" + metrics.getCasePassRate() + " < " + minCasePassRate);
        }
        if (metrics.getRunAccuracy() < minRunAccuracy) {
            failures.add("runAccuracy=" + metrics.getRunAccuracy() + " < " + minRunAccuracy);
        }
        if (metrics.getFormatErrorRate() > maxFormatErrorRate) {
            failures.add("formatErrorRate=" + metrics.getFormatErrorRate() + " > " + maxFormatErrorRate);
        }
        if (metrics.getInfrastructureErrorRate() > maxInfrastructureErrorRate) {
            failures.add("infrastructureErrorRate=" + metrics.getInfrastructureErrorRate()
                    + " > " + maxInfrastructureErrorRate);
        }
        if (metrics.getFinancialGeneralToStockAnalysisRate() > maxFinancialGeneralToStockRate) {
            failures.add("financialGeneralToStockAnalysisRate="
                    + metrics.getFinancialGeneralToStockAnalysisRate() + " > " + maxFinancialGeneralToStockRate);
        }
        if (!report.failedCaseIds().isEmpty()) {
            failures.add("failedCases=" + report.failedCaseIds());
        }
        assertTrue(failures.isEmpty(), "Online eval thresholds failed:\n" + String.join("\n", failures));
    }

    private double doubleSetting(String property, double defaultValue) {
        String value = System.getProperty(property);
        return value == null || value.isBlank() ? defaultValue : Double.parseDouble(value);
    }

    private Integer integerSetting(String property, String environment) {
        String value = setting(property, environment, null);
        if (value == null || value.isBlank()) {
            return null;
        }
        int parsed = Integer.parseInt(value);
        if (parsed < 1) {
            throw new IllegalArgumentException(property + " must be >= 1");
        }
        return parsed;
    }

    private Path resolveReportDirectory(String configuredDirectory, EvalRoutingMode routingMode) {
        if (configuredDirectory != null && !configuredDirectory.isBlank()) {
            return Path.of(configuredDirectory).resolve(routingMode.name().toLowerCase());
        }
        try {
            Path testClasses = Path.of(getClass().getProtectionDomain().getCodeSource().getLocation().toURI());
            return testClasses.getParent().resolve("eval-reports/intent-routing")
                    .resolve(routingMode.name().toLowerCase());
        } catch (Exception e) {
            return Path.of("ai-agent-study-app/target/eval-reports/intent-routing")
                    .resolve(routingMode.name().toLowerCase());
        }
    }

    private String setting(String property, String environment, String defaultValue) {
        String value = System.getProperty(property);
        if (value == null || value.isBlank()) {
            value = System.getenv(environment);
        }
        return value == null || value.isBlank() ? defaultValue : value;
    }
}
