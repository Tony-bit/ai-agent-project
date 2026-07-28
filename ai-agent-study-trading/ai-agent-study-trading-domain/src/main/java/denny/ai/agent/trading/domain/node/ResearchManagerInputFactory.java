package denny.ai.agent.trading.domain.node;

import denny.ai.agent.trading.domain.validation.NodeValidationAudit;
import denny.ai.agent.trading.domain.validation.NodeValidationRegistry;
import denny.ai.agent.trading.domain.vo.TradingContextVO;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import denny.ai.agent.trading.domain.signal.V2DecisionSignalFactory;

@Component
public class ResearchManagerInputFactory {

    private static final Map<String, String> ANALYST_NODES = Map.of(
            "fundamental", "FundamentalAnalystNode",
            "technical", "TechnicalAnalystNode",
            "sentiment", "SentimentAnalystNode",
            "news", "NewsAnalystNode");

    public ResearchManagerInput create(TradingContextVO context,
                                       NodeValidationRegistry registry,
                                       int currentRound) {
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(registry, "registry");
        Map<String, Object> reports = new LinkedHashMap<>();
        addIfValid(reports, "fundamental", context.getFundamentalReport(), registry);
        addIfValid(reports, "technical", context.getTechnicalReport(), registry);
        addIfValid(reports, "sentiment", context.getSentimentReport(), registry);
        addIfValid(reports, "news", context.getNewsReport(), registry);

        TradingContextVO.InvestmentDebateVO debate = context.getInvestmentDebate();
        List<denny.ai.agent.trading.api.vo.NarrativeNodeResult> bulls =
                debate != null && registry.isValid("BullResearcherNode")
                        ? List.copyOf(debate.getBullHistory()) : List.of();
        List<denny.ai.agent.trading.api.vo.NarrativeNodeResult> bears =
                debate != null && registry.isValid("BearResearcherNode")
                        ? List.copyOf(debate.getBearHistory()) : List.of();

        Map<String, NodeValidationAudit> statuses = new LinkedHashMap<>();
        ANALYST_NODES.values().forEach(node -> statuses.put(node, registry.statusOrMissing(node)));
        statuses.put("BullResearcherNode", registry.statusOrMissing("BullResearcherNode"));
        statuses.put("BearResearcherNode", registry.statusOrMissing("BearResearcherNode"));
        var signals = context.getDecisionSignals() == null
                ? new V2DecisionSignalFactory().fromReports(context) : context.getDecisionSignals();
        return new ResearchManagerInput(context.getTargetContext(), Map.copyOf(reports), signals, bulls, bears,
                Map.copyOf(statuses), context.getDataWarnings() == null
                ? List.of() : List.copyOf(context.getDataWarnings()), currentRound);
    }

    private void addIfValid(Map<String, Object> reports,
                            String reportName,
                            Object report,
                            NodeValidationRegistry registry) {
        String nodeName = ANALYST_NODES.get(reportName);
        if (report != null && registry.isValid(nodeName)) {
            reports.put(reportName, report);
        }
    }
}
