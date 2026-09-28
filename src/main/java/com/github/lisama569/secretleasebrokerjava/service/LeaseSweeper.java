package com.github.lisama569.secretleasebrokerjava.service;

import com.github.lisama569.secretleasebrokerjava.clock.TimeSource;
import com.github.lisama569.secretleasebrokerjava.model.Lease;
import com.github.lisama569.secretleasebrokerjava.model.LeaseStatus;
import com.github.lisama569.secretleasebrokerjava.store.LeaseStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 到期清扫器：周期扫描，把仍标记 ACTIVE 但已过 expiresAt 的租约落为 EXPIRED。
 * 与每次读写的惰性判定互为冗余，保证"到期立即失效"不依赖调用方触发。
 * 逐条走 {@link LeaseStore#mutate} 的分段锁，与续租/吊销并发安全。
 */
@Component
public class LeaseSweeper {

    private static final Logger log = LoggerFactory.getLogger(LeaseSweeper.class);

    private final LeaseStore leaseStore;
    private final AuditLog auditLog;
    private final TimeSource timeSource;

    public LeaseSweeper(LeaseStore leaseStore, AuditLog auditLog, TimeSource timeSource) {
        this.leaseStore = leaseStore;
        this.auditLog = auditLog;
        this.timeSource = timeSource;
    }

    @Scheduled(fixedDelayString = "${lease.sweep-delay-millis:1000}")
    public void sweepExpired() {
        long now = timeSource.nowMillis();
        for (Lease lease : leaseStore.findAll()) {
            if (lease.status() == LeaseStatus.ACTIVE && lease.expiresAt() <= now) {
                leaseStore.mutate(lease.id(), current -> {
                    if (current.status() == LeaseStatus.ACTIVE
                            && current.expiresAt() <= timeSource.nowMillis()) {
                        return new LeaseStore.Update(
                                new Lease(current.id(), current.callerId(), current.secretPath(),
                                        current.secretVersion(), current.issuedAt(),
                                        current.expiresAt(), current.ttlMillis(),
                                        current.renewCount(), LeaseStatus.EXPIRED, null),
                                true);
                    }
                    return new LeaseStore.Update(current, false);
                }).ifPresent(update -> {
                    if (update.changed()) {
                        auditLog.record("EXPIRE", true, update.newLease().callerId(),
                                lease.id(), update.newLease().secretPath(),
                                "清扫器检测到期 expiresAt=" + update.newLease().expiresAt());
                        log.debug("lease {} swept to EXPIRED", lease.id());
                    }
                });
            }
        }
    }
}
