package denny.ai.agent.domain.service.armory;

import denny.ai.agent.domain.model.valobj.AiClientModelVO.RetryConfig;
import denny.ai.agent.domain.service.armory.factory.element.AiErrorCodeExtractor;
import denny.ai.agent.domain.service.armory.factory.element.RetryChatModel;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.MockitoJUnitRunner;
import org.springframework.ai.chat.model.ChatModel;

import java.lang.reflect.Method;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

/**
 * AiErrorCodeExtractor 错误码提取测试
 * <p>
 * 测试覆盖：TC-Retry-041, TC-Retry-042, TC-Retry-043, TC-Retry-044, TC-Retry-045
 * </p>
 */
@RunWith(MockitoJUnitRunner.class)
public class ExtractErrorCodeTest {

    private final AiErrorCodeExtractor extractor = new AiErrorCodeExtractor();

    // TC-Retry-041: 提取智谱格式 errorCode
    @Test
    public void testExtractZhipuFormat() {
        String code = extractor.extract(
                new RuntimeException("{\"error\":{\"code\":\"1002\",\"message\":\"Authentication Token 非法\"}}"));
        assertEquals("1002", code);
    }

    // TC-Retry-042: 提取 OpenAI 格式 errorCode
    @Test
    public void testExtractOpenAIFormat() {
        String code = extractor.extract(
                new RuntimeException("{\"error\":{\"code\":\"rate_limit_exceeded\",\"message\":\"Rate limit exceeded\"}}"));
        assertEquals("rate_limit_exceeded", code);
    }

    // TC-Retry-043: 从类名推断 errorCode
    @Test
    public void testExtractFromClassName_RateLimit() {
        String code = extractor.extract(new RateLimitExceededException());
        assertEquals("429", code);
    }

    // TC-Retry-043: 从类名推断 errorCode - Timeout
    @Test
    public void testExtractFromClassName_Timeout() {
        String code = extractor.extract(new TimeoutException());
        assertEquals("timeout", code);
    }

    // TC-Retry-043: 从类名推断 errorCode - 500
    @Test
    public void testExtractFromClassName_InternalServerError() {
        String code = extractor.extract(new InternalServerErrorException());
        assertEquals("500", code);
    }

    // TC-Retry-043: 从类名推断 errorCode - 503
    @Test
    public void testExtractFromClassName_ServiceUnavailable() {
        String code = extractor.extract(new ServiceUnavailableException());
        assertEquals("503", code);
    }

    // TC-Retry-044: 从 HTTP 状态码提取
    @Test
    public void testExtractHttpStatusCode() {
        String code = extractor.extract(
                new RuntimeException("Received HTTP 503 Service Unavailable response"));
        assertEquals("503", code);
    }

    // TC-Retry-045: errorCode 统一小写化
    @Test
    public void testExtractErrorCode_Lowercase() {
        String code = extractor.extract(
                new RuntimeException("{\"error\":{\"code\":\"RATE_LIMIT_EXCEEDED\",\"message\":\"...\"}}"));
        assertEquals("rate_limit_exceeded", code);
    }

    // TC-Retry-046: 兜底返回原始消息（小写化）
    @Test
    public void testExtractUnknownFallback() {
        String code = extractor.extract(new RuntimeException("some random error with no code"));
        assertEquals("some random error with no code", code);
    }

    // TC-Retry-047: 异常消息为 null 返回 unknown
    @Test
    public void testExtractNullMessage() {
        String code = extractor.extract(new RuntimeException((String) null));
        assertEquals("unknown", code);
    }

    // TC-Retry-047: 异常消息为空字符串返回 unknown
    @Test
    public void testExtractEmptyMessage() {
        String code = extractor.extract(new RuntimeException(""));
        assertEquals("unknown", code);
    }

    // 额外：带冒号的消息截取冒号后内容
    @Test
    public void testExtractMessageAfterColon() {
        String code = extractor.extract(new RuntimeException("Error: something went wrong"));
        assertEquals("something went wrong", code);
    }

    // 额外：消息超长截取前64字符
    @Test
    public void testExtractLongMessage_truncated() {
        String longMsg = "a".repeat(100);
        String code = extractor.extract(new RuntimeException(longMsg));
        assertEquals(64, code.length());
    }

    // ========== isRetryable 辅助异常类 ==========

    private static class RateLimitExceededException extends RuntimeException {
        public RateLimitExceededException() {
            super("Rate limit exceeded");
        }
    }

    private static class TimeoutException extends RuntimeException {
        public TimeoutException() {
            super("Read timed out");
        }
    }

    private static class InternalServerErrorException extends RuntimeException {
        public InternalServerErrorException() {
            super("Internal error");
        }
    }

    private static class ServiceUnavailableException extends RuntimeException {
        public ServiceUnavailableException() {
            super("Service unavailable");
        }
    }
}
