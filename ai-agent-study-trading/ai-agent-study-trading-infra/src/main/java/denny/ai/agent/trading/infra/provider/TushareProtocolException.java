package denny.ai.agent.trading.infra.provider;

/**
 * Tushare 响应协议或结构错误。
 */
public class TushareProtocolException extends RuntimeException {

    private final String apiName;

    public TushareProtocolException(String apiName, String message) {
        super("Tushare protocol error: apiName=" + apiName + ", message=" + message);
        this.apiName = apiName;
    }

    public TushareProtocolException(String apiName, String message, Throwable cause) {
        super("Tushare protocol error: apiName=" + apiName + ", message=" + message, cause);
        this.apiName = apiName;
    }

    public String getApiName() {
        return apiName;
    }
}
