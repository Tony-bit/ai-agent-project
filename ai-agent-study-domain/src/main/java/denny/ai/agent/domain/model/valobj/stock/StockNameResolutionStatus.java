package denny.ai.agent.domain.model.valobj.stock;

/**
 * 股票名称解析结果状态。
 */
public enum StockNameResolutionStatus {
    NOT_FOUND,
    RESOLVED,
    AMBIGUOUS,
    TOO_MANY_CANDIDATES,
    ERROR
}
