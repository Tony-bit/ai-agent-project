package denny.ai.agent.infrastructure.rag;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * RAG 向量化服务：将文本转换为 Embedding 向量。
 * 注意：PgVectorStore 在 add() 时会自动调用 EmbeddingModel 进行向量化，
 * 这里主要用于日志记录和未来扩展（如自定义向量化逻辑）。
 *
 * @author denny
 */
@Slf4j
@Service
public class RagEmbeddingService {

    /**
     * 标记文档已准备好进行向量化。
     * 实际的向量化由 PgVectorStore.add() 自动完成。
     *
     * @param documents 文档列表
     * @return 原文档列表（不做修改）
     */
    public List<Document> embed(List<Document> documents) {
        if (documents.isEmpty()) {
            return documents;
        }

        // PgVectorStore 会自动处理 embedding，这里只做日志
        log.info("文档已准备向量化，文档数={}", documents.size());
        return documents;
    }
}
