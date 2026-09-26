package com.chris64233.granttranche.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

/** 通用 Bean 配置。使用可注入的 {@link Clock}，便于测试控制时间。 */
@Configuration
public class AppConfig {

    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }
}
