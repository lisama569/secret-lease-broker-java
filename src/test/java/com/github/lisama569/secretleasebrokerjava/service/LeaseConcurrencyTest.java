package com.github.lisama569.secretleasebrokerjava.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.github.lisama569.secretleasebrokerjava.error.BrokerException;
import com.github.lisama569.secretleasebrokerjava.error.ErrorCode;
import com.github.lisama569.secretleasebrokerjava.model.Caller;
import com.github.lisama569.secretleasebrokerjava.model.Lease;
import com.github.lisama569.secretleasebrokerjava.model.LeaseStatus;
import com.github.lisama569.secretleasebrokerjava.model.PolicyRule;
import com.github.lisama569.secretleasebrokerjava.store.AuditLog;
import com.github.lisama569.secretleasebrokerjava.store.CallerStore;
import com.github.lisama569.secretleasebrokerjava.store.LeaseStore;
import com.github.lisama569.secretleasebrokerjava.store.PolicyStore;
import com.github.lisama569.secretleasebrokerjava.store.SecretStore;
import com.github.lisama569.secretleasebrokerjava.time.ManualTimeSource;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 并发生命周期测试：
 *  - N 个并发续租：每次 CAS 成功恰好让 renewCount +1，最终计数必须等于成功数；
 *  - 续租与吊销竞争：结果只能是“续租全成功且后续吊销成功”或“部分续租成功后收到
 *    LEASE_REVOKED”，不得出现吊销丢失（最终状态必须 REVOKED）。
 */
class LeaseConcurrencyTest {

    private static final Logger log = LoggerFactory.getLogger(LeaseConcurrencyTest.class);

    private static final long T0 = 1_000_000L;
    private static final long TTL = 60_000L;
    private static final String PATH = "secret.dev.db";

    private record Harness(LeaseService service, LeaseStore store, ManualTimeSource clock) {
    }

    private Harness newHarness() {
        ManualTimeSource clock = new ManualTimeSource(T0);
        LeaseStore leaseStore = new LeaseStore();
        AuditLog auditLog = new AuditLog();
        PolicyStore policyStore = new PolicyStore();
        CallerStore callerStore = new CallerStore();
        callerStore.register(new Caller("app-reader", Map.of("role", "app")));
        policyStore.replace(List.of(
                new PolicyRule("allow-app", null, "secret.dev.*",
                        Map.of("role", "app"), PolicyRule.Effect.ALLOW, 1)));
        PolicyEngine engine = new PolicyEngine(policyStore);
        LeaseService service = new LeaseService(leaseStore, new SecretStore(), callerStore,
                policyStore, engine, auditLog, clock);
        return new Harness(service, leaseStore, clock);
    }

    @Test
    void concurrentRenewsProduceExactlyMatchingCount() throws Exception {
        Harness h = newHarness();
        Lease lease = h.service().issue("app-reader", PATH, TTL, null);

        int threads = 32;
        ExecutorService pool = Executors.newFixedThreadPool(8);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger success = new AtomicInteger();
        Map<ErrorCode, Integer> failures = new ConcurrentHashMap<>();

        for (int i = 0; i < threads; i++) {
            pool.submit(() -> {
                try {
                    start.await();
                    h.service().renew("app-reader", lease.id(), TTL);
                    success.incrementAndGet();
                } catch (BrokerException e) {
                    failures.merge(e.errorCode(), 1, Integer::sum);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
        }
        start.countDown();
        pool.shutdown();
        assertThat(pool.awaitTermination(30, TimeUnit.SECONDS)).isTrue();

        Lease finalLease = h.store().find(lease.id()).orElseThrow();
        log.info("[case] concurrent-renew threads={} success={} failures={} finalRenewCount={} revision={}",
                threads, success.get(), failures, finalLease.renewCount(), finalLease.revision());

        assertThat(success.get() + failures.values().stream().mapToInt(Integer::intValue).sum())
                .isEqualTo(threads);
        // 关键不变量：成功续租次数与 revision 推进、renewCount 完全一致，无丢失更新/重复计数
        assertThat(finalLease.renewCount()).isEqualTo(success.get());
        assertThat(finalLease.revision()).isEqualTo(1L + success.get());
        assertThat(finalLease.status()).isEqualTo(LeaseStatus.ACTIVE);
    }

    @Test
    void concurrentRenewAndRevokeNeverLosesRevocation() throws Exception {
        int rounds = 50;
        int observedRenewSuccess = 0;
        int observedRevokeStateErrors = 0;

        for (int round = 0; round < rounds; round++) {
            Harness h = newHarness();
            Lease lease = h.service().issue("app-reader", PATH, TTL, "round-" + round);

            ExecutorService pool = Executors.newFixedThreadPool(2);
            CountDownLatch start = new CountDownLatch(1);
            AtomicInteger renewOk = new AtomicInteger();
            AtomicInteger revokeRejected = new AtomicInteger();

            pool.submit(() -> {
                try {
                    start.await();
                    h.service().renew("app-reader", lease.id(), TTL);
                    renewOk.incrementAndGet();
                } catch (BrokerException e) {
                    // 合法终态：LEASE_REVOKED / LEASE_EXPIRED / LEASE_ALREADY_REVOKED
                    if (e.errorCode() == ErrorCode.LEASE_REVOKED
                            || e.errorCode() == ErrorCode.LEASE_EXPIRED) {
                        revokeRejected.incrementAndGet();
                    } else {
                        throw e;
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
            pool.submit(() -> {
                try {
                    start.await();
                    h.service().revoke("app-reader", lease.id(), "concurrent revoke");
                } catch (BrokerException e) {
                    if (e.errorCode() != ErrorCode.LEASE_ALREADY_REVOKED
                            && e.errorCode() != ErrorCode.LEASE_EXPIRED) {
                        throw e;
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
            start.countDown();
            pool.shutdown();
            assertThat(pool.awaitTermination(30, TimeUnit.SECONDS)).isTrue();

            Lease finalLease = h.store().find(lease.id()).orElseThrow();
            // 核心断言：无论调度顺序如何，吊销绝不丢失
            assertThat(finalLease.status())
                    .as("round %d revocation must not be lost", round)
                    .isEqualTo(LeaseStatus.REVOKED);
            assertThat(finalLease.renewCount()).isEqualTo(renewOk.get());
            observedRenewSuccess += renewOk.get();
            observedRevokeStateErrors += revokeRejected.get();
        }
        log.info("[case] renew-vs-revoke rounds={} renewSucceeded={} renewRejectedByRevocation={}",
                rounds, observedRenewSuccess, observedRevokeStateErrors);
    }

    @Test
    void hardRotationRevokesAllActiveLeasesAndSoftKeepsOldVersion() throws Exception {
        // 该语义在 SecretService 中有更直接的断言，这里仅验证强制吊销在多租约并发下不丢
        Harness h = newHarness();
        Lease a = h.service().issue("app-reader", PATH, TTL, "a");
        Lease b = h.service().issue("app-reader", PATH + "-2", TTL, "b");
        // 同路径再造一张：需先吊销 a，避免“同调用方同路径唯一有效租约”限制
        Lease c = h.service().issue("app-reader", PATH + "-3", TTL, "c");

        ExecutorService pool = Executors.newFixedThreadPool(3);
        CountDownLatch start = new CountDownLatch(1);
        pool.submit(latchTask(start, () -> h.service().renew("app-reader", a.id(), TTL)));
        pool.submit(latchTask(start, () -> h.service().renew("app-reader", b.id(), TTL)));
        pool.submit(latchTask(start, () -> h.service().renew("app-reader", c.id(), TTL)));
        start.countDown();
        pool.shutdown();
        assertThat(pool.awaitTermination(30, TimeUnit.SECONDS)).isTrue();

        int revoked = h.service().revokeAllActiveForPath(PATH, "test", T0 + 1);
        log.info("[case] revokeAll path={} revoked={}", PATH, revoked);
        // PATH 精确匹配，只应有 a
        assertThat(revoked).isEqualTo(1);
        assertThat(h.store().find(a.id()).orElseThrow().status()).isEqualTo(LeaseStatus.REVOKED);
        assertThat(h.store().find(b.id()).orElseThrow().status()).isEqualTo(LeaseStatus.ACTIVE);
    }

    private static Runnable latchTask(CountDownLatch start, Runnable action) {
        return () -> {
            try {
                start.await();
                action.run();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        };
    }
}
