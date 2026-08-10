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
import denny.ai.agent.trading.domain.prompt.AnalystPromptService;
import denny.ai.agent.trading.domain.execution.StructuredPayloadCodec;
import denny.ai.agent.trading.api.vo.payload.NewsAnalystPayload;
import denny.ai.agent.trading.domain.signal.NewsAnalysisPreprocessor;
import denny.ai.agent.trading.domain.vo.TradingContextVO;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Service;

import java.time.ZonedDateTime;
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

    @Resource private AnalystPromptService analystPromptService;
    @Resource private StructuredPayloadCodec structuredPayloadCodec;
    @Resource private NewsAnalysisPreprocessor newsAnalysisPreprocessor;

    @Override
    public String doApply(ExecuteCommandEntity requestParameter,
                           DefaultAutoAgentExecuteStrategyFactory.DynamicContext dynamicContext) throws Exception {
        log.info("=== 新闻分析师节点执行开始 ===");

        TradingContextVO context = dynamicContext.getValue(TRADING_CONTEXT_KEY);
        if (context == null || context.getStockInfo() == null) {
            log.error("交易上下文或股票信息为空");
            return "error: no trading context";
        }

        prepare(context, dynamicContext);
        return "news_analysis_prepared";
    }

    public NewsReportVO prepare(TradingContextVO context,
                                DefaultAutoAgentExecuteStrategyFactory.DynamicContext dynamicContext) {
        try {
            return prepareInternal(context, dynamicContext);
        } catch (RuntimeException error) {
            log.error("节点执行异常: nodeName=NewsAnalystNode, ticker={}",
                    tickerOf(context), error);
            throw error;
        }
    }

    private NewsReportVO prepareInternal(TradingContextVO context,
                                         DefaultAutoAgentExecuteStrategyFactory.DynamicContext dynamicContext) {
        if (context == null || context.getStockInfo() == null) {
            throw new IllegalArgumentException("trading context or stock info is missing");
        }
        StockInfoVO stockInfo = context.getStockInfo();
        String ticker = stockInfo.getTicker();

        sendAnalystEvent(dynamicContext, "analyst_start", "新闻分析开始: " + ticker);

        List<NewsItemVO> fetchedNews = denny.ai.agent.trading.domain.execution.TargetBoundStockDataProvider
                .bind(dataProvider, context.getTargetContext()).getNews(10);
        NewsAnalysisPreprocessor.Result preprocessing = newsAnalysisPreprocessor.prepare(
                fetchedNews,
                ZonedDateTime.now(NewsAnalysisPreprocessor.ANALYSIS_ZONE));
        List<NewsItemVO> newsItems = preprocessing.newsItems();

        log.info("新闻时间窗口过滤完成: ticker={}, fetched={}, retained={}, stale={}, future={}, unknownTime={}",
                ticker, fetchedNews == null ? 0 : fetchedNews.size(), newsItems.size(),
                preprocessing.staleCount(), preprocessing.futureCount(), preprocessing.unknownTimeCount());

        log.info("获取新闻数据: ticker={}, count={}", ticker, newsItems.size());

        sendAnalystEvent(dynamicContext, "analyst_progress", "已获取 " + newsItems.size() + " 条新闻，开始结构化分析...");

        NewsReportVO report = generateReport(stockInfo, newsItems, dynamicContext);

        log.info("新闻分析完成: ticker={}, rating={}, sentiment={}, confidence={}",
                ticker, report.getRating(), report.getOverallSentiment(), report.getConfidence());
        return report;
    }

    private String tickerOf(TradingContextVO context) {
        return context != null && context.getStockInfo() != null
                ? context.getStockInfo().getTicker() : "unknown";
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

        TradingContextVO context = dynamicContext.getValue(TRADING_CONTEXT_KEY);
        String prompt = analystPromptService.render("6005", context, dynamicContext,
                structuredNewsInput, NewsAnalystPayload.class);

        ChatClient chatClient = getChatClientByClientId("6005", 0);

        long startAt = System.currentTimeMillis();
        log.info("新闻分析师调用LLM | prompt长度={}", prompt.length());
        if (!shouldContinueSse(dynamicContext)) {
            throw new IllegalStateException("SSE已关闭，取消新闻分析师调用");
        }
        log.debug("LLM streaming input | operation=NewsAnalystNode | content=\n{}", prompt);
        String response = denny.ai.agent.trading.domain.execution.TradingLlmCallAudit.execute(
                context, "6005", "NewsAnalystNode",
                () -> collectStreamingResponse(denny.ai.agent.trading.domain.execution.TradingChatMemory.apply(
                        chatClient.prompt().user(prompt), context, dynamicContext, "NewsAnalystNode"),
                        "NewsAnalystNode", getSseEventSink(dynamicContext)));
        long latencyMs = System.currentTimeMillis() - startAt;

        log.info("新闻分析师LLM响应 | prompt长度={} | 响应长度={} | 耗时={}ms",
                prompt.length(), response.length(), latencyMs);

        if (denny.ai.agent.trading.domain.prompt.TradingPromptModeResolver.requireMode(dynamicContext)
                == denny.ai.agent.trading.domain.prompt.PromptContractMode.RELAXED_V3) {
            return NewsReportVO.builder().summary(response.trim()).newsItems(newsItems).build();
        }
        NewsAnalystPayload payload = structuredPayloadCodec.parse(response, NewsAnalystPayload.class);
        return toReport(payload, newsItems);
    }

    private NewsReportVO toReport(NewsAnalystPayload payload, List<NewsItemVO> newsItems) {
        return NewsReportVO.builder()
                .rating(payload.rating())
                .newsItems(newsItems)
                .overallSentiment(payload.overallSentiment())
                .summary(payload.summary())
                .confidence(payload.confidence())
                .enhancedSourceNewsIds(payload.enhancedSourceNewsIds())
                .targetEcho(payload.targetEcho())
                .deduplicatedEvents(payload.deduplicatedEvents().stream().map(event ->
                        NewsReportVO.NewsEventVO.builder()
                                .eventType(event.eventType()).eventTitle(event.eventTitle())
                                .sentiment(event.sentiment()).impactLevel(event.impactLevel())
                                .sourceNewsIds(event.sourceNewsIds())
                                .enhancedSourceNewsIds(event.enhancedSourceNewsIds())
                                .evidenceLevel(event.evidenceLevel()).evidenceQuality(event.evidenceQuality())
                                .summary(event.summary()).build()).toList())
                .newsThemes(payload.newsThemes().stream().map(theme ->
                        NewsReportVO.NewsThemeVO.builder()
                                .theme(theme.theme()).sentiment(theme.sentiment())
                                .impactLevel(theme.impactLevel()).evidenceIds(theme.evidenceIds())
                                .enhancedSourceNewsIds(theme.enhancedSourceNewsIds())
                                .evidenceLevel(theme.evidenceLevel()).evidenceQuality(theme.evidenceQuality())
                                .reason(theme.reason()).build()).toList())
                .riskWarnings(payload.riskWarnings().stream().map(risk ->
                        NewsReportVO.NewsRiskWarningVO.builder()
                                .risk(risk.risk()).impactLevel(risk.impactLevel())
                                .evidenceIds(risk.evidenceIds())
                                .enhancedSourceNewsIds(risk.enhancedSourceNewsIds())
                                .evidenceLevel(risk.evidenceLevel()).evidenceQuality(risk.evidenceQuality())
                                .reason(risk.reason()).build()).toList())
                .dataQuality(payload.dataQuality())
                .build();
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
