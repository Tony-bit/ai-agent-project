package denny.ai.agent.domain.model.valobj.stock;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StockNameIndexTest {

    @Test
    void findExact_keepsDuplicateStandardNamesWithoutOverwriting() {
        StockNameIndex index = StockNameIndex.of(List.of(
                record("平安银行", "000001"),
                record("平安银行", "600001")
        ));

        List<StockNameRecord> matches = index.findExact("平安银行");

        assertEquals(List.of(
                record("平安银行", "000001"),
                record("平安银行", "600001")
        ), matches);
    }

    @Test
    void normalize_removesWhitespaceAndAppliesNfkc() {
        assertEquals("华创", StockNameIndex.normalize(" 华 创 "));
        assertEquals("ABC", StockNameIndex.normalize("Ａ b c"));
    }

    @Test
    void findFuzzy_matchesPrefixSuffixAndMiddleFragments() {
        StockNameIndex index = StockNameIndex.of(List.of(
                record("北方华创", "002371"),
                record("华创云信", "600155"),
                record("东方甄选科技", "300999")
        ));

        StockNameIndex.SearchResult result = index.findFuzzy("华创", 10);

        assertEquals(2, result.totalMatches());
        assertEquals(List.of(
                record("北方华创", "002371"),
                record("华创云信", "600155")
        ), result.candidates());
    }

    @Test
    void findFuzzy_preservesStMarkersAndLatinCaseNormalization() {
        StockNameIndex index = StockNameIndex.of(List.of(
                record("*ST中安A", "600654"),
                record("st华微", "600360")
        ));

        StockNameIndex.SearchResult result = index.findFuzzy("*st", 10);

        assertEquals(1, result.totalMatches());
        assertEquals(record("*ST中安A", "600654"), result.candidates().get(0));
        assertEquals("ST华微", StockNameIndex.normalize("st 华微"));
    }

    @Test
    void findFuzzy_returnsStableOrderingByStockNameThenStockCode() {
        StockNameIndex index = StockNameIndex.of(List.of(
                record("华创云信", "600155"),
                record("北方华创", "002371"),
                record("华创股份", "300001"),
                record("华创股份", "000777")
        ));

        StockNameIndex.SearchResult result = index.findFuzzy("华创", 10);

        assertEquals(List.of(
                record("北方华创", "002371"),
                record("华创云信", "600155"),
                record("华创股份", "000777"),
                record("华创股份", "300001")
        ), result.candidates());
    }

    @Test
    void findFuzzy_truncatesCandidatesButCountsAllMatches() {
        StockNameIndex index = StockNameIndex.of(List.of(
                record("华创一号", "000001"),
                record("华创二号", "000002"),
                record("华创三号", "000003")
        ));

        StockNameIndex.SearchResult result = index.findFuzzy("华创", 2);

        assertEquals(3, result.totalMatches());
        assertEquals(2, result.candidates().size());
    }

    @Test
    void findFuzzy_rejectsInvalidCandidateLimit() {
        StockNameIndex index = StockNameIndex.of(List.of(record("华创云信", "600155")));

        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> index.findFuzzy("华创", 0));

        assertTrue(exception.getMessage().contains("maxCandidates"));
    }

    private static StockNameRecord record(String stockName, String stockCode) {
        return StockNameRecord.builder()
                .stockName(stockName)
                .stockCode(stockCode)
                .build();
    }
}
