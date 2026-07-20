package denny.ai.agent.domain.auth;

import org.springframework.stereotype.Component;

@Component
public class CurrentUserContext {

    private final ThreadLocal<AuthUser> currentUser = new ThreadLocal<>();

    public void setCurrentUser(AuthUser user) {
        if (user == null) {
            throw new IllegalArgumentException("current user must not be null");
        }
        currentUser.set(user);
    }

    public AuthUser getCurrentUser() {
        AuthUser user = currentUser.get();
        if (user == null) {
            throw new IllegalStateException("authentication required");
        }
        return user;
    }

    public AuthUser getCurrentUserOrNull() {
        return currentUser.get();
    }

    public String currentUserId() {
        return getCurrentUser().getUserId();
    }

    public void clear() {
        currentUser.remove();
    }
}
