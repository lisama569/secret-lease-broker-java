package com.github.lisama569.secretleasebrokerjava.clock;

/**
 * 时间来源抽象：测试中可替换为可控时钟，使过期等场景可复现。
 */
public interface TimeSource {
    long nowMillis();
}
