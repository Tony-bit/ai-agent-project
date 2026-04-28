package denny.ai.agent.trading.infra.provider.tushare.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Tushare daily（日线行情）接口响应。
 * <p>
 * Jackson 自动映射：trade_date → tradeDate, adj_close → adjClose
 *
 * @see <a href="https://tushare.pro/document/2?doc_id=27">Tushare daily 文档</a>
 */
@Data
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
@JsonIgnoreProperties(ignoreUnknown = true)
public class TushareDailyDTO extends TushareBaseDTO {

    private String tsCode;
    private String tradeDate;
    private BigDecimal open;
    private BigDecimal high;
    private BigDecimal low;
    private BigDecimal close;
    private BigDecimal preClose;
    private BigDecimal vol;
    private BigDecimal amount;
    private BigDecimal adjClose;
    private BigDecimal adjFactor;
    private BigDecimal change;
    private Double pctChg;
    private String isSt;

    public String getTradeDateFormatted() { return fmtDate(tradeDate); }
    public LocalDate getTradeDateLocal() { return localDate(tradeDate); }
    public Boolean getIsSt() { return "1".equals(isSt); }

    /**
     * 返回成交量（手），转为 Long。
     * vol 字段 Tushare 有时返回小数，统一用 BigDecimal 接收后转 Long。
     */
    public Long getVol() {
        return vol != null ? vol.longValue() : null;
    }
}
