package com.github.lisama569.secretleasebrokerjava.error;

/**
 * 细粒度错误码，保证调用方能区分“策略不允许 / 租约已失效 / 参数不合法”等场景。
 */
public enum ErrorCode {
    POLICY_DENIED(ErrorCategory.POLICY_DENIED),
    INVALID_PARAM(ErrorCategory.INVALID_PARAM),

    LEASE_NOT_FOUND(ErrorCategory.NOT_FOUND),
    LEASE_EXPIRED(ErrorCategory.LEASE_STATE),
    LEASE_REVOKED(ErrorCategory.LEASE_STATE),
    LEASE_CALLER_MISMATCH(ErrorCategory.POLICY_DENIED),
    LEASE_ALREADY_REVOKED(ErrorCategory.LEASE_STATE),
    RENEW_BEFORE_EXPIRY_TOO_EARLY(ErrorCategory.INVALID_PARAM),

    UNKNOWN_CALLER(ErrorCategory.NOT_FOUND),
    PATH_BLANK(ErrorCategory.INVALID_PARAM),
    TTL_OUT_OF_RANGE(ErrorCategory.INVALID_PARAM),
    ROTATION_MODE_INVALID(ErrorCategory.INVALID_PARAM),
    RULES_EMPTY(ErrorCategory.INVALID_PARAM),
    RULE_INVALID(ErrorCategory.INVALID_PARAM),

    SECRET_NOT_FOUND(ErrorCategory.NOT_FOUND),
    DUPLICATE_ACTIVE_LEASE(ErrorCategory.CONFLICT),
    CONCURRENT_MODIFICATION(ErrorCategory.CONFLICT),
    INTERNAL_ERROR(ErrorCategory.INTERNAL);

    private final ErrorCategory category;

    ErrorCode(ErrorCategory category) {
        this.category = category;
    }

    public ErrorCategory category() {
        return category;
    }
}
