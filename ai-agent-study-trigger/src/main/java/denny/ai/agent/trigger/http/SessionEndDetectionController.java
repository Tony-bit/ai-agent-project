package denny.ai.agent.trigger.http;

import denny.ai.agent.api.response.Response;
import denny.ai.agent.api.vo.SessionEndDetectionResultVO;
import denny.ai.agent.domain.service.chatsession.ISessionEndDetectionService;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.*;

/**
 * 会话结束检测 HTTP 接口
 *
 * @author denny
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/session")
@CrossOrigin(origins = "*", allowedHeaders = "*",
        methods = {RequestMethod.GET, RequestMethod.POST, RequestMethod.OPTIONS})
public class SessionEndDetectionController {

    @Resource
    private ISessionEndDetectionService sessionEndDetectionService;

    /**
     * 判断会话是否已结束
     * <p>
     * 三层检测策略：
     * 1. 关键词正则匹配（快速判断结束语）
     * 2. LLM 语义兜底（正则未命中时）
     * 3. 滑动窗口兜底（8 分钟无活动，内存追踪）
     * </p>
     *
     * @param sessionId   会话ID
     * @param userId      用户ID（用于滑动窗口追踪）
     * @param lastMessage 最后一条用户消息（可为空，仅用于前两层判断）
     * @return 检测结果
     */
    @GetMapping("/{sessionId}/ended")
    public Response<?> isSessionEnded(
            @PathVariable String sessionId,
            @RequestParam(required = false) String userId,
            @RequestParam(required = false) String lastMessage) {

        log.info("会话结束检测请求: sessionId={}, userId={}, lastMessage={}", sessionId, userId, lastMessage);

        if (!StringUtils.hasText(sessionId)) {
            return Response.error("400", "sessionId 不能为空");
        }

        boolean ended = sessionEndDetectionService.isSessionEnded(sessionId, userId, lastMessage);

        SessionEndDetectionResultVO result = SessionEndDetectionResultVO.builder()
                .ended(ended)
                .source(ended ? "DETECTED" : "NOT_ENDED")
                .reason(ended ? "会话已结束" : "会话未结束")
                .build();

        return Response.ok(result);
    }
}
