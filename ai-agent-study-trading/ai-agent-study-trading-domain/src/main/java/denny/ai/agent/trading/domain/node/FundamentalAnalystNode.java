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
import denny.ai.agent.trading.api.vo.payload.FundamentalAnalystPayload;
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
 * 5. 返回强类型报告，由 Stage 统一提交
 */
@Slf4j
@Service
public class FundamentalAnalystNode extends AbstractExecuteSupport {

    public static final String TRADING_CONTEXT_KEY = "trading_context";

    @Resource
    private IStockDataProvider dataProvider;

    @Resource
    private ArmoryObjectRegistry armoryObjectRegistry;

    @Resource
    private AnalystPromptService analystPromptService;

    @Resource
    private StructuredPayloadCodec structuredPayloadCodec;

    @Override
    public String doApply(ExecuteCommandEntity requestParameter,
                           DefaultAutoAgentExecuteStrategyFactory.DynamicContext dynamicContext) throws Exception {
        log.info("=== 基本面分析师节点执行开始 ===");

        TradingContextVO context = dynamicContext.getValue(TRADING_CONTEXT_KEY);
        if (context == null || context.getStockInfo() == null) {
            log.error("交易上下文或股票信息为空");
            return "error: no trading context";
        }

        prepare(context, dynamicContext);
        return "fundamental_analysis_prepared";
    }

    public FundamentalReportVO prepare(TradingContextVO context,
                                       DefaultAutoAgentExecuteStrategyFactory.DynamicContext dynamicContext) {
        try {
            return prepareInternal(context, dynamicContext);
        } catch (RuntimeException error) {
            log.error("节点执行异常: nodeName=FundamentalAnalystNode, ticker={}",
                    tickerOf(context), error);
            throw error;
        }
    }

    private FundamentalReportVO prepareInternal(TradingContextVO context,
                                                 DefaultAutoAgentExecuteStrategyFactory.DynamicContext dynamicContext) {
        if (context == null || context.getStockInfo() == null) {
            throw new IllegalArgumentException("trading context or stock info is missing");
        }
        StockInfoVO stockInfo = context.getStockInfo();
        String ticker = stockInfo.getTicker();

        sendAnalystEvent(dynamicContext, "analyst_start", "基本面分析开始: " + ticker);

        FundamentalDataVO fundamentalData = denny.ai.agent.trading.domain.execution.TargetBoundStockDataProvider
                .bind(dataProvider, context.getTargetContext()).getFundamentalData();

        log.info("获取基本面数据: ticker={}, pe={}, roe={}, grossMargin={}",
                ticker, fundamentalData.getPeRatio(), fundamentalData.getRoe(), fundamentalData.getGrossMargin());

        sendAnalystEvent(dynamicContext, "analyst_progress", "已获取基本面数据，开始分析...");

        FundamentalReportVO report = generateReport(stockInfo, fundamentalData, dynamicContext);

        log.info("基本面分析完成: ticker={}, rating={}", ticker, report.getRating());
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

    private FundamentalReportVO generateReport(StockInfoVO stockInfo,
                                             FundamentalDataVO data,
                                             DefaultAutoAgentExecuteStrategyFactory.DynamicContext dynamicContext) {
        TradingContextVO context = dynamicContext.getValue(TRADING_CONTEXT_KEY);
        String stockData = structuredPayloadCodec.toJson(java.util.Map.of(
                "stockInfo", stockInfo,
                "fundamentalData", data));
        String prompt = analystPromptService.render("6002", context, dynamicContext,
                stockData, FundamentalAnalystPayload.class);

        ChatClient chatClient = getChatClientByClientId("6002", 0);

        long startAt = System.currentTimeMillis();
        log.info("基本面分析师调用LLM | prompt长度={}", prompt.length());
        if (!shouldContinueSse(dynamicContext)) {
            throw new IllegalStateException("SSE已关闭，取消基本面分析师调用");
        }
        log.debug("LLM streaming input | operation=FundamentalAnalystNode | content=\n{}", prompt);
        String response = denny.ai.agent.trading.domain.execution.TradingLlmCallAudit.execute(
                context, "6002", "FundamentalAnalystNode",
                () -> collectStreamingResponse(denny.ai.agent.trading.domain.execution.TradingChatMemory.apply(
                        chatClient.prompt().user(prompt), context, dynamicContext, "FundamentalAnalystNode"),
                        "FundamentalAnalystNode", getSseEventSink(dynamicContext)));
        long latencyMs = System.currentTimeMillis() - startAt;

        log.info("基本面分析师LLM响应 | prompt长度={} | 响应长度={} | 耗时={}ms",
                prompt.length(), response.length(), latencyMs);

        if (denny.ai.agent.trading.domain.prompt.TradingPromptModeResolver.requireMode(dynamicContext)
                == denny.ai.agent.trading.domain.prompt.PromptContractMode.RELAXED_V3) {
            return FundamentalReportVO.builder().summary(response.trim()).rawData(data).build();
        }
        return parseReport(response, data);
    }

    private FundamentalReportVO parseReport(String llmResponse, FundamentalDataVO rawData) {
        FundamentalAnalystPayload payload = structuredPayloadCodec.parse(
                llmResponse, FundamentalAnalystPayload.class);
        return FundamentalReportVO.builder()
                .rating(payload.rating())
                .keyFindings(payload.keyFindings())
                .riskWarnings(payload.riskWarnings())
                .summary(payload.summary())
                .rawData(rawData)
                .targetEcho(payload.targetEcho())
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
            String json = text.substring(start, end + 1);
            json = json.replace('\u201C', '\u201D');
            return json;
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
            if (data.getRoe() > 20.0) score += 2;
            else if (data.getRoe() > 10.0) score += 1;
        }

        if (data.getGrossMargin() != null) {
            if (data.getGrossMargin() > 40.0) score += 2;
            else if (data.getGrossMargin() > 20.0) score += 1;
        }

        if (data.getNetMargin() != null) {
            if (data.getNetMargin() > 20.0) score += 2;
            else if (data.getNetMargin() > 10.0) score += 1;
        }

        if (data.getRevenueGrowth() != null) {
            if (data.getRevenueGrowth() > 15.0) score += 2;
            else if (data.getRevenueGrowth() > 5.0) score += 1;
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
        if (data.getDebtToAssets() != null && data.getDebtToAssets() > 70.0) {
            warnings.add("资产负债率偏高：" + String.format("%.2f%%", data.getDebtToAssets()));
        }
        if (data.getNetIncomeGrowth() != null && data.getNetIncomeGrowth() < 0) {
            warnings.add("净利润同比下降" + String.format("%.1f%%", Math.abs(data.getNetIncomeGrowth())));
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
