package com.github.lisama569.secretleasebrokerjava.model;

/**
 * 密钥的一个不可变版本。模拟后端：材料为进程内生成的随机串，不涉及真实凭据。
 *
 * @param secretPath 密钥路径
 * @param version    单调递增的版本号，从 1 开始
 * @param material   该版本的（模拟）密钥材料
 * @param createdAt  创建时间（epoch millis）
 */
public record SecretVersion(String secretPath, int version, String material, long createdAt) {
}
