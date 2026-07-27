package denny.ai.agent.trading.api.vo;

/** 数据提供者返回的待校验股票身份记录。 */
public record StockIdentityVO(String targetId, String stockName, String industry) {
}
