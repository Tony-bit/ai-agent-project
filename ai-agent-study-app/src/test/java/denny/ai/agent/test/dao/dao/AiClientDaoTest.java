package denny.ai.agent.test.dao.dao;

import denny.ai.agent.infrastructure.dao.IAiClientDao;
import denny.ai.agent.infrastructure.dao.po.AiClientPO;
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
 * AI客户端配置表 DAO 测试
 */
@Slf4j
@RunWith(SpringRunner.class)
@SpringBootTest
public class AiClientDaoTest {

    @Autowired
    private IAiClientDao aiClientDao;

    @Test
    public void testInsert() {
        AiClientPO po = new AiClientPO();
        po.setClientId("TEST_CLIENT_001");
        po.setClientName("测试客户端");
        po.setDescription("这是一个测试客户端");
        po.setStatus(1);
        po.setCreateTime(LocalDateTime.now());
        po.setUpdateTime(LocalDateTime.now());

        int result = aiClientDao.insert(po);
        assertTrue("插入失败", result > 0);
        assertNotNull("ID应该自动生成", po.getId());
        log.info("插入成功，ID: {}", po.getId());
    }

    @Test
    public void testQueryById() {
        AiClientPO po = aiClientDao.queryById(1L);
        if (po != null) {
            log.info("查询结果: {}", po);
            assertNotNull("查询结果不应为空", po);
        }
    }

    @Test
    public void testQueryByClientId() {
        AiClientPO po = aiClientDao.queryByClientId("3001");
        if (po != null) {
            log.info("查询结果: {}", po);
            assertNotNull("查询结果不应为空", po);
        }
    }

    @Test
    public void testQueryList() {
        AiClientPO query = new AiClientPO();
        query.setStatus(1);
        List<AiClientPO> list = aiClientDao.queryList(query);
        log.info("查询列表，数量: {}", list.size());
        assertNotNull("列表不应为空", list);
    }

    @Test
    public void testUpdate() {
        AiClientPO po = aiClientDao.queryById(1L);
        if (po != null) {
            po.setClientName("更新后的客户端名称");
            po.setUpdateTime(LocalDateTime.now());
            int result = aiClientDao.update(po);
            assertTrue("更新失败", result > 0);
            log.info("更新成功");
        }
    }
}

