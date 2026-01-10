package denny.ai.agent.test.dao.dao;

import denny.ai.agent.infrastructure.dao.IAiClientApiDao;
import denny.ai.agent.infrastructure.dao.po.AiClientApiPO;
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
 * OpenAI API配置表 DAO 测试
 */
@Slf4j
@RunWith(SpringRunner.class)
@SpringBootTest
public class AiClientApiDaoTest {

    @Autowired
    private IAiClientApiDao aiClientApiDao;

    @Test
    public void testInsert() {
        AiClientApiPO po = new AiClientApiPO();
        po.setApiId("TEST_API_001");
        po.setBaseUrl("https://api.test.com");
        po.setApiKey("test-api-key");
        po.setCompletionsPath("v1/chat/completions");
        po.setEmbeddingsPath("v1/embeddings");
        po.setStatus(1);
        po.setCreateTime(LocalDateTime.now());
        po.setUpdateTime(LocalDateTime.now());

        int result = aiClientApiDao.insert(po);
        assertTrue("插入失败", result > 0);
        log.info("插入成功，ID: {}", po.getId());
    }

    @Test
    public void testQueryById() {
        AiClientApiPO po = aiClientApiDao.queryById(1L);
        if (po != null) {
            log.info("查询结果: {}", po);
        }
    }

    @Test
    public void testQueryByApiId() {
        AiClientApiPO po = aiClientApiDao.queryByApiId("1001");
        if (po != null) {
            log.info("查询结果: {}", po);
        }
    }

    @Test
    public void testQueryList() {
        List<AiClientApiPO> list = aiClientApiDao.queryList(new AiClientApiPO());
        log.info("查询列表，数量: {}", list.size());
    }

    @Test
    public void testUpdate() {
        AiClientApiPO po = aiClientApiDao.queryById(1L);
        if (po != null) {
            po.setBaseUrl("https://api.updated.com");
            po.setUpdateTime(LocalDateTime.now());
            int result = aiClientApiDao.update(po);
            assertTrue("更新失败", result > 0);
            log.info("更新成功");
        }
    }
}

