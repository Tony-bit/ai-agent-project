package denny.ai.agent.trading.api.vo;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 交易 Agent SSE 事件类型枚举。
 * <p>
 * 定义所有交易 Agent 的 SSE 事件类型，用于流式输出。
 */
@Getter
@AllArgsConstructor
public enum TradingSseEventEnum {

    // ===== 分析师事件 =====
    ANALYST_START("analyst", "分析师开始分析"),
    ANALYST_PROGRESS("analyst", "分析师进度更新"),
    ANALYST_REPORT("analyst", "分析师报告完成"),

    // ===== 辩论事件 =====
    DEBATE_START("debate", "辩论开始"),
    DEBATE_ROUND("debate", "辩论轮次"),
    DEBATE_COMPLETE("debate", "辩论完成"),

    // ===== 交易员事件 =====
    TRADER_PLAN("trader", "交易员计划"),

    // ===== 风控事件 =====
    RISK_DEBATE("risk", "风控辩论"),
    RISK_COMPLETE("risk", "风控完成"),

    // ===== 最终决策事件 =====
    FINAL_DECISION("final", "最终决策"),

    // ===== 系统事件 =====
    PROGRESS("progress", "整体进度"),
    HEARTBEAT("heartbeat", "心跳保活"),
    ERROR("error", "错误信息"),
    COMPLETE("complete", "任务完成");

    /**
     * 事件类型
     */
    private final String type;

    /**
     * 事件描述
     */
    private final String description;

    /**
     * 获取完整的事件名称
     */
    public String getEventName() {
        return this.name().toLowerCase();
    }
}
