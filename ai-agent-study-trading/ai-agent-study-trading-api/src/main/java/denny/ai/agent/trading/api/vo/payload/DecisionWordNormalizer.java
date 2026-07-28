package denny.ai.agent.trading.api.vo.payload;

import java.util.Locale;

final class DecisionWordNormalizer {

    private DecisionWordNormalizer() {
    }

    static String normalizeResearch(String value) {
        String normalized = normalizeAction(value, true);
        if ("SKIP".equals(normalized)) {
            return "INSUFFICIENT_DATA";
        }
        if ("数据不足".equals(trim(value)) || "信息不足".equals(trim(value))) {
            return "INSUFFICIENT_DATA";
        }
        return normalized;
    }

    static String normalizeAction(String value, boolean allowSkip) {
        String text = trim(value);
        if (text == null) {
            return null;
        }
        return switch (text.toUpperCase(Locale.ROOT)) {
            case "BUY", "买入", "买" -> "BUY";
            case "SELL", "卖出", "卖" -> "SELL";
            case "HOLD", "持有", "观望" -> "HOLD";
            case "SKIP", "跳过", "数据不足", "信息不足" -> allowSkip ? "SKIP" : text;
            default -> text.toUpperCase(Locale.ROOT);
        };
    }

    static String trim(String value) {
        return value == null ? null : value.trim();
    }
}
