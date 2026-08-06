package denny.ai.agent.trading.infra.provider;

import denny.ai.agent.domain.model.valobj.stock.StockNameRecord;
import denny.ai.agent.trading.infra.provider.tushare.dto.TushareStockBasicDTO;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TushareStockNameSourceTest {

    @Test
    void loadActiveStockNames_callsSingleFullLoadAndReturnsMinimalRecords() {
        RecordingTushareApiClient apiClient = new RecordingTushareApiClient(List.of(
                dto("000001.SZ", "平安银行"),
                dto("600000.SH", "浦发银行"),
                dto("430001.BJ", "北交样例")
        ));
        TushareStockNameSource source = new TushareStockNameSource(apiClient);

        List<StockNameRecord> result = source.loadActiveStockNames();

        assertEquals(1, apiClient.invocations.size());
        Invocation invocation = apiClient.invocations.get(0);
        assertEquals("stock_basic", invocation.apiName());
        assertEquals(Map.of("list_status", "L"), invocation.params());
        assertEquals("ts_code,name", invocation.fields());
        assertEquals(List.of(
                new StockNameRecord("平安银行", "000001"),
                new StockNameRecord("浦发银行", "600000"),
                new StockNameRecord("北交样例", "430001")
        ), result);
    }

    @Test
    void loadActiveStockNames_rejectsEmptyDirectory() {
        TushareStockNameSource source = new TushareStockNameSource(
                new RecordingTushareApiClient(Collections.emptyList()));

        IllegalStateException exception = assertThrows(IllegalStateException.class, source::loadActiveStockNames);

        assertTrue(exception.getMessage().contains("empty"));
    }

    @Test
    void loadActiveStockNames_rejectsInvalidTsCode() {
        TushareStockNameSource source = new TushareStockNameSource(
                new RecordingTushareApiClient(List.of(dto("BAD", "平安银行"))));

        IllegalStateException exception = assertThrows(IllegalStateException.class, source::loadActiveStockNames);

        assertTrue(exception.getMessage().contains("Invalid Tushare ts_code"));
    }

    @Test
    void loadActiveStockNames_rejectsBlankStockName() {
        TushareStockNameSource source = new TushareStockNameSource(
                new RecordingTushareApiClient(List.of(dto("000001.SZ", "  "))));

        IllegalStateException exception = assertThrows(IllegalStateException.class, source::loadActiveStockNames);

        assertTrue(exception.getMessage().contains("stock name is blank"));
    }

    @Test
    void loadActiveStockNames_rejectsDuplicateStockCode() {
        TushareStockNameSource source = new TushareStockNameSource(
                new RecordingTushareApiClient(List.of(
                        dto("000001.SZ", "平安银行"),
                        dto("000001.SZ", "平安银行A")
                )));

        IllegalStateException exception = assertThrows(IllegalStateException.class, source::loadActiveStockNames);

        assertTrue(exception.getMessage().contains("Duplicate stock code"));
    }

    @Test
    void findByExactName_returnsCurrentRequestResultsOnly() {
        RecordingTushareApiClient apiClient = new RecordingTushareApiClient(List.of(
                dto("600155.SH", "华创云信"),
                dto("002371.SZ", "北方华创")
        ));
        TushareStockNameSource source = new TushareStockNameSource(apiClient);

        List<StockNameRecord> result = source.findByExactName("华创");

        assertEquals(1, apiClient.invocations.size());
        Invocation invocation = apiClient.invocations.get(0);
        assertEquals(Map.of("name", "华创", "list_status", "L"), invocation.params());
        assertEquals(List.of(
                new StockNameRecord("华创云信", "600155"),
                new StockNameRecord("北方华创", "002371")
        ), result);
    }

    @Test
    void findByExactName_returnsEmptyWhenTushareReturnsEmpty() {
        TushareStockNameSource source = new TushareStockNameSource(
                new RecordingTushareApiClient(Collections.emptyList()));

        List<StockNameRecord> result = source.findByExactName("不存在");

        assertTrue(result.isEmpty());
    }

    @Test
    void findByExactName_propagatesStrictExceptions() {
        TushareStockNameSource source = new TushareStockNameSource(
                new ThrowingTushareApiClient(new TushareProtocolException("stock_basic", "bad payload")));

        TushareProtocolException exception = assertThrows(
                TushareProtocolException.class,
                () -> source.findByExactName("平安银行"));

        assertEquals("stock_basic", exception.getApiName());
    }

    private static TushareStockBasicDTO dto(String tsCode, String name) {
        TushareStockBasicDTO dto = new TushareStockBasicDTO();
        dto.setTsCode(tsCode);
        dto.setName(name);
        return dto;
    }

    private record Invocation(String apiName, Map<String, Object> params, String fields) {
    }

    private static class RecordingTushareApiClient extends TushareApiClient {
        private final List<TushareStockBasicDTO> response;
        private final java.util.ArrayList<Invocation> invocations = new java.util.ArrayList<>();

        RecordingTushareApiClient(List<TushareStockBasicDTO> response) {
            super("test-token");
            this.response = response;
        }

        @Override
        public <T> List<T> callGenericStrict(Class<T> dtoClass, String apiName, Map<String, Object> params, String fields) {
            invocations.add(new Invocation(apiName, params, fields));
            return response.stream().map(dtoClass::cast).toList();
        }
    }

    private static class ThrowingTushareApiClient extends TushareApiClient {
        private final RuntimeException exception;

        ThrowingTushareApiClient(RuntimeException exception) {
            super("test-token");
            this.exception = exception;
        }

        @Override
        public <T> List<T> callGenericStrict(Class<T> dtoClass, String apiName, Map<String, Object> params, String fields) {
            throw exception;
        }
    }
}
