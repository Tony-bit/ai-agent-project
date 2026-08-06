package denny.ai.agent.domain.model.valobj.stock;

/**
 * 股票分析模式。
 */
public enum StockAnalysisMode {
    UNRESOLVED,
    QUICK,
    FULL;

    public static StockAnalysisMode fromCode(String code) {
        if (code == null || code.isBlank()) {
            return UNRESOLVED;
        }
        try {
            return StockAnalysisMode.valueOf(code.trim().toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            return UNRESOLVED;
        }
    }
}
