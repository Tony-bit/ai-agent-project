package denny.ai.agent.trading.infra.provider;

/**
 * Tushare HTTP/网络传输错误。
 */
public class TushareTransportException extends RuntimeException {

    private final String apiName;

    public TushareTransportException(String apiName, Throwable cause) {
        super("Tushare transport error: apiName=" + apiName + ", cause=" + cause.getMessage(), cause);
        this.apiName = apiName;
    }

    public String getApiName() {
        return apiName;
    }
}
