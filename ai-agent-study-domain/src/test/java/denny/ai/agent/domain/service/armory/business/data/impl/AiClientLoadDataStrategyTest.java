package denny.ai.agent.domain.service.armory.business.data.impl;

import denny.ai.agent.domain.adapter.repository.IAgentRepository;
import denny.ai.agent.domain.model.entity.ArmoryCommandEntity;
import denny.ai.agent.domain.model.valobj.AiAgentClientFlowConfigVO;
import denny.ai.agent.domain.service.armory.factory.DynamicContext;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.when;

@RunWith(MockitoJUnitRunner.class)
public class AiClientLoadDataStrategyTest {

    @Mock
    private IAgentRepository repository;

    private ThreadPoolExecutor executor;
    private AiClientLoadDataStrategy strategy;

    @Before
    public void setUp() {
        executor = new ThreadPoolExecutor(2, 6, 10, TimeUnit.SECONDS,
                new LinkedBlockingQueue<>());
        strategy = new AiClientLoadDataStrategy();
        ReflectionTestUtils.setField(strategy, "repository", repository);
        ReflectionTestUtils.setField(strategy, "threadPoolExecutor", executor);
        when(repository.queryAiClientApiVOListByClientIds(anyList())).thenReturn(List.of());
        when(repository.AiClientModelVOByClientIds(anyList())).thenReturn(List.of());
        when(repository.AiClientToolMcpVOByClientIds(anyList())).thenReturn(List.of());
        when(repository.queryAiClientSystemPromptMapByClientIds(anyList())).thenReturn(Map.of());
        when(repository.AiClientAdvisorVOByClientIds(anyList())).thenReturn(List.of());
        when(repository.AiClientVOByClientIds(anyList())).thenReturn(List.of());
    }

    @After
    public void tearDown() {
        executor.shutdownNow();
    }

    @Test
    public void should_accept_multiple_agents_when_compression_client_id_is_identical() {
        when(repository.queryActiveFlowConfigsByClientType("COMPRESSION_ASSISTANT"))
                .thenReturn(List.of(flow("3202", 1), flow("3202", 99)));
        List<String> originalIds = new ArrayList<>(List.of("1001"));
        DynamicContext context = new DynamicContext();

        strategy.loadData(ArmoryCommandEntity.builder().commandIdList(originalIds).build(), context);

        assertEquals("3202", context.getValue(AiClientLoadDataStrategy.GLOBAL_COMPRESSION_CLIENT_ID));
        assertEquals(List.of("1001"), originalIds);
    }

    @Test
    public void should_reject_multiple_distinct_global_compression_client_ids() {
        when(repository.queryActiveFlowConfigsByClientType("COMPRESSION_ASSISTANT"))
                .thenReturn(List.of(flow("3202", 1), flow("4202", 2)));

        assertThrows(IllegalStateException.class, () -> strategy.loadData(
                ArmoryCommandEntity.builder().commandIdList(List.of("1001")).build(),
                new DynamicContext()));
    }

    @Test
    public void should_reject_missing_global_compression_client() {
        when(repository.queryActiveFlowConfigsByClientType("COMPRESSION_ASSISTANT"))
                .thenReturn(List.of());

        assertThrows(IllegalStateException.class, () -> strategy.loadData(
                ArmoryCommandEntity.builder().commandIdList(List.of()).build(),
                new DynamicContext()));
    }

    private AiAgentClientFlowConfigVO flow(String clientId, int sequence) {
        return AiAgentClientFlowConfigVO.builder()
                .clientId(clientId)
                .clientType("COMPRESSION_ASSISTANT")
                .sequence(sequence)
                .build();
    }
}
