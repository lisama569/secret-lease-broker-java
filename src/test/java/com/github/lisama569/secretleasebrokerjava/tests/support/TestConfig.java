package com.github.lisama569.secretleasebrokerjava.tests.support;

import com.github.lisama569.secretleasebrokerjava.clock.TimeSource;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/**
 * 用可控时钟替换墙钟。固定起点 1_000_000ms，便于断言绝对时间。
 */
@TestConfiguration
public class TestConfig {

    @Bean
    @Primary
    public TimeSource mutableTimeSource() {
        return new MutableTimeSource(1_000_000L);
    }
}
