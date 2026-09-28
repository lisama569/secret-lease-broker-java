package com.github.lisama569.secretleasebrokerjava.clock;

import org.springframework.stereotype.Component;

/**
 * 墙钟时间来源。
 */
@Component
public class SystemTimeSource implements TimeSource {
    @Override
    public long nowMillis() {
        return System.currentTimeMillis();
    }
}
