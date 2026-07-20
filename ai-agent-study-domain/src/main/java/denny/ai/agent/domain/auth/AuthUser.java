package denny.ai.agent.domain.auth;

import lombok.Builder;
import lombok.Value;

/**
 * Authenticated user identity exposed to application services.
 */
@Value
@Builder
public class AuthUser {

    String userId;
    UserType userType;
    String account;
    UserStatus status;

    public enum UserType {
        ACCOUNT,
        GUEST
    }

    public enum UserStatus {
        ACTIVE,
        DISABLED
    }
}
