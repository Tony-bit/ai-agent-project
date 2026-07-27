package denny.ai.agent.trading.domain.service;

import denny.ai.agent.trading.api.provider.IStockDataProvider;
import denny.ai.agent.trading.api.vo.StockIdentityVO;
import denny.ai.agent.trading.api.vo.TargetContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;

/** 将路由候选解析为 Tushare 权威标的身份。 */
@Service
public class TargetContextFactory {

    private final IStockDataProvider stockDataProvider;
    private final Supplier<UUID> runIdSupplier;

    @Autowired
    public TargetContextFactory(IStockDataProvider stockDataProvider) {
        this(stockDataProvider, UUID::randomUUID);
    }

    TargetContextFactory(IStockDataProvider stockDataProvider, Supplier<UUID> runIdSupplier) {
        this.stockDataProvider = Objects.requireNonNull(stockDataProvider, "stockDataProvider must not be null");
        this.runIdSupplier = Objects.requireNonNull(runIdSupplier, "runIdSupplier must not be null");
    }

    public TargetContext create(String candidateTicker, LocalDate asOfDate) {
        String normalizedTicker = normalizeCandidate(candidateTicker);
        Objects.requireNonNull(asOfDate, "asOfDate must not be null");

        List<StockIdentityVO> identities = stockDataProvider.findStockIdentities(normalizedTicker);
        if (identities == null || identities.size() != 1) {
            int count = identities == null ? 0 : identities.size();
            throw new IllegalStateException("stock identity must resolve to exactly one record: count=" + count);
        }

        StockIdentityVO identity = identities.get(0);
        validateIdentity(normalizedTicker, identity);
        return new TargetContext(runIdSupplier.get().toString(), identity.targetId(),
                identity.stockName(), identity.industry(), asOfDate);
    }

    private String normalizeCandidate(String candidateTicker) {
        if (candidateTicker == null || candidateTicker.isBlank()) {
            throw new IllegalArgumentException("candidate ticker must not be blank");
        }
        String normalized = candidateTicker.trim().toUpperCase(Locale.ROOT);
        if (!normalized.matches("^[0-9]{6}(\\.(SH|SZ|BJ))?$")) {
            throw new IllegalArgumentException("candidate ticker must be a 6-digit A-share code");
        }
        return normalized;
    }

    private void validateIdentity(String candidateTicker, StockIdentityVO identity) {
        if (identity == null) {
            throw new IllegalStateException("stock identity record must not be null");
        }
        String targetId = identity.targetId();
        if (targetId == null || !targetId.matches("^[0-9]{6}\\.(SH|SZ|BJ)$")) {
            throw new IllegalStateException("stock identity returned an invalid targetId");
        }
        boolean matches = candidateTicker.length() == 6
                ? targetId.startsWith(candidateTicker + ".")
                : targetId.equals(candidateTicker);
        if (!matches) {
            throw new IllegalStateException("route candidate does not match authoritative targetId");
        }
        if (identity.stockName() == null || identity.stockName().isBlank()) {
            throw new IllegalStateException("stock identity returned a blank stockName");
        }
    }
}
