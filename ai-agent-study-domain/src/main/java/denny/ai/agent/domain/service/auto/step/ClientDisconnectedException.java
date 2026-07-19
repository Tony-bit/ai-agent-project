package denny.ai.agent.domain.service.auto.step;

public class ClientDisconnectedException extends RuntimeException {

    public ClientDisconnectedException(String message) {
        super(message);
    }

    public ClientDisconnectedException(String message, Throwable cause) {
        super(message, cause);
    }
}
