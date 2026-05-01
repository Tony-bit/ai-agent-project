package denny.ai.agent.domain.service.armory;

import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/**
 * AI Client HTTP 超时配置。
 * <p>
 * 仅为 RestClient（同步请求）配置超时。
 * WebClient 使用 Spring 默认配置，流式场景超时由 OpenAI API 侧或底层连接控制。
 * <p>
 * 说明：当前 classpath 中 Netty 5 支持已被 Spring Framework 6.2.x 移除，
 * Reactor Netty 4.x 的 io.netty 包不在 domain 模块的直接依赖中，
 * 故采用保守方案，仅覆盖同步请求超时。
 *
 * status: pending
 */
@Slf4j
@Configuration
public class AiClientHttpTimeoutConfig {

    private static final int READ_TIMEOUT_MS = 80000;   // 120 秒
    private static final int CONNECT_TIMEOUT_MS = 90000; // 60 秒

    @Bean
    public RestClient.Builder aiClientRestClientBuilder() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(CONNECT_TIMEOUT_MS);
        factory.setReadTimeout(READ_TIMEOUT_MS);

        RestClient restClient = RestClient.builder()
                .requestFactory(factory)
                .build();

        log.info("[AiClientHttpTimeoutConfig] RestClient configured, connectTimeout={}ms, readTimeout={}ms",
                CONNECT_TIMEOUT_MS, READ_TIMEOUT_MS);
        return restClient.mutate();
    }
}
