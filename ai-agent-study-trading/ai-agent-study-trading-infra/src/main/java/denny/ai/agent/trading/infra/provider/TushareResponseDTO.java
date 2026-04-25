package denny.ai.agent.trading.infra.provider;

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
public class TushareResponseDTO {

    /** 0=成功，非0=失败 */
    private int code;
    private String msg;
    private TushareData data;

    @Data
    public static class TushareData {
        /** 字段名列表，与 items 每行一一对应 */
        private List<String> fields;
        /** 数据行列表，每行数据与 fields 对应 */
        private List<List<Object>> items;
    }
}
