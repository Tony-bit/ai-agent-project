package denny.ai.agent.test.eval.routing;

public enum EvalRoutingMode {
    UNIFIED,
    SPLIT;

    public static EvalRoutingMode from(String value) {
        if (value == null || value.isBlank()) {
            return UNIFIED;
        }
        return EvalRoutingMode.valueOf(value.trim().toUpperCase());
    }
}
