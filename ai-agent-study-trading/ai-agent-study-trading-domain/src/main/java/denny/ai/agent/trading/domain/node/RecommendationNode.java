package denny.ai.agent.trading.domain.node;

import cn.bugstack.wrench.design.framework.tree.StrategyHandler;
import com.alibaba.fastjson.JSON;
import denny.ai.agent.domain.model.entity.AutoAgentExecuteResultEntity;
import denny.ai.agent.domain.model.entity.ExecuteCommandEntity;
import denny.ai.agent.domain.service.auto.step.AbstractExecuteSupport;
import denny.ai.agent.domain.service.auto.step.factory.DefaultAutoAgentExecuteStrategyFactory;
import denny.ai.agent.domain.service.armory.factory.ArmoryObjectRegistry;
import denny.ai.agent.trading.domain.config.TradingDriver;
import denny.ai.agent.trading.domain.prompt.RecommendationPromptTemplate;
import denny.ai.agent.trading.domain.vo.TradingContextVO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Service;

import jakarta.annotation.Resource;

/**
 * 推荐节点。
 */
@Slf4j
@Service
public class RecommendationNode extends AbstractExecuteSupport {

    public static final String TRADING_CONTEXT_KEY = "trading_context";

    @Resource
    private ArmoryObjectRegistry armoryObjectRegistry;

    @Override
    public String doApply(ExecuteCommandEntity requestParameter,
                           DefaultAutoAgentExecuteStrategyFactory.DynamicContext dynamicContext) throws Exception {
        log.info("=== 推荐节点执行开始 ===");

        TradingContextVO context = dynamicContext.getValue(TRADING_CONTEXT_KEY);
        if (context == null) {
            log.error("交易上下文为空");
            return "error: no trading context";
        }

        String ticker = context.getStockInfo().getTicker();

        sendRecommendationEvent(dynamicContext, "recommendation_start", "推荐节点开始生成投资建议...");

        String analysisSummary = buildAnalysisSummary(context);

        String planJson = generateInvestmentPlan(ticker, analysisSummary, dynamicContext);

        parseAndUpdatePlan(context, planJson);

        sendRecommendationEvent(dynamicContext, "recommendation_plan", JSON.toJSONString(context.getInvestmentPlan()));

        log.info("推荐节点执行完成: ticker={}, action={}",
                ticker, context.getInvestmentPlan() != null ? context.getInvestmentPlan().getAction() : "N/A");

        if (TradingDriver.getCurrent() != null) {
            TradingDriver.getCurrent().recommendationComplete();
        }

        return "recommendation_plan_completed";
    }

    @Override
    public StrategyHandler<ExecuteCommandEntity, DefaultAutoAgentExecuteStrategyFactory.DynamicContext, String> get(
            ExecuteCommandEntity requestParameter,
            DefaultAutoAgentExecuteStrategyFactory.DynamicContext dynamicContext) throws Exception {
        return null;
    }

    private String buildAnalysisSummary(TradingContextVO context) {
        StringBuilder sb = new StringBuilder();
        String ticker = context.getStockInfo().getTicker();
        java.math.BigDecimal price = context.getStockInfo().getCurrentPrice();

        sb.append("股票代码: ").append(ticker).append("\n");
        sb.append("当前价格: ").append(price).append("\n\n");

        if (context.getFundamentalReport() != null) {
            sb.append("【基本面分析】\n");
            sb.append("评分: ").append(context.getFundamentalReport().getRating()).append("/5\n");
            sb.append("总结: ").append(context.getFundamentalReport().getSummary()).append("\n\n");
        }

        if (context.getTechnicalReport() != null) {
            sb.append("【技术面分析】\n");
            sb.append("评分: ").append(context.getTechnicalReport().getRating()).append("/5\n");
            sb.append("趋势信号: ").append(context.getTechnicalReport().getTrendSignal()).append("\n");
            sb.append("总结: ").append(context.getTechnicalReport().getSummary()).append("\n\n");
        }

        if (context.getSentimentReport() != null) {
            sb.append("【情绪面分析】\n");
            sb.append("评分: ").append(context.getSentimentReport().getRating()).append("/5\n");
            sb.append("情绪得分: ").append(context.getSentimentReport().getSentimentScore()).append("\n");
            sb.append("总结: ").append(context.getSentimentReport().getSummary()).append("\n\n");
        }

        if (context.getNewsReport() != null) {
            sb.append("【新闻面分析】\n");
            sb.append("评分: ").append(context.getNewsReport().getRating()).append("/5\n");
            sb.append("整体情绪: ").append(context.getNewsReport().getOverallSentiment()).append("\n");
            sb.append("总结: ").append(context.getNewsReport().getSummary()).append("\n\n");
        }

        if (context.getInvestmentDebate() != null) {
            sb.append("【投资辩论结论】\n");
            sb.append("综合评分: ").append(context.getInvestmentDebate().getOverallScore()).append("\n");
            sb.append("辩论结论: ").append(context.getInvestmentDebate().getConclusion()).append("\n");
        }

        return sb.toString();
    }

    private String generateInvestmentPlan(String ticker, String analysisSummary,
                                      DefaultAutoAgentExecuteStrategyFactory.DynamicContext dynamicContext) {
        String prompt = RecommendationPromptTemplate.RECOMMENDATION_PROMPT.formatted(ticker, analysisSummary);

        ChatClient chatClient = getChatClientByClientId("6013", 0);

        long startAt = System.currentTimeMillis();
        log.info("推荐节点调用LLM | prompt长度={}", prompt.length());
        if (!shouldContinueSse(dynamicContext)) {
            log.info("SSE已关闭，跳过推荐节点LLM调用");
            return "";
        }
        String response = chatClient.prompt().user(prompt).call().content();
        long latencyMs = System.currentTimeMillis() - startAt;

        log.info("推荐节点LLM响应 | prompt长度={} | 响应长度={} | 耗时={}ms",
                prompt.length(), response.length(), latencyMs);

        return response;
    }

    private void parseAndUpdatePlan(TradingContextVO context, String llmResponse) {
        try {
            String jsonStr = extractJson(llmResponse);
            com.alibaba.fastjson.JSONObject json = JSON.parseObject(jsonStr);

            TradingContextVO.InvestmentPlanVO plan = TradingContextVO.InvestmentPlanVO.builder()
                    .action(getStringOrDefault(json, "action", "HOLD"))
                    .positionRatio(getDoubleOrDefault(json, "positionRatio", 0.0))
                    .entryPriceRange(getStringOrDefault(json, "entryPriceRange", ""))
                    .stopLossPrice(getStringOrDefault(json, "stopLossPrice", ""))
                    .takeProfitPrice(getStringOrDefault(json, "takeProfitPrice", ""))
                    .holdingPeriod(getStringOrDefault(json, "holdingPeriod", ""))
                    .riskRewardRatio(getDoubleOrDefault(json, "riskRewardRatio", 0.0))
                    .build();

            context.setInvestmentPlan(plan);
            log.info("投资计划解析成功: action={}, positionRatio={}", plan.getAction(), plan.getPositionRatio());
        } catch (Exception e) {
            log.error("解析投资计划失败: {}", llmResponse, e);
            TradingContextVO.InvestmentPlanVO fallbackPlan = TradingContextVO.InvestmentPlanVO.builder()
                    .action("HOLD")
                    .positionRatio(0.0)
                    .build();
            context.setInvestmentPlan(fallbackPlan);
        }
    }

    private String extractJson(String response) {
        String trimmed = response.trim();
        int jsonStart = trimmed.indexOf("{");
        int jsonEnd = trimmed.lastIndexOf("}");
        if (jsonStart >= 0 && jsonEnd > jsonStart) {
            String json = trimmed.substring(jsonStart, jsonEnd + 1);
            json = json.replace('\u201C', '\u201D');
            return json;
        }
        return "{}";
    }

    private String getStringOrDefault(com.alibaba.fastjson.JSONObject json, String key, String defaultValue) {
        return json.containsKey(key) ? json.getString(key) : defaultValue;
    }

    private Double getDoubleOrDefault(com.alibaba.fastjson.JSONObject json, String key, Double defaultValue) {
        return json.containsKey(key) ? json.getDouble(key) : defaultValue;
    }

    private void sendRecommendationEvent(DefaultAutoAgentExecuteStrategyFactory.DynamicContext dynamicContext,
                               String subType, String content) {
        AutoAgentExecuteResultEntity event = AutoAgentExecuteResultEntity.builder()
                .type("recommendation")
                .subType(subType)
                .step(dynamicContext.getStep())
                .content(content)
                .completed(false)
                .timestamp(System.currentTimeMillis())
                .build();

        sendSseResult(dynamicContext, event);
    }
}
