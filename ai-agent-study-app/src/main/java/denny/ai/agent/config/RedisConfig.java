package denny.ai.agent.config;

import org.apache.commons.pool2.impl.GenericObjectPoolConfig;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.connection.lettuce.LettucePoolingClientConfiguration;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;

/**
 * Redis 配置类
 * <p>
 * 为 ChatMemoryRepository 提供 StringRedisTemplate Bean。
 * 配置读取 application.yml 中的 redis.sdk.config 配置项。
 * </p>
 *
 * @author denny
 */
@Configuration
public class RedisConfig {

    @Value("${redis.sdk.config.host:localhost}")
    private String host;

    @Value("${redis.sdk.config.port:16379}")
    private int port;

    @Value("${redis.sdk.config.password:}")
    private String password;

    @Value("${redis.sdk.config.pool-size:10}")
    private int poolSize;

    @Value("${redis.sdk.config.min-idle-size:5}")
    private int minIdleSize;

    @Value("${redis.sdk.config.idle-timeout:30000}")
    private long idleTimeout;

    @Value("${redis.sdk.config.connect-timeout:5000}")
    private long connectTimeout;

    @Value("${redis.sdk.config.ping-interval:60000}")
    private long pingInterval;

    @SuppressWarnings("unchecked")
    @Bean
    public RedisConnectionFactory redisConnectionFactory() {
        RedisStandaloneConfiguration config = new RedisStandaloneConfiguration();
        config.setHostName(host);
        config.setPort(port);
        if (password != null && !password.isBlank()) {
            config.setPassword(password);
        }

        GenericObjectPoolConfig<?> pool = new GenericObjectPoolConfig<>();
        pool.setMaxTotal(poolSize);
        pool.setMinIdle(minIdleSize);
        pool.setMaxIdle(poolSize);
        pool.setMaxWait(Duration.ofMillis(idleTimeout));
        pool.setTestOnBorrow(true);
        pool.setTestWhileIdle(true);

        LettucePoolingClientConfiguration clientConfig = LettucePoolingClientConfiguration.builder()
                .commandTimeout(Duration.ofMillis(connectTimeout))
                .poolConfig((GenericObjectPoolConfig) pool)
                .build();

        return new LettuceConnectionFactory(config, clientConfig);
    }

    @Bean
    public StringRedisTemplate stringRedisTemplate(RedisConnectionFactory redisConnectionFactory) {
        return new StringRedisTemplate(redisConnectionFactory);
    }
}
