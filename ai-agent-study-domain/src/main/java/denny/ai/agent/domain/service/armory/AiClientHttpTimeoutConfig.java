package denny.ai.agent.domain.service.armory;

import denny.ai.agent.domain.service.armory.stream.SseChunkTimeoutFilter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.http.client.reactive.JdkClientHttpConnector;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;

import java.net.http.HttpClient;

/**
 * AI Client HTTP 超时配置。
 * RestClient 保留同步调用兼容超时，WebClient 使用 JDK connector 保护连接建立。
 *
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

    @Bean
    public WebClient.Builder aiClientWebClientBuilder(
            AiStreamingProperties properties,
            @Qualifier("aiStreamTimeoutScheduler") Scheduler scheduler) {
        properties.validate();
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(properties.getConnectTimeout())
                .build();
        log.info("[AiClientHttpTimeoutConfig] WebClient configured, connectTimeout={}ms",
                properties.getConnectTimeout().toMillis());
        WebClient.Builder builder = WebClient.builder()
                .clientConnector(new JdkClientHttpConnector(httpClient))
                .defaultHeader(HttpHeaders.ACCEPT, MediaType.TEXT_EVENT_STREAM_VALUE);
        if (properties.resolve(null).timeoutMode() == AiStreamingProperties.TimeoutMode.LAYERED) {
            builder.filter(new SseChunkTimeoutFilter(scheduler));
        }
        return builder;
    }

    @Bean(name = "aiStreamTimeoutScheduler", destroyMethod = "")
    public Scheduler aiStreamTimeoutScheduler() {
        return Schedulers.parallel();
    }
}
