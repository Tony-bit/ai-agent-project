package denny.ai.agent.domain.service.auto.step.routing;

import cn.bugstack.wrench.design.framework.tree.StrategyHandler;
import denny.ai.agent.domain.model.entity.ExecuteCommandEntity;
import denny.ai.agent.domain.model.valobj.SubTask;
import denny.ai.agent.domain.service.auto.step.AbstractExecuteSupport;
import denny.ai.agent.domain.service.auto.step.chat.GeneralChatNode;
import denny.ai.agent.domain.service.auto.step.factory.DefaultAutoAgentExecuteStrategyFactory;
import denny.ai.agent.domain.service.auto.step.pe.Step1AnalyzerNode;
import denny.ai.agent.domain.service.auto.step.react.IntelligentInspection;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Service;

import jakarta.annotation.Resource;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 多任务执行节点
 * <p>
 * 负责循环执行 taskList 中的所有子任务，并汇总结果。
 * </p>
 * <p>
 * 执行策略：根据 SubTask.executorNode 直接调用对应的 Spring Bean，无需再次判断 intent 类型。
 * </p>
 *
 * @author denny
 * 2026/05/31
 */
@Slf4j
@Service("multiTaskExecutionNode")
public class MultiTaskExecutionNode extends AbstractExecuteSupport {

    public static final String TASK_LIST_KEY = "taskList";
    public static final String ORIGINAL_MESSAGE_KEY = "originalMessage";

    @Resource
    private GeneralChatNode generalChatNode;

    @Resource
    private Step1AnalyzerNode step1AnalyzerNode;

    @Resource
    private IntelligentInspection intelligentInspection;

    private final Map<String, ExecutorAdapter> executorMap = new HashMap<>();

    @jakarta.annotation.PostConstruct
    public void init() {
        executorMap.put("generalChatNode", generalChatNode);
        executorMap.put("step1AnalyzerNode", step1AnalyzerNode);
        executorMap.put("intelligentInspection", intelligentInspection);
    }

    public String executeSubTask(SubTask subTask,
                                DefaultAutoAgentExecuteStrategyFactory.DynamicContext dynamicContext) throws Exception {

        String executorNode = subTask.getExecutorNode();
        log.info(">>> 执行子任务 [{}/{}]: taskId={}, executorNode={}, content={}",
                subTask.getTaskIndex(), subTask.getTotalTasks(),
                subTask.getTaskId(), executorNode, subTask.getContent());

        long startAt = System.currentTimeMillis();

        try {
            ExecutorAdapter executor = executorMap.get(executorNode);
            if (executor == null) {
                executor = tryGetTradingStarter(executorNode);
            }
            if (executor == null) {
                throw new IllegalStateException("未找到执行节点: " + executorNode);
            }

            String result = executor.executeSubTask(subTask, dynamicContext);

            subTask.setStatus(SubTask.SubTaskStatus.COMPLETED);
            subTask.setResult(result);
            subTask.setLatencyMs(System.currentTimeMillis() - startAt);

            log.info("<<< 子任务完成: taskId={}, executorNode={}, 耗时={}ms",
                    subTask.getTaskId(), executorNode, subTask.getLatencyMs());

            return result;

        } catch (Exception e) {
            log.error("子任务执行失败: taskId={}, executorNode={}, error={}",
                    subTask.getTaskId(), executorNode, e.getMessage(), e);
            subTask.setStatus(SubTask.SubTaskStatus.FAILED);
            subTask.setErrorMessage(e.getMessage());
            subTask.setLatencyMs(System.currentTimeMillis() - startAt);
            throw e;
        }
    }

    private ExecutorAdapter tryGetTradingStarter(String executorNode) {
        if (!"tradingStarter".equals(executorNode)) {
            return null;
        }
        Object starter = getBean(executorNode);
        if (starter == null) {
            return null;
        }
        String className = starter.getClass().getName();
        if (!className.equals("denny.ai.agent.trading.domain.config.TradingStarter")) {
            return null;
        }
        try {
            java.lang.reflect.Method method = starter.getClass().getMethod("startForSubTask",
                    String.class, Map.class,
                    denny.ai.agent.domain.service.auto.step.factory.DefaultAutoAgentExecuteStrategyFactory.DynamicContext.class);
            TradingStarterAdapter adapter = new TradingStarterAdapter(starter, method);
            return adapter;
        } catch (NoSuchMethodException e) {
            log.warn("TradingStarter 未找到 startForSubTask 方法: {}", e.getMessage());
            return null;
        }
    }

    private String invokeExecutor(Object executor, SubTask subTask,
                                DefaultAutoAgentExecuteStrategyFactory.DynamicContext dynamicContext) throws Exception {
        if (executor instanceof ExecutorAdapter) {
            return ((ExecutorAdapter) executor).executeSubTask(subTask, dynamicContext);
        }
        String className = executor.getClass().getName();
        if (className.equals("denny.ai.agent.trading.domain.config.TradingStarter")) {
            try {
                java.lang.reflect.Method method = executor.getClass().getMethod("startForSubTask",
                        String.class, Map.class,
                        denny.ai.agent.domain.service.auto.step.factory.DefaultAutoAgentExecuteStrategyFactory.DynamicContext.class);
                return (String) method.invoke(executor, subTask.getContent(), subTask.getSlots(), dynamicContext);
            } catch (java.lang.reflect.InvocationTargetException e) {
                throw (Exception) e.getCause();
            }
        }
        throw new IllegalStateException("不支持的执行节点类型: " + className);
    }

    @Override
    protected String doApply(ExecuteCommandEntity request,
                            DefaultAutoAgentExecuteStrategyFactory.DynamicContext dynamicContext) throws Exception {

        List<SubTask> taskList = dynamicContext.getValue(TASK_LIST_KEY);
        String originalMessage = dynamicContext.getValue(ORIGINAL_MESSAGE_KEY);

        log.info("=== 多任务执行开始，共 {} 个任务 ===", taskList.size());

        for (SubTask task : taskList) {
            task.setStatus(SubTask.SubTaskStatus.IN_PROGRESS);
            try {
                executeSubTask(task, dynamicContext);
            } catch (Exception e) {
                log.warn("子任务执行异常，继续执行下一个: taskId={}", task.getTaskId());
            }
        }

        log.info("=== 多任务执行完成，开始 LLM 汇总 ===");

        String summary = summarizeResults(originalMessage, taskList, dynamicContext);

        log.info("=== LLM 汇总完成，长度={} ===", summary.length());

        dynamicContext.setValue("generalChatResponse", summary);

        return summary;
    }

    private String summarizeResults(String originalMessage, List<SubTask> taskList,
                                  DefaultAutoAgentExecuteStrategyFactory.DynamicContext dynamicContext) {
        log.info("开始 LLM 汇总，共 {} 个结果", taskList.size());

        String summaryPrompt = buildSummaryPrompt(originalMessage, taskList);

        ChatClient chatClient = getChatClientByClientId("3001", 0);

        String fullContent = chatClient.prompt(summaryPrompt).call().content();
        return fullContent;
    }

    String buildSummaryPrompt(String originalMessage, List<SubTask> taskList) {
        StringBuilder sb = new StringBuilder();
        sb.append("## 用户原始请求\n").append(originalMessage).append("\n\n");
        sb.append("## 任务执行结果\n");

        for (SubTask task : taskList) {
            sb.append(String.format("[任务 %d/%d] %s\n",
                    task.getTaskIndex(), task.getTotalTasks(), task.getContent()));
            sb.append("状态: ").append(task.getStatus()).append("\n");

            if (task.getStatus() == SubTask.SubTaskStatus.COMPLETED) {
                sb.append("结果:\n").append(task.getResult()).append("\n");
            } else if (task.getStatus() == SubTask.SubTaskStatus.FAILED) {
                sb.append("错误: ").append(task.getErrorMessage()).append("\n");
            }
            sb.append("\n");
        }

        sb.append("## 要求\n");
        sb.append("请将以上执行结果整理成一份连贯，自然的回复返回给用户。\n");
        sb.append("保留各任务的核心信息，去除冗余内容，逻辑清晰地组织。\n");

        return sb.toString();
    }

    @Override
    public StrategyHandler<ExecuteCommandEntity,
            DefaultAutoAgentExecuteStrategyFactory.DynamicContext, String> get(
            ExecuteCommandEntity request,
            DefaultAutoAgentExecuteStrategyFactory.DynamicContext dynamicContext) throws Exception {
        return defaultStrategyHandler;
    }
}
