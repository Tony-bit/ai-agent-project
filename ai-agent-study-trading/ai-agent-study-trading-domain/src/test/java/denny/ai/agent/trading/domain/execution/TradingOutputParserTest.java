package denny.ai.agent.trading.domain.execution;

import com.fasterxml.jackson.databind.ObjectMapper;
import denny.ai.agent.trading.api.vo.NarrativeNodeResult;
import denny.ai.agent.trading.api.vo.payload.ResearchArgumentPayload;
import denny.ai.agent.trading.domain.narrative.ResearchArgumentNarrativeAdapter;
import denny.ai.agent.trading.domain.prompt.PromptContractMode;
import jakarta.validation.Validation;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TradingOutputParserTest {

    private final TradingOutputParser parser = new TradingOutputParser(new StructuredPayloadCodec(
            new ObjectMapper().findAndRegisterModules(),
            Validation.buildDefaultValidatorFactory().getValidator()));

    @Test
    void relaxedNarrativePreservesTrimmedTextWithoutEntityScanning() {
        NarrativeNodeResult result = parser.parseNarrative(PromptContractMode.RELAXED_V3,
                "  001309 德明利建议买入  ", "BULL", ResearchArgumentPayload.class,
                payload -> new ResearchArgumentNarrativeAdapter().adapt("BULL", payload));

        assertEquals(new NarrativeNodeResult("BULL", "001309 德明利建议买入"), result);
    }

    @Test
    void strictNarrativeStillUsesValidatedV2PayloadAdapter() {
        String response = """
                {"stance":"BULL","keyEvidence":[{"type":"FACT","confidence":"HIGH","claim":"证据"}],
                 "risks":[],"summary":"摘要","targetEcho":null}
                """;

        NarrativeNodeResult result = parser.parseNarrative(PromptContractMode.STRICT_V2,
                response, "BULL", ResearchArgumentPayload.class,
                payload -> new ResearchArgumentNarrativeAdapter().adapt("BULL", payload));

        assertEquals("## BULL 观点\n\n摘要\n\n### 关键证据\n- [FACT/HIGH] 证据", result.rawText());
    }

    @Test
    void relaxedStructuredParserAcceptsJsonWrappedInMarkdown() {
        record MinimalDecision(String decision, String reasoning) {
        }
        MinimalDecision result = parser.parseStructured(PromptContractMode.RELAXED_V3,
                "结果如下：\n```json\n{\"decision\":\"HOLD\",\"reasoning\":\"信息不足\"}\n```",
                MinimalDecision.class);

        assertEquals(new MinimalDecision("HOLD", "信息不足"), result);
    }
}
