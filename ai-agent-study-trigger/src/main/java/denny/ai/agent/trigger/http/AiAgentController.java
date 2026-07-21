package denny.ai.agent.trigger.http;

import com.alibaba.fastjson.JSON;
import denny.ai.agent.api.IAiAgentService;
import denny.ai.agent.api.dto.AutoAgentRequestDTO;
import denny.ai.agent.api.response.Response;
import denny.ai.agent.domain.auth.CurrentUserContext;
import denny.ai.agent.domain.model.entity.ExecuteCommandEntity;
import denny.ai.agent.domain.service.excute.IExecuteStrategy;
import denny.ai.agent.domain.service.oss.OSSUploadService;
import denny.ai.agent.infrastructure.service.SessionExecutionGuard;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyEmitter;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.ThreadPoolExecutor;

@Slf4j
@RestController
@RequestMapping("/api/v1/agent")
@CrossOrigin(origins = "*", allowedHeaders = {"Content-Type", "Authorization"}, methods = {
        RequestMethod.GET, RequestMethod.POST, RequestMethod.OPTIONS})
public class AiAgentController implements IAiAgentService {

    private final IExecuteStrategy autoAgentExecuteStrategy;
    private final ThreadPoolExecutor threadPoolExecutor;
    private final OSSUploadService ossUploadService;
    private final CurrentUserContext currentUserContext;
    private final SessionExecutionGuard executionGuard;

    public AiAgentController(@Qualifier("autoAgentExecuteStrategy") IExecuteStrategy autoAgentExecuteStrategy,
                             @Qualifier("threadPoolExecutor") ThreadPoolExecutor threadPoolExecutor,
                             OSSUploadService ossUploadService,
                             CurrentUserContext currentUserContext,
                             SessionExecutionGuard executionGuard) {
        this.autoAgentExecuteStrategy = autoAgentExecuteStrategy;
        this.threadPoolExecutor = threadPoolExecutor;
        this.ossUploadService = ossUploadService;
        this.currentUserContext = currentUserContext;
        this.executionGuard = executionGuard;
    }

    @Override
    @RequestMapping(value = "auto_agent", method = RequestMethod.POST,
            produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public ResponseBodyEmitter autoAgent(@RequestBody AutoAgentRequestDTO request, HttpServletResponse response) {
        log.info("AutoAgent streaming request: sessionId={}", request.getSessionId());
        return guardedRequest(request, request.getMaxStep(), request.getAgentType(), response);
    }

    @RequestMapping(value = "inspection", method = RequestMethod.POST,
            produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public ResponseBodyEmitter inspection(@RequestBody AutoAgentRequestDTO request, HttpServletResponse response) {
        log.info("Inspection streaming request: sessionId={}", request.getSessionId());
        return guardedRequest(request, 1, "inspection", response);
    }

    private ResponseBodyEmitter guardedRequest(AutoAgentRequestDTO request,
                                               Integer maxStep,
                                               String agentType,
                                               HttpServletResponse response) {
        String currentUserId = currentUserContext.currentUserId();
        final SessionExecutionGuard.ExecutionLease lease;
        try {
            lease = executionGuard.acquire(currentUserId, request.getSessionId());
        } catch (SessionExecutionGuard.ExecutionFailure failure) {
            int status = failure.getReason() == SessionExecutionGuard.FailureReason.INVALID ? 400 : 409;
            return buildErrorEmitter(response, status, failure.getMessage());
        }

        ExecuteCommandEntity command = ExecuteCommandEntity.builder()
                .aiAgentId(request.getAiAgentId())
                .message(request.getMessage())
                .sessionId(request.getSessionId())
                .maxStep(maxStep)
                .inputType(request.getInputType())
                .userId(currentUserId)
                .agentType(agentType)
                .build();
        return processAutoAgentRequest(command, response, lease);
    }

    private ResponseBodyEmitter processAutoAgentRequest(ExecuteCommandEntity command,
                                                        HttpServletResponse response,
                                                        SessionExecutionGuard.ExecutionLease lease) {
        configureSseResponse(response);
        ResponseBodyEmitter emitter = new ResponseBodyEmitter(Long.MAX_VALUE);
        try {
            threadPoolExecutor.execute(() -> {
                try {
                    autoAgentExecuteStrategy.execute(command, emitter);
                } catch (Exception exception) {
                    log.error("AutoAgent execution failed: sessionId={}", command.getSessionId(), exception);
                    completeWithError(emitter, "执行异常：" + exception.getMessage());
                } finally {
                    lease.close();
                }
            });
            return emitter;
        } catch (RuntimeException exception) {
            lease.close();
            log.error("AutoAgent task submission failed: sessionId={}", command.getSessionId(), exception);
            return buildErrorEmitter(response, 500, "operation failed");
        }
    }

    private void configureSseResponse(HttpServletResponse response) {
        response.setContentType(MediaType.TEXT_EVENT_STREAM_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setHeader("Cache-Control", "no-cache");
        response.setHeader("Connection", "keep-alive");
    }

    private ResponseBodyEmitter buildErrorEmitter(HttpServletResponse response, int status, String message) {
        response.setStatus(status);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        ResponseBodyEmitter emitter = new ResponseBodyEmitter();
        completeWithError(emitter, JSON.toJSONString(java.util.Map.of("error", message)));
        return emitter;
    }

    private void completeWithError(ResponseBodyEmitter emitter, String message) {
        try {
            emitter.send(message);
            emitter.complete();
        } catch (Exception exception) {
            emitter.completeWithError(exception);
        }
    }

    @Override
    @PostMapping(value = "/upload_image", consumes = MediaType.MULTIPART_FORM_DATA_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public Response<String> uploadImage(@RequestParam("file") MultipartFile file) {
        if (file.isEmpty()) {
            return new Response<>("401", "fail, upload file is null", null);
        }
        return new Response<>("200", "success", ossUploadService.upload(file));
    }
}
