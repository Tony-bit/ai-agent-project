package denny.ai.agent.domain.model.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.web.multipart.MultipartFile;

/**
 * 执行命令实体
 *
 * @author denny
 * 2025/7/27 16:46
 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class ExecuteCommandEntity {

    /**
     * 智能体类型
     * <p>
     * - null / "default": 默认对话流程（分析 -> 执行 -> 监督 -> 总结）
     * - "inspection": 智能巡检流程（直接执行巡检任务后结束）
     */
    public static final String AGENT_TYPE_DEFAULT = "default";
    public static final String AGENT_TYPE_INSPECTION = "inspection";

    private String aiAgentId;

    private String message;

    private String sessionId;

    private Integer maxStep;

    private Integer inputType;

    private MultipartFile file;

    private String userId;

    /**
     * 智能体类型，默认为 default
     */
    private String agentType;

}
