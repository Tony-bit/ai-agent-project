package denny.ai.agent.api.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.web.multipart.MultipartFile;

import java.io.Serial;
import java.io.Serializable;

/**
 * AutoAgent 请求 DTO
 *
 * @author denny
 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class AutoAgentRequestDTO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * AI智能体ID
     */
    private String aiAgentId;

    /**
     * 用户ID
     */
    private String userId;

    /**
     * 用户消息
     */
    private String message;

    /**
     * 会话ID
     */
    private String sessionId;

    /**
     * 最大执行步数
     */
    private Integer maxStep;

    /**
     * 输入的内容 0.文本 1.图片 2.音频
     */
    private Integer inputType;

    /**
     * file 图片、音频存储路径
     */
    private String url;

    /**
     * 智能体类型：
     * - null / "default": 默认对话流程（分析 -> 执行 -> 监督 -> 总结）
     * - "inspection": 智能巡检流程（直接执行巡检任务后结束）
     */
    private String agentType;

}