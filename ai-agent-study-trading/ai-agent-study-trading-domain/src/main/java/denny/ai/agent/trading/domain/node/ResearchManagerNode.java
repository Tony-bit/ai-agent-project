package denny.ai.agent.trading.domain.node;

import cn.bugstack.wrench.design.framework.tree.StrategyHandler;
import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import denny.ai.agent.domain.model.entity.AutoAgentExecuteResultEntity;
import denny.ai.agent.domain.model.entity.ExecuteCommandEntity;
import denny.ai.agent.domain.service.auto.step.AbstractExecuteSupport;
import denny.ai.agent.domain.service.auto.step.factory.DefaultAutoAgentExecuteStrategyFactory;
import denny.ai.agent.domain.service.armory.factory.ArmoryObjectRegistry;
import denny.ai.agent.trading.domain.config.TradingDriver;
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

        TradingContextVO.InvestmentDebateVO debate = context.getInvestmentDebate();
        String ticker = context.getStockInfo().getTicker();

        sendDebateEvent(dynamicContext, "research_manager_start",
                "研究主管开始评估辩论 - 第 " + (debate.getCurrentRound() + 1) + " 轮");

        String llmResponse = evaluateDebate(ticker, debate, dynamicContext);

        parseAndUpdateDebate(debate, llmResponse);

        decideDebateContinuation(debate);

        return "research_judgment_completed";
    }

    @Override
    public StrategyHandler<ExecuteCommandEntity, DefaultAutoAgentExecuteStrategyFactory.DynamicContext, String> get(
            ExecuteCommandEntity requestParameter,
            DefaultAutoAgentExecuteStrategyFactory.DynamicContext dynamicContext) throws Exception {
        return null;
    }

    private void decideDebateContinuation(TradingContextVO.InvestmentDebateVO debate) {
        if (TradingDriver.getCurrent() == null) {
            log.warn("No TradingDriver available for debate continuation");
            return;
        }

        TradingDriver currentDriver = TradingDriver.getCurrent();
        boolean roundExhausted = debate.getCurrentRound() >= debate.getMaxRounds();
        boolean debateComplete = debate.isDebateComplete();
        boolean needMore = debate.isNeedMoreDebate();

        if (roundExhausted || debateComplete) {
            currentDriver.debateFinish();
            log.info("辩论结束判断: roundExhausted={}, debateComplete={}", roundExhausted, debateComplete);
        } else if (needMore) {
            currentDriver.debateContinue();
            log.info("辩论继续: needMoreDebate=true");
        } else {
            currentDriver.debateFinish();
            log.info("辩论结束（保守兜底）: needMoreDebate={}", needMore);
        }
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
        String response = chatClient.prompt().user(prompt).call().content();
        long latencyMs = System.currentTimeMillis() - startAt;

        log.info("研究主管LLM响应 | prompt长度={} | 响应长度={} | 耗时={}ms",
                prompt.length(), response.length(), latencyMs);

        return response;
    }

    private void parseAndUpdateDebate(TradingContextVO.InvestmentDebateVO debate, String llmResponse) {
        try {
            String jsonStr = extractJson(llmResponse);
            JSONObject json = JSON.parseObject(jsonStr);

            if (json.containsKey("overallScore")) {
                debate.setOverallScore(json.getDouble("overallScore"));
            }
            if (json.containsKey("conclusion")) {
                debate.setConclusion(json.getString("conclusion"));
                debate.setJudgeDecision(json.getString("conclusion"));
            }
            if (json.containsKey("needMoreDebate")) {
                debate.setNeedMoreDebate(json.getBoolean("needMoreDebate"));
            }

            log.info("辩论评估结果: overallScore={}, conclusion={}, needMoreDebate={}",
                    debate.getOverallScore(), debate.getConclusion(), debate.isNeedMoreDebate());
        } catch (Exception e) {
            log.error("解析研究主管评估响应失败: {}", llmResponse, e);
            debate.setConclusion("综合评估：多空双方各有论据，建议进入下一阶段分析。");
            debate.setNeedMoreDebate(false);
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
