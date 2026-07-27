package denny.ai.agent.trading.domain.validation;

import denny.ai.agent.trading.api.vo.StockIdentityVO;
import denny.ai.agent.trading.api.vo.TargetContext;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Immutable entity allow-list for one node invocation. */
public final class AllowedEntitySet {

    private static final Pattern STOCK_CODE = Pattern.compile(
            "(?<![0-9])([0-9]{6})(?:\\.(?:SH|SZ|BJ))?(?![0-9])",
            Pattern.CASE_INSENSITIVE);

    private final Set<String> allowedCodes;
    private final Set<String> allowedNames;
    private final Map<String, String> knownStockNames;
    private final Set<String> allowedGeneralEntities;

    private AllowedEntitySet(Builder builder) {
        this.allowedCodes = Set.copyOf(builder.allowedCodes);
        this.allowedNames = Set.copyOf(builder.allowedNames);
        this.knownStockNames = Map.copyOf(builder.knownStockNames);
        this.allowedGeneralEntities = Set.copyOf(builder.allowedGeneralEntities);
    }

    public static Builder forTarget(TargetContext targetContext) {
        return new Builder(targetContext);
    }

    public List<String> findForeignEntities(String output) {
        if (output == null || output.isBlank()) {
            return List.of();
        }
        Set<String> foreign = new LinkedHashSet<>();
        Matcher matcher = STOCK_CODE.matcher(output.toUpperCase(Locale.ROOT));
        while (matcher.find()) {
            String code = matcher.group(1);
            if (!allowedCodes.contains(code)) {
                foreign.add(matcher.group());
            }
        }
        knownStockNames.forEach((name, targetId) -> {
            if (output.contains(name) && !allowedNames.contains(name)) {
                foreign.add(name + "(" + targetId + ")");
            }
        });
        return List.copyOf(foreign);
    }

    public boolean isGeneralEntityAllowed(String entity) {
        return entity != null && allowedGeneralEntities.contains(normalize(entity));
    }

    public static final class Builder {
        private final Set<String> allowedCodes = new LinkedHashSet<>();
        private final Set<String> allowedNames = new LinkedHashSet<>();
        private final Map<String, String> knownStockNames = new LinkedHashMap<>();
        private final Set<String> allowedGeneralEntities = new LinkedHashSet<>();

        private Builder(TargetContext targetContext) {
            Objects.requireNonNull(targetContext, "targetContext");
            allowStock(new StockIdentityVO(
                    targetContext.targetId(), targetContext.stockName(), targetContext.industry()));
            if (targetContext.industry() != null) {
                allowGeneralEntity(targetContext.industry());
            }
        }

        public Builder registerKnownStocks(Collection<StockIdentityVO> identities) {
            Objects.requireNonNull(identities, "identities").forEach(this::registerKnownStock);
            return this;
        }

        public Builder registerKnownStock(StockIdentityVO identity) {
            Objects.requireNonNull(identity, "identity");
            knownStockNames.put(normalize(identity.stockName()), canonicalTargetId(identity.targetId()));
            return this;
        }

        public Builder allowStock(StockIdentityVO identity) {
            registerKnownStock(identity);
            allowedCodes.add(stockCode(identity.targetId()));
            allowedNames.add(normalize(identity.stockName()));
            return this;
        }

        public Builder allowStockCode(String targetIdOrCode) {
            String normalized = normalize(targetIdOrCode).toUpperCase(Locale.ROOT);
            if (normalized.matches("[0-9]{6}")) {
                allowedCodes.add(normalized);
            } else {
                allowedCodes.add(stockCode(normalized));
            }
            return this;
        }

        public Builder allowGeneralEntity(String entity) {
            allowedGeneralEntities.add(normalize(entity));
            return this;
        }

        public AllowedEntitySet build() {
            return new AllowedEntitySet(this);
        }
    }

    private static String canonicalTargetId(String targetId) {
        String normalized = normalize(targetId).toUpperCase(Locale.ROOT);
        if (!normalized.matches("[0-9]{6}\\.(SH|SZ|BJ)")) {
            throw new IllegalArgumentException("targetId must be a canonical A-share ts_code");
        }
        return normalized;
    }

    private static String stockCode(String targetId) {
        return canonicalTargetId(targetId).substring(0, 6);
    }

    private static String normalize(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("entity value must not be blank");
        }
        return value.trim();
    }
}
