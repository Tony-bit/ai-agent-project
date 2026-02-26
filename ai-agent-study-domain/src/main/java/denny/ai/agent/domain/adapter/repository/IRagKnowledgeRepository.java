package denny.ai.agent.domain.adapter.repository;

import org.springframework.web.multipart.MultipartFile;

/**
 * RAG 知识检索仓储接口（向量 + ES 混合检索的领域抽象）。
 *
 * @author denny
 */
public interface IRagKnowledgeRepository {

    /**
     * 基于用户问题做一次知识检索，返回可直接注入到大模型 Prompt 的上下文文本。
     * 支持按 user_id 隔离，只检索该用户的知识库。
     *
     * @param userId   用户ID，用于知识库隔离
     * @param question 用户问题
     * @param topK     期望召回的文档条数上限
     * @return 经过简单重排与拼接后的上下文文本（可能为空字符串）
     */
    String retrieveContext(String userId, String question, int topK);

    /**
     * 上传文件并构建 RAG 知识库（完整 Pipeline）。
     * 流程：文件解析 → Chunk 切分 → Embedding 向量化 → 向量存储 + ES 存储
     *
     * @param userId   用户ID，用于知识库隔离
     * @param file     上传的文件（支持 PDF、Word、TXT、Markdown 等）
     * @param fileName 文件名（可选，用于标题）
     * @return 处理结果（成功/失败信息）
     */
    String uploadAndIndex(String userId, MultipartFile file, String fileName);

    /**
     * 删除用户的知识库文档（按 user_id 批量删除）。
     *
     * @param userId 用户ID
     * @return 删除的文档数量
     */
    int deleteUserKnowledge(String userId);

}
