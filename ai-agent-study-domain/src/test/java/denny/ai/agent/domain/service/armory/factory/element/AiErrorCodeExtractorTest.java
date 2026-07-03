package denny.ai.agent.domain.service.armory.factory.element;

import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.*;

/**
 * AiErrorCodeExtractor 错误码提取服务单元测试
 * <p>
 * 测试覆盖：
 * - TC-EX-01: Zhipu 格式解析
 * - TC-EX-02: OpenAI 格式解析
 * - TC-EX-03: 异常类名推断
 * - TC-EX-04: HTTP 状态码提取
 * - TC-EX-05: Fallback 消息截取
 * - TC-EX-06: 敏感信息脱敏
 * - TC-EX-07: 优先级验证
 * - TC-EX-08: 空值与边界条件
 * </p>
 */
public class AiErrorCodeExtractorTest {

    private AiErrorCodeExtractor extractor;

    @Before
    public void setUp() {
        extractor = new AiErrorCodeExtractor();
    }

    // ========== TC-EX-01: Zhipu 格式解析 ==========

    @Test
    public void testExtractZhipuStandard() {
        Exception e = new Exception("{\"error\":{\"code\":\"1002\",\"message\":\"invalid request\"}}");
        assertEquals("1002", extractor.extract(e));
    }

    @Test
    public void testExtractZhipuNumericCode() {
        Exception e = new Exception("{\"error\":{\"code\":\"500\",\"message\":\"server error\"}}");
        assertEquals("500", extractor.extract(e));
    }

    @Test
    public void testExtractZhipuWithSpaces() {
        Exception e = new Exception("{\"error\": { \"code\" : \"1302\" }}");
        assertEquals("1302", extractor.extract(e));
    }

    @Test
    public void testExtractZhipuNestedJson() {
        Exception e = new Exception("{\"error\":{\"code\":\"1211\",\"inner\":{\"msg\":\"test\"}}}");
        assertEquals("1211", extractor.extract(e));
    }

    @Test
    public void testExtractZhipuUpperCaseKeys() {
        Exception e = new Exception("{\"ERROR\":{\"CODE\":\"1261\"}}");
        assertEquals("1261", extractor.extract(e));
    }

    @Test
    public void testExtractZhipuUnderscoreFormat() {
        Exception e = new Exception("{\"error_code\":\"1301\"}");
        assertNotEquals("1301", extractor.extract(e));
    }

    // ========== TC-EX-02: OpenAI 格式解析 ==========

    @Test
    public void testExtractOpenAiRateLimit() {
        Exception e = new Exception("{\"error\":{\"code\":\"rate_limit_exceeded\",\"message\":\"rate limit\"}}");
        assertEquals("rate_limit_exceeded", extractor.extract(e));
    }

    @Test
    public void testExtractOpenAiModelNotFound() {
        Exception e = new Exception("{\"error\":{\"code\":\"model_not_found\"}}");
        assertEquals("model_not_found", extractor.extract(e));
    }

    @Test
    public void testExtractOpenAiSpecialChars() {
        Exception e = new Exception("{\"error\":{\"code\":\"invalid_request-error\"}}");
        assertEquals("invalid_request-error", extractor.extract(e));
    }

    // ========== TC-EX-03: 异常类名推断 ==========

    @Test
    public void testExtractFromClassNameRateLimit() {
        RateLimitException e = new RateLimitException("rate limit");
        assertEquals("429", extractor.extract(e));
    }

    @Test
    public void testExtractFromClassNameSocketTimeout() {
        SocketTimeoutException e = new SocketTimeoutException("connect timed out");
        assertEquals("timeout", extractor.extract(e));
    }

    @Test
    public void testExtractFromClassNameReadTimeout() {
        ReadTimeoutException e = new ReadTimeoutException("Read timed out");
        assertEquals("timeout", extractor.extract(e));
    }

    @Test
    public void testExtractFromClassNameAuth() {
        AuthException e = new AuthException("auth failed");
        assertEquals("401", extractor.extract(e));
    }

    @Test
    public void testExtractFromClassNameAccessDenied() {
        AccessDeniedException e = new AccessDeniedException("access denied");
        assertEquals("403", extractor.extract(e));
    }

    @Test
    public void testExtractFromClassNameInternalServerError() {
        InternalServerErrorException e = new InternalServerErrorException("internal error");
        assertEquals("500", extractor.extract(e));
    }

    @Test
    public void testExtractFromClassNameBadGateway() {
        BadGatewayException e = new BadGatewayException("bad gateway");
        assertEquals("502", extractor.extract(e));
    }

    @Test
    public void testExtractFromClassNameServiceUnavailable() {
        ServiceUnavailableException e = new ServiceUnavailableException("service unavailable");
        assertEquals("503", extractor.extract(e));
    }

    @Test
    public void testExtractFromClassNameGatewayTimeout() {
        GatewayTimeoutException e = new GatewayTimeoutException("gateway timeout");
        assertEquals("timeout", extractor.extract(e));
    }

    @Test
    public void testExtractFromClassNameServiceOverloaded() {
        ServiceOverloadedException e = new ServiceOverloadedException("service overloaded");
        assertEquals("529", extractor.extract(e));
    }

    // ========== TC-EX-04: HTTP 状态码提取 ==========

    @Test
    public void testExtractHttpCode() {
        Exception e = new Exception("HTTP 500 Internal Server Error");
        assertEquals("500", extractor.extract(e));
    }

    @Test
    public void testExtractHttpCodeMultiple() {
        Exception e = new Exception("Got 429 then 500");
        assertEquals("429", extractor.extract(e));
    }

    @Test
    public void testExtractHttpCodeNoPortMatch() {
        Exception e = new Exception("Connecting to 192.168.1.1:429");
        assertEquals("429", extractor.extract(e));
    }

    @Test
    public void testExtractHttpCodeInUrl() {
        Exception e = new Exception("https://api.example.com/error/500");
        assertEquals("500", extractor.extract(e));
    }

    @Test
    public void testExtractHttpCodeWithSpaces() {
        Exception e = new Exception("HTTP  429 Too Many Requests");
        assertEquals("429", extractor.extract(e));
    }

    // ========== TC-EX-05: Fallback 消息截取 ==========

    @Test
    public void testExtractFallbackWithColon() {
        Exception e = new Exception("java.lang.Error: Connection refused");
        assertEquals("connection refused", extractor.extract(e));
    }

    @Test
    public void testExtractFallbackNoColon() {
        Exception e = new Exception("simple error message");
        assertEquals("simple error message", extractor.extract(e));
    }

    @Test
    public void testExtractFallbackTruncate() {
        String longMessage = "a".repeat(100);
        Exception e = new Exception(longMessage);
        String result = extractor.extract(e);
        assertEquals(64, result.length());
    }

    @Test
    public void testExtractFallbackWhitespaceOnly() {
        Exception e = new Exception("   ");
        assertEquals("unknown", extractor.extract(e));
    }

    @Test
    public void testExtractNullMessage() {
        Exception e = new Exception((String) null);
        assertEquals("unknown", extractor.extract(e));
    }

    // ========== TC-EX-06: 敏感信息脱敏 ==========

    @Test
    public void testMaskJwtToken() {
        Exception e = new Exception("Bearer sk-test123");
        String result = extractor.extract(e);
        assertTrue("Result should mask Bearer token: " + result, result.contains("sk-***"));
    }

    @Test
    public void testMaskBearerToken() {
        Exception e = new Exception("Authorization: Bearer sk-xxx");
        String result = extractor.extract(e);
        assertTrue(result.contains("sk-***"));
    }

    @Test
    public void testMaskApiKey() {
        Exception e = new Exception("api_key=ak-xxxxx");
        String result = extractor.extract(e);
        assertTrue(result.contains("ak-***"));
    }

    @Test
    public void testMaskPassword() {
        Exception e = new Exception("password=secret123");
        String result = extractor.extract(e);
        assertTrue(result.contains("***"));
        assertFalse(result.contains("secret123"));
    }

    @Test
    public void testMaskSecret() {
        Exception e = new Exception("client_secret=xxx");
        String result = extractor.extract(e);
        assertTrue(result.contains("***"));
        assertFalse(result.contains("xxx"));
    }

    @Test
    public void testMaskUrlParams() {
        Exception e = new Exception("https://api?key=xxx&secret=y");
        String result = extractor.extract(e);
        assertTrue(result.contains("key=***"));
        assertTrue(result.contains("secret=***"));
    }

    // ========== TC-EX-07: 优先级验证 ==========

    @Test
    public void testPriorityZhipuOverOpenAi() {
        Exception e = new Exception("{\"error\":{\"code\":\"1002\"}}");
        assertEquals("1002", extractor.extract(e));
    }

    @Test
    public void testPriorityClassNameOverHttpCode() {
        SocketTimeoutException e = new SocketTimeoutException("HTTP 500 error");
        assertEquals("timeout", extractor.extract(e));
    }

    @Test
    public void testPriorityFallbackWhenNoMatch() {
        Exception e = new Exception("some random error without pattern");
        assertEquals("some random error without pattern", extractor.extract(e));
    }

    // ========== TC-EX-08: 空值与边界条件 ==========

    @Test
    public void testExtractEmptyString() {
        Exception e = new Exception("");
        assertEquals("unknown", extractor.extract(e));
    }

    @Test
    public void testExtractWhitespaceOnly() {
        Exception e = new Exception("   \n\t  ");
        assertEquals("unknown", extractor.extract(e));
    }

    @Test
    public void testExtractOnlyColon() {
        Exception e = new Exception(":");
        assertEquals(":", extractor.extract(e));
    }

    @Test
    public void testExtractColonAtStart() {
        Exception e = new Exception(":error message");
        String result = extractor.extract(e);
        assertTrue(result.startsWith("error") || result.equals(":error message"));
    }

    @Test
    public void testExtractNullException() {
        assertEquals("unknown", extractor.extract(null));
    }

    @Test
    public void testExtractNestedSocketTimeoutCause() {
        Exception e = new Exception(
                "Error while extracting response for type [org.springframework.ai.openai.api.OpenAiApi$ChatCompletion]",
                new java.net.SocketTimeoutException("Read timed out")
        );

        assertEquals("timeout", extractor.extract(e));
    }

    // ========== 测试用异常类 ==========

    private static class RateLimitException extends Exception {
        public RateLimitException(String message) { super(message); }
    }

    private static class SocketTimeoutException extends Exception {
        public SocketTimeoutException(String message) { super(message); }
    }

    private static class ReadTimeoutException extends Exception {
        public ReadTimeoutException(String message) { super(message); }
    }

    private static class AuthException extends Exception {
        public AuthException(String message) { super(message); }
    }

    private static class AccessDeniedException extends Exception {
        public AccessDeniedException(String message) { super(message); }
    }

    private static class InternalServerErrorException extends Exception {
        public InternalServerErrorException(String message) { super(message); }
    }

    private static class BadGatewayException extends Exception {
        public BadGatewayException(String message) { super(message); }
    }

    private static class ServiceUnavailableException extends Exception {
        public ServiceUnavailableException(String message) { super(message); }
    }

    private static class GatewayTimeoutException extends Exception {
        public GatewayTimeoutException(String message) { super(message); }
    }

    private static class ServiceOverloadedException extends Exception {
        public ServiceOverloadedException(String message) { super(message); }
    }
}
