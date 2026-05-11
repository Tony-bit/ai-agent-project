package denny.ai.agent.infrastructure.dao;

import denny.ai.agent.infrastructure.dao.po.IntentFewshotSamplePO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 意图识别 Few-Shot 样本 DAO
 *
 * @author denny
 * 2026/5/11
 */
@Mapper
public interface IIntentFewshotSampleDao {

    void insert(IntentFewshotSamplePO po);

    void updateById(IntentFewshotSamplePO po);

    void softDelete(@Param("id") Long id);

    IntentFewshotSamplePO queryById(@Param("id") Long id);

    List<IntentFewshotSamplePO> queryByStatus(@Param("status") Integer status);

    List<IntentFewshotSamplePO> queryByIntentCode(@Param("intentCode") String intentCode,
                                                  @Param("status") Integer status);

    List<IntentFewshotSamplePO> queryAllEnabled();

    int countByIntentCode(@Param("intentCode") String intentCode, @Param("status") Integer status);
}
