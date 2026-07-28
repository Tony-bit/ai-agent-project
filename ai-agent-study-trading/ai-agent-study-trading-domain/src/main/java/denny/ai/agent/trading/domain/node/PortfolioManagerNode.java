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
import denny.ai.agent.trading.domain.prompt.TradingRolePromptService;
import denny.ai.agent.trading.domain.execution.StructuredPayloadCodec;
import denny.ai.agent.trading.api.vo.payload.PortfolioDecisionPayload;
import denny.ai.agent.trading.api.vo.payload.PortfolioDecisionV3;
import denny.ai.agent.trading.api.vo.TargetContext;
import denny.ai.agent.trading.domain.service.TradingResultExportService;
import denny.ai.agent.trading.domain.service.RatingEngine;
import denny.ai.agent.trading.domain.execution.TradingOutputParser;
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

    @Resource private TradingRolePromptService rolePromptService;
    @Resource private StructuredPayloadCodec structuredPayloadCodec;
    @Resource private TradingOutputParser outputParser;
    @Resource private RatingEngine ratingEngine;
    @Resource private denny.ai.agent.trading.api.metrics.TradingRolloutMonitor rolloutMonitor;

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
        try {
            return prepareInternal(context, dynamicContext);
        } catch (RuntimeException error) {
            log.error("节点执行异常: nodeName=PortfolioManagerNode, ticker={}", tickerOf(context), error);
            throw error;
        }
    }

    private TradingContextVO.FinalTradeDecisionVO prepareInternal(
            TradingContextVO context,
            DefaultAutoAgentExecuteStrategyFactory.DynamicContext dynamicContext) {
        if (context == null || context.getStockInfo() == null) {
            throw new IllegalArgumentException("trading context or stock info is missing");
        }
        String ticker = context.getStockInfo().getTicker();

        sendFinalEvent(dynamicContext, "portfolio_manager_start", "组合经理开始最终审批...");

        String response = generateFinalDecision(context, dynamicContext);
        TradingContextVO.FinalTradeDecisionVO decision;
        if (denny.ai.agent.trading.domain.prompt.TradingPromptModeResolver.requireMode(dynamicContext)
                == denny.ai.agent.trading.domain.prompt.PromptContractMode.STRICT_V2) {
            PortfolioDecisionPayload payload = structuredPayloadCodec.parse(
                    response, PortfolioDecisionPayload.class);
            denny.ai.agent.trading.domain.validation.StrictTargetEchoGuard.requireMatch(
                    context.getTargetContext(), payload.targetEcho());
            decision = toDecision(payload, context.getTargetContext());
        } else {
            decision = toV3Decision(response, context);
        }
        enforceRiskFallback(context, decision);
        log.info("组合经理决策完成: ticker={}, decision={}", ticker, decision.getDecision());
        return decision;
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

    private String buildRiskSummary(TradingContextVO context) {
        StringBuilder sb = new StringBuilder();

        TradingContextVO.RiskDebateVO riskDebate = context.getRiskDebate();
        if (riskDebate != null) {
            if (riskDebate.getAggressiveHistory() != null && !riskDebate.getAggressiveHistory().isEmpty()) {
                sb.append("激进观点: ").append(riskDebate.getAggressiveHistory().get(0).rawText()).append("\n\n");
            }
            if (riskDebate.getConservativeHistory() != null && !riskDebate.getConservativeHistory().isEmpty()) {
                sb.append("保守观点: ").append(riskDebate.getConservativeHistory().get(0).rawText()).append("\n\n");
            }
            if (riskDebate.getNeutralHistory() != null && !riskDebate.getNeutralHistory().isEmpty()) {
                sb.append("中性观点: ").append(riskDebate.getNeutralHistory().get(0).rawText()).append("\n\n");
            }
        }

        return sb.length() > 0 ? sb.toString() : "No risk debate available.";
    }

    private String generateFinalDecision(TradingContextVO context,
                                     DefaultAutoAgentExecuteStrategyFactory.DynamicContext dynamicContext) {
        java.util.Map<String, Object> reports = new java.util.LinkedHashMap<>();
        reports.put("fundamental", context.getFundamentalReport());
        reports.put("technical", context.getTechnicalReport());
        reports.put("sentiment", context.getSentimentReport());
        reports.put("news", context.getNewsReport());
        reports.put("investmentPlan", context.getInvestmentPlan());
        reports.put("decisionSignals", context.getDecisionSignals());
        Class<?> outputType = denny.ai.agent.trading.domain.prompt.TradingPromptModeResolver
                .requireMode(dynamicContext) == denny.ai.agent.trading.domain.prompt.PromptContractMode.STRICT_V2
                ? PortfolioDecisionPayload.class : PortfolioDecisionV3.class;
        String prompt = rolePromptService.render("6009", context, dynamicContext,
                java.util.Map.of(
                        "analystReports", structuredPayloadCodec.toJson(reports),
                        "debateHistory", structuredPayloadCodec.toJson(context.getInvestmentDebate()),
                        "riskReports", structuredPayloadCodec.toJson(context.getRiskDebate()),
                        "validationStatus", structuredPayloadCodec.toJson(context.getDataWarnings())),
                outputType);

        ChatClient chatClient = getChatClientByClientId("6009", 0);

        long startAt = System.currentTimeMillis();
        log.info("组合经理调用LLM | prompt长度={}", prompt.length());
        if (!shouldContinueSse(dynamicContext)) {
            throw new IllegalStateException("SSE已关闭，取消组合经理调用");
        }
        String response = denny.ai.agent.trading.domain.execution.TradingLlmCallAudit.execute(
                context, "6009", "PortfolioManagerNode",
                () -> collectStreamingResponse(denny.ai.agent.trading.domain.execution.TradingChatMemory.apply(
                        chatClient.prompt().user(prompt), context, dynamicContext, "PortfolioManagerNode"),
                        "PortfolioManagerNode", getSseEventSink(dynamicContext)));
        long latencyMs = System.currentTimeMillis() - startAt;

        log.info("组合经理LLM响应 | prompt长度={} | 响应长度={} | 耗时={}ms",
                prompt.length(), response.length(), latencyMs);

        return response;
    }

    private TradingContextVO.FinalTradeDecisionVO toV3Decision(String response,
                                                                 TradingContextVO context) {
        try {
            PortfolioDecisionV3 payload = outputParser.parseStructured(
                    denny.ai.agent.trading.domain.prompt.PromptContractMode.RELAXED_V3,
                    response, PortfolioDecisionV3.class);
            RatingEngine.RatingResult rating = ratingEngine.calculate(context);
            java.util.List<String> warnings = new java.util.ArrayList<>(rating.getUnavailableReasons());
            return TradingContextVO.FinalTradeDecisionVO.builder()
                    .targetId(context.getTargetContext().targetId())
                    .stockName(context.getTargetContext().stockName())
                    .decision(payload.decision())
                    .confidence(rating.getConfidence())
                    .overallRating(rating.getAdjustedRating())
                    .reasoning(payload.reasoning())
                    .warnings(warnings)
                    .build();
        } catch (RuntimeException error) {
            if (rolloutMonitor != null) {
                rolloutMonitor.recordSafeFallback();
            }
            return TradingContextVO.FinalTradeDecisionVO.builder()
                    .targetId(context.getTargetContext().targetId())
                    .stockName(context.getTargetContext().stockName())
                    .decision("SKIP").confidence("LOW")
                    .reasoning("组合经理输出无法解析").warnings(java.util.List.of("SAFE_FALLBACK"))
                    .build();
        }
    }

    private void enforceRiskFallback(TradingContextVO context,
                                     TradingContextVO.FinalTradeDecisionVO decision) {
        TradingContextVO.RiskDebateVO risk = context.getRiskDebate();
        boolean noRiskNarrative = risk == null
                || ((risk.getAggressiveHistory() == null || risk.getAggressiveHistory().isEmpty())
                && (risk.getConservativeHistory() == null || risk.getConservativeHistory().isEmpty())
                && (risk.getNeutralHistory() == null || risk.getNeutralHistory().isEmpty()));
        if (noRiskNarrative && !java.util.Set.of("HOLD", "SKIP").contains(decision.getDecision())) {
            decision.setDecision("HOLD");
            java.util.List<String> warnings = decision.getWarnings() == null
                    ? new java.util.ArrayList<>() : new java.util.ArrayList<>(decision.getWarnings());
            warnings.add("RISK_SIGNALS_UNAVAILABLE");
            decision.setWarnings(warnings);
        }
    }

    private TradingContextVO.FinalTradeDecisionVO toDecision(PortfolioDecisionPayload payload,
                                                               TargetContext target) {
        return TradingContextVO.FinalTradeDecisionVO.builder()
                .targetId(target.targetId())
                .stockName(target.stockName())
                .decision(payload.decision())
                .confidence(payload.confidence())
                .overallRating(payload.overallRating())
                .reasoning(payload.reasoning())
                .warnings(payload.warnings())
                .targetEcho(payload.targetEcho())
                .build();
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
