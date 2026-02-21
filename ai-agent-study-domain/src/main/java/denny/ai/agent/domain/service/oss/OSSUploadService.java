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
import org.springframework.web.multipart.MultipartFile;

import javax.annotation.Resource;
import java.io.InputStream;

/**
 * oss上传实现类
 * @author Denny
 */
@Slf4j
public class OSSUploadService {
    @Resource
    private OSSUploadConfig ossUploadConfig;

    public String upload(MultipartFile file) {
        if (file.isEmpty()) {
            throw new IllegalArgumentException("上传文件为空");
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

            // 使用原始文件名作为 key（也可以自己拼接路径/UUID）
            String key = file.getOriginalFilename();

            ObjectMetadata objectMetadata = new ObjectMetadata();
            objectMetadata.setContentType(file.getContentType());
            objectMetadata.setContentLength(file.getSize());

            try (InputStream inputStream = file.getInputStream()) {
                s3.putObject(ossUploadConfig.getBucketName(), key, inputStream, objectMetadata);
            }

            String url = s3.getUrl(ossUploadConfig.getBucketName(), key).toString();
            log.info("图片上传成功，URL: {}", url);

            // 这里简单返回一个 JSON 字符串，前端方便解析
            return url;
        } catch (Exception e) {
            log.error("图片上传失败", e);
            return null;
        }
    }
}
