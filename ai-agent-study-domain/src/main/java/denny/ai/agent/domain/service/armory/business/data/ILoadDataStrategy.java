package denny.ai.agent.domain.service.armory.business.data;

import denny.ai.agent.domain.service.armory.factory.DynamicContext;
import denny.ai.agent.domain.model.entity.ArmoryCommandEntity;

public interface ILoadDataStrategy {
    void loadData(ArmoryCommandEntity armoryCommandEntity, DynamicContext dynamicContext);

}
