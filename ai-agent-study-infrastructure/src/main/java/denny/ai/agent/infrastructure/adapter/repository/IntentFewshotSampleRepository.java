package denny.ai.agent.infrastructure.adapter.repository;

import denny.ai.agent.domain.adapter.repository.IIntentFewshotSampleRepository;
import denny.ai.agent.domain.model.entity.IntentFewshotSample;
import denny.ai.agent.infrastructure.dao.IIntentFewshotSampleDao;
import denny.ai.agent.infrastructure.dao.po.IntentFewshotSamplePO;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 意图识别 Few-Shot 样本仓储实现
 *
 * @author denny
 * 2026/5/11
 */
@Slf4j
@Service("intentFewshotSampleRepository")
public class IntentFewshotSampleRepository implements IIntentFewshotSampleRepository {

    @Resource
    private IIntentFewshotSampleDao intentFewshotSampleDao;

    @Override
    public void save(IntentFewshotSample sample) {
        IntentFewshotSamplePO po = toPO(sample);
        po.setCreateTime(LocalDateTime.now());
        po.setUpdateTime(LocalDateTime.now());
        intentFewshotSampleDao.insert(po);
        sample.setId(po.getId());
    }

    @Override
    public void update(IntentFewshotSample sample) {
        IntentFewshotSamplePO po = toPO(sample);
        po.setUpdateTime(LocalDateTime.now());
        intentFewshotSampleDao.updateById(po);
    }

    @Override
    public void delete(Long id) {
        intentFewshotSampleDao.softDelete(id);
    }

    @Override
    public IntentFewshotSample queryById(Long id) {
        IntentFewshotSamplePO po = intentFewshotSampleDao.queryById(id);
        return po == null ? null : toEntity(po);
    }

    @Override
    public List<IntentFewshotSample> queryAllEnabled() {
        return intentFewshotSampleDao.queryAllEnabled()
                .stream()
                .map(this::toEntity)
                .collect(Collectors.toList());
    }

    @Override
    public List<IntentFewshotSample> queryByIntentCode(String intentCode) {
        return intentFewshotSampleDao.queryByIntentCode(intentCode, IntentFewshotSample.STATUS_ENABLED)
                .stream()
                .map(this::toEntity)
                .collect(Collectors.toList());
    }

    @Override
    public int countByIntentCode(String intentCode) {
        return intentFewshotSampleDao.countByIntentCode(intentCode, IntentFewshotSample.STATUS_ENABLED);
    }

    private IntentFewshotSamplePO toPO(IntentFewshotSample entity) {
        IntentFewshotSamplePO po = new IntentFewshotSamplePO();
        po.setId(entity.getId());
        po.setQueryText(entity.getQueryText());
        po.setIntentCode(entity.getIntentCode());
        po.setExampleJson(entity.getExampleJson());
        po.setDimension(entity.getDimension());
        po.setEmbedding(entity.getEmbedding());
        po.setStatus(entity.getStatus() != null ? entity.getStatus() : IntentFewshotSample.STATUS_ENABLED);
        return po;
    }

    private IntentFewshotSample toEntity(IntentFewshotSamplePO po) {
        return IntentFewshotSample.builder()
                .id(po.getId())
                .queryText(po.getQueryText())
                .intentCode(po.getIntentCode())
                .exampleJson(po.getExampleJson())
                .dimension(po.getDimension())
                .embedding(po.getEmbedding())
                .status(po.getStatus())
                .createTime(po.getCreateTime())
                .updateTime(po.getUpdateTime())
                .build();
    }
}
