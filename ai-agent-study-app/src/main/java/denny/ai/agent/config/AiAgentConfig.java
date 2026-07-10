package denny.ai.agent.config;

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
import org.springframework.jdbc.core.JdbcTemplate;

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
            @Value("${rag.embedding.config.model:text-embedding-v3}") String model,
            @Value("${rag.embedding.config.dimension}") Integer dimension) {
        return new DashscopeEmbeddingModel(apiUrl, apiKey, model, resolveEmbeddingDimension(dimension));
    }

    @Bean
    @Primary
    public PgVectorStore pgVectorStore(@Qualifier("pgVectorJdbcTemplate") JdbcTemplate jdbcTemplate,
                                      @Qualifier("customDashscopeEmbeddingModel") EmbeddingModel embeddingModel,
                                      @Value("${rag.embedding.config.dimension}") Integer dimension) {
        return PgVectorStore.builder(jdbcTemplate, embeddingModel)
                .vectorTableName("store_openai")
                .dimensions(resolveEmbeddingDimension(dimension))
                .build();
    }

    /**
     * 意图识别 Few-Shot 样本向量存储。
     * <p>
     * 使用独立的 PgVectorStore 表 `intent_fewshot_vector_store` 作为向量检索副本，
     * 与业务样本表 `intent_fewshot_sample_768` 以及通用 RAG 知识库 `store_openai` 数据隔离。
     * </p>
     *
     * @param jdbcTemplate      JDBC 模板
     * @param embeddingModel    Embedding 模型
     * @return PgVectorStore 实例
     */
    @Bean
    public PgVectorStore intentFewshotVectorStore(@Qualifier("pgVectorJdbcTemplate") JdbcTemplate jdbcTemplate,
                                                 @Qualifier("customDashscopeEmbeddingModel") EmbeddingModel embeddingModel,
                                                 @Value("${rag.embedding.config.dimension}") Integer dimension) {
        return PgVectorStore.builder(jdbcTemplate, embeddingModel)
                .vectorTableName("intent_fewshot_vector_store")
                .dimensions(resolveEmbeddingDimension(dimension))
                .build();
    }

    @Bean
    public TokenTextSplitter tokenTextSplitter() {
        return new TokenTextSplitter();
    }

    private int resolveEmbeddingDimension(Integer dimension) {
        if (dimension == null || dimension <= 0) {
            throw new IllegalArgumentException("rag.embedding.config.dimension must be configured with a positive value");
        }
        return dimension;
    }
}
