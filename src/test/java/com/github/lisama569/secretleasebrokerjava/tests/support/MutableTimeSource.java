package com.github.lisama569.secretleasebrokerjava.tests.support;

import com.github.lisama569.secretleasebrokerjava.clock.TimeSource;

import java.util.concurrent.atomic.AtomicLong;

/**
 * 测试用可控时钟：时间只在显式 advance 时前进，使过期/续租结论确定可复现。
 */
public class MutableTimeSource implements TimeSource {

    private final AtomicLong now;

    public MutableTimeSource(long startMillis) {
        this.now = new AtomicLong(startMillis);
    }

    @Override
    public long nowMillis() {
        return now.get();
    }

    public void advance(long deltaMillis) {
        now.addAndGet(deltaMillis);
    }

    public void set(long millis) {
        now.set(millis);
    }
}
