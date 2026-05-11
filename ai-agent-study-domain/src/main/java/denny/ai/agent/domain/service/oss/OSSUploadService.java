package denny.ai.agent.domain.service.oss;

import com.amazonaws.ClientConfiguration;
import com.amazonaws.auth.AWSCredentials;
import com.amazonaws.auth.AWSStaticCredentialsProvider;
import com.amazonaws.auth.BasicAWSCredentials;
import com.amazonaws.client.builder.AwsClientBuilder;
import com.amazonaws.services.s3.AmazonS3;
import com.amazonaws.services.s3.AmazonS3ClientBuilder;
import com.amazonaws.services.s3.model.ObjectMetadata;
import denny.ai.agent.domain.adapter.config.OSSUploadConfig;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import javax.annotation.Resource;
import java.io.InputStream;
import java.util.Set;
import java.util.UUID;

/**
 * oss上传实现类
 *
 * @author Denny
 */
@Slf4j
@Service
public class OSSUploadService {

    private static final long MAX_FILE_SIZE = 10 * 1024 * 1024;
    private static final Set<String> ALLOWED_TYPES = Set.of("image/jpeg", "image/png", "image/gif", "image/webp");

    @Resource
    private OSSUploadConfig ossUploadConfig;

    public String upload(MultipartFile file) {
        if (file.isEmpty()) {
            throw new IllegalArgumentException("上传文件为空");
        }
        if (file.getSize() > MAX_FILE_SIZE) {
            throw new IllegalArgumentException("文件大小超过限制，最大支持 10MB");
        }
        String contentType = file.getContentType();
        if (!ALLOWED_TYPES.contains(contentType)) {
            throw new IllegalArgumentException("不支持的文件类型，仅支持 jpeg/png/gif/webp");
        }
        try {
            // 构建 S3 客户端（与 OSSTest 中相同方式）
            ClientConfiguration config = new ClientConfiguration();
            AwsClientBuilder.EndpointConfiguration endpointConfig =
                    new AwsClientBuilder.EndpointConfiguration(
                            ossUploadConfig.getEndpoint(),
                            ossUploadConfig.getRegion()
                    );

            AWSCredentials awsCredentials = new BasicAWSCredentials(
                    ossUploadConfig.getAccessKey(),
                    ossUploadConfig.getSecretKey()
            );

            AmazonS3 s3 = AmazonS3ClientBuilder.standard()
                    .withEndpointConfiguration(endpointConfig)
                    .withClientConfiguration(config)
                    .withCredentials(new AWSStaticCredentialsProvider(awsCredentials))
                    .disableChunkedEncoding()
                    .build();

            // 使用 UUID 生成文件名，避免路径穿越风险
            String originalFilename = file.getOriginalFilename();
            String extension = originalFilename != null && originalFilename.contains(".")
                    ? originalFilename.substring(originalFilename.lastIndexOf("."))
                    : "";
            String key = UUID.randomUUID().toString().replace("-", "") + extension;

            ObjectMetadata objectMetadata = new ObjectMetadata();
            objectMetadata.setContentType(contentType);
            objectMetadata.setContentLength(file.getSize());

            try (InputStream inputStream = file.getInputStream()) {
                s3.putObject(ossUploadConfig.getBucketName(), key, inputStream, objectMetadata);
            }

            String url = s3.getUrl(ossUploadConfig.getBucketName(), key).toString();
            log.info("图片上传成功，URL: {}", url);

            return url;
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            log.error("图片上传失败", e);
            return null;
        }
    }
}
