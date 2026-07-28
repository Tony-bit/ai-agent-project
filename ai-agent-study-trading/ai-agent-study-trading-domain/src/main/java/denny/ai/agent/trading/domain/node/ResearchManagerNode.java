package denny.ai.agent.trading.domain.node;

import cn.bugstack.wrench.design.framework.tree.StrategyHandler;
import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import denny.ai.agent.domain.model.entity.AutoAgentExecuteResultEntity;
import denny.ai.agent.domain.model.entity.ExecuteCommandEntity;
import denny.ai.agent.domain.service.auto.step.AbstractExecuteSupport;
import denny.ai.agent.domain.service.auto.step.factory.DefaultAutoAgentExecuteStrategyFactory;
import denny.ai.agent.domain.service.armory.factory.ArmoryObjectRegistry;
import denny.ai.agent.trading.domain.prompt.DebatePromptTemplate;
import denny.ai.agent.trading.domain.prompt.TradingRolePromptService;
import denny.ai.agent.trading.domain.execution.StructuredPayloadCodec;
import denny.ai.agent.trading.domain.config.TradingStateContext;
import denny.ai.agent.trading.domain.validation.NodeValidationRegistry;
import denny.ai.agent.trading.api.vo.payload.ResearchManagerPayload;
import denny.ai.agent.trading.domain.vo.TradingContextVO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Service;

import jakarta.annotation.Resource;

/**
 * 研究主管节点。
 */
@Slf4j
@Service
public class ResearchManagerNode extends AbstractExecuteSupport {

    public static final String TRADING_CONTEXT_KEY = "trading_context";

    @Resource
    private ArmoryObjectRegistry armoryObjectRegistry;

    @Resource private TradingRolePromptService rolePromptService;
    @Resource private StructuredPayloadCodec structuredPayloadCodec;
    @Resource private ResearchManagerInputFactory researchManagerInputFactory;

    @Override
    public String doApply(ExecuteCommandEntity requestParameter,
                           DefaultAutoAgentExecuteStrategyFactory.DynamicContext dynamicContext) throws Exception {
        log.info("=== 研究主管节点执行开始 ===");

        TradingContextVO context = dynamicContext.getValue(TRADING_CONTEXT_KEY);
        if (context == null || context.getInvestmentDebate() == null) {
            log.error("交易上下文或辩论上下文为空");
            return "error: no debate context";
        }

        prepare(context, dynamicContext);
        return "research_judgment_prepared";
    }

    public ResearchManagerPayload prepare(TradingContextVO context,
                                    DefaultAutoAgentExecuteStrategyFactory.DynamicContext dynamicContext) {
        try {
            return prepareInternal(context, dynamicContext);
        } catch (RuntimeException error) {
            log.error("节点执行异常: nodeName=ResearchManagerNode, ticker={}", tickerOf(context), error);
            throw error;
        }
    }

    private ResearchManagerPayload prepareInternal(TradingContextVO context,
                                    DefaultAutoAgentExecuteStrategyFactory.DynamicContext dynamicContext) {
        if (context == null || context.getInvestmentDebate() == null) {
            throw new IllegalArgumentException("trading or debate context is missing");
        }
        TradingContextVO.InvestmentDebateVO debate = context.getInvestmentDebate();
        String ticker = context.getStockInfo().getTicker();

        sendDebateEvent(dynamicContext, "research_manager_start",
                "研究主管开始评估辩论 - 第 " + (debate.getCurrentRound() + 1) + " 轮");

        String llmResponse = evaluateDebate(ticker, debate, dynamicContext);

        return parseEvaluation(llmResponse);
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

    private String evaluateDebate(String ticker,
                                TradingContextVO.InvestmentDebateVO debate,
                                DefaultAutoAgentExecuteStrategyFactory.DynamicContext dynamicContext) {
        TradingContextVO context = dynamicContext.getValue(TRADING_CONTEXT_KEY);
        NodeValidationRegistry registry = dynamicContext.getValue(
                TradingStateContext.VALIDATION_REGISTRY_KEY);
        if (registry == null) {
            registry = new NodeValidationRegistry();
        }
        ResearchManagerInput managerInput = inputFactory().create(
                context, registry, debate.getCurrentRound() + 1);
        java.util.Map<String, Object> debateHistory = java.util.Map.of(
                "bull", managerInput.validatedBullHistory(),
                "bear", managerInput.validatedBearHistory());
        java.util.Map<String, Object> validationStatus = java.util.Map.of(
                "nodes", managerInput.validationStatuses(),
                "dataQualityWarnings", managerInput.dataQualityWarnings());
        String prompt = rolePromptService.render("6008", context, dynamicContext,
                java.util.Map.of(
                        "analystReports", structuredPayloadCodec.toJson(
                                managerInput.validatedAnalystReports()),
                        "debateHistory", structuredPayloadCodec.toJson(debateHistory),
                        "validationStatus", structuredPayloadCodec.toJson(validationStatus),
                        "currentRound", managerInput.currentRound()),
                ResearchManagerPayload.class);

        ChatClient chatClient = getChatClientByClientId("6008", 0);

        long startAt = System.currentTimeMillis();
        log.info("研究主管调用LLM | prompt长度={}", prompt.length());
        if (!shouldContinueSse(dynamicContext)) {
            throw new IllegalStateException("SSE已关闭，取消研究主管调用");
        }
        String response = collectStreamingResponse(denny.ai.agent.trading.domain.execution.TradingChatMemory.apply(
                chatClient.prompt().user(prompt), context, dynamicContext, "ResearchManagerNode"),
                "ResearchManagerNode", getSseEventSink(dynamicContext));
        long latencyMs = System.currentTimeMillis() - startAt;

        log.info("研究主管LLM响应 | prompt长度={} | 响应长度={} | 耗时={}ms",
                prompt.length(), response.length(), latencyMs);

        return response;
    }

    private ResearchManagerInputFactory inputFactory() {
        return researchManagerInputFactory == null
                ? new ResearchManagerInputFactory() : researchManagerInputFactory;
    }

    private ResearchManagerPayload parseEvaluation(String llmResponse) {
        return structuredPayloadCodec.parse(llmResponse, ResearchManagerPayload.class);
    }

    private void sendDebateEvent(DefaultAutoAgentExecuteStrategyFactory.DynamicContext dynamicContext,
                               String subType, String content) {
        AutoAgentExecuteResultEntity event = AutoAgentExecuteResultEntity.builder()
                .type("debate")
                .subType(subType)
                .step(dynamicContext.getStep())
                .content(content)
                .completed(false)
                .timestamp(System.currentTimeMillis())
                .build();

        sendSseResult(dynamicContext, event);
    }
}
