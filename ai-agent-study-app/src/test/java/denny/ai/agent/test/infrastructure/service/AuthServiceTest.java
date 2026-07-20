package denny.ai.agent.test.infrastructure.service;

import denny.ai.agent.domain.auth.AuthUser;
import denny.ai.agent.infrastructure.dao.IAuthUserDao;
import denny.ai.agent.infrastructure.dao.po.AuthUserPO;
import denny.ai.agent.infrastructure.service.AuthService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AuthServiceTest {

    private IAuthUserDao authUserDao;
    private BCryptPasswordEncoder passwordEncoder;
    private AuthService authService;

    @BeforeEach
    void setUp() {
        authUserDao = mock(IAuthUserDao.class);
        passwordEncoder = new BCryptPasswordEncoder();
        authService = new AuthService(authUserDao, passwordEncoder);
    }

    @Test
    void logsInActiveAccountWithMatchingPasswordHash() {
        AuthUserPO user = accountUser("demo", passwordEncoder.encode("correct-password"), "ACTIVE");
        when(authUserDao.queryByAccount("demo")).thenReturn(user);

        AuthUser result = authService.login(" demo ", "correct-password");

        assertEquals("user_demo", result.getUserId());
        assertEquals(AuthUser.UserType.ACCOUNT, result.getUserType());
        assertEquals("demo", result.getAccount());
    }

    @Test
    void rejectsUnknownAccountAndWrongPasswordWithSameError() {
        when(authUserDao.queryByAccount("missing")).thenReturn(null);
        when(authUserDao.queryByAccount("demo"))
                .thenReturn(accountUser("demo", passwordEncoder.encode("correct-password"), "ACTIVE"));

        AuthService.AuthFailure unknown = assertThrows(
                AuthService.AuthFailure.class,
                () -> authService.login("missing", "password"));
        AuthService.AuthFailure wrongPassword = assertThrows(
                AuthService.AuthFailure.class,
                () -> authService.login("demo", "wrong-password"));

        assertEquals(AuthService.AuthFailureReason.INVALID_CREDENTIALS, unknown.getReason());
        assertEquals(unknown.getMessage(), wrongPassword.getMessage());
    }

    @Test
    void rejectsDisabledAccountWithoutRevealingItsStatus() {
        when(authUserDao.queryByAccount("demo"))
                .thenReturn(accountUser("demo", passwordEncoder.encode("correct-password"), "DISABLED"));

        AuthService.AuthFailure failure = assertThrows(
                AuthService.AuthFailure.class,
                () -> authService.login("demo", "correct-password"));

        assertEquals(AuthService.AuthFailureReason.INVALID_CREDENTIALS, failure.getReason());
    }

    @Test
    void doesNotAcceptPlaintextStoredAsPasswordHash() {
        when(authUserDao.queryByAccount("demo"))
                .thenReturn(accountUser("demo", "correct-password", "ACTIVE"));

        assertThrows(AuthService.AuthFailure.class,
                () -> authService.login("demo", "correct-password"));
    }

    @Test
    void createsUniqueBackendOwnedGuestUsers() {
        AuthUser first = authService.createGuest();
        AuthUser second = authService.createGuest();

        assertTrue(first.getUserId().startsWith("guest_"));
        assertNotEquals(first.getUserId(), second.getUserId());
        assertEquals(AuthUser.UserType.GUEST, first.getUserType());
        assertNull(first.getAccount());
        verify(authUserDao, org.mockito.Mockito.times(2)).insert(any(AuthUserPO.class));
    }

    private AuthUserPO accountUser(String account, String passwordHash, String status) {
        AuthUserPO user = new AuthUserPO();
        user.setUserId("user_demo");
        user.setUserType("ACCOUNT");
        user.setAccount(account);
        user.setPasswordHash(passwordHash);
        user.setStatus(status);
        return user;
    }
}
