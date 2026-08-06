package denny.ai.agent.trading.infra.provider;

/**
 * Tushare API 返回业务错误。
 */
public class TushareApiException extends RuntimeException {

    private final String apiName;
    private final int code;
    private final String apiMessage;

    public TushareApiException(String apiName, int code, String apiMessage) {
        super("Tushare API error: apiName=" + apiName + ", code=" + code + ", msg=" + apiMessage);
        this.apiName = apiName;
        this.code = code;
        this.apiMessage = apiMessage;
    }

    public String getApiName() {
        return apiName;
    }

    public int getCode() {
        return code;
    }

    public String getApiMessage() {
        return apiMessage;
    }
}
