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
import denny.ai.agent.trading.domain.vo.TradingContextVO;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Service;

/**
 * 基本面分析师节点。
 * <p>
 * 职责：
 * 1. 调用 IStockDataProvider.getFundamentalData() 获取财务数据
 * 2. 使用 ChatClient + System Prompt 生成分析报告
 * 3. 生成 FundamentalReportVO 并写入 TradingContextVO
 * 4. 通过 sendSseResult() 发送流式进度事件
 * 5. 末尾调用 TradingDriver.getCurrent().analystComplete() 驱动状态机
 */
@Slf4j
@Service
public class FundamentalAnalystNode extends AbstractExecuteSupport {

    public static final String TRADING_CONTEXT_KEY = "trading_context";

    @Resource
    private IStockDataProvider dataProvider;

    @Resource
    private ArmoryObjectRegistry armoryObjectRegistry;

    @Override
    public String doApply(ExecuteCommandEntity requestParameter,
                           DefaultAutoAgentExecuteStrategyFactory.DynamicContext dynamicContext) throws Exception {
        log.info("=== 基本面分析师节点执行开始 ===");

        TradingContextVO context = dynamicContext.getValue(TRADING_CONTEXT_KEY);
        if (context == null || context.getStockInfo() == null) {
            log.error("交易上下文或股票信息为空");
            return "error: no trading context";
        }

        StockInfoVO stockInfo = context.getStockInfo();
        String ticker = stockInfo.getTicker();

        sendAnalystEvent(dynamicContext, "analyst_start", "基本面分析开始: " + ticker);

        FundamentalDataVO fundamentalData = dataProvider.getFundamentalData(ticker);

        log.info("获取基本面数据: ticker={}, pe={}, roe={}, grossMargin={}",
                ticker, fundamentalData.getPeRatio(), fundamentalData.getRoe(), fundamentalData.getGrossMargin());

        sendAnalystEvent(dynamicContext, "analyst_progress", "已获取基本面数据，开始分析...");

        FundamentalReportVO report = generateReport(stockInfo, fundamentalData, dynamicContext);

        sendAnalystEvent(dynamicContext, "analyst_report", JSON.toJSONString(report));

        context.setFundamentalReport(report);

        log.info("基本面分析完成: ticker={}, rating={}", ticker, report.getRating());

        if (TradingDriver.getCurrent() != null) {
            TradingDriver.getCurrent().analystComplete();
        }

        return "fundamental_analysis_completed";
    }

    @Override
    public StrategyHandler<ExecuteCommandEntity, DefaultAutoAgentExecuteStrategyFactory.DynamicContext, String> get(
            ExecuteCommandEntity requestParameter,
            DefaultAutoAgentExecuteStrategyFactory.DynamicContext dynamicContext) throws Exception {
        return null;
    }

    private FundamentalReportVO generateReport(StockInfoVO stockInfo,
                                             FundamentalDataVO data,
                                             DefaultAutoAgentExecuteStrategyFactory.DynamicContext dynamicContext) {
        String prompt = AnalystPromptTemplate.FUNDAMENTAL_ANALYST_PROMPT.formatted(
                stockInfo.getTicker(),
                stockInfo.getName(),
                stockInfo.getCurrentPrice(),
                nullToDefault(stockInfo.getPeRatio()),
                nullToDefault(data.getRevenueGrowth()),
                nullToDefault(data.getNetIncomeGrowth()),
                data.getRoe(),
                data.getGrossMargin(),
                data.getNetMargin(),
                data.getDebtToEquity(),
                data.getCurrentRatio(),
                nullToDefault(data.getFreeCashFlow())
        );

        ChatClient chatClient = getChatClientByClientId("6002", 0);

        long startAt = System.currentTimeMillis();
        String response = chatClient.prompt().user(prompt).call().content();
        long latencyMs = System.currentTimeMillis() - startAt;

        log.info("基本面分析 LLM 响应耗时: {}ms", latencyMs);

        return parseReport(response, data);
    }

    private FundamentalReportVO parseReport(String llmResponse, FundamentalDataVO rawData) {
        Integer jsonRating = parseJsonRating(llmResponse);
        int rating = (jsonRating != null) ? jsonRating : calculateRating(rawData);

        return FundamentalReportVO.builder()
                .rating(rating)
                .keyFindings(extractKeyFindings(llmResponse, rawData))
                .riskWarnings(extractRiskWarnings(llmResponse, rawData))
                .summary(extractSummary(llmResponse))
                .rawData(rawData)
                .build();
    }

    private java.util.List<String> extractKeyFindings(String llmResponse, FundamentalDataVO rawData) {
        java.util.List<String> findings = new java.util.ArrayList<>();
        try {
            String json = extractJson(llmResponse);
            if (json != null) {
                com.alibaba.fastjson.JSONObject obj = JSON.parseObject(json);
                if (obj != null && obj.containsKey("keyFindings")) {
                    return obj.getJSONArray("keyFindings").toJavaList(String.class);
                }
            }
        } catch (Exception e) {
            log.debug("Failed to parse keyFindings from LLM response: {}", e.getMessage());
        }
        findings.add("营收增长: " + formatField(rawData.getRevenueGrowth()));
        findings.add("净利润增长: " + formatField(rawData.getNetIncomeGrowth()));
        findings.add("ROE: " + formatField(rawData.getRoe()));
        return findings;
    }

    private Integer parseJsonRating(String llmResponse) {
        try {
            String json = extractJson(llmResponse);
            if (json == null) return null;

            com.alibaba.fastjson.JSONObject obj = JSON.parseObject(json);
            if (obj != null && obj.containsKey("rating")) {
                int rating = obj.getIntValue("rating");
                if (rating >= 1 && rating <= 5) {
                    log.debug("Parsed JSON rating: {}", rating);
                    return rating;
                }
            }
        } catch (Exception e) {
            log.debug("Failed to parse JSON rating from LLM response: {}", e.getMessage());
        }
        return null;
    }

    private String extractJson(String text) {
        if (text == null) return null;
        int start = text.indexOf("{");
        int end = text.lastIndexOf("}");
        if (start >= 0 && end > start) {
            return text.substring(start, end + 1);
        }
        return null;
    }

    private String extractSummary(String llmResponse) {
        if (llmResponse == null) return "";
        String json = extractJson(llmResponse);
        if (json != null) {
            try {
                com.alibaba.fastjson.JSONObject obj = JSON.parseObject(json);
                String summary = obj.getString("summary");
                if (summary != null && !summary.isBlank()) {
                    return summary;
                }
            } catch (Exception ignored) {}
        }
        return llmResponse;
    }

    private int calculateRating(FundamentalDataVO data) {
        int score = 0;

        if (data.getRoe() != null) {
            if (data.getRoe() > 0.20) score += 2;
            else if (data.getRoe() > 0.10) score += 1;
        }

        if (data.getGrossMargin() != null) {
            if (data.getGrossMargin() > 0.40) score += 2;
            else if (data.getGrossMargin() > 0.20) score += 1;
        }

        if (data.getNetMargin() != null) {
            if (data.getNetMargin() > 0.20) score += 2;
            else if (data.getNetMargin() > 0.10) score += 1;
        }

        if (data.getRevenueGrowth() != null) {
            if (data.getRevenueGrowth() > 0.15) score += 2;
            else if (data.getRevenueGrowth() > 0.05) score += 1;
        }

        return Math.max(1, Math.min(5, (score / 2) + 2));
    }

    private String formatField(Object value) {
        if (value == null) return "N/A";
        if (value instanceof Double) {
            return String.format("%.2f", value);
        }
        if (value instanceof java.math.BigDecimal) {
            return String.format("%.2f", ((java.math.BigDecimal) value).doubleValue());
        }
        return value.toString();
    }

    private String nullToDefault(Object value) {
        return value != null ? value.toString() : "N/A";
    }

    private java.util.List<String> extractRiskWarnings(String llmResponse, FundamentalDataVO data) {
        java.util.List<String> warnings = new java.util.ArrayList<>();
        if (data.getDebtToEquity() != null && data.getDebtToEquity() > 1.0) {
            warnings.add("资产负债率偏高，债务权益比=" + String.format("%.2f", data.getDebtToEquity()));
        }
        if (data.getNetIncomeGrowth() != null && data.getNetIncomeGrowth() < 0) {
            warnings.add("净利润同比下降" + String.format("%.1f%%", Math.abs(data.getNetIncomeGrowth()) * 100));
        }
        if (warnings.isEmpty()) {
            warnings.add("未发现明显财务风险");
        }
        return warnings;
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
