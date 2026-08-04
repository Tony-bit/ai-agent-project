package denny.ai.agent.domain.model.valobj;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 股票分析切槽 VO（STOCK_ANALYSIS 意图专属）
 *
 * @author denny
 * 2026/5/11
 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class StockSlot {

    /**
     * 股票代码或名称（如：平安银行、000001）
     */
    private String stockCode;

    /**
     * 股票正式名称。名称输入场景由股票搜索工具的唯一结果填充。
     */
    private String stockName;

    /**
     * 查询类型：走势分析/基本面分析/技术面分析/情绪分析/综合分析
     */
    private String stockQueryType;

    /**
     * 时间范围：如近一年、近三个月、今日等
     */
    private String timeRange;

    /**
     * 仅用于兼容旧路由 JSON，不参与股票身份构造或校验。
     */
    @Deprecated
    private String exchange;
}
