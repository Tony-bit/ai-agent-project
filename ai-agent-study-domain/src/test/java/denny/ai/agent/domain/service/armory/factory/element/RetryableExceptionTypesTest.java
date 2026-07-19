package denny.ai.agent.domain.service.armory.factory.element;

import org.junit.Test;

import static org.junit.Assert.*;

/**
 * RetryableExceptionTypes 可重试异常类型集单元测试
 * <p>
 * 测试覆盖：
 * - TC-RET-01: TransientAiException 识别
 * - TC-RET-02: 超时异常识别
 * - TC-RET-03: 连接异常识别
 * - TC-RET-04: 异常消息关键词识别
 * - TC-RET-05: 非可重试异常
 * - TC-RET-06: 组合场景
 * </p>
 */
public class RetryableExceptionTypesTest {

    // ========== TC-RET-01: TransientAiException 识别 ==========

    @Test
    public void testTransientAiExceptionStandard() {
        TransientAiException e = new TransientAiException("transient error");
        assertTrue(RetryableExceptionTypes.isRetryable(e));
    }

    @Test
    public void testTransientAiExceptionWithPackage() {
        TransientAiException e = new TransientAiException("denny.ai.agent.TransientAiException");
        assertTrue(RetryableExceptionTypes.isRetryable(e));
    }

    @Test
    public void testTransientAiExceptionSubclass() {
        TransientAiExceptionImpl e = new TransientAiExceptionImpl("subclass error");
        assertTrue(RetryableExceptionTypes.isRetryable(e));
    }

    // ========== TC-RET-02: 超时异常识别 ==========

    @Test
    public void testSocketTimeoutException() {
        java.net.SocketTimeoutException e = new java.net.SocketTimeoutException("connect timed out");
        assertTrue(RetryableExceptionTypes.isRetryable(e));
    }

    @Test
    public void testTimeoutException() {
        java.util.concurrent.TimeoutException e = new java.util.concurrent.TimeoutException("operation timed out");
        assertTrue(RetryableExceptionTypes.isRetryable(e));
    }

    @Test
    public void testCustomTimeoutNotRetryable() {
        MyTimeoutException e = new MyTimeoutException("custom timeout");
        assertFalse(RetryableExceptionTypes.isRetryable(e));
    }

    @Test
    public void testReadTimedOutNotRetryable() {
        ReadTimedOutException e = new ReadTimedOutException("read timed out");
        assertFalse(RetryableExceptionTypes.isRetryable(e));
    }

    // ========== TC-RET-03: 连接异常识别 ==========

    @Test
    public void testResourceAccessException() {
        org.springframework.web.client.ResourceAccessException e =
            new org.springframework.web.client.ResourceAccessException("connection failed", null);
        assertTrue(RetryableExceptionTypes.isRetryable(e));
    }

    @Test
    public void testRestClientExceptionNotRetryable() {
        RestClientException e = new RestClientException("rest client error");
        assertFalse(RetryableExceptionTypes.isRetryable(e));
    }

    @Test
    public void testJdkHttpConnectTimeoutException() {
        java.net.http.HttpConnectTimeoutException e =
                new java.net.http.HttpConnectTimeoutException("connect timed out");
        assertTrue(RetryableExceptionTypes.isRetryable(e));
    }

    @Test
    public void testRestClientExceptionWithSocketTimeoutCauseIsRetryable() {
        org.springframework.web.client.RestClientException e =
                new org.springframework.web.client.RestClientException(
                        "Error while extracting response",
                        new java.net.SocketTimeoutException("Read timed out")
                );

        assertTrue(RetryableExceptionTypes.isRetryable(e));
    }

    // ========== TC-RET-04: 异常消息关键词识别 ==========

    @Test
    public void testEconnresetLower() {
        ConnectionResetException e = new ConnectionResetException("Connection reset by peer");
        assertTrue(RetryableExceptionTypes.isRetryable(e));
    }

    @Test
    public void testEconnresetUpper() {
        ConnectionResetException e = new ConnectionResetException("ECONNRESET");
        assertTrue(RetryableExceptionTypes.isRetryable(e));
    }

    @Test
    public void testEpipe() {
        BrokenPipeException e = new BrokenPipeException("epipec: broken pipe");
        assertTrue(RetryableExceptionTypes.isRetryable(e));
    }

    @Test
    public void testConnectionResetLower() {
        ConnectionResetException e = new ConnectionResetException("connection reset");
        assertTrue(RetryableExceptionTypes.isRetryable(e));
    }

    @Test
    public void testConnectionRefused() {
        ConnectionRefusedException e = new ConnectionRefusedException("Connection refused");
        assertTrue(RetryableExceptionTypes.isRetryable(e));
    }

    @Test
    public void testConnectionTimedOut() {
        ConnectionTimedOutException e = new ConnectionTimedOutException("Connection timed out");
        assertTrue(RetryableExceptionTypes.isRetryable(e));
    }

    @Test
    public void testUnrelatedMessage() {
        InvalidParameterException e = new InvalidParameterException("Invalid parameter");
        assertFalse(RetryableExceptionTypes.isRetryable(e));
    }

    @Test
    public void testMixedKeywords() {
        MixedException e = new MixedException("Error: econnreset occurred");
        assertTrue(RetryableExceptionTypes.isRetryable(e));
    }

    @Test
    public void testKeywordInUrl() {
        UrlException e = new UrlException("https://api.com?err=econnreset");
        assertTrue(RetryableExceptionTypes.isRetryable(e));
    }

    @Test
    public void testNullMessage() {
        NullMessageException e = new NullMessageException(null);
        assertFalse(RetryableExceptionTypes.isRetryable(e));
    }

    // ========== TC-RET-05: 非可重试异常 ==========

    @Test
    public void testIllegalArgumentException() {
        IllegalArgumentException e = new IllegalArgumentException("invalid argument");
        assertFalse(RetryableExceptionTypes.isRetryable(e));
    }

    @Test
    public void testNullPointerException() {
        NullPointerException e = new NullPointerException("null pointer");
        assertFalse(RetryableExceptionTypes.isRetryable(e));
    }

    @Test
    public void testBusinessException() {
        BusinessException e = new BusinessException("business error");
        assertFalse(RetryableExceptionTypes.isRetryable(e));
    }

    @Test
    public void shouldTreatResponseValidationExceptionAsRetryable() {
        ResponseValidationException e = new ResponseValidationException(
                ResponseValidationFailureType.SCHEMA_VALIDATION_ERROR, "schema mismatch");

        assertTrue(RetryableExceptionTypes.isRetryable(e));
    }

    // ========== TC-RET-06: 组合场景 ==========

    @Test
    public void testBothClassNameAndMessageMatch() {
        SocketTimeoutWithEconnreset e = new SocketTimeoutWithEconnreset("econnreset occurred");
        assertTrue(RetryableExceptionTypes.isRetryable(e));
    }

    @Test
    public void testNullException() {
        assertFalse(RetryableExceptionTypes.isRetryable(null));
    }

    // ========== 测试用异常类 ==========

    private static class TransientAiException extends Exception {
        public TransientAiException(String message) { super(message); }
    }

    private static class TransientAiExceptionImpl extends TransientAiException {
        public TransientAiExceptionImpl(String message) { super(message); }
    }

    private static class MyTimeoutException extends Exception {
        public MyTimeoutException(String message) { super(message); }
    }

    private static class ReadTimedOutException extends Exception {
        public ReadTimedOutException(String message) { super(message); }
    }

    private static class RestClientException extends Exception {
        public RestClientException(String message) { super(message); }
    }

    private static class ConnectionResetException extends Exception {
        public ConnectionResetException(String message) { super(message); }
    }

    private static class BrokenPipeException extends Exception {
        public BrokenPipeException(String message) { super(message); }
    }

    private static class ConnectionRefusedException extends Exception {
        public ConnectionRefusedException(String message) { super(message); }
    }

    private static class ConnectionTimedOutException extends Exception {
        public ConnectionTimedOutException(String message) { super(message); }
    }

    private static class InvalidParameterException extends Exception {
        public InvalidParameterException(String message) { super(message); }
    }

    private static class MixedException extends Exception {
        public MixedException(String message) { super(message); }
    }

    private static class UrlException extends Exception {
        public UrlException(String message) { super(message); }
    }

    private static class NullMessageException extends Exception {
        public NullMessageException(String message) { super(message); }
    }

    private static class BusinessException extends Exception {
        public BusinessException(String message) { super(message); }
    }

    private static class SocketTimeoutWithEconnreset extends Exception {
        public SocketTimeoutWithEconnreset(String message) { super(message); }
    }
}
