package denny.ai.agent.trading.domain.prompt;

import denny.ai.agent.trading.api.vo.TargetContext;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TradingPromptRendererTest {

    private final TradingPromptRenderer renderer = new TradingPromptRenderer();

    @Test
    void rendersNamedVariablesAndJavaOwnedTargetContext() {
        TargetContext target = target();
        String template = "{{targetContext}}\nDATA={{stockData}}\nSCHEMA={{outputContract}}";
        TradingPromptSnapshot snapshot = snapshot(target, "6002", template);

        String rendered = renderer.render(snapshot, target, "6002",
                Map.of("stockData", "price=$52.89\\raw", "outputContract", "strict-json"));

        assertTrue(rendered.contains("TS代码：601318.SH"));
        assertTrue(rendered.contains("股票名称：中国平安"));
        assertTrue(rendered.contains("price=$52.89\\raw"));
        assertFalse(rendered.contains("{{"));
    }

    @Test
    void rendersNestedJsonInputsWithoutTreatingClosingBracesAsPlaceholders() {
        TargetContext target = target();
        String stockData = """
                {"stockInfo":{"ticker":"601318"},"analysisData":{"roe":2.479}}
                """.trim();
        String outputContract = """
                {"type":"object","properties":{"targetEcho":{"type":"object"}}}
                """.trim();

        for (String promptId : java.util.List.of("6002", "6003", "6004")) {
            String template = "{{targetContext}}\nDATA={{stockData}}\nSCHEMA={{outputContract}}";
            TradingPromptSnapshot snapshot = snapshot(target, promptId, template);

            String rendered = renderer.render(snapshot, target, promptId,
                    Map.of("stockData", stockData, "outputContract", outputContract));

            assertTrue(rendered.contains(stockData));
            assertTrue(rendered.contains(outputContract));
        }
    }

    @Test
    void rejectsMissingUnknownMalformedAndTargetOverride() {
        assertThrows(IllegalArgumentException.class,
                () -> renderer.validateTemplate("6002", "{{targetContext}} {{stockData}}"));
        assertThrows(IllegalArgumentException.class,
                () -> renderer.validateTemplate("6002",
                        "{{targetContext}} {{stockData}} {{outputContract}} {{unknown}}"));
        assertThrows(IllegalArgumentException.class,
                () -> renderer.validateTemplate("6002",
                        "{{targetContext}} {{stockData}} {{outputContract}} {{broken"));

        TargetContext target = target();
        TradingPromptSnapshot snapshot = snapshot(target, "6002",
                "{{targetContext}} {{stockData}} {{outputContract}}");
        assertThrows(IllegalArgumentException.class,
                () -> renderer.render(snapshot, target, "6002",
                        Map.of("targetContext", "forged", "stockData", "data",
                                "outputContract", "schema")));
    }

    private TradingPromptSnapshot snapshot(TargetContext target, String selectedId, String selectedTemplate) {
        Map<String, PromptVersion> prompts = TradingPromptSet.REQUIRED_PROMPT_IDS.stream()
                .collect(java.util.stream.Collectors.toMap(id -> id, id -> {
                    String content = id.equals(selectedId) ? selectedTemplate
                            : renderer.requiredPlaceholders(id).stream()
                            .map(name -> "{{" + name + "}}")
                            .collect(java.util.stream.Collectors.joining("\n"));
                    return new PromptVersion(id, 2, content, "0".repeat(64));
                }));
        return new TradingPromptSnapshot(target.runId(), prompts);
    }

    private TargetContext target() {
        return new TargetContext(UUID.randomUUID().toString(), "601318.SH",
                "中国平安", "保险", LocalDate.of(2026, 7, 22));
    }
}
