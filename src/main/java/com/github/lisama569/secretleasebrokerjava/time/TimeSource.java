package com.github.lisama569.secretleasebrokerjava.time;

/**
 * 时间来源抽象，便于测试中可控推进时间，验证过期语义。
 */
public interface TimeSource {
    long nowMillis();
}
