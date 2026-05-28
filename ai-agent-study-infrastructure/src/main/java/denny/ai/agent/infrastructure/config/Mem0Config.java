package denny.ai.agent.infrastructure.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import denny.ai.agent.infrastructure.mem0.Mem0RestClient;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestTemplate;

import java.time.Duration;

/**
 * Mem0 客户端配置
 * <p>
 * 独立配置，避免与 AiAgentConfig 的循环依赖。
 * </p>
 *
 * @author denny
 */
@Slf4j
@Configuration
public class Mem0Config {

    @Bean
    public RestTemplate mem0RestTemplate(
            @Value("${spring.ai.alibaba.mem0.client.timeout-seconds:120}") int timeoutSeconds) {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        Duration timeout = Duration.ofSeconds(timeoutSeconds);
        requestFactory.setConnectTimeout(timeout);
        requestFactory.setReadTimeout(timeout);
        return new RestTemplate(requestFactory);
    }

    @Bean
    public Mem0RestClient mem0RestClient(
            @Value("${spring.ai.alibaba.mem0.client.base-url}") String baseUrl,
            RestTemplate mem0RestTemplate,
            ObjectMapper objectMapper) {
        return new Mem0RestClient(mem0RestTemplate, baseUrl, objectMapper);
    }
}
