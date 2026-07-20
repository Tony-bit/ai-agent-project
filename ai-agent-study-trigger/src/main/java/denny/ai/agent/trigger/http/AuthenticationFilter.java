package denny.ai.agent.trigger.http;

import com.auth0.jwt.exceptions.JWTVerificationException;
import com.fasterxml.jackson.databind.ObjectMapper;
import denny.ai.agent.api.response.Response;
import denny.ai.agent.domain.auth.AuthUser;
import denny.ai.agent.domain.auth.CurrentUserContext;
import denny.ai.agent.infrastructure.security.JwtTokenService;
import denny.ai.agent.infrastructure.service.AuthService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class AuthenticationFilter extends OncePerRequestFilter {

    private static final String BEARER_PREFIX = "Bearer ";

    private final JwtTokenService tokenService;
    private final AuthService authService;
    private final CurrentUserContext currentUserContext;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public AuthenticationFilter(JwtTokenService tokenService,
                                AuthService authService,
                                CurrentUserContext currentUserContext) {
        this.tokenService = tokenService;
        this.authService = authService;
        this.currentUserContext = currentUserContext;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return isPublicRequest(request.getMethod(), request.getRequestURI());
    }

    public boolean isPublicRequest(String method, String path) {
        if ("OPTIONS".equalsIgnoreCase(method)) {
            return true;
        }
        if ("/api/v1/auth/login".equals(path) || "/api/v1/auth/guest".equals(path)) {
            return true;
        }
        if (path.startsWith("/actuator/health")) {
            return true;
        }
        return !path.startsWith("/api/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String authorization = request.getHeader("Authorization");
        if (authorization == null || !authorization.startsWith(BEARER_PREFIX)) {
            writeUnauthorized(response);
            return;
        }

        try {
            String token = authorization.substring(BEARER_PREFIX.length()).trim();
            if (token.isEmpty()) {
                writeUnauthorized(response);
                return;
            }
            JwtTokenService.TokenIdentity identity = tokenService.verify(token);
            AuthUser user = authService.findActiveUser(identity.getUserId());
            if (user == null) {
                writeUnauthorized(response);
                return;
            }
            currentUserContext.setCurrentUser(user);
            filterChain.doFilter(request, response);
        } catch (JWTVerificationException | IllegalArgumentException exception) {
            writeUnauthorized(response);
        } finally {
            currentUserContext.clear();
        }
    }

    private void writeUnauthorized(HttpServletResponse response) throws IOException {
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        objectMapper.writeValue(response.getWriter(), Response.error("401", "unauthorized"));
    }
}
