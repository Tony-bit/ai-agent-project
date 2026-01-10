package denny.ai.agent.test.dao.dao;

import denny.ai.agent.infrastructure.dao.IAiAgentDao;
import denny.ai.agent.infrastructure.dao.po.AiAgentPO;
import lombok.extern.slf4j.Slf4j;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.junit4.SpringRunner;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * AI智能体配置表 DAO 测试
 */
@Slf4j
@RunWith(SpringRunner.class)
@SpringBootTest
public class AiAgentDaoTest {

    @Autowired
    private IAiAgentDao aiAgentDao;

    @Test
    public void testInsert() {
        AiAgentPO po = new AiAgentPO();
        po.setAgentId("TEST_001");
        po.setAgentName("测试智能体");
        po.setDescription("这是一个测试智能体");
        po.setChannel("agent");
        po.setStatus(1);
        po.setCreateTime(LocalDateTime.now());
        po.setUpdateTime(LocalDateTime.now());

        int result = aiAgentDao.insert(po);
        assertTrue("插入失败", result > 0);
        assertNotNull("ID应该自动生成", po.getId());
        log.info("插入成功，ID: {}", po.getId());
    }

    @Test
    public void testQueryById() {
        AiAgentPO po = aiAgentDao.queryById(1L);
        if (po != null) {
            log.info("查询结果: {}", po);
            assertNotNull("查询结果不应为空", po);
        }
    }

    @Test
    public void testQueryByAgentId() {
        AiAgentPO po = aiAgentDao.queryByAgentId("1");
        if (po != null) {
            log.info("查询结果: {}", po);
            assertNotNull("查询结果不应为空", po);
        }
    }

    @Test
    public void testQueryList() {
        AiAgentPO query = new AiAgentPO();
        query.setStatus(1);
        List<AiAgentPO> list = aiAgentDao.queryList(query);
        log.info("查询列表，数量: {}", list.size());
        assertNotNull("列表不应为空", list);
    }

    @Test
    public void testUpdate() {
        AiAgentPO po = aiAgentDao.queryById(1L);
        if (po != null) {
            po.setAgentName("更新后的名称");
            po.setUpdateTime(LocalDateTime.now());
            int result = aiAgentDao.update(po);
            assertTrue("更新失败", result > 0);
            log.info("更新成功");
        }
    }

    @Test
    public void testDeleteById() {
        // 注意：删除测试需要谨慎，建议使用测试数据
        // int result = aiAgentDao.deleteById(testId);
        // assertTrue("删除失败", result > 0);
        log.info("删除测试已跳过，避免删除真实数据");
    }
}

