package denny.ai.agent.trigger.http;

import denny.ai.agent.api.response.Response;
import denny.ai.agent.domain.auth.AuthUser;
import denny.ai.agent.domain.auth.CurrentUserContext;
import denny.ai.agent.infrastructure.security.JwtTokenService;
import denny.ai.agent.infrastructure.service.AuthService;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/auth")
@CrossOrigin(origins = "*")
@AllArgsConstructor
public class AuthController {

    private final AuthService authService;
    private final JwtTokenService tokenService;
    private final CurrentUserContext currentUserContext;

    @PostMapping("/login")
    public ResponseEntity<Response<?>> login(@RequestBody LoginRequest request) {
        try {
            return ResponseEntity.ok(authenticationResponse(
                    authService.login(request.getAccount(), request.getPassword())));
        } catch (AuthService.AuthFailure failure) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Response.error("401", "unauthorized"));
        }
    }

    @PostMapping("/guest")
    public ResponseEntity<Response<?>> guest() {
        try {
            return ResponseEntity.ok(authenticationResponse(authService.createGuest()));
        } catch (AuthService.AuthFailure failure) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Response.error("500", "operation failed"));
        }
    }

    @GetMapping("/me")
    public Response<UserView> me() {
        return Response.ok(UserView.from(currentUserContext.getCurrentUser()));
    }

    private Response<AuthResult> authenticationResponse(AuthUser user) {
        JwtTokenService.IssuedToken token = tokenService.issue(user);
        return Response.ok(new AuthResult(
                token.getAccessToken(), token.getTokenType(), token.getExpiresIn(), UserView.from(user)));
    }

    @Data
    public static class LoginRequest {
        private String account;
        private String password;
    }

    @Value
    public static class AuthResult {
        String accessToken;
        String tokenType;
        long expiresIn;
        UserView user;
    }

    @Value
    public static class UserView {
        String userId;
        AuthUser.UserType userType;
        String account;

        static UserView from(AuthUser user) {
            return new UserView(user.getUserId(), user.getUserType(), user.getAccount());
        }
    }
}
