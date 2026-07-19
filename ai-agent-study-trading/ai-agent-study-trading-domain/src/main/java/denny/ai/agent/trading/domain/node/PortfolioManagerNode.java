package denny.ai.agent.trading.domain.node;

import cn.bugstack.wrench.design.framework.tree.StrategyHandler;
import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import denny.ai.agent.domain.model.entity.AutoAgentExecuteResultEntity;
import denny.ai.agent.domain.model.entity.ExecuteCommandEntity;
import denny.ai.agent.domain.service.auto.step.AbstractExecuteSupport;
import denny.ai.agent.domain.service.auto.step.factory.DefaultAutoAgentExecuteStrategyFactory;
import denny.ai.agent.domain.service.armory.factory.ArmoryObjectRegistry;
import denny.ai.agent.trading.api.vo.TradeDecisionEnum;
import denny.ai.agent.trading.domain.config.TradingDriver;
import denny.ai.agent.trading.domain.model.valobj.TradingResultVO;
import denny.ai.agent.trading.domain.prompt.PortfolioManagerPromptTemplate;
import denny.ai.agent.trading.domain.service.TradingResultExportService;
import denny.ai.agent.trading.domain.vo.TradingContextVO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Service;

import jakarta.annotation.Resource;

/**
 * 组合经理节点。
 */
@Slf4j
@Service
public class PortfolioManagerNode extends AbstractExecuteSupport {

    public static final String TRADING_CONTEXT_KEY = "trading_context";

    @Resource
    private ArmoryObjectRegistry armoryObjectRegistry;

    @Resource
    private TradingResultExportService tradingResultExportService;

    @Override
    public String doApply(ExecuteCommandEntity requestParameter,
                           DefaultAutoAgentExecuteStrategyFactory.DynamicContext dynamicContext) throws Exception {
        log.info("=== 组合经理节点执行开始 ===");

        TradingContextVO context = dynamicContext.getValue(TRADING_CONTEXT_KEY);
        if (context == null) {
            log.error("交易上下文为空");
            return "error: no trading context";
        }

        prepare(context, dynamicContext);
        return "portfolio_manager_prepared";
    }

    public TradingContextVO.FinalTradeDecisionVO prepare(
            TradingContextVO context,
            DefaultAutoAgentExecuteStrategyFactory.DynamicContext dynamicContext) {
        if (context == null || context.getStockInfo() == null) {
            throw new IllegalArgumentException("trading context or stock info is missing");
        }
        String ticker = context.getStockInfo().getTicker();

        sendFinalEvent(dynamicContext, "portfolio_manager_start", "组合经理开始最终审批...");

        String riskSummary = buildRiskSummary(context);

        String decisionJson = generateFinalDecision(ticker, context, riskSummary, dynamicContext);

        TradingContextVO.FinalTradeDecisionVO decision = parseFinalDecision(decisionJson);
        log.info("组合经理决策完成: ticker={}, decision={}", ticker, decision.getDecision());
        return decision;
    }

    @Override
    public StrategyHandler<ExecuteCommandEntity, DefaultAutoAgentExecuteStrategyFactory.DynamicContext, String> get(
            ExecuteCommandEntity requestParameter,
            DefaultAutoAgentExecuteStrategyFactory.DynamicContext dynamicContext) throws Exception {
        return null;
    }

    private String buildRiskSummary(TradingContextVO context) {
        StringBuilder sb = new StringBuilder();

        TradingContextVO.RiskDebateVO riskDebate = context.getRiskDebate();
        if (riskDebate != null) {
            if (riskDebate.getAggressiveHistory() != null && !riskDebate.getAggressiveHistory().isEmpty()) {
                sb.append("激进观点: ").append(riskDebate.getAggressiveHistory().get(0)).append("\n\n");
            }
            if (riskDebate.getConservativeHistory() != null && !riskDebate.getConservativeHistory().isEmpty()) {
                sb.append("保守观点: ").append(riskDebate.getConservativeHistory().get(0)).append("\n\n");
            }
            if (riskDebate.getNeutralHistory() != null && !riskDebate.getNeutralHistory().isEmpty()) {
                sb.append("中性观点: ").append(riskDebate.getNeutralHistory().get(0)).append("\n\n");
            }
        }

        return sb.length() > 0 ? sb.toString() : "No risk debate available.";
    }

    private String generateFinalDecision(String ticker, TradingContextVO context,
                                     String riskSummary,
                                     DefaultAutoAgentExecuteStrategyFactory.DynamicContext dynamicContext) {
        String prompt = PortfolioManagerPromptTemplate.PORTFOLIO_MANAGER_PROMPT.formatted(
                ticker,
                context.getInvestmentPlan() != null ? JSON.toJSONString(context.getInvestmentPlan()) : "{}",
                context.getInvestmentDebate() != null ? context.getInvestmentDebate().getConclusion() : "No debate conclusion",
                riskSummary
        );

        ChatClient chatClient = getChatClientByClientId("6009", 0);

        long startAt = System.currentTimeMillis();
        log.info("组合经理调用LLM | prompt长度={}", prompt.length());
        if (!shouldContinueSse(dynamicContext)) {
            log.info("SSE已关闭，跳过组合经理LLM调用");
            return "";
        }
        String response = collectStreamingResponse(chatClient.prompt().user(prompt),
                "PortfolioManagerNode", getSseEventSink(dynamicContext));
        long latencyMs = System.currentTimeMillis() - startAt;

        log.info("组合经理LLM响应 | prompt长度={} | 响应长度={} | 耗时={}ms",
                prompt.length(), response.length(), latencyMs);

        return response;
    }

    private TradingContextVO.FinalTradeDecisionVO parseFinalDecision(String llmResponse) {
        try {
            String jsonStr = extractJson(llmResponse);
            JSONObject json = JSON.parseObject(jsonStr);

            TradingContextVO.FinalTradeDecisionVO decision = TradingContextVO.FinalTradeDecisionVO.builder()
                    .decision(getStringOrDefault(json, "decision", "HOLD"))
                    .confidence(getStringOrDefault(json, "confidence", "MEDIUM"))
                    .overallRating(getDoubleOrDefault(json, "overallRating", 3.0))
                    .reasoning(getStringOrDefault(json, "reasoning", ""))
                    .build();

            log.info("最终决策解析成功: decision={}, confidence={}, overallRating={}",
                    decision.getDecision(), decision.getConfidence(), decision.getOverallRating());
            return decision;
        } catch (Exception e) {
            log.error("解析最终决策失败: {}", llmResponse, e);
            return TradingContextVO.FinalTradeDecisionVO.builder()
                    .decision("HOLD")
                    .confidence("LOW")
                    .overallRating(3.0)
                    .reasoning("分析失败，降级为持有")
                    .build();
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

    private String getStringOrDefault(JSONObject json, String key, String defaultValue) {
        return json.containsKey(key) ? json.getString(key) : defaultValue;
    }

    private Double getDoubleOrDefault(JSONObject json, String key, Double defaultValue) {
        return json.containsKey(key) ? json.getDouble(key) : defaultValue;
    }

    private void sendFinalEvent(DefaultAutoAgentExecuteStrategyFactory.DynamicContext dynamicContext,
                              String subType, String content) {
        AutoAgentExecuteResultEntity event = AutoAgentExecuteResultEntity.builder()
                .type("final")
                .subType(subType)
                .step(dynamicContext.getStep())
                .content(content)
                .completed(false)
                .timestamp(System.currentTimeMillis())
                .build();

        sendSseResult(dynamicContext, event);
    }
}
