package denny.ai.agent.domain.service.auto.step.routing;

import denny.ai.agent.domain.model.valobj.SubTask;
import denny.ai.agent.domain.service.auto.step.factory.DefaultAutoAgentExecuteStrategyFactory;

import java.lang.reflect.Method;
import java.util.Map;

/**
 * TradingStarter 执行器适配器
 * <p>
 * 将 TradingStarter 的 startForSubTask 方法适配为 ExecutorAdapter 接口，
 * 避免 ai-agent-study-domain 直接依赖 ai-agent-study-trading-domain。
 * </p>
 *
 * @author denny
 * 2026/05/31
 */
public class TradingStarterAdapter implements ExecutorAdapter {

    private final Object tradingStarter;
    private final Method startForSubTaskMethod;

    public TradingStarterAdapter(Object tradingStarter, Method startForSubTaskMethod) {
        this.tradingStarter = tradingStarter;
        this.startForSubTaskMethod = startForSubTaskMethod;
    }

    @Override
    public String executeSubTask(SubTask subTask,
                                DefaultAutoAgentExecuteStrategyFactory.DynamicContext dynamicContext) throws Exception {
        try {
            return (String) startForSubTaskMethod.invoke(
                    tradingStarter,
                    subTask.getContent(),
                    subTask.getSlots(),
                    dynamicContext
            );
        } catch (java.lang.reflect.InvocationTargetException e) {
            throw (Exception) e.getCause();
        }
    }
}
