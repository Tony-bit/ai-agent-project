package denny.ai.agent.infrastructure.service.episodicmemory;

import denny.ai.agent.domain.model.valobj.MemoryProperties;
import denny.ai.agent.infrastructure.mem0.Mem0RestClient;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;

import java.lang.reflect.Field;

import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * EpisodicMemoryServiceImpl 单元测试
 */
@RunWith(MockitoJUnitRunner.class)
public class EpisodicMemoryServiceImplTest {

    @Mock
    private Mem0RestClient mem0RestClient;

    private EpisodicMemoryServiceImpl service;

    private MemoryProperties memoryProperties;

    @Before
    public void setUp() throws Exception {
        service = new EpisodicMemoryServiceImpl();
        setField(service, "mem0RestClient", mem0RestClient);

        memoryProperties = new MemoryProperties();
        memoryProperties.setEpisodicMemoryLimit(5);
        setField(service, "memoryProperties", memoryProperties);
    }

    private void setField(Object target, String fieldName, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(target, value);
    }

    @Test
    public void testSearchEpisodicMemories_QueryIsBlank_ReturnsEmpty() {
        String result = service.searchEpisodicMemories("user-001", "", 5);
        assertEquals("", result);
        verifyNoInteractions(mem0RestClient);
    }

    @Test
    public void testSearchEpisodicMemories_QueryIsNull_ReturnsEmpty() {
        String result = service.searchEpisodicMemories("user-001", null, 5);
        assertEquals("", result);
        verifyNoInteractions(mem0RestClient);
    }

    @Test
    public void testSearchEpisodicMemories_QueryIsWhitespace_ReturnsEmpty() {
        String result = service.searchEpisodicMemories("user-001", "   ", 5);
        assertEquals("", result);
        verifyNoInteractions(mem0RestClient);
    }

    @Test
    public void testSearchEpisodicMemories_NormalSearch_ReturnsFormattedResults() {
        Mem0RestClient.Mem0ServerResp resp = new Mem0RestClient.Mem0ServerResp();
        Mem0RestClient.Mem0ServerResp.Mem0Results result1 = new Mem0RestClient.Mem0ServerResp.Mem0Results();
        result1.setMemory("用户喜欢喝咖啡");
        result1.setScore(0.95);
        resp.setResults(java.util.List.of(result1));

        when(mem0RestClient.searchMemories(any())).thenReturn(resp);

        String result = service.searchEpisodicMemories("user-001", "喜欢什么", 5);

        assertTrue(result.contains("[情景记忆]"));
        assertTrue(result.contains("用户喜欢喝咖啡"));
        assertTrue(result.contains("相似度"));
    }

    @Test
    public void testSearchEpisodicMemories_NoResults_ReturnsEmpty() {
        Mem0RestClient.Mem0ServerResp resp = new Mem0RestClient.Mem0ServerResp();
        resp.setResults(java.util.Collections.emptyList());

        when(mem0RestClient.searchMemories(any())).thenReturn(resp);

        String result = service.searchEpisodicMemories("user-001", "不存在的关键词", 5);

        assertEquals("", result);
    }

    @Test
    public void testSearchEpisodicMemories_NullResponse_ReturnsEmpty() {
        when(mem0RestClient.searchMemories(any())).thenReturn(null);

        String result = service.searchEpisodicMemories("user-001", "测试", 5);

        assertEquals("", result);
    }

    @Test
    public void testSearchEpisodicMemories_Exception_ReturnsEmpty() {
        when(mem0RestClient.searchMemories(any())).thenThrow(new RuntimeException("Mem0 错误"));

        String result = service.searchEpisodicMemories("user-001", "测试", 5);

        assertEquals("", result);
    }

    @Test
    public void testSearchEpisodicMemories_LimitParameter_IsPassed() {
        Mem0RestClient.Mem0ServerResp resp = new Mem0RestClient.Mem0ServerResp();
        resp.setResults(java.util.Collections.emptyList());
        when(mem0RestClient.searchMemories(any())).thenReturn(resp);

        service.searchEpisodicMemories("user-001", "测试", 10);

        verify(mem0RestClient).searchMemories(argThat(request ->
            request.getLimit() == 10
        ));
    }
}
