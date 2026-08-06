package denny.ai.agent.trading.infra.provider;

import denny.ai.agent.domain.model.valobj.stock.StockNameRecord;
import denny.ai.agent.domain.service.stock.StockNameSource;
import denny.ai.agent.trading.infra.provider.tushare.dto.TushareStockBasicDTO;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Loads active A-share stock names from Tushare stock_basic.
 */
@Slf4j
public class TushareStockNameSource implements StockNameSource {

    private static final String API_NAME = "stock_basic";
    private static final String LIST_STATUS_ACTIVE = "L";
    private static final String FIELDS = "ts_code,name";
    private static final Pattern TS_CODE_PATTERN = Pattern.compile("^(\\d{6})\\.(SH|SZ|BJ)$");

    private final TushareApiClient apiClient;

    public TushareStockNameSource(TushareApiClient apiClient) {
        this.apiClient = apiClient;
    }

    @Override
    public List<StockNameRecord> loadActiveStockNames() {
        List<TushareStockBasicDTO> rows = apiClient.callGenericStrict(
                TushareStockBasicDTO.class,
                API_NAME,
                Map.of("list_status", LIST_STATUS_ACTIVE),
                FIELDS);

        List<StockNameRecord> records = normalize(rows, true);
        if (records.isEmpty()) {
            throw new IllegalStateException("Tushare active stock directory is empty");
        }
        log.info("Loaded active stock names from Tushare: count={}", records.size());
        return records;
    }

    @Override
    public List<StockNameRecord> findByExactName(String stockName) {
        List<TushareStockBasicDTO> rows = apiClient.callGenericStrict(
                TushareStockBasicDTO.class,
                API_NAME,
                Map.of("name", stockName, "list_status", LIST_STATUS_ACTIVE),
                FIELDS);
        return normalize(rows, false);
    }

    private List<StockNameRecord> normalize(List<TushareStockBasicDTO> rows, boolean rejectDuplicateStockCode) {
        List<StockNameRecord> results = new ArrayList<>(rows.size());
        Set<String> seenCodes = rejectDuplicateStockCode ? new LinkedHashSet<>() : null;
        for (TushareStockBasicDTO row : rows) {
            StockNameRecord record = toRecord(row);
            if (seenCodes != null && !seenCodes.add(record.getStockCode())) {
                throw new IllegalStateException("Duplicate stock code in Tushare directory: " + record.getStockCode());
            }
            results.add(record);
        }
        return results;
    }

    private StockNameRecord toRecord(TushareStockBasicDTO row) {
        if (row == null) {
            throw new IllegalStateException("Tushare stock record is null");
        }
        String stockName = row.getName();
        if (stockName == null || stockName.isBlank()) {
            throw new IllegalStateException("Tushare stock name is blank");
        }
        String tsCode = row.getTsCode();
        if (tsCode == null || tsCode.isBlank()) {
            throw new IllegalStateException("Tushare ts_code is blank");
        }

        var matcher = TS_CODE_PATTERN.matcher(tsCode.trim());
        if (!matcher.matches()) {
            throw new IllegalStateException("Invalid Tushare ts_code: " + tsCode);
        }

        return StockNameRecord.builder()
                .stockName(stockName)
                .stockCode(matcher.group(1))
                .build();
    }
}
