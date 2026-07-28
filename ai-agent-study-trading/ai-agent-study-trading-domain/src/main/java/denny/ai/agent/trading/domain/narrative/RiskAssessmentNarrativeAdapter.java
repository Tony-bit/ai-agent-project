package denny.ai.agent.trading.domain.narrative;

import denny.ai.agent.trading.api.vo.NarrativeNodeResult;
import denny.ai.agent.trading.api.vo.payload.RiskAssessmentPayload;

import java.util.Objects;

public final class RiskAssessmentNarrativeAdapter {

    public static final String VERSION = "risk-assessment-markdown-v1";

    public NarrativeNodeResult adapt(String javaRole, RiskAssessmentPayload payload) {
        Objects.requireNonNull(payload, "payload");
        StringBuilder markdown = new StringBuilder()
                .append("## ").append(javaRole).append(" 风险意见\n\n")
                .append(payload.summary().trim())
                .append("\n\n### V2 风险评分\n")
                .append(payload.riskScore()).append("/5");
        if (payload.riskItems() != null && !payload.riskItems().isEmpty()) {
            markdown.append("\n\n### 风险项");
            for (String item : payload.riskItems()) {
                markdown.append("\n- ").append(item);
            }
        }
        if (payload.mitigations() != null && !payload.mitigations().isEmpty()) {
            markdown.append("\n\n### 缓解措施");
            for (String mitigation : payload.mitigations()) {
                markdown.append("\n- ").append(mitigation);
            }
        }
        return new NarrativeNodeResult(javaRole, markdown.toString());
    }
}
