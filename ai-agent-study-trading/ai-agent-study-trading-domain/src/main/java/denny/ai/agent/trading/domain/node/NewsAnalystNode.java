package denny.ai.agent.trading.domain.node;

import cn.bugstack.wrench.design.framework.tree.StrategyHandler;
import com.alibaba.fastjson.JSON;
import denny.ai.agent.domain.model.entity.AutoAgentExecuteResultEntity;
import denny.ai.agent.domain.model.entity.ExecuteCommandEntity;
import denny.ai.agent.domain.service.auto.step.AbstractExecuteSupport;
import denny.ai.agent.domain.service.auto.step.factory.DefaultAutoAgentExecuteStrategyFactory;
import denny.ai.agent.domain.service.armory.factory.ArmoryObjectRegistry;
import denny.ai.agent.trading.api.provider.IStockDataProvider;
import denny.ai.agent.trading.api.vo.NewsItemVO;
import denny.ai.agent.trading.api.vo.NewsReportVO;
import denny.ai.agent.trading.api.vo.StockInfoVO;
import denny.ai.agent.trading.domain.config.TradingDriver;
import denny.ai.agent.trading.domain.prompt.AnalystPromptTemplate;
import denny.ai.agent.trading.domain.vo.TradingContextVO;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 新闻分析师节点。
 */
@Slf4j
@Service
public class NewsAnalystNode extends AbstractExecuteSupport {

    public static final String TRADING_CONTEXT_KEY = "trading_context";

    private final NewsAnalysisStructuredProcessor structuredProcessor = new NewsAnalysisStructuredProcessor();

    @Resource
    private IStockDataProvider dataProvider;

    @Resource
    private ArmoryObjectRegistry armoryObjectRegistry;

    @Override
    public String doApply(ExecuteCommandEntity requestParameter,
                           DefaultAutoAgentExecuteStrategyFactory.DynamicContext dynamicContext) throws Exception {
        log.info("=== 新闻分析师节点执行开始 ===");

        TradingContextVO context = dynamicContext.getValue(TRADING_CONTEXT_KEY);
        if (context == null || context.getStockInfo() == null) {
            log.error("交易上下文或股票信息为空");
            return "error: no trading context";
        }

        StockInfoVO stockInfo = context.getStockInfo();
        String ticker = stockInfo.getTicker();

        sendAnalystEvent(dynamicContext, "analyst_start", "新闻分析开始: " + ticker);

        List<NewsItemVO> newsItems = dataProvider.getNews(ticker, 10);

        log.info("获取新闻数据: ticker={}, count={}", ticker, newsItems.size());

        sendAnalystEvent(dynamicContext, "analyst_progress", "已获取 " + newsItems.size() + " 条新闻，开始结构化分析...");

        NewsReportVO report = generateReport(stockInfo, newsItems, dynamicContext);

        sendAnalystEvent(dynamicContext, "analyst_report", JSON.toJSONString(report));

        context.setNewsReport(report);

        log.info("新闻分析完成: ticker={}, rating={}, sentiment={}, confidence={}",
                ticker, report.getRating(), report.getOverallSentiment(), report.getConfidence());

        if (TradingDriver.getCurrent() != null) {
            TradingDriver.getCurrent().analystComplete();
        }

        return "news_analysis_completed";
    }

    @Override
    public StrategyHandler<ExecuteCommandEntity, DefaultAutoAgentExecuteStrategyFactory.DynamicContext, String> get(
            ExecuteCommandEntity requestParameter,
            DefaultAutoAgentExecuteStrategyFactory.DynamicContext dynamicContext) throws Exception {
        return null;
    }

    private NewsReportVO generateReport(StockInfoVO stockInfo,
                                        List<NewsItemVO> newsItems,
                                        DefaultAutoAgentExecuteStrategyFactory.DynamicContext dynamicContext) {
        String structuredNewsInput = structuredProcessor.buildLlmInput(stockInfo.getTicker(), stockInfo.getName(), newsItems);

        String prompt = AnalystPromptTemplate.NEWS_ANALYST_STRUCTURED_PROMPT
                .replaceFirst("%s", stockInfo.getTicker())
                .replaceFirst("%s", structuredNewsInput);

        ChatClient chatClient = getChatClientByClientId("6005", 0);

        long startAt = System.currentTimeMillis();
        log.info("新闻分析师调用LLM | prompt长度={}", prompt.length());
        String response = chatClient.prompt().user(prompt).call().content();
        long latencyMs = System.currentTimeMillis() - startAt;

        log.info("新闻分析师LLM响应 | prompt长度={} | 响应长度={} | 耗时={}ms",
                prompt.length(), response.length(), latencyMs);

        return structuredProcessor.parseReport(response, newsItems);
    }

    private void sendAnalystEvent(DefaultAutoAgentExecuteStrategyFactory.DynamicContext dynamicContext,
                                  String subType, String content) {
        AutoAgentExecuteResultEntity event = AutoAgentExecuteResultEntity.builder()
                .type("analyst")
                .subType(subType)
                .step(dynamicContext.getStep())
                .content(content)
                .completed(false)
                .timestamp(System.currentTimeMillis())
                .build();

        sendSseResult(dynamicContext, event);
    }
}
