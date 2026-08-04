package denny.ai.agent.trading.domain.exception;

public class StockIdentityProviderException extends RuntimeException {

    public StockIdentityProviderException(String ticker, Throwable cause) {
        super("Stock identity provider failed: ticker=" + ticker, cause);
    }
}
