package com.github.lisama569.secretleasebrokerjava.model;

/**
 * 租约在其生命周期内的状态。
 */
public enum LeaseStatus {
    /** 生效中（未过期且未吊销）。 */
    ACTIVE,
    /** 到期失效（惰性判定 + 清扫任务落入该状态）。 */
    EXPIRED,
    /** 被显式吊销，立即失效。 */
    REVOKED
}
