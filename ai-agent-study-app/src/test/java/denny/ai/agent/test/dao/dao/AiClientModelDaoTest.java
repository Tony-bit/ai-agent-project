package denny.ai.agent.test.dao.dao;

import denny.ai.agent.infrastructure.dao.IAiClientModelDao;
import denny.ai.agent.infrastructure.dao.po.AiClientModelPO;
import lombok.extern.slf4j.Slf4j;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.junit4.SpringRunner;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.Assert.assertTrue;

/**
 * 聊天模型配置表 DAO 测试
 */
@Slf4j
@RunWith(SpringRunner.class)
@SpringBootTest
public class AiClientModelDaoTest {

    @Autowired
    private IAiClientModelDao aiClientModelDao;

    @Test
    public void testInsert() {
        AiClientModelPO po = new AiClientModelPO();
        po.setModelId("TEST_MODEL_001");
        po.setApiId("1001");
        po.setModelName("test-model");
        po.setModelType("openai");
        po.setStatus(1);
        po.setCreateTime(LocalDateTime.now());
        po.setUpdateTime(LocalDateTime.now());

        int result = aiClientModelDao.insert(po);
        assertTrue("插入失败", result > 0);
        log.info("插入成功，ID: {}", po.getId());
    }

    @Test
    public void testQueryById() {
        AiClientModelPO po = aiClientModelDao.queryById(1L);
        if (po != null) {
            log.info("查询结果: {}", po);
        }
    }

    @Test
    public void testQueryByModelId() {
        AiClientModelPO po = aiClientModelDao.queryByModelId("2001");
        if (po != null) {
            log.info("查询结果: {}", po);
        }
    }

    @Test
    public void testQueryList() {
        List<AiClientModelPO> list = aiClientModelDao.queryList(new AiClientModelPO());
        log.info("查询列表，数量: {}", list.size());
    }

    @Test
    public void testUpdate() {
        AiClientModelPO po = aiClientModelDao.queryById(1L);
        if (po != null) {
            po.setModelName("updated-model");
            po.setUpdateTime(LocalDateTime.now());
            int result = aiClientModelDao.update(po);
            assertTrue("更新失败", result > 0);
            log.info("更新成功");
        }
    }
}

