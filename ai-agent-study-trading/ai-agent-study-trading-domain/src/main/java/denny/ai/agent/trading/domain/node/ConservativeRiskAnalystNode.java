package denny.ai.agent.trading.domain.node;

import cn.bugstack.wrench.design.framework.tree.StrategyHandler;
import denny.ai.agent.domain.model.entity.AutoAgentExecuteResultEntity;
import denny.ai.agent.domain.model.entity.ExecuteCommandEntity;
import denny.ai.agent.domain.service.auto.step.AbstractExecuteSupport;
import denny.ai.agent.domain.service.auto.step.factory.DefaultAutoAgentExecuteStrategyFactory;
import denny.ai.agent.domain.service.armory.factory.ArmoryObjectRegistry;
import denny.ai.agent.trading.domain.config.TradingDriver;
import denny.ai.agent.trading.domain.prompt.RiskAnalystPromptTemplate;
import denny.ai.agent.trading.domain.prompt.TradingRolePromptService;
import denny.ai.agent.trading.domain.execution.StructuredPayloadCodec;
import denny.ai.agent.trading.api.vo.payload.RiskAssessmentPayload;
import denny.ai.agent.trading.api.vo.NarrativeNodeResult;
import denny.ai.agent.trading.domain.vo.TradingContextVO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Service;

import jakarta.annotation.Resource;
import java.util.ArrayList;

/**
 * 保守风控分析师节点。
 */
@Slf4j
@Service
public class ConservativeRiskAnalystNode extends AbstractExecuteSupport {

    public static final String TRADING_CONTEXT_KEY = "trading_context";

    @Resource
    private ArmoryObjectRegistry armoryObjectRegistry;
    @Resource private TradingRolePromptService rolePromptService;
    @Resource private StructuredPayloadCodec structuredPayloadCodec;

    @Override
    public String doApply(ExecuteCommandEntity requestParameter,
                           DefaultAutoAgentExecuteStrategyFactory.DynamicContext dynamicContext) throws Exception {
        log.info("=== 保守风控分析师节点执行开始 ===");

        TradingContextVO context = dynamicContext.getValue(TRADING_CONTEXT_KEY);
        if (context == null) {
            log.error("交易上下文为空");
            return "error: no trading context";
        }

        prepare(context, dynamicContext);
        return "conservative_risk_prepared";
    }

    public NarrativeNodeResult prepare(TradingContextVO context,
                          DefaultAutoAgentExecuteStrategyFactory.DynamicContext dynamicContext) {
        try {
            return prepareInternal(context, dynamicContext);
        } catch (RuntimeException error) {
            log.error("节点执行异常: nodeName=ConservativeRiskAnalystNode, ticker={}", tickerOf(context), error);
            throw error;
        }
    }

    private NarrativeNodeResult prepareInternal(TradingContextVO context,
                          DefaultAutoAgentExecuteStrategyFactory.DynamicContext dynamicContext) {
        if (context == null) {
            throw new IllegalArgumentException("trading context is missing");
        }
        sendRiskEvent(dynamicContext, "conservative_start", "保守风控分析师开始分析...");
        NarrativeNodeResult opinion = generateRiskOpinion(context, dynamicContext);
        log.info("保守风控分析师分析完成");
        return opinion;
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

    private NarrativeNodeResult generateRiskOpinion(TradingContextVO context,
                                   DefaultAutoAgentExecuteStrategyFactory.DynamicContext dynamicContext) {
        String prompt = rolePromptService.render("6011", context, dynamicContext,
                java.util.Map.of(
                        "investmentPlan", structuredPayloadCodec.toJson(context.getInvestmentPlan()),
                        "riskReports", structuredPayloadCodec.toJson(java.util.Map.of(
                                "narratives", context.getRiskDebate(),
                                "decisionSignals", context.getDecisionSignals()))),
                RiskAssessmentPayload.class);

        ChatClient chatClient = getChatClientByClientId("6011", 0);

        long startAt = System.currentTimeMillis();
        log.info("保守风控分析师调用LLM | prompt长度={}", prompt.length());
        if (!shouldContinueSse(dynamicContext)) {
            throw new IllegalStateException("SSE已关闭，取消保守风控分析师调用");
        }
        log.debug("LLM streaming input | operation=ConservativeRiskAnalystNode | content=\n{}", prompt);
        String response = denny.ai.agent.trading.domain.execution.TradingLlmCallAudit.execute(
                context, "6011", "ConservativeRiskAnalystNode",
                () -> collectStreamingResponse(denny.ai.agent.trading.domain.execution.TradingChatMemory.apply(
                        chatClient.prompt().user(prompt), context, dynamicContext, "ConservativeRiskAnalystNode"),
                        "ConservativeRiskAnalystNode", getSseEventSink(dynamicContext)));
        long latencyMs = System.currentTimeMillis() - startAt;

        log.info("保守风控分析师LLM响应 | prompt长度={} | 响应长度={} | 耗时={}ms",
                prompt.length(), response.length(), latencyMs);

        if (denny.ai.agent.trading.domain.prompt.TradingPromptModeResolver.requireMode(dynamicContext)
                == denny.ai.agent.trading.domain.prompt.PromptContractMode.RELAXED_V3) {
            return new NarrativeNodeResult("CONSERVATIVE", response);
        }
        RiskAssessmentPayload payload = structuredPayloadCodec.parse(response, RiskAssessmentPayload.class);
        if (!"CONSERVATIVE".equals(payload.perspective())) {
            throw new denny.ai.agent.trading.domain.execution.StructuredPayloadException("invalid risk perspective");
        }
        denny.ai.agent.trading.domain.validation.StrictTargetEchoGuard.requireMatch(
                context.getTargetContext(), payload.targetEcho());
        return new denny.ai.agent.trading.domain.narrative.RiskAssessmentNarrativeAdapter()
                .adapt("CONSERVATIVE", payload);
    }

    private void sendRiskEvent(DefaultAutoAgentExecuteStrategyFactory.DynamicContext dynamicContext,
                             String subType, String content) {
        AutoAgentExecuteResultEntity event = AutoAgentExecuteResultEntity.builder()
                .type("risk_debate")
                .subType(subType)
                .step(dynamicContext.getStep())
                .content(content)
                .completed(false)
                .timestamp(System.currentTimeMillis())
                .build();

        sendSseResult(dynamicContext, event);
    }
}
