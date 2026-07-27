package denny.ai.agent.trading.domain.node;

import denny.ai.agent.trading.api.vo.TargetContext;
import denny.ai.agent.trading.api.vo.payload.ResearchArgumentPayload;
import denny.ai.agent.trading.domain.validation.NodeValidationAudit;

import java.util.List;
import java.util.Map;

public record ResearchManagerInput(
        TargetContext targetContext,
        Map<String, Object> validatedAnalystReports,
        List<ResearchArgumentPayload> validatedBullHistory,
        List<ResearchArgumentPayload> validatedBearHistory,
        Map<String, NodeValidationAudit> validationStatuses,
        List<String> dataQualityWarnings,
        int currentRound
) {
}
