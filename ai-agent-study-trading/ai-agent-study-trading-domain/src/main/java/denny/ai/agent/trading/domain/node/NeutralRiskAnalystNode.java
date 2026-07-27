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
import denny.ai.agent.trading.domain.vo.TradingContextVO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Service;

import jakarta.annotation.Resource;
import java.util.ArrayList;

/**
 * 中性风控分析师节点。
 */
@Slf4j
@Service
public class NeutralRiskAnalystNode extends AbstractExecuteSupport {

    public static final String TRADING_CONTEXT_KEY = "trading_context";

    @Resource
    private ArmoryObjectRegistry armoryObjectRegistry;
    @Resource private TradingRolePromptService rolePromptService;
    @Resource private StructuredPayloadCodec structuredPayloadCodec;

    @Override
    public String doApply(ExecuteCommandEntity requestParameter,
                           DefaultAutoAgentExecuteStrategyFactory.DynamicContext dynamicContext) throws Exception {
        log.info("=== 中性风控分析师节点执行开始 ===");

        TradingContextVO context = dynamicContext.getValue(TRADING_CONTEXT_KEY);
        if (context == null) {
            log.error("交易上下文为空");
            return "error: no trading context";
        }

        prepare(context, dynamicContext);
        return "neutral_risk_prepared";
    }

    public RiskAssessmentPayload prepare(TradingContextVO context,
                          DefaultAutoAgentExecuteStrategyFactory.DynamicContext dynamicContext) {
        if (context == null) {
            throw new IllegalArgumentException("trading context is missing");
        }
        sendRiskEvent(dynamicContext, "neutral_start", "中性风控分析师开始分析...");
        RiskAssessmentPayload opinion = generateRiskOpinion(context, dynamicContext);
        log.info("中性风控分析师分析完成");
        return opinion;
    }

    @Override
    public StrategyHandler<ExecuteCommandEntity, DefaultAutoAgentExecuteStrategyFactory.DynamicContext, String> get(
            ExecuteCommandEntity requestParameter,
            DefaultAutoAgentExecuteStrategyFactory.DynamicContext dynamicContext) throws Exception {
        return null;
    }

    private RiskAssessmentPayload generateRiskOpinion(TradingContextVO context,
                                   DefaultAutoAgentExecuteStrategyFactory.DynamicContext dynamicContext) {
        String prompt = rolePromptService.render("6010", context, dynamicContext,
                java.util.Map.of(
                        "investmentPlan", structuredPayloadCodec.toJson(context.getInvestmentPlan()),
                        "riskReports", structuredPayloadCodec.toJson(context.getRiskDebate())),
                RiskAssessmentPayload.class);

        ChatClient chatClient = getChatClientByClientId("6010", 0);

        long startAt = System.currentTimeMillis();
        log.info("中性风控分析师调用LLM | prompt长度={}", prompt.length());
        if (!shouldContinueSse(dynamicContext)) {
            throw new IllegalStateException("SSE已关闭，取消中性风控分析师调用");
        }
        String response = collectStreamingResponse(denny.ai.agent.trading.domain.execution.TradingChatMemory.apply(
                chatClient.prompt().user(prompt), context, dynamicContext, "NeutralRiskAnalystNode"),
                "NeutralRiskAnalystNode", getSseEventSink(dynamicContext));
        long latencyMs = System.currentTimeMillis() - startAt;

        log.info("中性风控分析师LLM响应 | prompt长度={} | 响应长度={} | 耗时={}ms",
                prompt.length(), response.length(), latencyMs);

        RiskAssessmentPayload payload = structuredPayloadCodec.parse(response, RiskAssessmentPayload.class);
        if (!"NEUTRAL".equals(payload.perspective())) {
            throw new denny.ai.agent.trading.domain.execution.StructuredPayloadException("invalid risk perspective");
        }
        return payload;
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
