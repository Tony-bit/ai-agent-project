package denny.ai.agent.domain.service.auto.step.routing;

import denny.ai.agent.domain.model.valobj.SubTask;
import denny.ai.agent.domain.service.auto.step.factory.DefaultAutoAgentExecuteStrategyFactory;

/**
 * 执行器适配器接口
 * <p>
 * 定义多任务执行场景下，各执行节点的标准执行方法。
 * </p>
 *
 * @author denny
 * 2026/05/31
 */
public interface ExecutorAdapter {

    /**
     * 执行子任务
     *
     * @param subTask         子任务
     * @param dynamicContext  动态上下文
     * @return 执行结果文本
     */
    String executeSubTask(SubTask subTask, DefaultAutoAgentExecuteStrategyFactory.DynamicContext dynamicContext) throws Exception;
}
