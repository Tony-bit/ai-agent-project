package denny.ai.agent.trading.domain.service;

import denny.ai.agent.trading.api.provider.IStockDataProvider;
import denny.ai.agent.trading.api.vo.*;

import java.util.List;

abstract class StubStockDataProvider implements IStockDataProvider {
    @Override public StockInfoVO getStockInfo(String ticker) { return null; }
    @Override public List<OHLCVBarVO> getHistoricalBars(String ticker, String startDate, String endDate) { return List.of(); }
    @Override public TechnicalIndicatorsVO getTechnicalIndicators(String ticker, String startDate, String endDate) { return null; }
    @Override public FundamentalDataVO getFundamentalData(String ticker) { return null; }
    @Override public List<NewsItemVO> getNews(String ticker, int limit) { return List.of(); }
    @Override public SentimentDataVO getSentiment(String ticker) { return null; }
    @Override public List<StockSearchResultVO> searchByName(String name) { return List.of(); }
}
