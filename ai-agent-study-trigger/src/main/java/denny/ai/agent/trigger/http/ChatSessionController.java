package denny.ai.agent.trigger.http;

import denny.ai.agent.api.response.Response;
import denny.ai.agent.api.vo.MessageListResult;
import denny.ai.agent.api.vo.SessionListResult;
import denny.ai.agent.infrastructure.service.ChatSessionQueryService;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

/**
 * 会话列表查询 HTTP 接口
 *
 * @author denny
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/session")
@CrossOrigin(origins = "*", allowedHeaders = "*", methods = {RequestMethod.GET, RequestMethod.POST, RequestMethod.OPTIONS})
public class ChatSessionController {

    @Resource
    private ChatSessionQueryService chatSessionQueryService;

    /**
     * 获取用户会话列表（支持游标分页）
     *
     * @param userId     用户ID
     * @param cursorTime 游标时间（首次不传）
     * @param cursorId   游标ID（首次不传）
     * @return 会话列表
     */
    @GetMapping("/list")
    public Response<SessionListResult> getSessionList(
            @RequestParam(name = "userId") String userId,
            @RequestParam(name = "cursorTime", required = false) String cursorTime,
            @RequestParam(name = "cursorId", required = false) String cursorId) {
        log.info("获取会话列表: userId={}, cursorTime={}, cursorId={}", userId, cursorTime, cursorId);
        SessionListResult result = chatSessionQueryService.getSessionList(userId, cursorTime, cursorId);
        return Response.ok(result);
    }

    /**
     * 获取会话消息列表（支持游标分页）
     *
     * @param sessionId   会话ID
     * @param cursorIndex 游标索引（首次不传，返回最新的10条）
     * @return 消息列表
     */
    @GetMapping("/{sessionId}/messages")
    public Response<MessageListResult> getSessionMessages(
            @PathVariable String sessionId,
            @RequestParam(required = false) Integer cursorIndex) {
        log.info("获取会话消息: sessionId={}, cursorIndex={}", sessionId, cursorIndex);
        MessageListResult result = chatSessionQueryService.getSessionMessages(sessionId, cursorIndex);
        return Response.ok(result);
    }
}
