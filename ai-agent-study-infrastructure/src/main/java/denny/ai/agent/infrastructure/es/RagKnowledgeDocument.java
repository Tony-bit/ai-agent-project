package denny.ai.agent.infrastructure.es;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;
import org.springframework.data.annotation.Id;
import org.springframework.data.elasticsearch.annotations.Document;
import org.springframework.data.elasticsearch.annotations.Field;
import org.springframework.data.elasticsearch.annotations.FieldType;

/**
 * RAG 知识文档实体（支持 user_id 隔离）
 */
@Data
public class RagKnowledgeDocument {

    private String id;

    /** 用户ID，用于知识库隔离 */
    @JsonProperty("user_id")
    @Field(type = FieldType.Keyword)
    private String userId;

    @JsonProperty("title")
    @Field(type = FieldType.Text, analyzer = "ik_max_word", searchAnalyzer = "ik_smart")
    private String title;

    @JsonProperty("content")
    @Field(type = FieldType.Text, analyzer = "ik_max_word", searchAnalyzer = "ik_smart")
    private String content;

    /** 文件名 */
    @JsonProperty("file_name")
    @Field(type = FieldType.Keyword)
    private String fileName;

    /** 文件类型 */
    @JsonProperty("file_type")
    @Field(type = FieldType.Keyword)
    private String fileType;

    /** Chunk 索引（同一文件的不同 chunk） */
    @JsonProperty("chunk_index")
    @Field(type = FieldType.Integer)
    private Integer chunkIndex;
}
