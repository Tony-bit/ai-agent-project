package denny.ai.agent.trading.domain.node;

import cn.bugstack.wrench.design.framework.tree.StrategyHandler;
import denny.ai.agent.domain.model.entity.AutoAgentExecuteResultEntity;
import denny.ai.agent.domain.model.entity.ExecuteCommandEntity;
import denny.ai.agent.domain.service.auto.step.AbstractExecuteSupport;
import denny.ai.agent.domain.service.auto.step.factory.DefaultAutoAgentExecuteStrategyFactory;
import denny.ai.agent.domain.service.armory.factory.ArmoryObjectRegistry;
import denny.ai.agent.trading.api.vo.*;
import denny.ai.agent.trading.domain.prompt.DebatePromptTemplate;
import denny.ai.agent.trading.domain.prompt.TradingRolePromptService;
import denny.ai.agent.trading.domain.execution.StructuredPayloadCodec;
import denny.ai.agent.trading.api.vo.payload.ResearchArgumentPayload;
import denny.ai.agent.trading.domain.vo.TradingContextVO;
import denny.ai.agent.trading.domain.vo.TradingContextVO.InvestmentDebateVO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Service;

import jakarta.annotation.Resource;

/**
 * 空头研究员节点。
 */
@Slf4j
@Service
public class BearResearcherNode extends AbstractExecuteSupport {

    public static final String TRADING_CONTEXT_KEY = "trading_context";

    @Resource
    private ArmoryObjectRegistry armoryObjectRegistry;

    @Resource private TradingRolePromptService rolePromptService;
    @Resource private StructuredPayloadCodec structuredPayloadCodec;

    @Override
    public String doApply(ExecuteCommandEntity requestParameter,
                           DefaultAutoAgentExecuteStrategyFactory.DynamicContext dynamicContext) throws Exception {
        log.info("=== 空头研究员节点执行开始 ===");

        TradingContextVO context = dynamicContext.getValue(TRADING_CONTEXT_KEY);
        if (context == null) {
            log.error("交易上下文为空");
            return "error: no trading context";
        }

        prepare(context, dynamicContext);
        return "bear_analysis_prepared";
    }

    public ResearchArgumentPayload prepare(TradingContextVO context,
                          DefaultAutoAgentExecuteStrategyFactory.DynamicContext dynamicContext) {
        if (context == null || context.getStockInfo() == null) {
            throw new IllegalArgumentException("trading context or stock info is missing");
        }
        sendDebateEvent(dynamicContext, "bear_start", "空头研究员开始分析...");

        ResearchArgumentPayload bearThesis = generateBearThesis(context, dynamicContext);

        log.info("空头研究员分析完成: ticker={}", context.getStockInfo().getTicker());
        return bearThesis;
    }

    @Override
    public StrategyHandler<ExecuteCommandEntity, DefaultAutoAgentExecuteStrategyFactory.DynamicContext, String> get(
            ExecuteCommandEntity requestParameter,
            DefaultAutoAgentExecuteStrategyFactory.DynamicContext dynamicContext) throws Exception {
        return null;
    }

    private String buildReportSummary(TradingContextVO context) {
        StringBuilder sb = new StringBuilder();

        if (context.getFundamentalReport() != null) {
            sb.append("【基本面分析】\n").append(context.getFundamentalReport().getSummary()).append("\n\n");
        }
        if (context.getTechnicalReport() != null) {
            sb.append("【技术面分析】\n").append(context.getTechnicalReport().getSummary()).append("\n\n");
        }
        if (context.getSentimentReport() != null) {
            sb.append("【情绪面分析】\n").append(context.getSentimentReport().getSummary()).append("\n\n");
        }
        if (context.getNewsReport() != null) {
            sb.append("【新闻面分析】\n").append(context.getNewsReport().getSummary()).append("\n\n");
        }

        if (context.getDataWarnings() != null && !context.getDataWarnings().isEmpty()) {
            sb.append("【数据质量警告】以下异常仅用于降低置信度，不代表标的身份不一致：\n");
            for (String warning : context.getDataWarnings()) {
                sb.append("  - ").append(warning).append("\n");
            }
            sb.append("\n");
        }

        return sb.length() > 0 ? sb.toString() : "No analyst reports available.";
    }

    private ResearchArgumentPayload generateBearThesis(TradingContextVO context,
                                      DefaultAutoAgentExecuteStrategyFactory.DynamicContext dynamicContext) {
        java.util.Map<String, Object> reports = new java.util.LinkedHashMap<>();
        reports.put("fundamental", context.getFundamentalReport());
        reports.put("technical", context.getTechnicalReport());
        reports.put("sentiment", context.getSentimentReport());
        reports.put("news", context.getNewsReport());
        reports.put("dataWarnings", context.getDataWarnings());
        String analystReports = structuredPayloadCodec.toJson(reports);
        String debateHistory = structuredPayloadCodec.toJson(context.getInvestmentDebate());
        String prompt = rolePromptService.render("6007", context, dynamicContext,
                java.util.Map.of("analystReports", analystReports,
                        "debateHistory", debateHistory), ResearchArgumentPayload.class);

        ChatClient chatClient = getChatClientByClientId("6007", 0);

        long startAt = System.currentTimeMillis();
        log.info("空头研究员调用LLM | prompt长度={}", prompt.length());
        if (!shouldContinueSse(dynamicContext)) {
            throw new IllegalStateException("SSE已关闭，取消空头研究员调用");
        }
        String response = collectStreamingResponse(denny.ai.agent.trading.domain.execution.TradingChatMemory.apply(
                chatClient.prompt().user(prompt), context, dynamicContext, "BearResearcherNode"),
                "BearResearcherNode", getSseEventSink(dynamicContext));
        long latencyMs = System.currentTimeMillis() - startAt;

        log.info("空头研究员LLM响应 | prompt长度={} | 响应长度={} | 耗时={}ms",
                prompt.length(), response.length(), latencyMs);

        ResearchArgumentPayload payload = structuredPayloadCodec.parse(response, ResearchArgumentPayload.class);
        if (!"BEAR".equals(payload.stance())) {
            throw new denny.ai.agent.trading.domain.execution.StructuredPayloadException(
                    "bear researcher returned a non-BEAR stance");
        }
        return payload;
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
