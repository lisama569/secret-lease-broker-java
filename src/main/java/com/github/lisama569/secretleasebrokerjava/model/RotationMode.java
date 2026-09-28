package com.github.lisama569.secretleasebrokerjava.model;

/**
 * 密钥轮换模式：
 * SOFT - 旧租约继续绑定旧版本，直到自然到期；
 * HARD - 轮换同时吊销该路径下全部有效租约。
 */
public enum RotationMode {
    SOFT,
    HARD
}
