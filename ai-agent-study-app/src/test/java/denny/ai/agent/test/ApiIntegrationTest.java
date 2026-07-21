package denny.ai.agent.test;

import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.OpenAiApi;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Slf4j
public class ApiIntegrationTest {

    @Test
    public void testDeepSeekStreamingChatClient() {
        String baseUrl = System.getenv().getOrDefault("DEEPSEEK_BASE_URL", "https://api.deepseek.com");
        String apiKey = "sk-ff8961676bbc4d62abfe2c2dad6f2c20";
        assertNotNull(apiKey, "Please set the DEEPSEEK_API_KEY environment variable");
        assertFalse(apiKey.isBlank(), "DEEPSEEK_API_KEY must not be blank");

        OpenAiApi openAiApi = OpenAiApi.builder()
                .baseUrl(baseUrl)
                .apiKey(apiKey)
                .build();
        OpenAiChatModel chatModel = OpenAiChatModel.builder()
                .openAiApi(openAiApi)
                .defaultOptions(OpenAiChatOptions.builder()
                        .model("deepseek-v4-pro")
                        .build())
                .build();
        ChatClient chatClient = ChatClient.builder(chatModel).build();

        long startedAt = System.nanoTime();
        AtomicLong firstContentAt = new AtomicLong();
        AtomicInteger chunkCount = new AtomicInteger();
        StringBuilder fullContent = new StringBuilder();

        chatClient.prompt()
                .user("请只回答 deepseek api ok")
                .stream()
                .content()
                .doOnNext(chunk -> {
                    if (chunk != null && !chunk.isBlank()) {
                        firstContentAt.compareAndSet(0L, System.nanoTime());
                        chunkCount.incrementAndGet();
                        fullContent.append(chunk);
                        log.info("DeepSeek stream chunk: {}", chunk);
                    }
                })
                .blockLast();

        String content = fullContent.toString();
        assertNotNull(content, "DeepSeek response must not be null");
        assertFalse(content.isBlank(), "DeepSeek response must not be blank");
        assertTrue(chunkCount.get() > 0, "DeepSeek stream must contain at least one chunk");

        long completedAt = System.nanoTime();
        long firstContentLatencyMs = (firstContentAt.get() - startedAt) / 1_000_000L;
        long totalLatencyMs = (completedAt - startedAt) / 1_000_000L;
        log.info("DeepSeek streaming completed: content={}, firstContentLatencyMs={}, "
                        + "totalLatencyMs={}, chunkCount={}",
                content, firstContentLatencyMs, totalLatencyMs, chunkCount.get());
    }

}
