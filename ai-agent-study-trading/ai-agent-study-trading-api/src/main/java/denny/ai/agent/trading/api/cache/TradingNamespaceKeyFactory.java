package denny.ai.agent.trading.api.cache;

import denny.ai.agent.trading.api.vo.TargetContext;

import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.stream.Collectors;

public final class TradingNamespaceKeyFactory {

    private TradingNamespaceKeyFactory() {
    }

    public static String chatMemory(String sessionId, TargetContext target, String nodeName) {
        Objects.requireNonNull(target, "target");
        return String.join(":", "trading", segment(sessionId), segment(target.runId()),
                segment(target.targetId()), segment(nodeName));
    }

    public static String rawData(String provider,
                                 String apiName,
                                 String targetId,
                                 String dateScope,
                                 Map<String, ?> params,
                                 String schemaVersion) {
        String normalizedParams = params == null || params.isEmpty() ? null
                : new TreeMap<>(params).entrySet().stream()
                .map(entry -> segment(entry.getKey()) + "=" + segment(String.valueOf(entry.getValue())))
                .collect(Collectors.joining("&"));
        String base = String.join(":", segment(provider), segment(apiName),
                segment(targetId).toUpperCase(java.util.Locale.ROOT), segment(dateScope));
        return base + (normalizedParams == null ? "" : ":" + normalizedParams)
                + ":" + segment(schemaVersion);
    }

    private static String segment(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("namespace key segment must not be blank");
        }
        return value.trim().replace("%", "%25").replace(":", "%3A");
    }
}
