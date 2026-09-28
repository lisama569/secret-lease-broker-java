package com.github.lisama569.secretleasebrokerjava.model;

import java.util.UUID;

/**
 * 租约的不可变状态快照。所有生命周期变更都通过整体替换快照完成（CAS），
 * revision 在每次变更后递增。
 */
public record Lease(UUID id,
                    String callerId,
                    String path,
                    long secretVersion,
                    long issuedAtMillis,
                    long expiresAtMillis,
                    LeaseStatus status,
                    int renewCount,
                    Long revokedAtMillis,
                    String revokedReason,
                    String idempotencyKey,
                    long revision) {

    public boolean isActiveAt(long nowMillis) {
        return status == LeaseStatus.ACTIVE
                && nowMillis >= issuedAtMillis
                && nowMillis < expiresAtMillis;
    }
}
