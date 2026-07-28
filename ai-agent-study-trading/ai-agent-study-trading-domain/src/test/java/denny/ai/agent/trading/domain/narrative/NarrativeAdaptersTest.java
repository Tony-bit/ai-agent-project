package denny.ai.agent.trading.domain.narrative;

import denny.ai.agent.trading.api.vo.NarrativeNodeResult;
import denny.ai.agent.trading.api.vo.payload.ResearchArgumentPayload;
import denny.ai.agent.trading.api.vo.payload.RiskAssessmentPayload;
import denny.ai.agent.trading.api.vo.payload.TargetEchoPayload;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class NarrativeAdaptersTest {

    @Test
    void narrativeModelContainsOnlyJavaRoleAndRawText() {
        assertEquals(List.of("role", "rawText"), Arrays.stream(NarrativeNodeResult.class.getRecordComponents())
                .map(component -> component.getName()).toList());
    }

    @Test
    void researchAdapterMatchesGoldenAndPreservesDuplicatesWithoutTargetEcho() throws IOException {
        ResearchArgumentPayload payload = new ResearchArgumentPayload(
                "BEAR",
                List.of(
                        new ResearchArgumentPayload.EvidenceArgument("FACT", "HIGH", "ROE 同比提升"),
                        new ResearchArgumentPayload.EvidenceArgument("FACT", "HIGH", "ROE 同比提升"),
                        new ResearchArgumentPayload.EvidenceArgument("INFERENCE", "LOW", "估值可能修复")),
                List.of("宏观需求放缓", "宏观需求放缓"),
                "盈利能力持续改善。",
                new TargetEchoPayload("001309", "德明利"));

        NarrativeNodeResult result = new ResearchArgumentNarrativeAdapter().adapt("BULL", payload);

        assertEquals("BULL", result.role());
        assertEquals(golden("research-argument-markdown-v1.md"), result.rawText());
        assertFalse(result.rawText().contains("001309"));
        assertFalse(result.rawText().contains("德明利"));
    }

    @Test
    void riskAdapterMatchesGoldenAndPreservesOrderAndDuplicatesWithoutTargetEcho() throws IOException {
        RiskAssessmentPayload payload = new RiskAssessmentPayload(
                "AGGRESSIVE", 2,
                List.of("流动性收紧", "流动性收紧"),
                List.of("降低仓位", "设置止损", "降低仓位"),
                "短期波动风险需要控制。",
                new TargetEchoPayload("001309", "德明利"));

        NarrativeNodeResult result = new RiskAssessmentNarrativeAdapter().adapt("CONSERVATIVE", payload);

        assertEquals("CONSERVATIVE", result.role());
        assertEquals(golden("risk-assessment-markdown-v1.md"), result.rawText());
        assertFalse(result.rawText().contains("001309"));
        assertFalse(result.rawText().contains("德明利"));
    }

    @Test
    void adaptersOmitEmptyOptionalSections() {
        ResearchArgumentPayload research = new ResearchArgumentPayload(
                "BULL", List.of(), List.of(), "摘要", null);
        RiskAssessmentPayload risk = new RiskAssessmentPayload(
                "NEUTRAL", 3, List.of(), List.of(), "摘要", null);

        assertEquals("## BULL 观点\n\n摘要",
                new ResearchArgumentNarrativeAdapter().adapt("BULL", research).rawText());
        assertEquals("## NEUTRAL 风险意见\n\n摘要\n\n### V2 风险评分\n3/5",
                new RiskAssessmentNarrativeAdapter().adapt("NEUTRAL", risk).rawText());
    }

    private String golden(String name) throws IOException {
        try (var input = getClass().getResourceAsStream("/narrative/" + name)) {
            if (input == null) {
                throw new IllegalStateException("missing golden file: " + name);
            }
            return new String(input.readAllBytes(), StandardCharsets.UTF_8)
                    .replace("\r\n", "\n").trim();
        }
    }
}
