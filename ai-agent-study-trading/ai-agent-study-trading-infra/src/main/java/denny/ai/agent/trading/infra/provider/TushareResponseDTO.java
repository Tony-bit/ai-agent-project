package denny.ai.agent.trading.infra.provider;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;
import java.util.List;

/**
 * Tushare API 统一响应结构。
 * <p>
 * 所有 Tushare 接口返回格式统一为：
 * <pre>
 * {
 *   "code": 0,
 *   "msg": "",
 *   "data": {
 *     "fields": ["trade_date", "open", "high", ...],
 *     "items": [["20240101", "10.5", "11.2", ...], ...]
 *   }
 * }
 * </pre>
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class TushareResponseDTO {

    /** 0=成功，非0=失败 */
    private int code;
    private String msg;
    private TushareData data;

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class TushareData {
        /** ts_code: TS股票代码，symbol: 股票代码，name: 股票名称，area: 地域，industry: 所属行业，market: 市场类型，exchange: 交易所代码，list_date: 上市日期，list_status: 上市状态（L上市/D退市/G过会未交易/P暂停上市），is_hs: 是否沪深港通（N否/H沪股通/S深股通），具体以实际接口返回为准 */
        private List<String> fields;

        private List<List<Object>> items;

        /** true: 还有更多数据，false: 数据已全部返回，null: 不支持分页 */
        private Boolean has_more;
    }
}
