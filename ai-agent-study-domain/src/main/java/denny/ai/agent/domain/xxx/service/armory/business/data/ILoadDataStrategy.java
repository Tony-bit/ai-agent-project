package denny.ai.agent.domain.xxx.service.armory.business.data;

import denny.ai.agent.domain.xxx.model.entity.ArmoryCommandEntity;
import denny.ai.agent.domain.xxx.service.armory.factory.DynamicContext;

public interface ILoadDataStrategy {
    void loadData(ArmoryCommandEntity armoryCommandEntity, DynamicContext dynamicContext);

}
