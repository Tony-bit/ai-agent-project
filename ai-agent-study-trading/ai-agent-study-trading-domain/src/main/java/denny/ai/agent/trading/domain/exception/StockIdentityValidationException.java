package denny.ai.agent.trading.domain.exception;

public class StockIdentityValidationException extends RuntimeException {

    public StockIdentityValidationException(String message) {
        super(message);
    }

    public StockIdentityValidationException(String message, Throwable cause) {
        super(message, cause);
    }
}
