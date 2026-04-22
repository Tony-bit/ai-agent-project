package denny.ai.agent.trading.domain.node;

import cn.bugstack.wrench.design.framework.tree.StrategyHandler;
import com.alibaba.fastjson.JSON;
import denny.ai.agent.domain.model.entity.AutoAgentExecuteResultEntity;
import denny.ai.agent.domain.model.entity.ExecuteCommandEntity;
import denny.ai.agent.domain.service.auto.step.AbstractExecuteSupport;
import denny.ai.agent.domain.service.auto.step.factory.DefaultAutoAgentExecuteStrategyFactory;
import denny.ai.agent.domain.service.armory.factory.ArmoryObjectRegistry;
import denny.ai.agent.trading.api.vo.TradeDecisionEnum;
import denny.ai.agent.trading.domain.prompt.RiskAnalystPromptTemplate;
import denny.ai.agent.trading.domain.vo.TradingContextVO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Service;

import jakarta.annotation.Resource;
import java.util.ArrayList;
import java.util.List;

/**
 * 激进风控分析师节点。
 * <p>
 * 职责：
 * 1. 读取 TradingContextVO.investmentPlan
 * 2. 根据激进风格给出风控意见
 * 3. 追加意见到 RiskDebateVO
 * 4. SSE 发送 risk_debate 事件
 */
@Slf4j
@Service
public class AggressiveRiskAnalystNode extends AbstractExecuteSupport {

    public static final String TRADING_CONTEXT_KEY = "trading_context";
    public static final String TRADING_STEP_KEY = "trading_step";

    @Resource
    private ArmoryObjectRegistry armoryObjectRegistry;

    @Override
    protected String doApply(ExecuteCommandEntity requestParameter,
                           DefaultAutoAgentExecuteStrategyFactory.DynamicContext dynamicContext) throws Exception {
        log.info("=== 激进风控分析师节点执行开始 ===");

        TradingContextVO context = dynamicContext.getValue(TRADING_CONTEXT_KEY);
        if (context == null) {
            log.error("交易上下文为空");
            return "error: no trading context";
        }

        sendRiskEvent(dynamicContext, "aggressive_start", "激进风控分析师开始分析...");

        // 获取或创建风控辩论上下文
        TradingContextVO.RiskDebateVO riskDebate = context.getRiskDebate();
        if (riskDebate == null) {
            riskDebate = TradingContextVO.RiskDebateVO.builder()
                    .riskItems(new ArrayList<>())
                    .mitigations(new ArrayList<>())
                    .build();
            context.setRiskDebate(riskDebate);
        }

        // 生成风控意见
        String opinion = generateRiskOpinion(context, dynamicContext);

        // 更新风控辩论上下文
        if (riskDebate.getAggressiveHistory() == null) {
            riskDebate.setAggressiveHistory(new ArrayList<>());
        }
        riskDebate.getAggressiveHistory().add(opinion);

        sendRiskEvent(dynamicContext, "aggressive_opinion", opinion);

        log.info("激进风控分析师分析完成");

        return "aggressive_risk_completed";
    }

    @Override
    public StrategyHandler<ExecuteCommandEntity, DefaultAutoAgentExecuteStrategyFactory.DynamicContext, String> get(
            ExecuteCommandEntity requestParameter,
            DefaultAutoAgentExecuteStrategyFactory.DynamicContext dynamicContext) throws Exception {
        return null;
    }

    private String generateRiskOpinion(TradingContextVO context,
                                    DefaultAutoAgentExecuteStrategyFactory.DynamicContext dynamicContext) {
        String prompt = RiskAnalystPromptTemplate.AGGRESSIVE_ANALYST_PROMPT.formatted(
                context.getStockInfo().getTicker(),
                context.getStockInfo().getCurrentPrice(),
                context.getInvestmentPlan() != null ? JSON.toJSONString(context.getInvestmentPlan()) : "{}"
        );

        ChatClient chatClient = getChatClientByClientId("default", 0);

        long startAt = System.currentTimeMillis();
        String response = chatClient.prompt().user(prompt).call().content();
        long latencyMs = System.currentTimeMillis() - startAt;

        log.info("激进风控分析师 LLM 响应耗时: {}ms", latencyMs);

        return response;
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
