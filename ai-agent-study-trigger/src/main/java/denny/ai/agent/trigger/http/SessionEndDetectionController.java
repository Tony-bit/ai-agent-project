package denny.ai.agent.trigger.http;

import denny.ai.agent.api.response.Response;
import denny.ai.agent.domain.service.chatsession.ISessionEndDetectionService;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
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

}
