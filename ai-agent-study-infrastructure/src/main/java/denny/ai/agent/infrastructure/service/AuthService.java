package denny.ai.agent.infrastructure.service;

import denny.ai.agent.domain.auth.AuthUser;
import denny.ai.agent.infrastructure.dao.IAuthUserDao;
import denny.ai.agent.infrastructure.dao.po.AuthUserPO;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.Locale;
import java.util.UUID;

@Service
public class AuthService {

    private static final int MAX_ACCOUNT_LENGTH = 128;
    private static final int MAX_GUEST_INSERT_ATTEMPTS = 3;
    private static final String INVALID_CREDENTIALS_MESSAGE = "invalid credentials";

    private final IAuthUserDao authUserDao;
    private final PasswordEncoder passwordEncoder;

    @Autowired
    public AuthService(IAuthUserDao authUserDao) {
        this(authUserDao, new BCryptPasswordEncoder());
    }

    public AuthService(IAuthUserDao authUserDao, PasswordEncoder passwordEncoder) {
        this.authUserDao = authUserDao;
        this.passwordEncoder = passwordEncoder;
    }

    public AuthUser login(String account, String password) {
        String normalizedAccount = account == null ? null : account.trim();
        if (!StringUtils.hasText(normalizedAccount)
                || normalizedAccount.length() > MAX_ACCOUNT_LENGTH
                || !StringUtils.hasText(password)) {
            throw invalidCredentials();
        }

        AuthUserPO user = authUserDao.queryByAccount(normalizedAccount);
        if (user == null || !isActiveAccount(user) || !matches(password, user.getPasswordHash())) {
            throw invalidCredentials();
        }
        return toDomain(user);
    }

    public AuthUser createGuest() {
        for (int attempt = 0; attempt < MAX_GUEST_INSERT_ATTEMPTS; attempt++) {
            AuthUserPO guest = new AuthUserPO();
            guest.setUserId("guest_" + UUID.randomUUID().toString().replace("-", ""));
            guest.setUserType(AuthUser.UserType.GUEST.name());
            guest.setStatus(AuthUser.UserStatus.ACTIVE.name());
            try {
                authUserDao.insert(guest);
                return toDomain(guest);
            } catch (DuplicateKeyException ignored) {
                // Retry only the vanishingly rare generated-ID collision.
            }
        }
        throw new AuthFailure(AuthFailureReason.GUEST_CREATION_FAILED, "guest creation failed");
    }

    public AuthUser findActiveUser(String userId) {
        if (!StringUtils.hasText(userId)) {
            return null;
        }
        AuthUserPO user = authUserDao.queryByUserId(userId);
        if (user == null || !AuthUser.UserStatus.ACTIVE.name().equalsIgnoreCase(user.getStatus())) {
            return null;
        }
        return toDomain(user);
    }

    private boolean isActiveAccount(AuthUserPO user) {
        return AuthUser.UserType.ACCOUNT.name().equalsIgnoreCase(user.getUserType())
                && AuthUser.UserStatus.ACTIVE.name().equalsIgnoreCase(user.getStatus())
                && StringUtils.hasText(user.getPasswordHash());
    }

    private boolean matches(String password, String passwordHash) {
        try {
            return passwordEncoder.matches(password, passwordHash);
        } catch (IllegalArgumentException exception) {
            return false;
        }
    }

    private AuthUser toDomain(AuthUserPO user) {
        return AuthUser.builder()
                .userId(user.getUserId())
                .userType(AuthUser.UserType.valueOf(user.getUserType().toUpperCase(Locale.ROOT)))
                .account(user.getAccount())
                .status(AuthUser.UserStatus.valueOf(user.getStatus().toUpperCase(Locale.ROOT)))
                .build();
    }

    private AuthFailure invalidCredentials() {
        return new AuthFailure(AuthFailureReason.INVALID_CREDENTIALS, INVALID_CREDENTIALS_MESSAGE);
    }

    public enum AuthFailureReason {
        INVALID_CREDENTIALS,
        GUEST_CREATION_FAILED
    }

    public static class AuthFailure extends RuntimeException {

        private final AuthFailureReason reason;

        public AuthFailure(AuthFailureReason reason, String message) {
            super(message);
            this.reason = reason;
        }

        public AuthFailureReason getReason() {
            return reason;
        }
    }
}
