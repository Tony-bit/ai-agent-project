package denny.ai.agent.test;

import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.document.Document;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.ai.reader.tika.TikaDocumentReader;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.ai.vectorstore.pgvector.PgVectorStore;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.junit4.SpringRunner;

import java.util.List;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;

@Slf4j
@RunWith(SpringRunner.class)
@SpringBootTest
public class ApiIntegrationTest {

    @Resource
    private TokenTextSplitter tokenTextSplitter;

    @Resource
    private PgVectorStore pgVectorStore;

    @Test
    public void test() {
        log.info("测试完成");
    }

    @Test
    public void testDeepSeekChatClient() {
        String apiKey = "sk-a0df0f93a5ac475e8c73c6e3495cfc05";
        assertNotNull("Please set the DEEPSEEK_API_KEY environment variable", apiKey);
        assertFalse("DEEPSEEK_API_KEY must not be blank", apiKey.isBlank());

        OpenAiApi openAiApi = OpenAiApi.builder()
                .baseUrl("https://api.deepseek.com")
                .apiKey(apiKey)
                .build();
        OpenAiChatModel chatModel = OpenAiChatModel.builder()
                .openAiApi(openAiApi)
                .defaultOptions(OpenAiChatOptions.builder()
                        .model("deepseek-v4-flash")
                        .build())
                .build();
        ChatClient chatClient = ChatClient.builder(chatModel).build();

        String content = chatClient.prompt()
                .user("请只回答 deepseek api ok")
                .call()
                .content();

        assertNotNull("DeepSeek response must not be null", content);
        assertFalse("DeepSeek response must not be blank", content.isBlank());
        log.info("DeepSeek API response: {}", content);
    }

    @Test
    public void upload() {
        TikaDocumentReader reader = new TikaDocumentReader("classpath:/data/file.text");

        List<Document> documents = reader.get();
        List<Document> documentSplitterList = tokenTextSplitter.apply(documents);

        documents.forEach(doc -> doc.getMetadata().put("knowledge", "知识库名称"));
        documentSplitterList.forEach(doc -> doc.getMetadata().put("knowledge", "知识库名称"));

        pgVectorStore.accept(documentSplitterList);

        log.info("上传完成");
    }

}
