package com.github.lisama569.secretleasebrokerjava.time;

/**
 * 测试用可控时钟：时间只在显式 advance 时前进，保证过期结论可复现。
 */
public class ManualTimeSource implements TimeSource {

    private volatile long current;

    public ManualTimeSource(long startMillis) {
        this.current = startMillis;
    }

    @Override
    public long nowMillis() {
        return current;
    }

    public void advanceMillis(long delta) {
        if (delta < 0) {
            throw new IllegalArgumentException("delta must be >= 0");
        }
        current += delta;
    }
}
