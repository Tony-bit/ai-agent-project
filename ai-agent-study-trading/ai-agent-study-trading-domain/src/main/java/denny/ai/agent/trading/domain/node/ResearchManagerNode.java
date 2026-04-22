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
 * <p>
 * 职责：
 * 1. 读取 InvestmentDebateVO 中当前轮次的双方论点
 * 2. 调用 ChatClient（deep_think_model）进行综合判断
 * 3. 决定是否需要下一轮辩论
 * 4. 若辩论结束，输出 judgeDecision（综合判断结果）
 * 5. SSE 发送 debate_complete 事件
 */
@Slf4j
@Service
public class ResearchManagerNode extends AbstractExecuteSupport {

    public static final String TRADING_CONTEXT_KEY = "trading_context";
    public static final String TRADING_STEP_KEY = "trading_step";

    @Resource
    private ArmoryObjectRegistry armoryObjectRegistry;

    @Override
    protected String doApply(ExecuteCommandEntity requestParameter,
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

        // 调用 LLM 进行综合判断
        String llmResponse = evaluateDebate(ticker, debate, dynamicContext);

        // 解析 LLM 响应
        parseAndUpdateDebate(debate, llmResponse);

        // 检查是否需要继续辩论
        if (debate.isDebateComplete() || debate.getCurrentRound() >= debate.getMaxRounds()) {
            sendDebateEvent(dynamicContext, "debate_complete", JSON.toJSONString(debate));
            dynamicContext.setValue(TRADING_STEP_KEY, "trader_decision");
            log.info("辩论结束，研究主管判断: {}", debate.getConclusion());
        } else {
            // 推进到下一轮
            debate.nextRound();
            dynamicContext.setValue(TRADING_STEP_KEY, "investment_debate_next_round");
            log.info("辩论继续，第 {} 轮完成，进入第 {} 轮", debate.getCurrentRound() - 1, debate.getCurrentRound());
        }

        return "research_judgment_completed";
    }

    @Override
    public StrategyHandler<ExecuteCommandEntity, DefaultAutoAgentExecuteStrategyFactory.DynamicContext, String> get(
            ExecuteCommandEntity requestParameter,
            DefaultAutoAgentExecuteStrategyFactory.DynamicContext dynamicContext) throws Exception {
        return null;
    }

    /**
     * 调用 LLM 评估辩论。
     */
    private String evaluateDebate(String ticker,
                                TradingContextVO.InvestmentDebateVO debate,
                                DefaultAutoAgentExecuteStrategyFactory.DynamicContext dynamicContext) {
        // 构建辩论历史
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

        ChatClient chatClient = getChatClientByClientId("default", 0);

        long startAt = System.currentTimeMillis();
        String response = chatClient.prompt().user(prompt).call().content();
        long latencyMs = System.currentTimeMillis() - startAt;

        log.info("研究主管 LLM 响应耗时: {}ms", latencyMs);

        return response;
    }

    /**
     * 解析 LLM 响应并更新辩论状态。
     */
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
            // 降级处理
            debate.setConclusion("综合评估：多空双方各有论据，建议进入下一阶段分析。");
            debate.setNeedMoreDebate(false);
        }
    }

    /**
     * 从 LLM 响应中提取 JSON 字符串。
     */
    private String extractJson(String response) {
        String trimmed = response.trim();
        int jsonStart = trimmed.indexOf("{");
        int jsonEnd = trimmed.lastIndexOf("}");
        if (jsonStart >= 0 && jsonEnd > jsonStart) {
            return trimmed.substring(jsonStart, jsonEnd + 1);
        }
        return "{}";
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
