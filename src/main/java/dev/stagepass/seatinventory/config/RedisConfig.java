package dev.stagepass.seatinventory.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * Redis configuration. Uses Lettuce connection factory (Spring Boot auto-configured).
 * StringRedisTemplate is used throughout — seat hold values are UUID strings.
 */
@Configuration
public class RedisConfig {

    /**
     * StringRedisTemplate: key and value serialised as plain UTF-8 strings.
     * Correct for the seat hold key schema (keys and values are UUID strings or
     * the "BOOKED" sentinel — never binary-serialised Java objects).
     */
    @Bean
    public StringRedisTemplate stringRedisTemplate(
            final RedisConnectionFactory connectionFactory) {
        return new StringRedisTemplate(connectionFactory);
    }
}
