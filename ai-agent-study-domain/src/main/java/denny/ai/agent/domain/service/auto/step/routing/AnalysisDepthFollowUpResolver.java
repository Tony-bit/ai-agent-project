package denny.ai.agent.domain.service.auto.step.routing;

import denny.ai.agent.domain.model.valobj.BaseSlot;
import denny.ai.agent.domain.model.valobj.MultiIntentRoutingResult;
import denny.ai.agent.domain.model.valobj.SubTask;
import denny.ai.agent.domain.model.valobj.enums.ConfidenceEnum;
import denny.ai.agent.domain.model.valobj.enums.IntentTypeEnum;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

@Component
public class AnalysisDepthFollowUpResolver {

    static final String CLARIFICATION_PROMPT = "你需要快速了解，还是进行完整投资分析？";

    private static final Set<String> QUICK_CHOICES = Set.of(
            "快速了解", "我要快速了解", "我想快速了解", "选择快速了解", "就快速了解"
    );
    private static final Set<String> FULL_CHOICES = Set.of(
            "完整投资分析", "进行完整投资分析", "我要进行完整投资分析",
            "我想进行完整投资分析", "选择完整投资分析", "就进行完整投资分析"
    );
    private static final List<String> ANALYSIS_CUES = List.of(
            "分析", "看看", "看一下", "看下", "怎么样", "研究", "评估", "了解"
    );
    private static final List<String> GENERIC_TERMS = List.of(
            "帮我分析一下", "给我分析一下", "帮我看一下", "帮我看看", "给我看看",
            "分析一下", "看一下", "看看", "怎么样", "最近", "走势", "情况",
            "这只股票", "该股票", "这个股票", "这家公司", "该公司", "股票", "公司",
            "帮我", "给我", "请", "一下", "分析", "研究", "评估", "了解"
    );
    private static final Pattern STOCK_CODE = Pattern.compile("(?<!\\d)\\d{6}(?!\\d)");
    private static final Pattern SEPARATORS = Pattern.compile("[\\p{P}\\p{S}\\s]+");

    public Resolution resolve(String currentMessage, List<String> historyMessages) {
        Choice choice = parseChoice(currentMessage);
        if (choice == null || historyMessages == null || historyMessages.size() < 2) {
            return Resolution.unresolved(currentMessage);
        }

        int assistantIndex = lastNonBlankIndex(historyMessages, historyMessages.size() - 1);
        if (assistantIndex < 1) {
            return Resolution.unresolved(currentMessage);
        }
        String assistantMessage = contentForRole(historyMessages.get(assistantIndex), "assistant");
        if (!CLARIFICATION_PROMPT.equals(normalizePrompt(assistantMessage))) {
            return Resolution.unresolved(currentMessage);
        }

        int userIndex = lastNonBlankIndex(historyMessages, assistantIndex - 1);
        if (userIndex < 0) {
            return Resolution.unresolved(currentMessage);
        }
        String originalQuery = contentForRole(historyMessages.get(userIndex), "user");
        if (!containsResolvableFinancialObject(originalQuery)) {
            return Resolution.unresolved(currentMessage);
        }

        String depthInstruction = choice == Choice.FULL
                ? "进行完整投资分析"
                : "快速了解，仅提供客观金融概览";
        return new Resolution(true, originalQuery, originalQuery + "；" + depthInstruction, choice);
    }

    public MultiIntentRoutingResult enforce(MultiIntentRoutingResult modelResult, Resolution resolution) {
        if (resolution == null || !resolution.resolved()) {
            return modelResult;
        }
        IntentTypeEnum intent = resolution.choice() == Choice.FULL
                ? IntentTypeEnum.STOCK_ANALYSIS
                : IntentTypeEnum.FINANCIAL_GENERAL;
        String executorNode = intent == IntentTypeEnum.STOCK_ANALYSIS ? "tradingStarter" : "generalChatNode";
        Map<String, Object> slots = retainedSlots(modelResult, resolution.originalQuery());
        SubTask task = SubTask.builder()
                .taskId("sub-1")
                .taskIndex(1)
                .totalTasks(1)
                .content(resolution.effectiveQuery())
                .intent(intent)
                .executorNode(executorNode)
                .confidence(ConfidenceEnum.HIGH)
                .slots(slots)
                .dependsOn(List.of())
                .status(SubTask.SubTaskStatus.PENDING)
                .taskType(0)
                .build();
        return MultiIntentRoutingResult.builder()
                .multiTask(false)
                .needsClarification(false)
                .missingInfo(List.of())
                .clarificationPrompt("")
                .reasoning("Resolved the fixed analysis-depth clarification from the nearest conversation turn")
                .taskList(List.of(task))
                .metrics(modelResult == null ? null : modelResult.getMetrics())
                .build();
    }

    private Map<String, Object> retainedSlots(MultiIntentRoutingResult modelResult, String originalQuery) {
        if (modelResult != null && modelResult.getTaskList() != null && modelResult.getTaskList().size() == 1) {
            Map<String, Object> slots = modelResult.getTaskList().get(0).getSlots();
            if (slots != null && !slots.isEmpty()) {
                return slots;
            }
        }
        return Map.of(
                "baseSlot", BaseSlot.builder().topic(originalQuery).sentiment("neutral").build(),
                "intentSpecificSlots", Map.of()
        );
    }

    private Choice parseChoice(String currentMessage) {
        String normalized = normalizeChoice(currentMessage);
        if (FULL_CHOICES.contains(normalized)) {
            return Choice.FULL;
        }
        if (QUICK_CHOICES.contains(normalized)) {
            return Choice.QUICK;
        }
        return null;
    }

    private boolean containsResolvableFinancialObject(String query) {
        if (!StringUtils.hasText(query)) {
            return false;
        }
        if (STOCK_CODE.matcher(query).find()) {
            return true;
        }
        boolean hasAnalysisCue = ANALYSIS_CUES.stream().anyMatch(query::contains);
        if (!hasAnalysisCue) {
            return false;
        }
        String candidate = query;
        for (String term : GENERIC_TERMS) {
            candidate = candidate.replace(term, "");
        }
        candidate = SEPARATORS.matcher(candidate).replaceAll("");
        return candidate.length() >= 2;
    }

    private int lastNonBlankIndex(List<String> historyMessages, int start) {
        for (int i = start; i >= 0; i--) {
            if (StringUtils.hasText(historyMessages.get(i))) {
                return i;
            }
        }
        return -1;
    }

    private String contentForRole(String message, String expectedRole) {
        if (!StringUtils.hasText(message)) {
            return null;
        }
        int separator = message.indexOf(':');
        if (separator <= 0 || !expectedRole.equalsIgnoreCase(message.substring(0, separator).trim())) {
            return null;
        }
        return message.substring(separator + 1).trim();
    }

    private String normalizeChoice(String value) {
        if (!StringUtils.hasText(value)) {
            return "";
        }
        return SEPARATORS.matcher(value).replaceAll("");
    }

    private String normalizePrompt(String value) {
        return value == null ? null : value.trim();
    }

    public enum Choice {
        QUICK,
        FULL
    }

    public record Resolution(boolean resolved, String originalQuery, String effectiveQuery, Choice choice) {
        static Resolution unresolved(String currentMessage) {
            return new Resolution(false, null, currentMessage, null);
        }
    }
}
