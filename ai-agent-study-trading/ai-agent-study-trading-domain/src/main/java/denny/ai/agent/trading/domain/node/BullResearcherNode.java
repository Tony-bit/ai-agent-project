package denny.ai.agent.trading.domain.node;

import cn.bugstack.wrench.design.framework.tree.StrategyHandler;
import com.alibaba.fastjson.JSON;
import denny.ai.agent.domain.model.entity.AutoAgentExecuteResultEntity;
import denny.ai.agent.domain.model.entity.ExecuteCommandEntity;
import denny.ai.agent.domain.service.auto.step.AbstractExecuteSupport;
import denny.ai.agent.domain.service.auto.step.factory.DefaultAutoAgentExecuteStrategyFactory;
import denny.ai.agent.domain.service.armory.factory.ArmoryObjectRegistry;
import denny.ai.agent.trading.api.vo.*;
import denny.ai.agent.trading.domain.prompt.DebatePromptTemplate;
import denny.ai.agent.trading.domain.vo.TradingContextVO;
import denny.ai.agent.trading.domain.vo.TradingContextVO.InvestmentDebateVO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Service;

import jakarta.annotation.Resource;

/**
 * 多头研究员节点。
 * <p>
 * 职责：
 * 1. 读取 TradingContextVO 中所有分析师报告
 * 2. 调用 ChatClient + 多头研究员 Prompt 生成看多论点
 * 3. 将论点追加到 InvestmentDebateVO.bullHistory
 * 4. SSE 发送 debate_round 事件（多头视角）
 */
@Slf4j
@Service
public class BullResearcherNode extends AbstractExecuteSupport {

    public static final String TRADING_CONTEXT_KEY = "trading_context";
    public static final String TRADING_STEP_KEY = "trading_step";

    @Resource
    private ArmoryObjectRegistry armoryObjectRegistry;

    @Override
    protected String doApply(ExecuteCommandEntity requestParameter,
                           DefaultAutoAgentExecuteStrategyFactory.DynamicContext dynamicContext) throws Exception {
        log.info("=== 多头研究员节点执行开始 ===");

        TradingContextVO context = dynamicContext.getValue(TRADING_CONTEXT_KEY);
        if (context == null) {
            log.error("交易上下文为空");
            return "error: no trading context";
        }

        sendDebateEvent(dynamicContext, "bull_start", "多头研究员开始分析...");

        // 构建分析师报告摘要
        String reportSummary = buildReportSummary(context);

        // 调用 LLM 生成多头论点
        String bullThesis = generateBullThesis(context.getStockInfo().getTicker(), reportSummary, dynamicContext);

        // 更新辩论上下文
        InvestmentDebateVO debate = context.getInvestmentDebate();
        if (debate == null) {
            debate = InvestmentDebateVO.createNew(2);
            context.setInvestmentDebate(debate);
        }

        debate.addBullArgument(bullThesis);
        debate.addToHistory("[Round " + debate.getCurrentRound() + " - BULL] " + bullThesis);

        sendDebateEvent(dynamicContext, "bull_thesis", bullThesis);

        log.info("多头研究员分析完成: ticker={}", context.getStockInfo().getTicker());

        dynamicContext.setValue(TRADING_STEP_KEY, "investment_debate");

        return "bull_analysis_completed";
    }

    @Override
    public StrategyHandler<ExecuteCommandEntity, DefaultAutoAgentExecuteStrategyFactory.DynamicContext, String> get(
            ExecuteCommandEntity requestParameter,
            DefaultAutoAgentExecuteStrategyFactory.DynamicContext dynamicContext) throws Exception {
        return null;
    }

    /**
     * 构建分析师报告摘要。
     */
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

    /**
     * 调用 LLM 生成多头论点。
     */
    private String generateBullThesis(String ticker, String reportSummary,
                                     DefaultAutoAgentExecuteStrategyFactory.DynamicContext dynamicContext) {
        String prompt = DebatePromptTemplate.BULL_RESEARCHER_PROMPT.formatted(ticker, reportSummary);

        ChatClient chatClient = getChatClientByClientId("default", 0);

        long startAt = System.currentTimeMillis();
        String response = chatClient.prompt().user(prompt).call().content();
        long latencyMs = System.currentTimeMillis() - startAt;

        log.info("多头研究员 LLM 响应耗时: {}ms", latencyMs);

        return response;
    }

    /**
     * 发送辩论事件。
     */
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
