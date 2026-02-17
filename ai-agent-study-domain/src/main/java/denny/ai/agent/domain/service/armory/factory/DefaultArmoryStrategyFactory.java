package denny.ai.agent.domain.service.armory.factory;

import cn.bugstack.wrench.design.framework.tree.StrategyHandler;
import denny.ai.agent.domain.service.armory.RootNode;
import denny.ai.agent.domain.model.entity.ArmoryCommandEntity;
import org.springframework.stereotype.Service;

/**
 * 工厂类
 */
@Service
public class DefaultArmoryStrategyFactory {
    private final RootNode rootNode;

    public DefaultArmoryStrategyFactory(RootNode rootNode) {
        this.rootNode = rootNode;
    }

    public StrategyHandler<ArmoryCommandEntity, DynamicContext, String> armoryStrategyHandler(){
        return rootNode;
    }
}
