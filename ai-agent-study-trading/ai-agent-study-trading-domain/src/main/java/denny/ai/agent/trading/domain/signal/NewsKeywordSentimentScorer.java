package denny.ai.agent.trading.domain.signal;

import denny.ai.agent.trading.api.vo.NewsItemVO;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

@Component
public final class NewsKeywordSentimentScorer {

    public static final String VERSION = "news-keyword-score-v1";

    private static final double SUMMARY_WEIGHT = 0.6;
    private static final Pattern HTML_TAG_PATTERN = Pattern.compile("<[^>]+>");
    private static final Pattern CONTROL_PATTERN = Pattern.compile("[\\p{Cntrl}&&[^\\r\\n\\t]]");
    private static final Pattern WHITESPACE_PATTERN = Pattern.compile("\\s+");

    private static final List<String> STRONG_DEGREE_WORDS = List.of(
            "大幅", "显著", "大增", "大降");
    private static final List<String> WEAK_DEGREE_WORDS = List.of(
            "小幅", "轻微", "略有");

    private static final List<Rule> COMPOSITE_RULES = List.of(
            rule("turnaround", 0.75, "扭亏为盈"),
            rule("loss_narrowed", 0.35, "亏损收窄"),
            rule("loss_widened", -0.70, "亏损扩大"),
            rule("below_expectations", -0.60, "增长不及预期", "低于预期", "不及预期"),
            rule("above_expectations", 0.60, "超出预期", "超预期"),
            rule("rating_downgrade", -0.60, "下调评级"),
            rule("rating_upgrade", 0.55, "上调评级"),
            rule("pledge_release", 0.20, "解除质押", "解押"),
            rule("new_pledge", -0.30, "新增质押", "质押")
    );

    private static final List<Rule> DIRECTIONAL_RULES = List.of(
            rule("limit_up", 1.00, "涨停"),
            rule("surge", 0.90, "暴涨"),
            rule("large_rise", 0.80, "大涨"),
            rule("record_high", 0.70, "创新高"),
            rule("buyback", 0.65, "回购"),
            rule("approval", 0.60, "获批", "批准"),
            rule("major_contract", 0.60, "中标", "签署重大合同"),
            rule("holding_increase", 0.55, "增持"),
            rule("net_profit_growth", 0.50, "净利润增长"),
            rule("revenue_growth", 0.40, "营收增长"),
            rule("essential_drug_catalog", 0.45,
                    "纳入国家基本药物目录", "纳入国家基药目录", "纳入基药目录"),
            rule("breakthrough", 0.35, "突破"),
            rule("delisting", -1.00, "退市"),
            rule("limit_down", -1.00, "跌停"),
            rule("crash", -0.90, "暴跌"),
            rule("investigation", -0.80, "立案调查", "被立案"),
            rule("major_violation", -0.80, "重大违法"),
            rule("default", -0.75, "违约"),
            rule("risk_warning", -0.70, "风险警示"),
            rule("penalty", -0.65, "处罚"),
            rule("litigation", -0.55, "诉讼"),
            rule("holding_reduction", -0.50, "减持"),
            rule("net_profit_decline", -0.50, "净利润下降", "净利润下滑"),
            rule("revenue_decline", -0.40, "营收下降", "营收下滑")
    );

    public ScoreResult score(NewsItemVO item) {
        if (item == null) {
            return unavailable();
        }

        String title = clean(item.getTitle());
        String summary = clean(item.getSummary());
        if (title.isBlank() && summary.isBlank()) {
            return unavailable();
        }

        Set<String> matchedRuleIds = new LinkedHashSet<>();
        SectionScore titleScore = scoreSection(title, Set.of());
        matchedRuleIds.addAll(titleScore.matchedRuleIds());

        SectionScore summaryScore = scoreSection(summary, matchedRuleIds);
        matchedRuleIds.addAll(summaryScore.matchedRuleIds());
        if (matchedRuleIds.isEmpty()) {
            return unavailable();
        }

        double combined = titleScore.score() + summaryScore.score() * SUMMARY_WEIGHT;
        double clamped = Math.max(-1.0, Math.min(1.0, combined));
        if (Math.abs(clamped) < 1e-12) {
            clamped = 0.0;
        }
        return new ScoreResult(clamped, List.copyOf(matchedRuleIds));
    }

    private SectionScore scoreSection(String text, Set<String> excludedRuleIds) {
        if (text.isBlank()) {
            return new SectionScore(0.0, List.of());
        }

        double degreeMultiplier = degreeMultiplier(text);
        String workingText = removeDegreeWords(text);
        List<String> matchedRuleIds = new ArrayList<>();
        double score = 0.0;

        MatchResult composite = matchRules(workingText, COMPOSITE_RULES,
                excludedRuleIds, matchedRuleIds);
        workingText = composite.remainingText();
        score += composite.score();

        MatchResult directional = matchRules(workingText, DIRECTIONAL_RULES,
                excludedRuleIds, matchedRuleIds);
        score += directional.score();

        return new SectionScore(score * degreeMultiplier, List.copyOf(matchedRuleIds));
    }

    private MatchResult matchRules(String text,
                                   List<Rule> rules,
                                   Set<String> excludedRuleIds,
                                   List<String> matchedRuleIds) {
        String remainingText = text;
        double score = 0.0;
        for (Rule rule : rules) {
            if (excludedRuleIds.contains(rule.id())) {
                continue;
            }
            String phrase = rule.firstMatch(remainingText);
            if (phrase == null) {
                continue;
            }
            score += rule.score();
            matchedRuleIds.add(rule.id());
            remainingText = remainingText.replace(phrase, " ");
        }
        return new MatchResult(score, remainingText);
    }

    private double degreeMultiplier(String text) {
        if (containsAny(text, STRONG_DEGREE_WORDS)) {
            return 1.2;
        }
        if (containsAny(text, WEAK_DEGREE_WORDS)) {
            return 0.7;
        }
        return 1.0;
    }

    private String removeDegreeWords(String text) {
        String result = text.replace("大增", "增长").replace("大降", "下降");
        for (String word : List.of("大幅", "显著")) {
            result = result.replace(word, "");
        }
        for (String word : WEAK_DEGREE_WORDS) {
            result = result.replace(word, "");
        }
        return result;
    }

    private boolean containsAny(String text, Collection<String> candidates) {
        return candidates.stream().anyMatch(text::contains);
    }

    private String clean(String value) {
        if (value == null) {
            return "";
        }
        String withoutTags = HTML_TAG_PATTERN.matcher(value).replaceAll(" ");
        String unescaped = withoutTags
                .replace("&nbsp;", " ")
                .replace("&amp;", "&")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace('\u3000', ' ')
                .replace('\u00A0', ' ');
        String withoutControls = CONTROL_PATTERN.matcher(unescaped).replaceAll("");
        return WHITESPACE_PATTERN.matcher(withoutControls).replaceAll(" ").trim();
    }

    private static Rule rule(String id, double score, String... phrases) {
        return new Rule(id, score, List.of(phrases));
    }

    private ScoreResult unavailable() {
        return new ScoreResult(null, List.of());
    }

    public record ScoreResult(Double score, List<String> matchedRules) {
        public ScoreResult {
            matchedRules = matchedRules == null ? List.of() : List.copyOf(matchedRules);
        }

        public boolean available() {
            return score != null;
        }
    }

    private record Rule(String id, double score, List<String> phrases) {
        String firstMatch(String text) {
            return phrases.stream().filter(text::contains).findFirst().orElse(null);
        }
    }

    private record MatchResult(double score, String remainingText) {
    }

    private record SectionScore(double score, List<String> matchedRuleIds) {
    }
}
