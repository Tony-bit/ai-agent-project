package denny.ai.agent.trading.domain.pipeline;

public class TradingPipelineException extends RuntimeException {

    public TradingPipelineException(String message) {
        super(message);
    }

    public TradingPipelineException(String message, Throwable cause) {
        super(message, cause);
    }
}
