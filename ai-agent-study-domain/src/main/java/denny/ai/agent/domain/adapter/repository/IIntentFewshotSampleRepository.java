package denny.ai.agent.domain.adapter.repository;

import denny.ai.agent.domain.model.entity.IntentFewshotSample;

import java.util.List;

/**
 * 意图识别 Few-Shot 样本仓储接口
 *
 * @author denny
 * 2026/5/11
 */
public interface IIntentFewshotSampleRepository {

    /**
     * 新增样本
     */
    void save(IntentFewshotSample sample);

    /**
     * 更新样本
     */
    void update(IntentFewshotSample sample);

    /**
     * 软删除样本
     */
    void delete(Long id);

    /**
     * 根据 ID 查询
     */
    IntentFewshotSample queryById(Long id);

    /**
     * 查询所有启用状态的样本
     */
    List<IntentFewshotSample> queryAllEnabled();

    /**
     * 根据意图编码查询启用状态的样本
     */
    List<IntentFewshotSample> queryByIntentCode(String intentCode);

    /**
     * 统计某意图编码的样本数量
     */
    int countByIntentCode(String intentCode);
}
