package denny.ai.agent.trading.domain.narrative;

import denny.ai.agent.trading.api.vo.NarrativeNodeResult;
import denny.ai.agent.trading.api.vo.payload.ResearchArgumentPayload;

import java.util.Objects;

public final class ResearchArgumentNarrativeAdapter {

    public static final String VERSION = "research-argument-markdown-v1";

    public NarrativeNodeResult adapt(String javaRole, ResearchArgumentPayload payload) {
        Objects.requireNonNull(payload, "payload");
        StringBuilder markdown = new StringBuilder()
                .append("## ").append(javaRole).append(" 观点\n\n")
                .append(payload.summary().trim());
        if (payload.keyEvidence() != null && !payload.keyEvidence().isEmpty()) {
            markdown.append("\n\n### 关键证据");
            for (ResearchArgumentPayload.EvidenceArgument evidence : payload.keyEvidence()) {
                markdown.append("\n- [").append(evidence.type()).append('/')
                        .append(evidence.confidence()).append("] ").append(evidence.claim());
            }
        }
        if (payload.risks() != null && !payload.risks().isEmpty()) {
            markdown.append("\n\n### 风险");
            for (String risk : payload.risks()) {
                markdown.append("\n- ").append(risk);
            }
        }
        return new NarrativeNodeResult(javaRole, markdown.toString());
    }
}
