package denny.ai.agent.trading.domain.exception;

public class StockIdentityNotFoundException extends RuntimeException {

    public StockIdentityNotFoundException(String ticker) {
        super("Stock identity was not found: ticker=" + ticker);
    }
}
