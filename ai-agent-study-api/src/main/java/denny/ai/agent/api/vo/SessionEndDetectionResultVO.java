package denny.ai.agent.api.vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 会话结束检测结果 VO
 *
 * @author denny
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SessionEndDetectionResultVO {

    /**
     * true = 已结束，false = 未结束
     */
    private boolean ended;

    /**
     * 检测结果来源：KEYWORD / LLM / TIME_WINDOW / NOT_ENDED
     */
    private String source;

    /**
     * 检测详情说明
     */
    private String reason;
}
