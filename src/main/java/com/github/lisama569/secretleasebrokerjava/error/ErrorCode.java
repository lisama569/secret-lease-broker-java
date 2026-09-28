package com.github.lisama569.secretleasebrokerjava.error;

/**
 * 可区分的失败原因码。所有面向调用方的拒绝都必须映射为其中之一，
 * 保证并发/异常场景下结论可解释、可复现。
 */
public enum ErrorCode {
    /** 参数不合法（缺少身份、路径非法、TTL 越界等）。 */
    INVALID_PARAMETER,
    /** 策略不允许：默认拒绝，未命中任何 ALLOW 规则，或命中了更高优先级的 DENY。 */
    POLICY_DENIED,
    /** 租约不存在（ID 无法识别）。 */
    LEASE_NOT_FOUND,
    /** 租约已失效：已过期、已吊销或因轮换而废止，不能再续租或取用。 */
    LEASE_NOT_ACTIVE,
    /** 重复吊销：租约此前已被吊销，吊销不可重复执行。 */
    LEASE_ALREADY_REVOKED,
    /** 租约绑定的密钥版本与当前生效版本不一致（轮换后用旧租约取新版本）。 */
    SECRET_VERSION_MISMATCH,
    /** 引用的密钥路径不存在。 */
    SECRET_NOT_FOUND,
    /** 并发冲突：同一时刻对同一租约的变更竞争失败，调用方可安全重试读操作。 */
    CONCURRENT_MODIFICATION
}
