package com.github.lisama569.secretleasebrokerjava.model;

/**
 * 短期租约：调用方在限定时间内访问某个密钥版本的授权凭证。
 * 租约是不可变快照；续租产生新版本对象（保留同一 id 与 issuedAt，版本号递增）。
 *
 * @param id            租约标识，签发时生成，续租保持不变
 * @param callerId      承租调用方
 * @param secretPath    密钥路径
 * @param secretVersion 绑定时密钥的版本号；轮换后旧租约仍绑定旧版本
 * @param issuedAt      首次签发时间（epoch millis）
 * @param expiresAt     当前到期时间（epoch millis）
 * @param ttlMillis     当前租期长度
 * @param renewCount    已续租次数
 * @param status        ACTIVE / EXPIRED / REVOKED
 * @param revokedAt     吊销时间，未吊销为 null
 */
public record Lease(String id,
                    String callerId,
                    String secretPath,
                    int secretVersion,
                    long issuedAt,
                    long expiresAt,
                    long ttlMillis,
                    int renewCount,
                    LeaseStatus status,
                    Long revokedAt) {
}
