package com.github.lisama569.secretleasebrokerjava.model;

/**
 * 一条可追溯的审计记录。
 * result 取值：SUCCESS / DENIED / REJECTED。
 */
public record AuditEvent(long sequence,
                         long timestampMillis,
                         AuditEventType type,
                         String callerId,
                         String path,
                         String leaseId,
                         String result,
                         String detail) {
}
