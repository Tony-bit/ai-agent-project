package denny.ai.agent.trading.domain.execution;

import denny.ai.agent.trading.api.provider.IStockDataProvider;
import denny.ai.agent.trading.api.vo.*;
import lombok.extern.slf4j.Slf4j;

import java.util.List;
import java.util.Objects;

@Slf4j
public final class TargetBoundStockDataProvider {

    private final IStockDataProvider delegate;
    private final TargetContext target;

    private TargetBoundStockDataProvider(IStockDataProvider delegate, TargetContext target) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        this.target = Objects.requireNonNull(target,
                "IDENTITY_BOUNDARY_VIOLATION: targetContext is required");
    }

    public static TargetBoundStockDataProvider bind(IStockDataProvider delegate, TargetContext target) {
        return new TargetBoundStockDataProvider(delegate, target);
    }

    public StockInfoVO getStockInfo() {
        return delegate.getStockInfo(target.targetId());
    }

    public List<OHLCVBarVO> getHistoricalBars(String startDate, String endDate) {
        return delegate.getHistoricalBars(target.targetId(), startDate, endDate);
    }

    public TechnicalIndicatorsVO getTechnicalIndicators(String startDate, String endDate) {
        return delegate.getTechnicalIndicators(target.targetId(), startDate, endDate);
    }

    public FundamentalDataVO getFundamentalData() {
        return delegate.getFundamentalData(target.targetId());
    }

    public List<NewsItemVO> getNews(int limit) {
        return delegate.getNews(target.targetId(), limit);
    }

    public SentimentDataVO getSentiment() {
        return delegate.getSentiment(target.targetId());
    }

    public String effectiveTicker(String originalTicker) {
        if (!target.targetId().equalsIgnoreCase(String.valueOf(originalTicker))) {
            log.warn("TOOL_TARGET_OVERRIDDEN runId={} originalTicker={} effectiveTicker={}",
                    target.runId(), originalTicker, target.targetId());
        }
        return target.targetId();
    }
}
