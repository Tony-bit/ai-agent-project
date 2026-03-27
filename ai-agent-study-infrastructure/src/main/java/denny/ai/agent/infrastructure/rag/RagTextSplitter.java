package denny.ai.agent.infrastructure.rag;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * RAG 文本分块服务：将长文本切分为适合向量化的 Chunk。
 * 使用固定字符数作为切分依据（简化实现，避免 API 兼容性问题）。
 *
 * @author denny
 */
@Slf4j
@Service
public class RagTextSplitter {

    // 默认配置：每个 chunk 最多 1000 字符，重叠 200 字符（保证上下文连续性）
    private static final int DEFAULT_CHUNK_SIZE = 1000;
    private static final int DEFAULT_CHUNK_OVERLAP = 200;

    /**
     * 将文档列表切分为更小的 chunk。
     *
     * @param documents 原始文档列表
     * @return 切分后的 Document 列表
     */
    public List<Document> split(List<Document> documents) {
        return split(documents, DEFAULT_CHUNK_SIZE, DEFAULT_CHUNK_OVERLAP);
    }

    /**
     * 自定义 chunk 大小和重叠。
     */
    public List<Document> split(List<Document> documents, int chunkSize, int chunkOverlap) {
        List<Document> chunks = new ArrayList<>();
        
        for (Document doc : documents) {
            String text = doc.getText();
            if (text == null || text.isEmpty()) {
                continue;
            }
            
            // 简单按字符数切分
            int start = 0;
            int chunkIndex = 0;
            while (start < text.length()) {
                int end = Math.min(start + chunkSize, text.length());
                String chunkText = text.substring(start, end);
                
                // 创建新的 Document
                Document chunk = new Document(chunkText);
                
                // 添加 chunk 元数据
                chunk.getMetadata().put("chunkIndex", chunkIndex);
                chunk.getMetadata().put("totalChunks", (int) Math.ceil((double) text.length() / chunkSize));
                
                // 保留原始文档的元数据
                doc.getMetadata().forEach((key, value) -> {
                    if (!chunk.getMetadata().containsKey(key)) {
                        chunk.getMetadata().put(key, value);
                    }
                });
                
                chunks.add(chunk);
                
                // 移动到下一个 chunk（考虑重叠）
                // 关键修复：短文本场景下 end - chunkOverlap 可能为负数，需做边界保护，避免 substring 下标越界。
                int nextStart = end - chunkOverlap;

                // 如果已经到达文本末尾，直接结束
                if (end >= text.length()) {
                    break;
                }

                // 防止 nextStart 回退或不前进导致死循环/负下标
                if (nextStart <= start) {
                    nextStart = end;
                }
                if (nextStart < 0) {
                    nextStart = 0;
                }

                start = nextStart;
                chunkIndex++;
            }
        }
        
        log.info("文本切分完成，原始文档数={}, 切分后 chunk 数={}", documents.size(), chunks.size());
        return chunks;
    }
}
