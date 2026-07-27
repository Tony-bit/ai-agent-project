package denny.ai.agent.trading.config;

import denny.ai.agent.infrastructure.dao.IAiClientSystemPromptDao;
import denny.ai.agent.infrastructure.dao.po.AiClientSystemPromptPO;
import denny.ai.agent.trading.domain.prompt.TradingPromptRecord;
import denny.ai.agent.trading.domain.prompt.TradingPromptRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Set;

@Repository
public class MyBatisTradingPromptRepository implements TradingPromptRepository {

    private final IAiClientSystemPromptDao dao;

    public MyBatisTradingPromptRepository(IAiClientSystemPromptDao dao) {
        this.dao = dao;
    }

    @Override
    public List<TradingPromptRecord> findVersionSet(Set<String> promptIds,
                                                     int promptType,
                                                     int version) {
        return dao.queryVersionSet(promptIds, promptType, version).stream()
                .map(this::toRecord)
                .toList();
    }

    @Override
    public List<TradingPromptRecord> findActiveSet(Set<String> promptIds, int promptType) {
        return dao.queryActiveSet(promptIds, promptType).stream()
                .map(this::toRecord)
                .toList();
    }

    @Override
    public void deactivateAll(Set<String> promptIds, int promptType) {
        dao.deactivatePromptSet(promptIds, promptType);
    }

    @Override
    public int activateVersion(Set<String> promptIds, int promptType, int version) {
        return dao.activatePromptSetVersion(promptIds, promptType, version);
    }

    private TradingPromptRecord toRecord(AiClientSystemPromptPO prompt) {
        return new TradingPromptRecord(prompt.getId(), prompt.getPromptId(),
                prompt.getPromptType(), prompt.getVersion(), prompt.getPromptContent(),
                Integer.valueOf(1).equals(prompt.getStatus()));
    }
}
