package denny.ai.agent.infrastructure.adapter.repository;

import denny.ai.agent.domain.model.valobj.AiClientToolMcpVO;
import denny.ai.agent.infrastructure.dao.IAiClientConfigDao;
import denny.ai.agent.infrastructure.dao.IAiClientToolMcpDao;
import denny.ai.agent.infrastructure.dao.po.AiClientConfigPO;
import denny.ai.agent.infrastructure.dao.po.AiClientToolMcpPO;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@RunWith(MockitoJUnitRunner.class)
public class AgentRepositoryMcpConfigTest {

    @InjectMocks
    private AgentRepository repository;

    @Mock
    private IAiClientConfigDao clientConfigDao;

    @Mock
    private IAiClientToolMcpDao clientToolMcpDao;

    @Test
    public void shouldLoadMcpFromClientRelation() {
        AiClientConfigPO modelRelation = relation("model", "2009", 1);
        AiClientConfigPO mcpRelation = relation("tool_mcp", "5006", 1);
        when(clientConfigDao.queryBySourceTypeAndId("client", "3101"))
                .thenReturn(List.of(modelRelation, mcpRelation));

        AiClientToolMcpPO mcp = new AiClientToolMcpPO();
        mcp.setMcpId("5006");
        mcp.setMcpName("local-tools");
        mcp.setTransportType("sse");
        mcp.setTransportConfig("{\"baseUri\":\"http://localhost:8080\",\"sseEndpoint\":\"/sse\"}");
        mcp.setRequestTimeout(1);
        mcp.setStatus(1);
        when(clientToolMcpDao.queryByMcpId("5006")).thenReturn(mcp);

        List<AiClientToolMcpVO> result = repository.AiClientToolMcpVOByClientIds(List.of("3101"));

        assertEquals(1, result.size());
        assertEquals("5006", result.get(0).getMcpId());
        verify(clientConfigDao, never()).queryBySourceTypeAndId("model", "2009");
    }

    @Test
    public void shouldIgnoreModelRelationInClientMcpLoader() {
        AiClientConfigPO modelRelation = relation("model", "2009", 1);
        when(clientConfigDao.queryBySourceTypeAndId("client", "3101"))
                .thenReturn(List.of(modelRelation));

        List<AiClientToolMcpVO> result = repository.AiClientToolMcpVOByClientIds(List.of("3101"));

        assertEquals(0, result.size());
        verify(clientToolMcpDao, never()).queryByMcpId("5006");
    }

    private AiClientConfigPO relation(String targetType, String targetId, int status) {
        AiClientConfigPO relation = new AiClientConfigPO();
        relation.setTargetType(targetType);
        relation.setTargetId(targetId);
        relation.setStatus(status);
        return relation;
    }
}
