package denny.ai.agent.trigger.http;

import denny.ai.agent.api.response.Response;
import denny.ai.agent.api.vo.MessageListResult;
import denny.ai.agent.api.vo.SessionListResult;
import denny.ai.agent.domain.auth.CurrentUserContext;
import denny.ai.agent.domain.service.chatsession.ISessionMemoryPersistenceService;
import denny.ai.agent.domain.service.chatsession.SessionAccessState;
import denny.ai.agent.infrastructure.service.ChatSessionCommandService;
import denny.ai.agent.infrastructure.service.ChatSessionQueryService;
import denny.ai.agent.infrastructure.service.SessionOwnershipService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/session")
@CrossOrigin(origins = "*", allowedHeaders = {"Content-Type", "Authorization"}, methods = {
        RequestMethod.GET, RequestMethod.POST, RequestMethod.DELETE, RequestMethod.OPTIONS})
public class ChatSessionController {

    private final ChatSessionQueryService queryService;
    private final ChatSessionCommandService commandService;
    private final SessionOwnershipService ownershipService;
    private final ISessionMemoryPersistenceService memoryPersistenceService;
    private final CurrentUserContext currentUserContext;

    public ChatSessionController(ChatSessionQueryService queryService,
                                 ChatSessionCommandService commandService,
                                 SessionOwnershipService ownershipService,
                                 ISessionMemoryPersistenceService memoryPersistenceService,
                                 CurrentUserContext currentUserContext) {
        this.queryService = queryService;
        this.commandService = commandService;
        this.ownershipService = ownershipService;
        this.memoryPersistenceService = memoryPersistenceService;
        this.currentUserContext = currentUserContext;
    }

    @GetMapping("/list")
    public ResponseEntity<Response<?>> getSessionList(
            @RequestParam(name = "cursorTime", required = false) String cursorTime,
            @RequestParam(name = "cursorId", required = false) String cursorId) {
        String userId = currentUserContext.currentUserId();
        SessionListResult result = queryService.getSessionList(userId, cursorTime, cursorId);
        return ResponseEntity.ok(Response.ok(result));
    }

    @GetMapping("/{sessionId}/messages")
    public ResponseEntity<Response<?>> getSessionMessages(
            @PathVariable("sessionId") String sessionId,
            @RequestParam(name = "cursorIndex", required = false) Integer cursorIndex) {
        try {
            MessageListResult result = queryService.getSessionMessages(
                    currentUserContext.currentUserId(), sessionId, cursorIndex);
            return ResponseEntity.ok(Response.ok(result));
        } catch (IllegalArgumentException exception) {
            return error(HttpStatus.BAD_REQUEST, "400", "invalid request");
        } catch (ChatSessionQueryService.SessionQueryFailure failure) {
            return error(HttpStatus.CONFLICT, "409", "session id unavailable");
        }
    }

    @DeleteMapping("/{sessionId}")
    public ResponseEntity<Response<?>> deleteSession(@PathVariable("sessionId") String sessionId) {
        try {
            commandService.deleteOwnedSession(currentUserContext.currentUserId(), sessionId);
            return ResponseEntity.ok(Response.ok());
        } catch (IllegalArgumentException exception) {
            return error(HttpStatus.BAD_REQUEST, "400", "invalid request");
        } catch (ChatSessionCommandService.SessionCommandFailure failure) {
            if (failure.getReason() == ChatSessionCommandService.FailureReason.RUNNING) {
                return error(HttpStatus.CONFLICT, "409", "session is running");
            }
            return error(HttpStatus.NOT_FOUND, "404", "session not found");
        }
    }

    @PostMapping("/{sessionId}/sync-memory")
    public ResponseEntity<Response<?>> syncSessionMemory(@PathVariable("sessionId") String sessionId) {
        String userId = currentUserContext.currentUserId();
        try {
            if (ownershipService.resolve(userId, sessionId) != SessionAccessState.OWNED) {
                return error(HttpStatus.NOT_FOUND, "404", "session not found");
            }
            memoryPersistenceService.syncSessionToMemory(userId, sessionId);
            return ResponseEntity.ok(Response.ok());
        } catch (IllegalArgumentException exception) {
            return error(HttpStatus.BAD_REQUEST, "400", "invalid request");
        }
    }

    private ResponseEntity<Response<?>> error(HttpStatus status, String code, String info) {
        return ResponseEntity.status(status).body(Response.error(code, info));
    }
}
