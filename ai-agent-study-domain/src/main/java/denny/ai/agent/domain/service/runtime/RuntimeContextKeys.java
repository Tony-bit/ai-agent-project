package denny.ai.agent.domain.service.runtime;

public final class RuntimeContextKeys {

    public static final String TURN_CONTEXT = "turnRuntimeContext";
    public static final String SESSION_CONTEXT = "sessionRuntimeContext";
    public static final String USER_CONTEXT = "userRuntimeContext";
    public static final String RECENT_HISTORY_MESSAGES = "recentHistoryMessages";
    public static final String PERSONA = "persona";
    public static final String FLOW_CONFIG_MAP = "aiAgentClientFlowConfigVOMap";

    private RuntimeContextKeys() {
    }
}
