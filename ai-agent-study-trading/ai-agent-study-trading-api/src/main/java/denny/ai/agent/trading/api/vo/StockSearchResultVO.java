package denny.ai.agent.trading.api.vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 股票搜索结果值对象。
 * <p>
 * 用于根据股票名称搜索时返回匹配的股票列表。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class StockSearchResultVO {

    /**
     * 股票代码（6位数字），如 603259
     */
    private String ticker;

    /**
     * 股票名称，如 药明康德
     */
    private String name;

    /**
     * 交易所代码，如 SSE（上交所）、SZSE（深交所）、BSE（北交所）
     */
    private String exchange;

    /**
     * 市场类型，如 主板、创业板、科创板、北交所
     */
    private String market;

    /**
     * Tushare 完整代码格式，如 603259.SH
     */
    private String tsCode;
}
