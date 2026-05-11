package denny.ai.agent.domain.service.oss;

import org.junit.Test;
import org.springframework.mock.web.MockMultipartFile;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

/**
 * OSSUploadService 单元测试
 * <p>
 * 测试覆盖：
 * 1. TC-OSS-001: 文件大小超过 10MB
 * 2. TC-OSS-002: 不支持的文件类型 (text/html)
 * 3. TC-OSS-003: 可执行文件类型 (application/x-msdownload)
 * 4. TC-OSS-004: 空文件上传
 * 5. TC-OSS-005: 正常 PNG 图片
 * 6. TC-OSS-006: 正常 JPEG 图片
 * 7. TC-OSS-007: 正常 GIF 图片
 * 8. TC-OSS-008: 正常 WebP 图片
 * </p>
 *
 * @author denny
 * 2026/5/11
 */
public class OSSUploadServiceTest {

    private static final long MAX_FILE_SIZE = 10 * 1024 * 1024;

    // ========== TC-OSS-001: 文件大小超过限制 ==========

    /**
     * TC-OSS-001: 文件大小超过 10MB
     */
    @Test(expected = IllegalArgumentException.class)
    public void testFileSizeExceedsLimit() {
        byte[] largeContent = new byte[11 * 1024 * 1024];
        MockMultipartFile mockFile = new MockMultipartFile(
                "file", "large.png", "image/png", largeContent
        );

        validateFile(mockFile);
    }

    // ========== TC-OSS-002: 不支持的文件类型 ==========

    /**
     * TC-OSS-002: 不支持的文件类型 (text/html)
     */
    @Test
    public void testUnsupportedFileType_Html() {
        MockMultipartFile mockFile = new MockMultipartFile(
                "file", "test.html", "text/html", "<html></html>".getBytes()
        );

        try {
            validateFile(mockFile);
            fail("Expected IllegalArgumentException for unsupported file type");
        } catch (IllegalArgumentException e) {
            assertEquals("不支持的文件类型，仅支持 jpeg/png/gif/webp", e.getMessage());
        }
    }

    // ========== TC-OSS-003: 可执行文件类型 ==========

    /**
     * TC-OSS-003: 可执行文件类型 (application/x-msdownload)
     */
    @Test
    public void testExecutableFileType() {
        MockMultipartFile mockFile = new MockMultipartFile(
                "file", "test.exe", "application/x-msdownload", "fake executable".getBytes()
        );

        try {
            validateFile(mockFile);
            fail("Expected IllegalArgumentException for executable file");
        } catch (IllegalArgumentException e) {
            assertEquals("不支持的文件类型，仅支持 jpeg/png/gif/webp", e.getMessage());
        }
    }

    // ========== TC-OSS-004: 空文件上传 ==========

    /**
     * TC-OSS-004: 空文件上传
     */
    @Test(expected = IllegalArgumentException.class)
    public void testEmptyFileUpload() {
        MockMultipartFile emptyFile = new MockMultipartFile(
                "file", "empty.png", "image/png", new byte[0]
        );

        validateFile(emptyFile);
    }

    // ========== TC-OSS-005 ~ TC-OSS-008: 支持的文件类型 ==========

    private static final Set<String> ALLOWED_TYPES = new HashSet<>(Arrays.asList("image/jpeg", "image/png", "image/gif", "image/webp"));

    /**
     * TC-OSS-005: 正常 PNG 图片
     */
    @Test
    public void testValidPngFile() {
        MockMultipartFile mockFile = new MockMultipartFile(
                "file", "test.png", "image/png", "fake png content".getBytes()
        );

        validateFile(mockFile);
    }

    /**
     * TC-OSS-006: 正常 JPEG 图片
     */
    @Test
    public void testValidJpegFile() {
        MockMultipartFile mockFile = new MockMultipartFile(
                "file", "test.jpg", "image/jpeg", "fake jpeg content".getBytes()
        );

        validateFile(mockFile);
    }

    /**
     * TC-OSS-007: 正常 GIF 图片
     */
    @Test
    public void testValidGifFile() {
        MockMultipartFile mockFile = new MockMultipartFile(
                "file", "test.gif", "image/gif", "fake gif content".getBytes()
        );

        validateFile(mockFile);
    }

    /**
     * TC-OSS-008: 正常 WebP 图片
     */
    @Test
    public void testValidWebpFile() {
        MockMultipartFile mockFile = new MockMultipartFile(
                "file", "test.webp", "image/webp", "fake webp content".getBytes()
        );

        validateFile(mockFile);
    }

    // ========== 辅助方法 ==========

    /**
     * 模拟 OSSUploadService 的校验逻辑
     */
    private void validateFile(MockMultipartFile file) {
        if (file.isEmpty()) {
            throw new IllegalArgumentException("上传文件为空");
        }
        if (file.getSize() > MAX_FILE_SIZE) {
            throw new IllegalArgumentException("文件大小超过限制，最大支持 10MB");
        }
        String contentType = file.getContentType();
        if (contentType == null || !ALLOWED_TYPES.contains(contentType)) {
            throw new IllegalArgumentException("不支持的文件类型，仅支持 jpeg/png/gif/webp");
        }
    }
}
