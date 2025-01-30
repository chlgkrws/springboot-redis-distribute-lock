package com.lock.redis.config

import org.redisson.Redisson
import org.redisson.api.RedissonReactiveClient
import org.redisson.config.Config
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration
class RedissonConfig {
    @Value("\${spring.data.redis.host:redis}")
    private lateinit var host: String

    @Value("\${spring.data.redis.port:6379}")
    private lateinit var port: String

    @Bean
    fun redissonClient(): RedissonReactiveClient {
        val config = Config()
        config.useSingleServer()
            .setAddress("redis://$host:$port")
            .setConnectionPoolSize(4)
            .setConnectionMinimumIdleSize(4)
            .setIdleConnectionTimeout(10000)
            .setConnectTimeout(10000)
            .setTimeout(3000)
            .setRetryAttempts(3)
            .setRetryInterval(1500)

        return Redisson.create(config).reactive()
    }
} 
