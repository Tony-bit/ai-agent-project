package denny.ai.agent.trading.trigger.http;

import denny.ai.agent.trading.api.vo.AnalystTypeEnum;
import lombok.Data;

import java.io.Serializable;
import java.util.List;

/**
 * 股票分析请求 DTO。
 */
@Data
public class TradingAnalysisRequestDTO implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 股票代码（如 AAPL、NVDA）
     */
    private String ticker;

    /**
     * 分析日期，格式 yyyy-MM-dd（可选，默认当天）
     */
    private String tradeDate;

    /**
     * 启用的分析师类型列表（可选，默认全部）
     */
    private List<AnalystTypeEnum> selectedAnalysts;

    /**
     * 最大辩论轮次（可选，默认2）
     */
    private Integer maxDebateRounds = 2;

    /**
     * 最大风控辩论轮次（可选，默认1）
     */
    private Integer maxRiskRounds = 1;

    /**
     * 会话 ID（可选，用于记忆上下文）
     */
    private String sessionId;
}
