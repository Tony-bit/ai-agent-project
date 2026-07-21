package denny.ai.agent.test.infrastructure.service;

import denny.ai.agent.domain.auth.AuthUser;
import denny.ai.agent.infrastructure.dao.IAuthUserDao;
import denny.ai.agent.infrastructure.dao.po.AuthUserPO;
import denny.ai.agent.infrastructure.service.AuthService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
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

    @Test
    void registersActiveAccountWithNormalizedNameAndBcryptPassword() {
        AuthUser result = authService.register(" new_user ", "secure-password");

        ArgumentCaptor<AuthUserPO> captor = ArgumentCaptor.forClass(AuthUserPO.class);
        verify(authUserDao).insert(captor.capture());
        AuthUserPO inserted = captor.getValue();
        assertTrue(inserted.getUserId().startsWith("user_"));
        assertEquals("ACCOUNT", inserted.getUserType());
        assertEquals("new_user", inserted.getAccount());
        assertEquals("ACTIVE", inserted.getStatus());
        assertNotEquals("secure-password", inserted.getPasswordHash());
        assertTrue(passwordEncoder.matches("secure-password", inserted.getPasswordHash()));
        assertEquals(inserted.getUserId(), result.getUserId());
        assertEquals(AuthUser.UserType.ACCOUNT, result.getUserType());
    }

    @Test
    void rejectsInvalidRegistrationInputsWithoutWritingUser() {
        String[] invalidAccounts = {null, "", "ab", "user name", "user@example.com", "a".repeat(33)};
        for (String account : invalidAccounts) {
            AuthService.AuthFailure failure = assertThrows(AuthService.AuthFailure.class,
                    () -> authService.register(account, "secure-password"));
            assertEquals(AuthService.AuthFailureReason.INVALID_REGISTRATION, failure.getReason());
        }

        for (String password : new String[]{"short", "x".repeat(73)}) {
            AuthService.AuthFailure failure = assertThrows(AuthService.AuthFailure.class,
                    () -> authService.register("valid_user", password));
            assertEquals(AuthService.AuthFailureReason.INVALID_REGISTRATION, failure.getReason());
        }
        verify(authUserDao, never()).insert(any(AuthUserPO.class));
    }

    @Test
    void rejectsExistingAccountAndConcurrentUniqueKeyConflict() {
        when(authUserDao.queryByAccount("existing"))
                .thenReturn(accountUser("existing", passwordEncoder.encode("secure-password"), "ACTIVE"));
        AuthService.AuthFailure existing = assertThrows(AuthService.AuthFailure.class,
                () -> authService.register("existing", "secure-password"));

        when(authUserDao.queryByAccount("contended")).thenReturn(null);
        when(authUserDao.insert(any(AuthUserPO.class)))
                .thenThrow(new DuplicateKeyException("duplicate"));
        AuthService.AuthFailure contended = assertThrows(AuthService.AuthFailure.class,
                () -> authService.register("contended", "secure-password"));

        assertEquals(AuthService.AuthFailureReason.ACCOUNT_ALREADY_EXISTS, existing.getReason());
        assertEquals(AuthService.AuthFailureReason.ACCOUNT_ALREADY_EXISTS, contended.getReason());
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
