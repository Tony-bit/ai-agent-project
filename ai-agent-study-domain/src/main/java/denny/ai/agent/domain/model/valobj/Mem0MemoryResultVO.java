package denny.ai.agent.domain.model.valobj;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

/**
 * Mem0 记忆检索结果值对象
 * <p>
 * 封装 Mem0 REST API 记忆检索返回结果，
 * 屏蔽底层响应结构，直接暴露业务关心的字段。
 * </p>
 *
 * @author denny
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Mem0MemoryResultVO {

    /**
     * 记忆内容
     */
    private String memory;

    /**
     * 记忆 ID
     */
    private String memoryId;

    /**
     * 相似度评分
     */
    private Double score;

    /**
     * 附加元数据（Map结构）
     */
    private Map<String, Object> metadata;

    /**
     * 创建时间
     */
    private String createdAt;
}
