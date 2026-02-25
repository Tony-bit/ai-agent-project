package denny.ai.agent.infrastructure.es;

import denny.ai.agent.Application;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(classes = Application.class)
class RagKnowledgeEsGatewayTest {

    @Autowired
    private RagKnowledgeEsGateway gateway;

    @Test
    void testSaveAndSearch() throws Exception {
        String id = gateway.saveSampleDoc();
        System.out.println("saved id = " + id);

        gateway.searchByKeyword("错误码");
    }
}
