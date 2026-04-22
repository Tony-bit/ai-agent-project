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
import denny.ai.agent.trading.domain.prompt.AnalystPromptTemplate;
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
 * <p>
 * 职责：
 * 1. 调用 IStockDataProvider 获取 K 线数据和技术指标
 * 2. 使用 ChatClient + System Prompt 生成分析报告
 * 3. 生成 TechnicalReportVO 并写入 TradingContextVO
 * 4. 通过 sendSseResult() 发送流式进度事件
 */
@Slf4j
@Service
public class TechnicalAnalystNode extends AbstractExecuteSupport {

    public static final String TRADING_CONTEXT_KEY = "trading_context";
    public static final String TRADING_STEP_KEY = "trading_step";

    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    @Resource
    private IStockDataProvider dataProvider;

    @Resource
    private ArmoryObjectRegistry armoryObjectRegistry;

    @Override
    protected String doApply(ExecuteCommandEntity requestParameter,
                           DefaultAutoAgentExecuteStrategyFactory.DynamicContext dynamicContext) throws Exception {
        log.info("=== 技术分析师节点执行开始 ===");

        TradingContextVO context = dynamicContext.getValue(TRADING_CONTEXT_KEY);
        if (context == null || context.getStockInfo() == null) {
            log.error("交易上下文或股票信息为空");
            return "error: no trading context";
        }

        StockInfoVO stockInfo = context.getStockInfo();
        String ticker = stockInfo.getTicker();

        sendAnalystEvent(dynamicContext, "analyst_start", "技术分析开始: " + ticker);

        // 获取 K 线数据（最近 60 个交易日）
        LocalDate endDate = LocalDate.now();
        LocalDate startDate = endDate.minusDays(90);
        List<OHLCVBarVO> bars = dataProvider.getHistoricalBars(ticker,
                startDate.format(DATE_FMT), endDate.format(DATE_FMT));

        sendAnalystEvent(dynamicContext, "analyst_progress", "已获取 K 线数据 " + bars.size() + " 条");

        // 获取技术指标
        TechnicalIndicatorsVO indicators = dataProvider.getTechnicalIndicators(ticker,
                startDate.format(DATE_FMT), endDate.format(DATE_FMT));

        log.info("获取技术指标: ticker={}, RSI6={}, MACD={}",
                ticker, indicators.getRsi6(), indicators.getMacd());

        // 生成分析报告
        TechnicalReportVO report = generateReport(stockInfo, bars, indicators, dynamicContext);

        sendAnalystEvent(dynamicContext, "analyst_report", JSON.toJSONString(report));

        context.setTechnicalReport(report);

        log.info("技术分析完成: ticker={}, rating={}, trend={}",
                ticker, report.getRating(), report.getTrendSignal());

        dynamicContext.setValue(TRADING_STEP_KEY, "analyst_collection");

        return "technical_analysis_completed";
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
        // 计算布林带位置
        String bollPosition = calculateBollingerPosition(indicators);

        String prompt = AnalystPromptTemplate.TECHNICAL_ANALYST_PROMPT.formatted(
                stockInfo.getTicker(),
                stockInfo.getCurrentPrice(),
                indicators.getMa5(),
                indicators.getMa20(),
                indicators.getRsi6(),
                indicators.getRsi12(),
                indicators.getMacd(),
                indicators.getMacdSignal(),
                bollPosition,
                indicators.getVolumeRatio()
        );

        ChatClient chatClient = getChatClientByClientId("default", 0);

        long startAt = System.currentTimeMillis();
        String response = chatClient.prompt().user(prompt).call().content();
        long latencyMs = System.currentTimeMillis() - startAt;

        log.info("技术分析 LLM 响应耗时: {}ms", latencyMs);

        return parseReport(response, indicators);
    }

    private TechnicalReportVO parseReport(String llmResponse, TechnicalIndicatorsVO indicators) {
        String trendSignal = determineTrendSignal(indicators);
        int rating = calculateRating(indicators, trendSignal);

        return TechnicalReportVO.builder()
                .rating(rating)
                .trendSignal(trendSignal)
                .keyPatterns(extractKeyPatterns(indicators))
                .summary(llmResponse)
                .indicators(indicators)
                .build();
    }

    private String determineTrendSignal(TechnicalIndicatorsVO indicators) {
        BigDecimal price = indicators.getMa5(); // 使用当前价格
        BigDecimal bollUpper = indicators.getBollUpper();
        BigDecimal bollLower = indicators.getBollLower();
        BigDecimal bollMiddle = indicators.getBollMiddle();

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

        // RSI 评分
        Double rsi6 = indicators.getRsi6();
        if (rsi6 != null) {
            if (rsi6 >= 30 && rsi6 <= 70) {
                score += 2; // 正常区间
            } else if (rsi6 < 30) {
                score += 1; // 超卖，可能反弹
            } else {
                score += 1; // 超买
            }
        }

        // MACD 评分
        BigDecimal macdHist = indicators.getMacdHistogram();
        if (macdHist != null) {
            if (macdHist.compareTo(BigDecimal.ZERO) > 0) {
                score += 2; // 金叉
            } else {
                score += 1; // 死叉
            }
        }

        // 均线排列评分
        BigDecimal ma5 = indicators.getMa5();
        BigDecimal ma20 = indicators.getMa20();
        if (ma5 != null && ma20 != null && ma5.compareTo(ma20) > 0) {
            score += 2;
        }

        // 趋势评分
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

    /**
     * 计算布林带位置。
     */
    private String calculateBollingerPosition(TechnicalIndicatorsVO indicators) {
        BigDecimal price = indicators.getMa5(); // 使用当前价格
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
