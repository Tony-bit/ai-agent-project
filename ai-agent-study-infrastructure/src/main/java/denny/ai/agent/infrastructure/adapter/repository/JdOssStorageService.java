package denny.ai.agent.infrastructure.adapter.repository;

import com.amazonaws.ClientConfiguration;
import com.amazonaws.auth.AWSCredentials;
import com.amazonaws.auth.AWSStaticCredentialsProvider;
import com.amazonaws.auth.BasicAWSCredentials;
import com.amazonaws.client.builder.AwsClientBuilder;
import com.amazonaws.services.s3.AmazonS3;
import com.amazonaws.services.s3.AmazonS3ClientBuilder;
import com.amazonaws.services.s3.model.ObjectMetadata;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import javax.annotation.PostConstruct;
import java.io.InputStream;
import java.util.UUID;

@Slf4j
@Service
public class JdOssStorageService {

    private AmazonS3 s3;

    // TODO: 替换为实际的配置信息
    private final String accessKey = "YOUR_ACCESS_KEY";
    private final String secretKey = "YOUR_SECRET_KEY";
    private final String endpoint = "https://s3.cn-north-1.jdcloud-oss.com"; // 示例地域
    private final String region = "cn-north-1";
    private final String bucketName = "YOUR_BUCKET_NAME";

    @PostConstruct
    public void init() {
        ClientConfiguration config = new ClientConfiguration();
        AwsClientBuilder.EndpointConfiguration endpointConfig =
                new AwsClientBuilder.EndpointConfiguration(endpoint, region);

        AWSCredentials awsCredentials = new BasicAWSCredentials(accessKey, secretKey);

        this.s3 = AmazonS3ClientBuilder.standard()
                .withEndpointConfiguration(endpointConfig)
                .withClientConfiguration(config)
                .withCredentials(new AWSStaticCredentialsProvider(awsCredentials))
                .disableChunkedEncoding()
                .build();
        log.info("京东云 OSS 客户端初始化成功");
    }

    /**
     * 上传文件并返回公网可访问的 URL
     */
    public String uploadPublic(MultipartFile file) {
        String originalFilename = file.getOriginalFilename();
        String extension = "";
        if (originalFilename != null && originalFilename.contains(".")) {
            extension = originalFilename.substring(originalFilename.lastIndexOf("."));
        }

        String key = "ai-agent/" + UUID.randomUUID() + extension;

        try {
            ObjectMetadata metadata = new ObjectMetadata();
            metadata.setContentType(file.getContentType() != null ? file.getContentType() : "application/octet-stream");
            metadata.setContentLength(file.getSize());

            try (InputStream inputStream = file.getInputStream()) {
                s3.putObject(bucketName, key, inputStream, metadata);
            }

            // 获取公网 URL (假设 bucket 已配置为公网读)
            String url = s3.getUrl(bucketName, key).toString();
            log.info("文件上传成功: {}", url);
            return url;
        } catch (Exception e) {
            log.error("上传文件到京东云 OSS 失败", e);
            throw new RuntimeException("上传文件失败", e);
        }
    }
}
