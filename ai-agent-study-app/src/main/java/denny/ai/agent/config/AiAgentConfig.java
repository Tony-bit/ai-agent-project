package denny.ai.agent.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import denny.ai.agent.infrastructure.mem0.Mem0RestClient;
import jakarta.annotation.Resource;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.ai.vectorstore.pgvector.PgVectorStore;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.client.RestTemplate;

import java.time.Duration;

@Configuration
public class AiAgentConfig {

    @Resource
    @Qualifier("openAiChatModel")
    private ChatModel chatModel;

    @Bean
    public ChatClient chatClient() {
        return ChatClient.builder(chatModel).build();
    }

    @Bean
    @Primary
    public EmbeddingModel customDashscopeEmbeddingModel(
            @Value("${rag.embedding.config.base-url}") String apiUrl,
            @Value("${rag.embedding.config.api-key}") String apiKey,
            @Value("${rag.embedding.config.model:qwen3-vl-embedding}") String model,
            @Value("${rag.embedding.config.dimension:768}") Integer dimension) {
        Integer actualDimension = (dimension == null || dimension <= 0) ? null : dimension;
        return new DashscopeEmbeddingModel(apiUrl, apiKey, model, actualDimension);
    }

    @Bean
    @Primary
    public PgVectorStore pgVectorStore(@Qualifier("pgVectorJdbcTemplate") JdbcTemplate jdbcTemplate,
                                      @Qualifier("customDashscopeEmbeddingModel") EmbeddingModel embeddingModel) {
        return PgVectorStore.builder(jdbcTemplate, embeddingModel)
                .vectorTableName("store_openai")
                .build();
    }

    /**
     * 意图识别 Few-Shot 样本向量存储。
     * <p>
     * 使用 {@link #pgVectorStore}，表名为 intent_fewshot_vector_store，
     * 与通用 RAG 知识库（vector_store 表）数据隔离。
     * </p>
     *
     * @param jdbcTemplate      JDBC 模板
     * @param embeddingModel    Embedding 模型
     * @return PgVectorStore 实例
     */
    @Bean
    public PgVectorStore intentFewshotVectorStore(@Qualifier("pgVectorJdbcTemplate") JdbcTemplate jdbcTemplate,
                                                 @Qualifier("customDashscopeEmbeddingModel") EmbeddingModel embeddingModel) {
        return PgVectorStore.builder(jdbcTemplate, embeddingModel)
                .vectorTableName("intent_fewshot_sample")
                .build();
    }

    @Bean
    public TokenTextSplitter tokenTextSplitter() {
        return new TokenTextSplitter();
    }

    @Bean
    public RestTemplate mem0RestTemplate(
            @Value("${spring.ai.alibaba.mem0.client.timeout-seconds:120}") int timeoutSeconds) {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        Duration timeout = Duration.ofSeconds(timeoutSeconds);
        requestFactory.setConnectTimeout(timeout);
        requestFactory.setReadTimeout(timeout);
        return new RestTemplate(requestFactory);
    }

    @Bean
    public Mem0RestClient mem0RestClient(
            @Value("${spring.ai.alibaba.mem0.client.base-url}") String baseUrl,
            RestTemplate mem0RestTemplate,
            ObjectMapper objectMapper) {
        return new Mem0RestClient(mem0RestTemplate, baseUrl, objectMapper);
    }
}
