package denny.ai.agent.trading.domain.node;

import cn.bugstack.wrench.design.framework.tree.StrategyHandler;
import denny.ai.agent.domain.model.entity.AutoAgentExecuteResultEntity;
import denny.ai.agent.domain.model.entity.ExecuteCommandEntity;
import denny.ai.agent.domain.service.auto.step.AbstractExecuteSupport;
import denny.ai.agent.domain.service.auto.step.factory.DefaultAutoAgentExecuteStrategyFactory;
import denny.ai.agent.domain.service.armory.factory.ArmoryObjectRegistry;
import denny.ai.agent.trading.api.vo.*;
import denny.ai.agent.trading.domain.config.TradingDriver;
import denny.ai.agent.trading.domain.prompt.DebatePromptTemplate;
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

    @Override
    public String doApply(ExecuteCommandEntity requestParameter,
                           DefaultAutoAgentExecuteStrategyFactory.DynamicContext dynamicContext) throws Exception {
        log.info("=== 空头研究员节点执行开始 ===");

        TradingContextVO context = dynamicContext.getValue(TRADING_CONTEXT_KEY);
        if (context == null) {
            log.error("交易上下文为空");
            return "error: no trading context";
        }

        sendDebateEvent(dynamicContext, "bear_start", "空头研究员开始分析...");

        String reportSummary = buildReportSummary(context);

        String bearThesis = generateBearThesis(context.getStockInfo().getTicker(), reportSummary, dynamicContext);

        InvestmentDebateVO debate = context.getInvestmentDebate();
        if (debate == null) {
            debate = InvestmentDebateVO.createNew(2);
            context.setInvestmentDebate(debate);
        }

        debate.addBearArgument(bearThesis);
        debate.addToHistory("[Round " + debate.getCurrentRound() + " - BEAR] " + bearThesis);

        sendDebateEvent(dynamicContext, "bear_thesis", bearThesis);

        log.info("空头研究员分析完成: ticker={}", context.getStockInfo().getTicker());

        if (TradingDriver.getCurrent() != null) {
            TradingDriver.getCurrent().debateComplete();
        }

        return "bear_analysis_completed";
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

        return sb.length() > 0 ? sb.toString() : "No analyst reports available.";
    }

    private String generateBearThesis(String ticker, String reportSummary,
                                      DefaultAutoAgentExecuteStrategyFactory.DynamicContext dynamicContext) {
        String prompt = DebatePromptTemplate.BEAR_RESEARCHER_PROMPT.formatted(ticker, reportSummary);

        ChatClient chatClient = getChatClientByClientId("6007", 0);

        long startAt = System.currentTimeMillis();
        log.info("空头研究员调用LLM | prompt长度={}", prompt.length());
        String response = chatClient.prompt().user(prompt).call().content();
        long latencyMs = System.currentTimeMillis() - startAt;

        log.info("空头研究员LLM响应 | prompt长度={} | 响应长度={} | 耗时={}ms",
                prompt.length(), response.length(), latencyMs);

        return response;
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
