package com.github.lisama569.secretleasebrokerjava.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 租约时长约束。
 */
@ConfigurationProperties(prefix = "lease")
public class LeaseProperties {

    /** 默认 TTL（毫秒），请求未显式给定时使用。 */
    private long defaultTtlMillis = 60_000L;

    /** 允许的最小 TTL（毫秒），防止传入 0/负数。 */
    private long minTtlMillis = 1_000L;

    /** 允许的最大 TTL（毫秒），短期租约不得超过该上限。 */
    private long maxTtlMillis = 3_600_000L;

    public long getDefaultTtlMillis() {
        return defaultTtlMillis;
    }

    public void setDefaultTtlMillis(long defaultTtlMillis) {
        this.defaultTtlMillis = defaultTtlMillis;
    }

    public long getMinTtlMillis() {
        return minTtlMillis;
    }

    public void setMinTtlMillis(long minTtlMillis) {
        this.minTtlMillis = minTtlMillis;
    }

    public long getMaxTtlMillis() {
        return maxTtlMillis;
    }

    public void setMaxTtlMillis(long maxTtlMillis) {
        this.maxTtlMillis = maxTtlMillis;
    }
}
