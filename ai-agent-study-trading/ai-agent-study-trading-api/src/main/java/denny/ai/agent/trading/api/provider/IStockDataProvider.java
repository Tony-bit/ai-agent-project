package denny.ai.agent.trading.api.provider;

import denny.ai.agent.trading.api.vo.*;

import java.util.List;

/**
 * 股票数据 Provider 接口。
 * <p>
 * 定义获取股票数据的标准方法，Phase 1-5 使用 {@link denny.ai.agent.trading.infra.provider.MockStockDataProvider} 返回 Mock 数据，
 * Phase 6 替换为 {@code TushareStockDataProvider} 获取真实数据。
 * <p>
 * 接口不变，实现可替换。
 */
public interface IStockDataProvider {

    /**
     * 获取股票基本信息。
     *
     * @param ticker 股票代码，如 NVDA、AAPL
     * @return 股票基本信息
     */
    StockInfoVO getStockInfo(String ticker);

    /**
     * 获取历史 K 线数据。
     *
     * @param ticker    股票代码
     * @param startDate 开始日期，格式 yyyy-MM-dd
     * @param endDate   结束日期，格式 yyyy-MM-dd
     * @return K线数据列表
     */
    List<OHLCVBarVO> getHistoricalBars(String ticker, String startDate, String endDate);

    /**
     * 获取技术指标数据。
     *
     * @param ticker    股票代码
     * @param startDate 开始日期，格式 yyyy-MM-dd
     * @param endDate   结束日期，格式 yyyy-MM-dd
     * @return 技术指标数据
     */
    TechnicalIndicatorsVO getTechnicalIndicators(String ticker, String startDate, String endDate);

    /**
     * 获取基本面数据。
     *
     * @param ticker 股票代码
     * @return 基本面数据
     */
    FundamentalDataVO getFundamentalData(String ticker);

    /**
     * 获取新闻列表。
     *
     * @param ticker 股票代码
     * @param limit  返回条数上限
     * @return 新闻列表
     */
    List<NewsItemVO> getNews(String ticker, int limit);

    /**
     * 获取情绪数据。
     *
     * @param ticker 股票代码
     * @return 情绪数据
     */
    SentimentDataVO getSentiment(String ticker);

    /**
     * 根据股票名称搜索股票代码。
     * <p>
     * 调用 Tushare stock_basic 接口的 name 参数进行模糊匹配。
     *
     * @param name 股票名称（支持模糊匹配，如"药明康德"）
     * @return 匹配的股票列表
     */
    List<StockSearchResultVO> searchByName(String name);
}
