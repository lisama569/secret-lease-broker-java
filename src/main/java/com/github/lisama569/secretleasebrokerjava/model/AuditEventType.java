package com.github.lisama569.secretleasebrokerjava.model;

/**
 * 审计事件类型。
 */
public enum AuditEventType {
    LEASE_ISSUE,
    LEASE_RENEW,
    LEASE_REVOKE,
    SECRET_ROTATE,
    SECRET_READ,
    POLICY_DECIDE,
    POLICY_REPLACE
}
