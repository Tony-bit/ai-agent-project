package denny.ai.agent.test.eval.routing;

import com.alibaba.fastjson.JSON;
import denny.ai.agent.domain.model.valobj.enums.IntentTypeEnum;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class IntentRoutingOnlineEvalCaseLoader {

    static final String RESOURCE_PATH = "eval/intent-routing-online-cases.json";
    private static final Set<String> CATEGORIES = Set.of("single-task", "multi-task", "clarification");

    public List<IntentRoutingOnlineEvalCase> loadAll() {
        try (InputStream input = getClass().getClassLoader().getResourceAsStream(RESOURCE_PATH)) {
            if (input == null) {
                throw new IllegalStateException("Missing online eval dataset: " + RESOURCE_PATH);
            }
            String json = new String(input.readAllBytes(), StandardCharsets.UTF_8);
            List<IntentRoutingOnlineEvalCase> cases = JSON.parseArray(json, IntentRoutingOnlineEvalCase.class);
            return cases == null ? List.of() : cases;
        } catch (IOException e) {
            throw new IllegalStateException("Failed to read online eval dataset", e);
        }
    }

    public List<IntentRoutingOnlineEvalCase> loadRunnable(String suite, String tag) {
        return loadAll().stream()
                .filter(c -> !Boolean.FALSE.equals(c.getEnabled()))
                .filter(c -> isBlank(suite) || suite.equals(c.getSuite()))
                .filter(c -> isBlank(tag) || (c.getTags() != null && c.getTags().contains(tag)))
                .toList();
    }

    public List<String> validate(List<IntentRoutingOnlineEvalCase> cases) {
        List<String> errors = new ArrayList<>();
        Set<String> caseIds = new HashSet<>();
        if (cases == null || cases.isEmpty()) {
            errors.add("Dataset must not be empty");
            return errors;
        }

        for (int i = 0; i < cases.size(); i++) {
            IntentRoutingOnlineEvalCase c = cases.get(i);
            String location = c == null || isBlank(c.getCaseId()) ? "case[" + i + "]" : c.getCaseId();
            if (c == null) {
                errors.add(location + ": case must not be null");
                continue;
            }
            if (isBlank(c.getCaseId())) {
                errors.add(location + ": caseId is required");
            } else if (!caseIds.add(c.getCaseId())) {
                errors.add(location + ": duplicate caseId");
            }
            if (isBlank(c.getSuite())) {
                errors.add(location + ": suite is required");
            }
            if (!CATEGORIES.contains(c.getCategory())) {
                errors.add(location + ": unsupported category " + c.getCategory());
            }
            if (c.getInput() == null || isBlank(c.getInput().getQuery())) {
                errors.add(location + ": input.query is required");
            }
            if (c.getExpected() == null) {
                errors.add(location + ": expected is required");
            } else {
                validateExpected(c, location, errors);
            }
            if (c.getEvaluation() == null) {
                errors.add(location + ": evaluation is required");
            } else {
                validateEvaluation(c.getEvaluation(), location, errors);
            }
        }
        return errors;
    }

    private void validateExpected(IntentRoutingOnlineEvalCase c, String location, List<String> errors) {
        IntentRoutingOnlineEvalCase.Expected expected = c.getExpected();
        if (expected.getMultiTask() == null || expected.getNeedsClarification() == null) {
            errors.add(location + ": multiTask and needsClarification are required");
        }
        List<String> intents = expected.getTaskIntents() == null ? List.of() : expected.getTaskIntents();
        for (String intent : intents) {
            if (IntentTypeEnum.fromCode(intent) == IntentTypeEnum.UNKNOWN) {
                errors.add(location + ": invalid task intent " + intent);
            }
        }
        if ("clarification".equals(c.getCategory())) {
            if (!Boolean.TRUE.equals(expected.getNeedsClarification()) || !intents.isEmpty()) {
                errors.add(location + ": clarification case must require clarification and have no tasks");
            }
        } else if (Boolean.TRUE.equals(expected.getNeedsClarification()) || intents.isEmpty()) {
            errors.add(location + ": routing case must have task intents and not require clarification");
        }
        if ("single-task".equals(c.getCategory()) && intents.size() != 1) {
            errors.add(location + ": single-task case must declare exactly one intent");
        }
        if ("multi-task".equals(c.getCategory()) && intents.size() < 2) {
            errors.add(location + ": multi-task case must declare at least two intents");
        }
    }

    private void validateEvaluation(IntentRoutingOnlineEvalCase.Evaluation evaluation,
                                    String location,
                                    List<String> errors) {
        if (evaluation.getRuns() == null || evaluation.getRuns() < 1) {
            errors.add(location + ": runs must be >= 1");
        }
        validateRate(evaluation.getMinPassRate(), location + ": minPassRate", errors);
        validateRate(evaluation.getMinConsistencyRate(), location + ": minConsistencyRate", errors);
    }

    private void validateRate(Double value, String name, List<String> errors) {
        if (value == null || value < 0 || value > 1) {
            errors.add(name + " must be between 0 and 1");
        }
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
