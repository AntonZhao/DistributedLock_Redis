package com.anton.config;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Configuration;
import redis.embedded.RedisServer;

import javax.annotation.PreDestroy;

@Configuration
public class EmbeddedRedis implements ApplicationRunner {

    private static RedisServer redisServer;

    @PreDestroy
    public void stopRedis() {
        redisServer.stop();
    }

    @Override
    public void run(ApplicationArguments args) throws Exception {
        redisServer = RedisServer.builder().port(63799).setting("bind 127.0.0.1").setting("requirepass test").build();
        redisServer.start();
    }
}
