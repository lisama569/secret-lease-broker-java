package com.github.lisama569.secretleasebrokerjava.model;

/**
 * 一条不可变审计记录：租约/策略生命周期中的每一步都留痕，保证可追溯。
 *
 * @param sequence  进程内单调递增的审计序号
 * @param timestamp 发生时间（epoch millis）
 * @param eventType 事件类型（ISSUE / RENEW / REVOKE / ROTATE / EXPIRE / POLICY_UPDATE / DENY 等）
 * @param success  是否成功
 * @param callerId  相关调用方，可为 null
 * @param leaseId   相关租约，可为 null
 * @param secretPath 相关密钥路径，可为 null
 * @param detail    结构化或人类可读细节（命中规则、失败原因、版本变化等）
 */
public record AuditEvent(long sequence,
                         long timestamp,
                         String eventType,
                         boolean success,
                         String callerId,
                         String leaseId,
                         String secretPath,
                         String detail) {
}
