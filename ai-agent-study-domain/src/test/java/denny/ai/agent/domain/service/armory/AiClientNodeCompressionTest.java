package denny.ai.agent.domain.service.armory;

import denny.ai.agent.domain.model.entity.ArmoryCommandEntity;
import denny.ai.agent.domain.model.valobj.AiClientVO;
import denny.ai.agent.domain.model.valobj.enums.AiAgentEnumVO;
import denny.ai.agent.domain.service.armory.business.data.impl.AiClientLoadDataStrategy;
import denny.ai.agent.domain.service.armory.factory.ArmoryObjectRegistry;
import denny.ai.agent.domain.service.armory.factory.DynamicContext;
import org.junit.Test;

import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;

public class AiClientNodeCompressionTest {

    @Test
    public void should_reject_compression_client_without_task_type_one() {
        DynamicContext context = context(List.of(client("3202", 2)));

        assertThrows(IllegalStateException.class,
                () -> new AiClientNode().doApply(command(), context));
    }

    @Test
    public void should_reject_duplicate_task_type_one_compression_clients() {
        DynamicContext context = context(List.of(client("3202", 1), client("3202", 1)));

        assertThrows(IllegalStateException.class,
                () -> new AiClientNode().doApply(command(), context));
    }

    @Test
    public void should_register_same_global_compression_client_idempotently() {
        ArmoryObjectRegistry registry = new ArmoryObjectRegistry();
        Object first = new Object();
        Object replacement = new Object();

        registry.registerGlobalCompressionClient("3202", first);
        registry.registerGlobalCompressionClient("3202", replacement);

        assertSame(replacement, registry.get(ArmoryObjectRegistry.COMPRESSION_CHAT_CLIENT));
    }

    @Test
    public void should_reject_conflicting_global_compression_client_id() {
        ArmoryObjectRegistry registry = new ArmoryObjectRegistry();
        registry.registerGlobalCompressionClient("3202", new Object());

        assertThrows(IllegalStateException.class,
                () -> registry.registerGlobalCompressionClient("4202", new Object()));
    }

    private DynamicContext context(List<AiClientVO> clients) {
        DynamicContext context = new DynamicContext();
        context.setValue(AiAgentEnumVO.AI_CLIENT.getDataName(), clients);
        context.setValue(AiAgentEnumVO.AI_CLIENT_SYSTEM_PROMPT.getDataName(), Map.of());
        context.setValue(AiClientLoadDataStrategy.GLOBAL_COMPRESSION_CLIENT_ID, "3202");
        return context;
    }

    private ArmoryCommandEntity command() {
        return ArmoryCommandEntity.builder().commandType("client").commandIdList(List.of()).build();
    }

    private AiClientVO client(String clientId, int taskType) {
        return AiClientVO.builder()
                .clientId(clientId)
                .taskType(taskType)
                .promptIdList(List.of())
                .mcpIdList(List.of())
                .advisorIdList(List.of())
                .build();
    }
}
