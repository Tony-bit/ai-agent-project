package denny.ai.agent.trading.domain.node;

import cn.bugstack.wrench.design.framework.tree.StrategyHandler;
import denny.ai.agent.domain.model.entity.ExecuteCommandEntity;
import denny.ai.agent.domain.service.auto.step.AbstractExecuteSupport;
import denny.ai.agent.domain.service.auto.step.factory.DefaultAutoAgentExecuteStrategyFactory;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 交易 Agent 根节点（已重构为哑节点）。
 * <p>
 * 路由逻辑已迁移至 Spring State Machine（TradingStateMachineConfig）。
 * 本节点保留以避免破坏策略树结构，但不再参与实际路由。
 * <p>
 * 注意：所有节点不再持有 nodeRegistry 引用，彻底消除循环依赖。
 */
@Slf4j
@Service
public class TradingRootNode extends AbstractExecuteSupport {

    @Override
    protected String doApply(ExecuteCommandEntity requestParameter,
                           DefaultAutoAgentExecuteStrategyFactory.DynamicContext dynamicContext) throws Exception {
        log.warn("TradingRootNode.doApply() 被调用，但路由逻辑已迁移至状态机，此方法不再执行任何操作");
        return "trading_root_noop";
    }

    @Override
    public StrategyHandler<ExecuteCommandEntity, DefaultAutoAgentExecuteStrategyFactory.DynamicContext, String> get(
            ExecuteCommandEntity requestParameter,
            DefaultAutoAgentExecuteStrategyFactory.DynamicContext dynamicContext) throws Exception {
        return null;
    }
}
