package denny.ai.agent.trading.infra.provider.tushare.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import lombok.Data;

/**
 * Tushare stock_basic 接口响应。
 * <p>
 * Jackson 自动映射：ts_code → tsCode, list_date → listDate
 *
 * @see <a href="https://tushare.pro/document/2?doc_id=25">Tushare stock_basic 文档</a>
 */
@Data
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
@JsonIgnoreProperties(ignoreUnknown = true)
public class TushareStockBasicDTO extends TushareBaseDTO {

    private String tsCode;
    private String symbol;
    private String name;
    private String area;
    private String industry;
    private String fullname;
    private String enname;
    private String cnspell;
    private String market;
    private String exchange;
    private String currType;
    private String listStatus;
    private String listDate;
    private String delistDate;
    private String isHs;
    private String actName;
    private String actEntType;

    public String getListDateFormatted() { return fmtDate(listDate); }
    public String getDelistDateFormatted() { return fmtDate(delistDate); }
}
