package denny.ai.agent.trading.domain.node;

import cn.bugstack.wrench.design.framework.tree.StrategyHandler;
import com.alibaba.fastjson.JSON;
import denny.ai.agent.domain.model.entity.AutoAgentExecuteResultEntity;
import denny.ai.agent.domain.model.entity.ExecuteCommandEntity;
import denny.ai.agent.domain.service.auto.step.AbstractExecuteSupport;
import denny.ai.agent.domain.service.auto.step.factory.DefaultAutoAgentExecuteStrategyFactory;
import denny.ai.agent.domain.service.armory.factory.ArmoryObjectRegistry;
import denny.ai.agent.trading.api.provider.IStockDataProvider;
import denny.ai.agent.trading.api.vo.*;
import denny.ai.agent.trading.domain.config.TradingDriver;
import denny.ai.agent.trading.domain.prompt.AnalystPromptTemplate;
import denny.ai.agent.trading.domain.vo.TradingContextVO;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * 情绪分析师节点。
 */
@Slf4j
@Service
public class SentimentAnalystNode extends AbstractExecuteSupport {

    public static final String TRADING_CONTEXT_KEY = "trading_context";

    @Resource
    private IStockDataProvider dataProvider;

    @Resource
    private ArmoryObjectRegistry armoryObjectRegistry;

    @Override
    public String doApply(ExecuteCommandEntity requestParameter,
                           DefaultAutoAgentExecuteStrategyFactory.DynamicContext dynamicContext) throws Exception {
        log.info("=== 情绪分析师节点执行开始 ===");

        TradingContextVO context = dynamicContext.getValue(TRADING_CONTEXT_KEY);
        if (context == null || context.getStockInfo() == null) {
            log.error("交易上下文或股票信息为空");
            return "error: no trading context";
        }

        StockInfoVO stockInfo = context.getStockInfo();
        String ticker = stockInfo.getTicker();

        sendAnalystEvent(dynamicContext, "analyst_start", "情绪分析开始: " + ticker);

        SentimentDataVO sentimentData = dataProvider.getSentiment(ticker);

        log.info("获取情绪数据: ticker={}, overallScore={}, fearGreedIndex={}",
                ticker, sentimentData.getOverallScore(), sentimentData.getFearGreedIndex());

        sendAnalystEvent(dynamicContext, "analyst_progress", "已获取情绪数据，开始分析...");

        SentimentReportVO report = generateReport(stockInfo, sentimentData, dynamicContext);

        sendAnalystEvent(dynamicContext, "analyst_report", JSON.toJSONString(report));

        context.setSentimentReport(report);

        log.info("情绪分析完成: ticker={}, rating={}, sentimentScore={}",
                ticker, report.getRating(), report.getSentimentScore());

        if (TradingDriver.getCurrent() != null) {
            TradingDriver.getCurrent().analystComplete();
        }

        return "sentiment_analysis_completed";
    }

    @Override
    public StrategyHandler<ExecuteCommandEntity, DefaultAutoAgentExecuteStrategyFactory.DynamicContext, String> get(
            ExecuteCommandEntity requestParameter,
            DefaultAutoAgentExecuteStrategyFactory.DynamicContext dynamicContext) throws Exception {
        return null;
    }

    private SentimentReportVO generateReport(StockInfoVO stockInfo,
                                          SentimentDataVO sentimentData,
                                          DefaultAutoAgentExecuteStrategyFactory.DynamicContext dynamicContext) {
        String prompt = AnalystPromptTemplate.SENTIMENT_ANALYST_PROMPT.formatted(
                stockInfo.getTicker(),
                sentimentData.getOverallScore(),
                sentimentData.getSocialMediaScore(),
                sentimentData.getNewsScore(),
                sentimentData.getAnalystScore(),
                sentimentData.getBullRatio(),
                sentimentData.getBearRatio(),
                sentimentData.getFearGreedIndex(),
                sentimentData.getShortTermScore(),
                sentimentData.getMediumTermScore(),
                sentimentData.getLongTermScore()
        );

        ChatClient chatClient = getChatClientByClientId("6004", 0);

        long startAt = System.currentTimeMillis();
        log.info("情绪分析师调用LLM | prompt长度={}", prompt.length());
        if (!shouldContinueSse(dynamicContext)) {
            log.info("SSE已关闭，跳过情绪分析师LLM调用");
            return parseReport("", sentimentData);
        }
        String response = chatClient.prompt().user(prompt).call().content();
        long latencyMs = System.currentTimeMillis() - startAt;

        log.info("情绪分析师LLM响应 | prompt长度={} | 响应长度={} | 耗时={}ms",
                prompt.length(), response.length(), latencyMs);

        return parseReport(response, sentimentData);
    }

    private SentimentReportVO parseReport(String llmResponse, SentimentDataVO sentimentData) {
        int rating = calculateRating(sentimentData);

        return SentimentReportVO.builder()
                .rating(rating)
                .sentimentScore(sentimentData.getOverallScore())
                .keySentiments(extractKeySentiments(sentimentData))
                .summary(llmResponse)
                .build();
    }

    private int calculateRating(SentimentDataVO sentimentData) {
        int score = 0;

        Double overallScore = sentimentData.getOverallScore();
        if (overallScore != null) {
            if (overallScore > 0.6) score += 3;
            else if (overallScore > 0.4) score += 2;
            else if (overallScore > 0.2) score += 1;
        }

        Integer fearGreedIndex = sentimentData.getFearGreedIndex();
        if (fearGreedIndex != null) {
            if (fearGreedIndex >= 40 && fearGreedIndex <= 60) {
                score += 2;
            } else {
                score += 1;
            }
        }

        Double bullRatio = sentimentData.getBullRatio();
        if (bullRatio != null && bullRatio > 0.6) {
            score += 1;
        }

        return Math.max(1, Math.min(5, (score / 2) + 2));
    }

    private List<String> extractKeySentiments(SentimentDataVO sentimentData) {
        List<String> sentiments = new ArrayList<>();

        if (sentimentData.getOverallScore() != null) {
            String sentimentLevel;
            if (sentimentData.getOverallScore() > 0.6) {
                sentimentLevel = "偏乐观";
            } else if (sentimentData.getOverallScore() > 0.4) {
                sentimentLevel = "中性";
            } else if (sentimentData.getOverallScore() > 0.2) {
                sentimentLevel = "偏谨慎";
            } else {
                sentimentLevel = "偏悲观";
            }
            sentiments.add("市场整体情绪：" + sentimentLevel + " (得分：" + String.format("%.2f", sentimentData.getOverallScore()) + ")");
        }

        if (sentimentData.getFearGreedIndex() != null) {
            String fgLevel;
            int fg = sentimentData.getFearGreedIndex();
            if (fg >= 75) fgLevel = "极度贪婪";
            else if (fg >= 55) fgLevel = "贪婪";
            else if (fg >= 45) fgLevel = "中性";
            else if (fg >= 25) fgLevel = "恐惧";
            else fgLevel = "极度恐惧";
            sentiments.add("恐惧贪婪指数：" + fg + " (" + fgLevel + ")");
        }

        if (sentimentData.getSocialBuzz() != null && sentimentData.getSocialBuzz() > 7.0) {
            sentiments.add("社交媒体讨论热度较高 (得分：" + sentimentData.getSocialBuzz() + ")");
        }

        if (sentiments.isEmpty()) {
            sentiments.add("情绪数据处于正常范围");
        }

        return sentiments;
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
