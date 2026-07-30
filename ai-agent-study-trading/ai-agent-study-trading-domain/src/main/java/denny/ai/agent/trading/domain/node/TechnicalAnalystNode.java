package denny.ai.agent.trading.domain.node;

import com.alibaba.fastjson.JSON;
import denny.ai.agent.domain.model.entity.AutoAgentExecuteResultEntity;
import denny.ai.agent.domain.model.entity.ExecuteCommandEntity;
import denny.ai.agent.domain.service.auto.step.AbstractExecuteSupport;
import denny.ai.agent.domain.service.auto.step.factory.DefaultAutoAgentExecuteStrategyFactory;
import cn.bugstack.wrench.design.framework.tree.StrategyHandler;
import denny.ai.agent.domain.service.armory.factory.ArmoryObjectRegistry;
import denny.ai.agent.trading.api.provider.IStockDataProvider;
import denny.ai.agent.trading.api.vo.*;
import denny.ai.agent.trading.domain.config.TradingDriver;
import denny.ai.agent.trading.domain.prompt.AnalystPromptTemplate;
import denny.ai.agent.trading.domain.prompt.AnalystPromptService;
import denny.ai.agent.trading.domain.execution.StructuredPayloadCodec;
import denny.ai.agent.trading.api.vo.payload.TechnicalAnalystPayload;
import denny.ai.agent.trading.domain.vo.TradingContextVO;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * 技术分析师节点。
 */
@Slf4j
@Service
public class TechnicalAnalystNode extends AbstractExecuteSupport {

    public static final String TRADING_CONTEXT_KEY = "trading_context";

    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    @Resource
    private IStockDataProvider dataProvider;

    @Resource
    private ArmoryObjectRegistry armoryObjectRegistry;

    @Resource private AnalystPromptService analystPromptService;
    @Resource private StructuredPayloadCodec structuredPayloadCodec;

    @Override
    public String doApply(ExecuteCommandEntity requestParameter,
                           DefaultAutoAgentExecuteStrategyFactory.DynamicContext dynamicContext) throws Exception {
        log.info("=== 技术分析师节点执行开始 ===");

        TradingContextVO context = dynamicContext.getValue(TRADING_CONTEXT_KEY);
        if (context == null || context.getStockInfo() == null) {
            log.error("交易上下文或股票信息为空");
            return "error: no trading context";
        }

        prepare(context, dynamicContext);
        return "technical_analysis_prepared";
    }

    public TechnicalReportVO prepare(TradingContextVO context,
                                     DefaultAutoAgentExecuteStrategyFactory.DynamicContext dynamicContext) {
        try {
            return prepareInternal(context, dynamicContext);
        } catch (RuntimeException error) {
            log.error("节点执行异常: nodeName=TechnicalAnalystNode, ticker={}",
                    tickerOf(context), error);
            throw error;
        }
    }

    private TechnicalReportVO prepareInternal(TradingContextVO context,
                                               DefaultAutoAgentExecuteStrategyFactory.DynamicContext dynamicContext) {
        if (context == null || context.getStockInfo() == null) {
            throw new IllegalArgumentException("trading context or stock info is missing");
        }
        StockInfoVO stockInfo = context.getStockInfo();
        String ticker = stockInfo.getTicker();

        sendAnalystEvent(dynamicContext, "analyst_start", "技术分析开始: " + ticker);

        LocalDate endDate = LocalDate.now();
        LocalDate startDate = endDate.minusDays(90);
        var boundProvider = denny.ai.agent.trading.domain.execution.TargetBoundStockDataProvider
                .bind(dataProvider, context.getTargetContext());
        List<OHLCVBarVO> bars = boundProvider.getHistoricalBars(
                startDate.format(DATE_FMT), endDate.format(DATE_FMT));

        sendAnalystEvent(dynamicContext, "analyst_progress", "已获取 K 线数据 " + bars.size() + " 条");

        TechnicalIndicatorsVO indicators = boundProvider.getTechnicalIndicators(
                startDate.format(DATE_FMT), endDate.format(DATE_FMT));

        log.info("获取技术指标: ticker={}, RSI6={}, MACD={}",
                ticker, indicators.getRsi6(), indicators.getMacd());

        TechnicalReportVO report = generateReport(stockInfo, bars, indicators, dynamicContext);

        log.info("技术分析完成: ticker={}, rating={}, trend={}",
                ticker, report.getRating(), report.getTrendSignal());
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

    private TechnicalReportVO generateReport(StockInfoVO stockInfo,
                                           List<OHLCVBarVO> bars,
                                           TechnicalIndicatorsVO indicators,
                                           DefaultAutoAgentExecuteStrategyFactory.DynamicContext dynamicContext) {
        TradingContextVO context = dynamicContext.getValue(TRADING_CONTEXT_KEY);
        String stockData = structuredPayloadCodec.toJson(java.util.Map.of(
                "stockInfo", stockInfo,
                "bars", bars,
                "indicators", indicators));
        String prompt = analystPromptService.render("6003", context, dynamicContext,
                stockData, TechnicalAnalystPayload.class);

        ChatClient chatClient = getChatClientByClientId("6003", 0);

        long startAt = System.currentTimeMillis();
        log.info("技术分析师调用LLM | prompt长度={}", prompt.length());
        if (!shouldContinueSse(dynamicContext)) {
            throw new IllegalStateException("SSE已关闭，取消技术分析师调用");
        }
        log.debug("LLM streaming input | operation=TechnicalAnalystNode | content=\n{}", prompt);
        String response = denny.ai.agent.trading.domain.execution.TradingLlmCallAudit.execute(
                context, "6003", "TechnicalAnalystNode",
                () -> collectStreamingResponse(denny.ai.agent.trading.domain.execution.TradingChatMemory.apply(
                        chatClient.prompt().user(prompt), context, dynamicContext, "TechnicalAnalystNode"),
                        "TechnicalAnalystNode", getSseEventSink(dynamicContext)));
        long latencyMs = System.currentTimeMillis() - startAt;

        log.info("技术分析师LLM响应 | prompt长度={} | 响应长度={} | 耗时={}ms",
                prompt.length(), response.length(), latencyMs);

        if (denny.ai.agent.trading.domain.prompt.TradingPromptModeResolver.requireMode(dynamicContext)
                == denny.ai.agent.trading.domain.prompt.PromptContractMode.RELAXED_V3) {
            return TechnicalReportVO.builder().summary(response.trim()).indicators(indicators).build();
        }
        return parseReport(response, indicators);
    }

    private TechnicalReportVO parseReport(String llmResponse, TechnicalIndicatorsVO indicators) {
        TechnicalAnalystPayload payload = structuredPayloadCodec.parse(
                llmResponse, TechnicalAnalystPayload.class);
        return TechnicalReportVO.builder()
                .rating(payload.rating())
                .trendSignal(payload.trendSignal())
                .keyPatterns(payload.keyPatterns())
                .summary(payload.summary())
                .indicators(indicators)
                .targetEcho(payload.targetEcho())
                .build();
    }

    private String determineTrendSignal(TechnicalIndicatorsVO indicators) {
        BigDecimal price = indicators.getMa5();
        BigDecimal bollUpper = indicators.getBollUpper();
        BigDecimal bollLower = indicators.getBollLower();

        if (price == null || bollUpper == null || bollLower == null || bollLower.compareTo(bollUpper) >= 0) {
            return "震荡";
        }

        BigDecimal bandWidth = bollUpper.subtract(bollLower);
        if (bandWidth.compareTo(BigDecimal.ZERO) == 0) {
            return "震荡";
        }

        BigDecimal position = price.subtract(bollLower).divide(bandWidth, 4, java.math.RoundingMode.HALF_UP)
                .multiply(BigDecimal.valueOf(100));

        return position.setScale(0, java.math.RoundingMode.HALF_UP).toString();
    }

    private int calculateRating(TechnicalIndicatorsVO indicators, String trendSignal) {
        int score = 0;

        Double rsi6 = indicators.getRsi6();
        if (rsi6 != null) {
            if (rsi6 >= 30 && rsi6 <= 70) {
                score += 2;
            } else if (rsi6 < 30) {
                score += 1;
            } else {
                score += 1;
            }
        }

        BigDecimal macdHist = indicators.getMacdHistogram();
        if (macdHist != null) {
            if (macdHist.compareTo(BigDecimal.ZERO) > 0) {
                score += 2;
            } else {
                score += 1;
            }
        }

        BigDecimal ma5 = indicators.getMa5();
        BigDecimal ma20 = indicators.getMa20();
        if (ma5 != null && ma20 != null && ma5.compareTo(ma20) > 0) {
            score += 2;
        }

        if ("上涨".equals(trendSignal)) {
            score += 2;
        } else if ("震荡".equals(trendSignal)) {
            score += 1;
        }

        return Math.max(1, Math.min(5, (score / 2) + 2));
    }

    private List<String> extractKeyPatterns(TechnicalIndicatorsVO indicators) {
        List<String> patterns = new ArrayList<>();

        if (indicators.getRsi6() != null) {
            if (indicators.getRsi6() < 30) {
                patterns.add("RSI 超卖区域，可能存在反弹机会");
            } else if (indicators.getRsi6() > 70) {
                patterns.add("RSI 超买区域，注意回调风险");
            }
        }

        BigDecimal macdHist = indicators.getMacdHistogram();
        if (macdHist != null) {
            if (macdHist.compareTo(BigDecimal.ZERO) > 0) {
                patterns.add("MACD Histogram 为正，动能偏多");
            } else {
                patterns.add("MACD Histogram 为负，动能偏空");
            }
        }

        if (indicators.getVolumeRatio() != null && indicators.getVolumeRatio() > 1.5) {
            patterns.add("成交量放大，波动加剧");
        }

        if (patterns.isEmpty()) {
            patterns.add("技术指标处于正常范围");
        }

        return patterns;
    }

    private String calculateBollingerPosition(TechnicalIndicatorsVO indicators) {
        BigDecimal price = indicators.getMa5();
        BigDecimal bollUpper = indicators.getBollUpper();
        BigDecimal bollLower = indicators.getBollLower();

        if (price == null || bollUpper == null || bollLower == null || bollLower.compareTo(bollUpper) >= 0) {
            return "50";
        }

        BigDecimal bandWidth = bollUpper.subtract(bollLower);
        if (bandWidth.compareTo(BigDecimal.ZERO) == 0) {
            return "50";
        }

        BigDecimal position = price.subtract(bollLower).divide(bandWidth, 4, java.math.RoundingMode.HALF_UP)
                .multiply(BigDecimal.valueOf(100));

        return position.setScale(0, java.math.RoundingMode.HALF_UP).toString();
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
