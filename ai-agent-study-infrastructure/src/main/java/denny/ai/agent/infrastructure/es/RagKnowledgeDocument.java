package denny.ai.agent.infrastructure.es;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;
import org.springframework.data.annotation.Id;
import org.springframework.data.elasticsearch.annotations.Document;
import org.springframework.data.elasticsearch.annotations.Field;
import org.springframework.data.elasticsearch.annotations.FieldType;

/**
 * 简单 RAG 知识文档实体
 */
@Data
public class RagKnowledgeDocument {


    private String id;

    @JsonProperty("title")
    private String title;

    @JsonProperty("content")
    private String content;
}
