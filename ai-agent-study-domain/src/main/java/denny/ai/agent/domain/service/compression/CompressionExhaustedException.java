package denny.ai.agent.domain.service.compression;

public class CompressionExhaustedException extends RuntimeException {

    public CompressionExhaustedException(String message) {
        super(message);
    }

    public CompressionExhaustedException(String message, Throwable cause) {
        super(message, cause);
    }
}
