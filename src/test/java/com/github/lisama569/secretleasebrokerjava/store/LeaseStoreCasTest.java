package com.github.lisama569.secretleasebrokerjava.store;

import static org.assertj.core.api.Assertions.assertThat;

import com.github.lisama569.secretleasebrokerjava.model.Lease;
import com.github.lisama569.secretleasebrokerjava.model.LeaseStatus;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 确定性验证乐观锁：同一 revision 的两个更新只有第一个能提交，
 * 第二个 compareAndSwap 必须失败（empty），从存储层杜绝丢失更新。
 */
class LeaseStoreCasTest {

    private static final Logger log = LoggerFactory.getLogger(LeaseStoreCasTest.class);

    private Lease baseLease() {
        long now = 1_000L;
        return new Lease(UUID.randomUUID(), "caller", "p", 1L,
                now, now + 10_000L, LeaseStatus.ACTIVE, 0, null, null, null, 1L);
    }

    @Test
    void staleRevisionCasFailsAndNoUpdateIsLost() {
        LeaseStore store = new LeaseStore();
        Lease lease = baseLease();
        assertThat(store.putIfAbsent(lease)).isTrue();

        // 续租方 A 与吊销方 B 都基于 revision=1 提交
        var renewA = store.compareAndSwap(lease.id(), 1L, l -> new Lease(
                l.id(), l.callerId(), l.path(), l.secretVersion(),
                l.issuedAtMillis(), l.expiresAtMillis() + 1000, LeaseStatus.ACTIVE,
                l.renewCount() + 1, null, null, l.idempotencyKey(), 2L));
        var revokeB = store.compareAndSwap(lease.id(), 1L, l -> new Lease(
                l.id(), l.callerId(), l.path(), l.secretVersion(),
                l.issuedAtMillis(), l.expiresAtMillis(), LeaseStatus.REVOKED,
                l.renewCount(), 2_000L, "late", l.idempotencyKey(), 2L));

        log.info("[case] cas-race renewPresent={} revokePresent={}", renewA.isPresent(), revokeB.isPresent());
        assertThat(renewA).isPresent();
        assertThat(revokeB).as("stale revision must not commit").isEmpty();

        Lease current = store.find(lease.id()).orElseThrow();
        assertThat(current.revision()).isEqualTo(2L);
        assertThat(current.renewCount()).isOne();
        assertThat(current.status()).isEqualTo(LeaseStatus.ACTIVE);

        // 失败方用最新 revision=2 重试吊销，必须成功
        var retry = store.compareAndSwap(lease.id(), 2L, l -> new Lease(
                l.id(), l.callerId(), l.path(), l.secretVersion(),
                l.issuedAtMillis(), l.expiresAtMillis(), LeaseStatus.REVOKED,
                l.renewCount(), 2_000L, "retry", l.idempotencyKey(), 3L));
        assertThat(retry).isPresent();
        assertThat(store.find(lease.id()).orElseThrow().status()).isEqualTo(LeaseStatus.REVOKED);
        assertThat(store.find(lease.id()).orElseThrow().revision()).isEqualTo(3L);
    }
}
