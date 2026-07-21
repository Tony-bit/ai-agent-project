package denny.ai.agent.test.trigger.http;

import denny.ai.agent.domain.auth.AuthUser;
import denny.ai.agent.domain.auth.CurrentUserContext;
import denny.ai.agent.infrastructure.security.JwtProperties;
import denny.ai.agent.infrastructure.security.JwtTokenService;
import denny.ai.agent.infrastructure.service.AuthService;
import denny.ai.agent.trigger.http.AuthenticationFilter;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AuthenticationFilterTest {

    private AuthService authService;
    private CurrentUserContext currentUserContext;
    private JwtTokenService tokenService;
    private AuthenticationFilter filter;

    @BeforeEach
    void setUp() {
        authService = mock(AuthService.class);
        currentUserContext = new CurrentUserContext();
        tokenService = tokenService(3600);
        filter = new AuthenticationFilter(tokenService, authService, currentUserContext);
    }

    @Test
    void missingBearerTokenReturns401WithoutInvokingChain() throws Exception {
        FilterResult result = invoke(null);

        assertEquals(401, result.response.getStatus());
        assertFalse(result.chainInvoked);
    }

    @Test
    void invalidOrExpiredTokenReturns401() throws Exception {
        assertEquals(401, invoke("Bearer not-a-jwt").response.getStatus());

        String expired = tokenService(-1).issue(accountUser()).getAccessToken();
        assertEquals(401, invoke("Bearer " + expired).response.getStatus());
    }

    @Test
    void missingOrDisabledDatabaseUserReturns401() throws Exception {
        String token = tokenService.issue(accountUser()).getAccessToken();
        when(authService.findActiveUser("user_demo")).thenReturn(null);

        FilterResult result = invoke("Bearer " + token);

        assertEquals(401, result.response.getStatus());
        assertFalse(result.chainInvoked);
    }

    @Test
    void validTokenInjectsUserForRequestAndAlwaysClearsContext() throws Exception {
        AuthUser user = accountUser();
        String token = tokenService.issue(user).getAccessToken();
        when(authService.findActiveUser("user_demo")).thenReturn(user);
        AtomicReference<String> userSeenByChain = new AtomicReference<>();

        MockHttpServletRequest request = request("Bearer " + token);
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = (req, res) -> userSeenByChain.set(currentUserContext.currentUserId());
        filter.doFilter(request, response, chain);

        assertEquals("user_demo", userSeenByChain.get());
        assertNull(currentUserContext.getCurrentUserOrNull());
    }

    @Test
    void loginRegisterGuestStaticAndOptionsRequestsAreNotFiltered() throws Exception {
        assertTrue(filter.isPublicRequest("POST", "/api/v1/auth/login"));
        assertTrue(filter.isPublicRequest("POST", "/api/v1/auth/register"));
        assertTrue(filter.isPublicRequest("POST", "/api/v1/auth/guest"));
        assertTrue(filter.isPublicRequest("GET", "/index.html"));
        assertTrue(filter.isPublicRequest("OPTIONS", "/api/v1/session/list"));
        assertFalse(filter.isPublicRequest("GET", "/api/v1/auth/me"));
    }

    private FilterResult invoke(String authorization) throws Exception {
        MockHttpServletRequest request = request(authorization);
        MockHttpServletResponse response = new MockHttpServletResponse();
        boolean[] invoked = {false};
        filter.doFilter(request, response, (req, res) -> invoked[0] = true);
        return new FilterResult(response, invoked[0]);
    }

    private MockHttpServletRequest request(String authorization) {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/session/list");
        if (authorization != null) {
            request.addHeader("Authorization", authorization);
        }
        return request;
    }

    private JwtTokenService tokenService(long expiresInSeconds) {
        JwtProperties properties = new JwtProperties();
        properties.setSecret("test-secret-that-is-long-enough-for-hmac-signing");
        properties.setExpiresInSeconds(expiresInSeconds);
        return new JwtTokenService(properties);
    }

    private AuthUser accountUser() {
        return AuthUser.builder().userId("user_demo").userType(AuthUser.UserType.ACCOUNT)
                .account("demo").status(AuthUser.UserStatus.ACTIVE).build();
    }

    private record FilterResult(MockHttpServletResponse response, boolean chainInvoked) {
    }
}
