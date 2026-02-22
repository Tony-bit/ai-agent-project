package denny.ai.agent.domain.adapter.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * OSS对象存储桶 自动装配配置属性
 *
 * @author denny
 * 2026/2/21 9:11
 */
@Data
@Configuration
@ConfigurationProperties(prefix = "spring.datasource.oss")
public class OSSUploadConfig {
    /**
     * oss存储的鉴权账号
     */
    private String accessKey;

    /**
     * oss存储的秘钥
     */
    private String secretKey;

    /**
     * oss存储的url
     */
    private String endpoint;

    /**
     * oss存储的地区
     */
    private String region;

    /**
     * oss存储的名
     */
    private String bucketName;
}
