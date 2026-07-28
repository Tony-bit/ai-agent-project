package denny.ai.agent.trading.api.vo;

import java.util.List;

public record ResearchManagerResult(
        String recommendation,
        String reasoning,
        String status,
        Double overallScore,
        boolean needMoreDebate,
        List<String> warnings
) {
    public ResearchManagerResult {
        warnings = warnings == null ? List.of() : List.copyOf(warnings);
    }
}
