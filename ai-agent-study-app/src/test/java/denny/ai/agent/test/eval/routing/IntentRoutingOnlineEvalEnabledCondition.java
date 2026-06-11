package denny.ai.agent.test.eval.routing;

import org.junit.jupiter.api.extension.ConditionEvaluationResult;
import org.junit.jupiter.api.extension.ExecutionCondition;
import org.junit.jupiter.api.extension.ExtensionContext;

public class IntentRoutingOnlineEvalEnabledCondition implements ExecutionCondition {

    private static final ConditionEvaluationResult ENABLED =
            ConditionEvaluationResult.enabled("Online evaluation is enabled");
    private static final ConditionEvaluationResult DISABLED =
            ConditionEvaluationResult.disabled("Online evaluation is disabled");

    @Override
    public ConditionEvaluationResult evaluateExecutionCondition(ExtensionContext context) {
        String property = System.getProperty("intent.routing.online.eval.enabled");
        String environment = System.getenv("INTENT_ROUTING_ONLINE_EVAL_ENABLED");
        return Boolean.parseBoolean(property) || Boolean.parseBoolean(environment) ? ENABLED : DISABLED;
    }
}
