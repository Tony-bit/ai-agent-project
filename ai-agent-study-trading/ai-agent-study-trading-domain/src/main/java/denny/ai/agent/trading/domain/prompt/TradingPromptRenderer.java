package denny.ai.agent.trading.domain.prompt;

import denny.ai.agent.trading.api.vo.TargetContext;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class TradingPromptRenderer {

    private static final Pattern PLACEHOLDER = Pattern.compile("\\{\\{([a-zA-Z][a-zA-Z0-9]*)}}" );
    private static final Map<String, Set<String>> REQUIRED = Map.ofEntries(
            Map.entry("6002", Set.of("targetContext", "stockData", "outputContract")),
            Map.entry("6003", Set.of("targetContext", "stockData", "outputContract")),
            Map.entry("6004", Set.of("targetContext", "stockData", "outputContract")),
            Map.entry("6005", Set.of("targetContext", "stockData", "outputContract")),
            Map.entry("6006", Set.of("targetContext", "analystReports", "debateHistory", "outputContract")),
            Map.entry("6007", Set.of("targetContext", "analystReports", "debateHistory", "outputContract")),
            Map.entry("6008", Set.of("targetContext", "analystReports", "debateHistory",
                    "validationStatus", "currentRound", "outputContract")),
            Map.entry("6009", Set.of("targetContext", "analystReports", "debateHistory",
                    "riskReports", "validationStatus", "outputContract")),
            Map.entry("6010", Set.of("targetContext", "investmentPlan", "riskReports", "outputContract")),
            Map.entry("6011", Set.of("targetContext", "investmentPlan", "riskReports", "outputContract")),
            Map.entry("6012", Set.of("targetContext", "investmentPlan", "riskReports", "outputContract")),
            Map.entry("6013", Set.of("targetContext", "analystReports", "debateHistory",
                    "validationStatus", "outputContract"))
    );

    public void validateTemplate(String promptId, String template) {
        Set<String> required = requiredPlaceholders(promptId);
        if (template == null || template.isBlank()) {
            throw new IllegalArgumentException("prompt template must not be blank: " + promptId);
        }
        Set<String> actual = extractPlaceholders(template);
        if (!actual.equals(required)) {
            throw new IllegalArgumentException("prompt placeholders do not match role contract: " + promptId
                    + ", required=" + required + ", actual=" + actual);
        }
        String stripped = PLACEHOLDER.matcher(template).replaceAll("");
        if (stripped.contains("{{") || stripped.contains("}}")) {
            throw new IllegalArgumentException("prompt contains malformed placeholders: " + promptId);
        }
    }

    public String render(TradingPromptSnapshot snapshot,
                         TargetContext targetContext,
                         String promptId,
                         Map<String, ?> variables) {
        PromptVersion prompt = snapshot.require(promptId);
        if (!snapshot.runId().equals(targetContext.runId())) {
            throw new IllegalArgumentException("prompt snapshot and targetContext runId must match");
        }
        validateTemplate(promptId, prompt.content());

        Map<String, Object> values = new HashMap<>();
        if (variables != null) {
            values.putAll(variables);
        }
        if (values.containsKey("targetContext")) {
            throw new IllegalArgumentException("targetContext is injected by Java and cannot be overridden");
        }
        values.put("targetContext", renderTargetContext(targetContext));
        Set<String> required = requiredPlaceholders(promptId);
        if (!values.keySet().equals(required)) {
            throw new IllegalArgumentException("render variables do not match role contract: " + promptId);
        }

        Matcher matcher = PLACEHOLDER.matcher(prompt.content());
        StringBuilder rendered = new StringBuilder();
        while (matcher.find()) {
            Object value = values.get(matcher.group(1));
            if (value == null) {
                throw new IllegalArgumentException("render variable must not be null: " + matcher.group(1));
            }
            matcher.appendReplacement(rendered, Matcher.quoteReplacement(value.toString()));
        }
        matcher.appendTail(rendered);
        if (rendered.indexOf("{{") >= 0 || rendered.indexOf("}}") >= 0) {
            throw new IllegalStateException("rendered prompt contains unresolved placeholders: " + promptId);
        }
        return rendered.toString();
    }

    public Set<String> requiredPlaceholders(String promptId) {
        Set<String> required = REQUIRED.get(promptId);
        if (required == null) {
            throw new IllegalArgumentException("unknown trading promptId: " + promptId);
        }
        return required;
    }

    public String renderTargetContext(TargetContext target) {
        String industry = target.industry() == null ? "未提供" : target.industry();
        return """
                本次唯一分析标的：
                - 股票代码：%s
                - TS代码：%s
                - 股票名称：%s
                - 行业：%s
                - 数据截止日期：%s

                所有分析、工具调用、报告和投资判断必须只针对该标的。
                不得把其他公司作为本次分析主体；输入资料出现的相关公司只能按原始关联关系引用。
                不得使用模型记忆补充输入资料中不存在的公司事实或数值。
                """.formatted(target.stockCode(), target.targetId(), target.stockName(),
                industry, target.asOfDate());
    }

    private Set<String> extractPlaceholders(String template) {
        java.util.HashSet<String> placeholders = new java.util.HashSet<>();
        Matcher matcher = PLACEHOLDER.matcher(template);
        while (matcher.find()) {
            placeholders.add(matcher.group(1));
        }
        return Set.copyOf(placeholders);
    }
}
