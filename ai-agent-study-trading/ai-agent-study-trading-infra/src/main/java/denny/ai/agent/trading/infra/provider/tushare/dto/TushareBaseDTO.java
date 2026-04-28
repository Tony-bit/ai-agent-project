package denny.ai.agent.trading.infra.provider.tushare.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;

/**
 * Tushare 响应 DTO 基类。
 * <p>
 * 提供统一的类型转换工具方法，供子类 getter 调用。
 * 字段映射由 Lombok + Jackson 注解自动处理（见子类 @JsonNaming）。
 */
public abstract class TushareBaseDTO {

    private static final DateTimeFormatter TUSHARE_DATE = DateTimeFormatter.ofPattern("yyyyMMdd");

    protected BigDecimal dec(Double v) {
        return v != null ? BigDecimal.valueOf(v) : null;
    }

    protected LocalDate localDate(String v) {
        if (v == null || v.length() != 8) return null;
        try { return LocalDate.parse(v, TUSHARE_DATE); } catch (Exception e) { return null; }
    }

    /**
     * 格式化日期：Tushare "20260425" → "2026-04-25"
     */
    protected String fmtDate(String v) {
        if (v == null || v.length() != 8) return v;
        return v.substring(0, 4) + "-" + v.substring(4, 6) + "-" + v.substring(6, 8);
    }
}
