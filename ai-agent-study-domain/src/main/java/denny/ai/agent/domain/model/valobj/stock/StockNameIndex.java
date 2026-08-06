package denny.ai.agent.domain.model.valobj.stock;

import lombok.Getter;

import java.text.Normalizer;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Immutable in-memory index for stock name lookup.
 */
@Getter
public class StockNameIndex {

    private static final Pattern WHITESPACE_PATTERN = Pattern.compile("\\s+");
    private static final Comparator<StockNameRecord> RECORD_ORDER =
            Comparator.comparing(StockNameRecord::getStockName)
                    .thenComparing(StockNameRecord::getStockCode);

    private final List<StockNameRecord> records;
    private final Map<String, List<StockNameRecord>> exactNameMap;
    private final Instant loadedAt;
    private final Instant expiresAt;

    private StockNameIndex(List<StockNameRecord> records,
                           Map<String, List<StockNameRecord>> exactNameMap,
                           Instant loadedAt,
                           Instant expiresAt) {
        this.records = records;
        this.exactNameMap = exactNameMap;
        this.loadedAt = loadedAt;
        this.expiresAt = expiresAt;
    }

    public static StockNameIndex of(List<StockNameRecord> sourceRecords) {
        return of(sourceRecords, null, null);
    }

    public static StockNameIndex of(List<StockNameRecord> sourceRecords, Instant loadedAt, Instant expiresAt) {
        Objects.requireNonNull(sourceRecords, "sourceRecords");
        if ((loadedAt == null) != (expiresAt == null)) {
            throw new IllegalArgumentException("loadedAt and expiresAt must both be null or both be non-null");
        }
        if (loadedAt != null && !loadedAt.isBefore(expiresAt)) {
            throw new IllegalArgumentException("expiresAt must be after loadedAt");
        }

        List<StockNameRecord> orderedRecords = sourceRecords.stream()
                .map(StockNameIndex::copyRecord)
                .sorted(RECORD_ORDER)
                .toList();

        Map<String, List<StockNameRecord>> mutableExactNameMap = new LinkedHashMap<>();
        for (StockNameRecord record : orderedRecords) {
            mutableExactNameMap.computeIfAbsent(normalize(record.getStockName()), ignored -> new ArrayList<>())
                    .add(record);
        }

        Map<String, List<StockNameRecord>> immutableExactNameMap = new LinkedHashMap<>();
        for (Map.Entry<String, List<StockNameRecord>> entry : mutableExactNameMap.entrySet()) {
            immutableExactNameMap.put(entry.getKey(), List.copyOf(entry.getValue()));
        }

        return new StockNameIndex(
                List.copyOf(orderedRecords),
                Collections.unmodifiableMap(immutableExactNameMap),
                loadedAt,
                expiresAt);
    }

    public List<StockNameRecord> findExact(String stockNameQuery) {
        String normalizedQuery = normalize(stockNameQuery);
        if (normalizedQuery.isEmpty()) {
            return List.of();
        }
        return exactNameMap.getOrDefault(normalizedQuery, List.of());
    }

    public SearchResult findFuzzy(String stockNameQuery, int maxCandidates) {
        if (maxCandidates <= 0) {
            throw new IllegalArgumentException("maxCandidates must be greater than 0");
        }

        String normalizedQuery = normalize(stockNameQuery);
        if (normalizedQuery.isEmpty()) {
            return new SearchResult(List.of(), 0);
        }

        List<StockNameRecord> matches = new ArrayList<>();
        int totalMatches = 0;
        for (StockNameRecord record : records) {
            if (!normalize(record.getStockName()).contains(normalizedQuery)) {
                continue;
            }
            totalMatches++;
            if (matches.size() < maxCandidates) {
                matches.add(record);
            }
        }
        return new SearchResult(List.copyOf(matches), totalMatches);
    }

    public static String normalize(String value) {
        if (value == null) {
            return "";
        }
        String normalized = Normalizer.normalize(value, Normalizer.Form.NFKC);
        normalized = WHITESPACE_PATTERN.matcher(normalized).replaceAll("");
        return normalized.toUpperCase(Locale.ROOT);
    }

    private static StockNameRecord copyRecord(StockNameRecord record) {
        if (record == null) {
            throw new IllegalArgumentException("stock name record must not be null");
        }
        if (record.getStockName() == null || record.getStockName().isBlank()) {
            throw new IllegalArgumentException("stock name must not be blank");
        }
        if (record.getStockCode() == null || !record.getStockCode().matches("^\\d{6}$")) {
            throw new IllegalArgumentException("stock code must be six digits");
        }
        return StockNameRecord.builder()
                .stockName(record.getStockName())
                .stockCode(record.getStockCode())
                .build();
    }

    public record SearchResult(List<StockNameRecord> candidates, int totalMatches) {
    }
}
