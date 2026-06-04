package denny.ai.agent.domain.model.valobj;

import com.alibaba.fastjson.JSON;
import denny.ai.agent.domain.model.valobj.enums.IntentTypeEnum;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class SubTaskTest {

    @Test
    void shouldDeserializeDependsOnFromJson() {
        String json = """
                {
                  \"taskId\": \"sub-2\",
                  \"taskIndex\": 2,
                  \"totalTasks\": 3,
                  \"content\": \"基于 <$DEPENDENCY$ taskId=\\\"sub-1\\\" /> 进行总结\",
                  \"intent\": \"GENERAL_CHAT\",
                  \"executorNode\": \"generalChatNode\",
                  \"dependsOn\": [\"sub-1\", \"sub-0\"]
                }
                """;

        SubTask subTask = JSON.parseObject(json, SubTask.class);

        assertNotNull(subTask);
        assertEquals("sub-2", subTask.getTaskId());
        assertEquals(IntentTypeEnum.GENERAL_CHAT, subTask.getIntent());
        assertEquals(List.of("sub-1", "sub-0"), subTask.getDependsOn());
    }
}
