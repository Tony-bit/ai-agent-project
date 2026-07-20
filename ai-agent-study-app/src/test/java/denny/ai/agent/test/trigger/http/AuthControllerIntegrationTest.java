package denny.ai.agent.test.trigger.http;

import denny.ai.agent.domain.auth.AuthUser;
import denny.ai.agent.domain.auth.CurrentUserContext;
import denny.ai.agent.infrastructure.security.JwtProperties;
import denny.ai.agent.infrastructure.security.JwtTokenService;
import denny.ai.agent.infrastructure.service.AuthService;
import denny.ai.agent.trigger.http.AuthController;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class AuthControllerIntegrationTest {

    private AuthService authService;
    private CurrentUserContext currentUserContext;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        authService = mock(AuthService.class);
        currentUserContext = new CurrentUserContext();
        JwtProperties properties = new JwtProperties();
        properties.setSecret("test-secret-that-is-long-enough-for-hmac-signing");
        properties.setExpiresInSeconds(3600);
        JwtTokenService tokenService = new JwtTokenService(properties);
        mockMvc = MockMvcBuilders.standaloneSetup(
                new AuthController(authService, tokenService, currentUserContext)).build();
    }

    @Test
    void loginReturnsTokenExpiryAndCurrentUser() throws Exception {
        when(authService.login("demo", "password")).thenReturn(accountUser());

        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"account\":\"demo\",\"password\":\"password\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("200"))
                .andExpect(jsonPath("$.data.tokenType").value("Bearer"))
                .andExpect(jsonPath("$.data.expiresIn").value(3600))
                .andExpect(jsonPath("$.data.accessToken").isNotEmpty())
                .andExpect(jsonPath("$.data.user.userId").value("user_demo"))
                .andExpect(jsonPath("$.data.user.passwordHash").doesNotExist());
    }

    @Test
    void invalidLoginUsesUnifiedUnauthorizedResponse() throws Exception {
        when(authService.login("missing", "wrong")).thenThrow(new AuthService.AuthFailure(
                AuthService.AuthFailureReason.INVALID_CREDENTIALS, "invalid credentials"));

        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"account\":\"missing\",\"password\":\"wrong\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("401"))
                .andExpect(jsonPath("$.info").value("unauthorized"));
    }

    @Test
    void guestCreatesNewIdentityAndToken() throws Exception {
        when(authService.createGuest()).thenReturn(guestUser());

        mockMvc.perform(post("/api/v1/auth/guest"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.user.userId").value("guest_123"))
                .andExpect(jsonPath("$.data.user.userType").value("GUEST"))
                .andExpect(jsonPath("$.data.user.account").isEmpty());
    }

    @Test
    void meReturnsAuthenticatedAccountOrGuest() throws Exception {
        currentUserContext.setCurrentUser(guestUser());

        mockMvc.perform(get("/api/v1/auth/me"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.userId").value("guest_123"))
                .andExpect(jsonPath("$.data.userType").value("GUEST"));
    }

    private AuthUser accountUser() {
        return AuthUser.builder().userId("user_demo").userType(AuthUser.UserType.ACCOUNT)
                .account("demo").status(AuthUser.UserStatus.ACTIVE).build();
    }

    private AuthUser guestUser() {
        return AuthUser.builder().userId("guest_123").userType(AuthUser.UserType.GUEST)
                .status(AuthUser.UserStatus.ACTIVE).build();
    }
}
