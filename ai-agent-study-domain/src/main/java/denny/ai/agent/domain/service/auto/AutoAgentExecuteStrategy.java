package denny.ai.agent.domain.service.auto;

import cn.bugstack.wrench.design.framework.tree.StrategyHandler;
import com.alibaba.fastjson.JSON;
import denny.ai.agent.domain.adapter.repository.IAgentRepository;
import denny.ai.agent.domain.model.entity.AutoAgentExecuteResultEntity;
import denny.ai.agent.domain.model.entity.ExecuteCommandEntity;
import denny.ai.agent.domain.model.valobj.AiAgentClientFlowConfigVO;
import denny.ai.agent.domain.service.auto.step.factory.DefaultAutoAgentExecuteStrategyFactory;
import denny.ai.agent.domain.service.excute.IExecuteStrategy;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyEmitter;

import java.util.Map;
import java.util.UUID;

/**
 * 自动执行策略
 *
 * @author denny
 * 2025/8/5 09:49
 */
@Slf4j
@Service
public class AutoAgentExecuteStrategy implements IExecuteStrategy {

    @Resource
    private DefaultAutoAgentExecuteStrategyFactory defaultAutoAgentExecuteStrategyFactory;

    @Resource
    private IAgentRepository repository;

    @Override
    public void execute(ExecuteCommandEntity executeCommandEntity, ResponseBodyEmitter emitter) throws Exception {
        // 创建动态上下文并初始化必要字段
        DefaultAutoAgentExecuteStrategyFactory.DynamicContext dynamicContext = new DefaultAutoAgentExecuteStrategyFactory.DynamicContext();
        dynamicContext.setMaxStep(executeCommandEntity.getMaxStep() != null ? executeCommandEntity.getMaxStep() : 3);
        dynamicContext.setExecutionHistory(new StringBuilder());
        dynamicContext.setCurrentTask(executeCommandEntity.getMessage());
        dynamicContext.setValue("emitter", emitter);

        log.info(">>> [AutoAgentExecuteStrategy.execute] dynamicContext创建, hashCode={}, dataObjects={}",
                System.identityHashCode(dynamicContext), dynamicContext.getDataObjects().keySet());

        // 初始化追踪ID
        String traceId = UUID.randomUUID().toString().replace("-", "");
        dynamicContext.setTraceId(traceId);

        // 根据智能体类型构建上下文
        if (ExecuteCommandEntity.AGENT_TYPE_INSPECTION.equals(executeCommandEntity.getAgentType())) {
            // 巡检流程：提前加载客户端配置，供 IntelligentInspection 直接使用
            initInspectionContext(executeCommandEntity, dynamicContext);
        }

        StrategyHandler<ExecuteCommandEntity, DefaultAutoAgentExecuteStrategyFactory.DynamicContext, String> executeHandler
                = defaultAutoAgentExecuteStrategyFactory.armoryStrategyHandler();

        log.info("开始执行处理器链，agentType={}, aiAgentId={}", executeCommandEntity.getAgentType(), executeCommandEntity.getAiAgentId());

        String apply = executeHandler.apply(executeCommandEntity, dynamicContext);
        log.info("测试结果:{}", apply);

        // 发送完成标识并关闭流
        ResponseBodyEmitter emitter2 = dynamicContext.getValue("emitter");
        if (emitter2 == null) {
            log.error("【SSE致命错误】execute方法中的emitter为空！");
        } else {
            try {
                AutoAgentExecuteResultEntity completeResult = AutoAgentExecuteResultEntity.createCompleteResult(executeCommandEntity.getSessionId());
                String sseData = "data: " + JSON.toJSONString(completeResult) + "\n\n";
                log.info(">>> [AutoAgentStrategy] 发送最终完成标识: {}", sseData);
                emitter2.send(sseData);
                log.info("<<< [AutoAgentStrategy] 完成标识发送成功，准备关闭流");
                emitter2.complete();
                log.info("<<< [AutoAgentStrategy] SSE流已关闭");
            } catch (Exception e) {
                log.error("【SSE致命错误】发送完成标识或关闭SSE流失败：{}", e.getMessage(), e);
            }
        }
    }

    /**
     * 初始化巡检流程的上下文
     * <p>
     * 巡检节点独立执行，不需要经过 RootNode 的多步流程链，
     * 所以需要在此处提前加载客户端配置。
     */
    private void initInspectionContext(ExecuteCommandEntity executeCommandEntity,
                                       DefaultAutoAgentExecuteStrategyFactory.DynamicContext dynamicContext) {
        log.info("=== 巡检智能体执行开始 ===");
        log.info("会话ID: {}", executeCommandEntity.getSessionId());
        log.info("追踪ID: {}", dynamicContext.getTraceId());

        // 加载客户端配置
        Map<String, AiAgentClientFlowConfigVO> aiAgentClientFlowConfigVOMap = repository.queryAiAgentClientFlowConfig(executeCommandEntity.getAiAgentId());
        dynamicContext.setAiAgentClientFlowConfigVOMap(aiAgentClientFlowConfigVOMap);

        log.info("客户端配置加载完成，共 {} 个节点配置",
                aiAgentClientFlowConfigVOMap != null ? aiAgentClientFlowConfigVOMap.size() : 0);
    }

}
