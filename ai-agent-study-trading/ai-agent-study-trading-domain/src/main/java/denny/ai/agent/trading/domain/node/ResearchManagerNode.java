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

    public DebateEvaluation prepare(TradingContextVO context,
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

    @Override
    public StrategyHandler<ExecuteCommandEntity, DefaultAutoAgentExecuteStrategyFactory.DynamicContext, String> get(
            ExecuteCommandEntity requestParameter,
            DefaultAutoAgentExecuteStrategyFactory.DynamicContext dynamicContext) throws Exception {
        return null;
    }

    private String evaluateDebate(String ticker,
                                TradingContextVO.InvestmentDebateVO debate,
                                DefaultAutoAgentExecuteStrategyFactory.DynamicContext dynamicContext) {
        StringBuilder bullHistory = new StringBuilder();
        for (int i = 0; i < debate.getBullHistory().size(); i++) {
            bullHistory.append("Round ").append(i + 1).append(": ").append(debate.getBullHistory().get(i)).append("\n\n");
        }

        StringBuilder bearHistory = new StringBuilder();
        for (int i = 0; i < debate.getBearHistory().size(); i++) {
            bearHistory.append("Round ").append(i + 1).append(": ").append(debate.getBearHistory().get(i)).append("\n\n");
        }

        String prompt = DebatePromptTemplate.RESEARCH_MANAGER_PROMPT.formatted(
                ticker,
                debate.getCurrentRound() + 1,
                bullHistory.toString(),
                bearHistory.toString()
        );

        ChatClient chatClient = getChatClientByClientId("6008", 0);

        long startAt = System.currentTimeMillis();
        log.info("研究主管调用LLM | prompt长度={}", prompt.length());
        if (!shouldContinueSse(dynamicContext)) {
            log.info("SSE已关闭，跳过研究主管LLM调用");
            return "";
        }
        String response = collectStreamingResponse(chatClient.prompt().user(prompt),
                "ResearchManagerNode", getSseEventSink(dynamicContext));
        long latencyMs = System.currentTimeMillis() - startAt;

        log.info("研究主管LLM响应 | prompt长度={} | 响应长度={} | 耗时={}ms",
                prompt.length(), response.length(), latencyMs);

        return response;
    }

    private DebateEvaluation parseEvaluation(String llmResponse) {
        try {
            String jsonStr = extractJson(llmResponse);
            JSONObject json = JSON.parseObject(jsonStr);
            Double overallScore = json.containsKey("overallScore")
                    ? json.getDouble("overallScore") : null;
            String conclusion = json.containsKey("conclusion")
                    ? json.getString("conclusion") : null;
            boolean needMoreDebate = json.containsKey("needMoreDebate")
                    && json.getBooleanValue("needMoreDebate");
            log.info("辩论评估结果: overallScore={}, conclusion={}, needMoreDebate={}",
                    overallScore, conclusion, needMoreDebate);
            return new DebateEvaluation(overallScore, conclusion, needMoreDebate);
        } catch (Exception e) {
            log.error("解析研究主管评估响应失败: {}", llmResponse, e);
            return new DebateEvaluation(null,
                    "综合评估：多空双方各有论据，建议进入下一阶段分析。", false);
        }
    }

    public record DebateEvaluation(Double overallScore,
                                   String conclusion,
                                   boolean needMoreDebate) {
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
