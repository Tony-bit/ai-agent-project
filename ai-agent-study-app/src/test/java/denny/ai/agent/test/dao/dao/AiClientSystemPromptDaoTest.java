package denny.ai.agent.test.dao.dao;

import denny.ai.agent.infrastructure.dao.IAiClientSystemPromptDao;
import denny.ai.agent.infrastructure.dao.po.AiClientSystemPromptPO;
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
 * 系统提示词配置表 DAO 测试
 */
@Slf4j
@RunWith(SpringRunner.class)
@SpringBootTest
public class AiClientSystemPromptDaoTest {

    @Autowired
    private IAiClientSystemPromptDao aiClientSystemPromptDao;

    @Test
    public void testInsert() {
        AiClientSystemPromptPO po = new AiClientSystemPromptPO();
        po.setPromptId("TEST_PROMPT_001");
        po.setPromptName("测试提示词");
        po.setPromptContent("你是一个测试助手");
        po.setDescription("测试描述");
        po.setStatus(1);
        po.setCreateTime(LocalDateTime.now());
        po.setUpdateTime(LocalDateTime.now());

        int result = aiClientSystemPromptDao.insert(po);
        assertTrue("插入失败", result > 0);
        log.info("插入成功，ID: {}", po.getId());
    }

    @Test
    public void testQueryById() {
        AiClientSystemPromptPO po = aiClientSystemPromptDao.queryById(6L);
        if (po != null) {
            log.info("查询结果: {}", po);
        }
    }

    @Test
    public void testQueryByPromptId() {
        AiClientSystemPromptPO po = aiClientSystemPromptDao.queryByPromptId("6001");
        if (po != null) {
            log.info("查询结果: {}", po);
        }
    }

    @Test
    public void testQueryList() {
        List<AiClientSystemPromptPO> list = aiClientSystemPromptDao.queryList(new AiClientSystemPromptPO());
        log.info("查询列表，数量: {}", list.size());
    }

    @Test
    public void testUpdate() {
        AiClientSystemPromptPO po = aiClientSystemPromptDao.queryById(6L);
        if (po != null) {
            po.setPromptName("更新后的提示词名称");
            po.setUpdateTime(LocalDateTime.now());
            int result = aiClientSystemPromptDao.update(po);
            assertTrue("更新失败", result > 0);
            log.info("更新成功");
        }
    }
}

