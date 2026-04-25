package denny.ai.agent.trading.infra.provider;

import denny.ai.agent.trading.api.vo.NewsItemVO;

import java.util.List;

/**
 * 新闻搜索 Provider 接口。
 * <p>
 * 定义关键字搜索新闻的标准方法。
 * Phase 1-5 使用 {@link denny.ai.agent.trading.infra.provider.MockStockDataProvider} 中的空实现，
 * Phase 6 替换为 {@link SinaNewsDataProvider} 接入新浪财经。
 */
public interface INewsSearchProvider {

    /**
     * 按关键字搜索新闻。
     *
     * @param keyword 搜索关键字（如股票名称、行业主题、政策关键词）
     * @param limit   返回条数上限
     * @return 新闻列表，按发布时间倒序排列
     */
    List<NewsItemVO> searchNews(String keyword, int limit);
}
