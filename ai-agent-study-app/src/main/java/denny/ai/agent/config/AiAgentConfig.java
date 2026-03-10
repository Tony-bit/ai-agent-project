package denny.ai.agent.config;

import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.ai.vectorstore.pgvector.PgVectorStore;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

@Configuration
public class AiAgentConfig {

    @Bean
    public EmbeddingModel dashscopeEmbeddingModel(
            @Value("${rag.embedding.config.base-url}") String apiUrl,
            @Value("${rag.embedding.config.api-key}") String apiKey,
            @Value("${rag.embedding.config.model:qwen3-vl-embedding}") String model,
            @Value("${rag.embedding.config.dimension:768}") Integer dimension) {
        Integer actualDimension = (dimension == null || dimension <= 0) ? null : dimension;
        return new DashscopeEmbeddingModel(apiUrl, apiKey, model, actualDimension);
    }

    @Bean
    public PgVectorStore pgVectorStore(@Qualifier("pgVectorJdbcTemplate") JdbcTemplate jdbcTemplate,
                                       @Qualifier("dashscopeEmbeddingModel") EmbeddingModel embeddingModel) {
        return PgVectorStore.builder(jdbcTemplate, embeddingModel)
                .vectorTableName("vector_store")
                .build();
    }

    @Bean
    public TokenTextSplitter tokenTextSplitter() {
        return new TokenTextSplitter();
    }
}
