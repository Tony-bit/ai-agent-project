package denny.ai.agent.domain.service.armory;

import cn.bugstack.wrench.design.framework.tree.AbstractMultiThreadStrategyRouter;
import denny.ai.agent.domain.adapter.repository.IAgentRepository;
import denny.ai.agent.domain.model.entity.ArmoryCommandEntity;
import denny.ai.agent.domain.service.armory.factory.ArmoryObjectRegistry;
import denny.ai.agent.domain.service.armory.factory.DynamicContext;
import jakarta.annotation.Resource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationContext;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeoutException;

public abstract class AbstractArmorySupport extends AbstractMultiThreadStrategyRouter<ArmoryCommandEntity, DynamicContext, String> {

    private final Logger log = LoggerFactory.getLogger(AbstractArmorySupport.class);

    @Resource
    protected ApplicationContext applicationContext;

    @Resource
    protected ThreadPoolExecutor threadPoolExecutor;

    @Resource
    protected IAgentRepository repository;

    @Resource
    protected ArmoryObjectRegistry armoryObjectRegistry;

    @Override
    protected void multiThread(ArmoryCommandEntity requestParameter, DynamicContext dynamicContext) throws ExecutionException, InterruptedException, TimeoutException {
        // 缺省的
    }

    protected String getBeanName(String beanId) {
        return null;
    }

    protected String getDataName() {
        return null;
    }

    /**
     * 通用对象注册方法：优先放入 Armory 注册表，不再动态改 BeanDefinition。
     */
    protected synchronized <T> void registerBean(String beanName, Class<T> beanClass, T beanInstance) {
        armoryObjectRegistry.put(beanName, beanInstance);
        log.info("成功注册对象到 ArmoryRegistry: {}", beanName);
    }

    @SuppressWarnings("unchecked")
    protected <T> T getBean(String beanName) {
        // 1. 优先从 Armory 注册表获取
        Object fromRegistry = armoryObjectRegistry.get(beanName);
        if (fromRegistry != null) {
            return (T) fromRegistry;
        }

        // 2. 回退到 Spring 容器（兼容原有静态Bean）
        return (T) applicationContext.getBean(beanName);
    }
}
