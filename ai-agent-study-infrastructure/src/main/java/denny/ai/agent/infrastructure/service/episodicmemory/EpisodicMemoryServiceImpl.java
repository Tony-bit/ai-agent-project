package denny.ai.agent.infrastructure.service.episodicmemory;

import denny.ai.agent.domain.model.valobj.MemoryProperties;
import denny.ai.agent.domain.service.episodicmemory.IEpisodicMemoryService;
import denny.ai.agent.infrastructure.mem0.Mem0RestClient;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 情景记忆搜索服务实现
 * <p>
 * 直接调用 Mem0 searchMemories 接口，返回格式化后的记忆列表。
 * 无 Redis 缓存：每次用户消息的 query 不同，缓存命中极低。
 * </p>
 *
 * @author denny
 */
@Slf4j
@Service
public class EpisodicMemoryServiceImpl implements IEpisodicMemoryService {

    @Resource
    private Mem0RestClient mem0RestClient;

    @Resource
    private MemoryProperties memoryProperties;

    @Override
    public String searchEpisodicMemories(String userId, String query, int limit) {
        if (query == null || query.isBlank()) {
            log.debug("query 为空，跳过情景记忆搜索, userId={}", userId);
            return "";
        }

        try {
            Mem0RestClient.SearchRequest request = Mem0RestClient.SearchRequest.builder()
                    .query(query)
                    .user_id(userId)
                    .limit(limit)
                    .build();

            Mem0RestClient.Mem0ServerResp resp = mem0RestClient.searchMemories(request);
            String result = formatSearchResults(resp);

            if (!result.isEmpty()) {
                log.info("情景记忆搜索成功, userId={}, queryLen={}, resultLen={}",
                        userId, query.length(), result.length());
            }
            return result;

        } catch (Exception e) {
            log.warn("情景记忆搜索失败，降级返回空, userId={}, queryLen={}, error={}",
                    userId, query.length(), e.getMessage());
            return "";
        }
    }

    private String formatSearchResults(Mem0RestClient.Mem0ServerResp resp) {
        if (resp == null || resp.getResults() == null || resp.getResults().isEmpty()) {
            return "";
        }

        StringBuilder sb = new StringBuilder();
        sb.append("\n\n[情景记忆]\n");

        int idx = 1;
        for (Mem0RestClient.Mem0ServerResp.Mem0Results result : resp.getResults()) {
            String scoreStr = result.getScore() != null
                    ? String.format(" (相似度: %.2f)", result.getScore())
                    : "";
            sb.append(idx++).append(". ")
              .append(result.getMemory())
              .append(scoreStr)
              .append("\n");
        }

        return sb.toString();
    }
}
