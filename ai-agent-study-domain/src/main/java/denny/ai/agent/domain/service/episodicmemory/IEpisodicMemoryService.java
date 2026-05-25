package denny.ai.agent.domain.service.episodicmemory;

/**
 * 情景记忆搜索服务接口
 * <p>
 * 提供跨会话情景记忆的语义搜索能力，直接调用 Mem0 searchMemories 接口，
 * 查询结果格式化后返回。无需缓存（每次 query 不同，缓存命中极低）。
 * </p>
 *
 * @author denny
 */
public interface IEpisodicMemoryService {

    int DEFAULT_LIMIT = 5;

    /**
     * 搜索情景记忆
     * <p>
     * 直接调用 Mem0 searchMemories 接口，返回格式化后的记忆列表。
     * </p>
     *
     * @param userId 用户ID
     * @param query  搜索关键词（直接使用用户原始消息）
     * @param limit  返回数量上限，默认 DEFAULT_LIMIT
     * @return 格式化后的记忆字符串，无结果时返回友好提示
     */
    String searchEpisodicMemories(String userId, String query, int limit);

    default String searchEpisodicMemories(String userId, String query) {
        return searchEpisodicMemories(userId, query, DEFAULT_LIMIT);
    }
}
