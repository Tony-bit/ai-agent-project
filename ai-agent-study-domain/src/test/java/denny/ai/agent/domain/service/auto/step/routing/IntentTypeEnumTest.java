package denny.ai.agent.domain.service.auto.step.routing;

import denny.ai.agent.domain.model.valobj.enums.IntentTypeEnum;
import org.junit.Test;

import static org.junit.Assert.*;

/**
 * IntentTypeEnum 枚举单元测试
 * <p>
 * 测试覆盖：
 * 1. TC-Enum-001 ~ TC-Enum-008: 验证 8 种意图类型枚举的 code 定义正确性
 * 2. fromCode 边界测试
 * </p>
 *
 * @author denny
 * 2026/5/11
 */
public class IntentTypeEnumTest {

    // ========== TC-Enum-001 ~ TC-Enum-008: 枚举 code 校验 ==========

    /**
     * TC-Enum-001: 股票分析枚举_校验code
     */
    @Test
    public void testStockAnalysisCode() {
        assertEquals("STOCK_ANALYSIS", IntentTypeEnum.STOCK_ANALYSIS.getCode());
    }

    /**
     * TC-Enum-002: PE推理枚举_校验code
     */
    @Test
    public void testPEReasoningCode() {
        assertEquals("PE_REASONING", IntentTypeEnum.PE_REASONING.getCode());
    }

    /**
     * TC-Enum-003: PE计算枚举_校验code
     */
    @Test
    public void testPECalculationCode() {
        assertEquals("PE_CALCULATION", IntentTypeEnum.PE_CALCULATION.getCode());
    }

    /**
     * TC-Enum-004: PE检索枚举_校验code
     */
    @Test
    public void testPERetrievalCode() {
        assertEquals("PE_RETRIEVAL", IntentTypeEnum.PE_RETRIEVAL.getCode());
    }

    /**
     * TC-Enum-005: 巡检枚举_校验code
     */
    @Test
    public void testInspectionCode() {
        assertEquals("INSPECTION", IntentTypeEnum.INSPECTION.getCode());
    }

    /**
     * TC-Enum-006: 通用对话枚举_校验code
     */
    @Test
    public void testGeneralChatCode() {
        assertEquals("GENERAL_CHAT", IntentTypeEnum.GENERAL_CHAT.getCode());
    }

    /**
     * TC-Enum-007: 模糊意图枚举_校验code
     */
    @Test
    public void testAmbiguousCode() {
        assertEquals("AMBIGUOUS", IntentTypeEnum.AMBIGUOUS.getCode());
    }

    /**
     * TC-Enum-008: 未知意图枚举_校验code
     */
    @Test
    public void testUnknownCode() {
        assertEquals("UNKNOWN", IntentTypeEnum.UNKNOWN.getCode());
    }

    // ========== fromCode 边界测试 ==========

    /**
     * TC-Enum-009: fromCode 正常转换
     */
    @Test
    public void testFromCode_validCode() {
        assertEquals(IntentTypeEnum.STOCK_ANALYSIS, IntentTypeEnum.fromCode("STOCK_ANALYSIS"));
        assertEquals(IntentTypeEnum.PE_REASONING, IntentTypeEnum.fromCode("PE_REASONING"));
        assertEquals(IntentTypeEnum.GENERAL_CHAT, IntentTypeEnum.fromCode("GENERAL_CHAT"));
    }

    /**
     * TC-Enum-010: fromCode 无效code降级为UNKNOWN
     */
    @Test
    public void testFromCode_invalidCode_returnsUnknown() {
        assertEquals(IntentTypeEnum.UNKNOWN, IntentTypeEnum.fromCode("INVALID"));
        assertEquals(IntentTypeEnum.UNKNOWN, IntentTypeEnum.fromCode("random_text"));
        assertEquals(IntentTypeEnum.UNKNOWN, IntentTypeEnum.fromCode(""));
    }

    /**
     * TC-Enum-011: fromCode null降级为UNKNOWN
     */
    @Test
    public void testFromCode_null_returnsUnknown() {
        assertEquals(IntentTypeEnum.UNKNOWN, IntentTypeEnum.fromCode(null));
    }

    /**
     * TC-Enum-012: fromCode 大小写敏感（严格匹配）
     */
    @Test
    public void testFromCode_caseSensitive() {
        assertEquals(IntentTypeEnum.STOCK_ANALYSIS, IntentTypeEnum.fromCode("STOCK_ANALYSIS"));
        assertEquals(IntentTypeEnum.UNKNOWN, IntentTypeEnum.fromCode("stock_analysis"));
        assertEquals(IntentTypeEnum.PE_REASONING, IntentTypeEnum.fromCode("PE_REASONING"));
        assertEquals(IntentTypeEnum.UNKNOWN, IntentTypeEnum.fromCode("pe_reasoning"));
    }
}
