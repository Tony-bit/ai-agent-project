package denny.ai.agent.infrastructure.rag;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.reader.tika.TikaDocumentReader;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * RAG 文档解析服务：支持多种文件格式（PDF、Word、TXT、Markdown 等）。
 * 使用 Spring AI Tika 进行文件解析。
 *
 * @author denny
 */
@Slf4j
@Service
public class RagDocumentParser {

    /**
     * 解析文件，提取文本内容。
     *
     * @param file     上传的文件
     * @param fileName 文件名（用于标识）
     * @return 解析后的 Document 列表（每个 Document 代表一个文本块）
     */
    public List<Document> parse(MultipartFile file, String fileName) {
        try {
            List<Document> documents = new ArrayList<>();
            String finalFileName = fileName != null ? fileName : file.getOriginalFilename();
            String fileType = getFileType(file.getOriginalFilename());
            
            // 优先尝试 Tika 解析（支持 PDF、Word 等）
            // 注意：如果 TikaDocumentReader 构造函数不支持 InputStream，可以改用 Resource
            try {
                // 使用 Spring Resource 包装 MultipartFile
                org.springframework.core.io.Resource resource = 
                    new org.springframework.core.io.InputStreamResource(file.getInputStream());
                TikaDocumentReader reader = new TikaDocumentReader(resource);
                documents = reader.get();
            } catch (Exception e) {
                // 如果 Tika 解析失败，尝试简单文本读取（适用于 TXT、MD 文件）
                if ("text".equals(fileType) || "markdown".equals(fileType)) {
                    String content = new String(file.getBytes(), "UTF-8");
                    Document doc = new Document(content);
                    doc.getMetadata().put("fileName", finalFileName);
                    doc.getMetadata().put("fileType", fileType);
                    documents = List.of(doc);
                    log.info("使用文本方式解析文件，fileName={}", finalFileName);
                } else {
                    log.warn("Tika 解析失败且不支持文本方式，fileName={}, error={}", finalFileName, e.getMessage());
                    throw new RuntimeException("不支持的文件格式或解析失败: " + e.getMessage(), e);
                }
            }
            
            // 为每个文档添加元数据
            for (Document doc : documents) {
                doc.getMetadata().put("fileName", finalFileName);
                doc.getMetadata().put("fileType", fileType);
            }
            
            log.info("文件解析成功，fileName={}, 提取文档数={}", finalFileName, documents.size());
            return documents;
        } catch (IOException e) {
            log.error("文件解析失败，fileName={}", fileName, e);
            throw new RuntimeException("文件解析失败: " + e.getMessage(), e);
        } catch (Exception e) {
            log.error("文件解析异常，fileName={}", fileName, e);
            throw new RuntimeException("文件解析异常: " + e.getMessage(), e);
        }
    }

    /**
     * 根据文件名获取文件类型。
     */
    private String getFileType(String fileName) {
        if (fileName == null) {
            return "unknown";
        }
        String lower = fileName.toLowerCase();
        if (lower.endsWith(".pdf")) return "pdf";
        if (lower.endsWith(".doc") || lower.endsWith(".docx")) return "word";
        if (lower.endsWith(".txt")) return "text";
        if (lower.endsWith(".md") || lower.endsWith(".markdown")) return "markdown";
        if (lower.endsWith(".xls") || lower.endsWith(".xlsx")) return "excel";
        return "unknown";
    }
}
