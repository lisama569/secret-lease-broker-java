package com.github.lisama569.secretleasebrokerjava.error;

/**
 * 失败原因的大类，对应不同 HTTP 状态码。
 */
public enum ErrorCategory {
    POLICY_DENIED,
    LEASE_STATE,
    INVALID_PARAM,
    NOT_FOUND,
    CONFLICT,
    INTERNAL
}
