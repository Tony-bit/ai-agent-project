package denny.ai.agent.trading.domain.node;

import denny.ai.agent.trading.api.vo.TargetContext;
import denny.ai.agent.trading.api.vo.NarrativeNodeResult;
import denny.ai.agent.trading.domain.validation.NodeValidationAudit;
import denny.ai.agent.trading.api.vo.signal.DecisionSignalSet;

import java.util.List;
import java.util.Map;

public record ResearchManagerInput(
        TargetContext targetContext,
        Map<String, Object> validatedAnalystReports,
        DecisionSignalSet decisionSignals,
        List<NarrativeNodeResult> validatedBullHistory,
        List<NarrativeNodeResult> validatedBearHistory,
        Map<String, NodeValidationAudit> validationStatuses,
        List<String> dataQualityWarnings,
        int currentRound
) {
}
