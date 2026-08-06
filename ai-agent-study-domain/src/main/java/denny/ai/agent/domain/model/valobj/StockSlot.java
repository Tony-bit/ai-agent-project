package denny.ai.agent.domain.model.valobj;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 股票分析槽位 VO。
 *
 * 保持与旧路由 JSON 的反序列化兼容，同时为股票名称补全故事提供新字段。
 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class StockSlot {

    /**
     * 用户原始输入中提取出的连续股票名称片段。
     */
    private String stockNameQuery;

    /**
     * 六位股票代码。
     */
    private String stockCode;

    /**
     * Java 解析后的规范股票名称。
     */
    private String stockName;

    /**
     * 股票分析模式：UNRESOLVED / QUICK / FULL。
     */
    private String analysisMode;

    /**
     * 兼容旧 Trading 查询类型。
     */
    private String stockQueryType;

    /**
     * 兼容旧时间范围字段。
     */
    private String timeRange;

    /**
     * 仅用于兼容旧路由 JSON，不参与股票身份构造或校验。
     */
    @Deprecated
    private String exchange;
}
