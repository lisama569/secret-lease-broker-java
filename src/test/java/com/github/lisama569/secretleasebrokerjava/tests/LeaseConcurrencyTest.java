package com.github.lisama569.secretleasebrokerjava.tests;

import com.github.lisama569.secretleasebrokerjava.error.ErrorCode;
import com.github.lisama569.secretleasebrokerjava.error.ServiceException;
import com.github.lisama569.secretleasebrokerjava.model.Action;
import com.github.lisama569.secretleasebrokerjava.model.Caller;
import com.github.lisama569.secretleasebrokerjava.model.Effect;
import com.github.lisama569.secretleasebrokerjava.model.Lease;
import com.github.lisama569.secretleasebrokerjava.model.LeaseStatus;
import com.github.lisama569.secretleasebrokerjava.model.PolicyRule;
import com.github.lisama569.secretleasebrokerjava.service.AdminService;
import com.github.lisama569.secretleasebrokerjava.service.AuditLog;
import com.github.lisama569.secretleasebrokerjava.service.LeaseService;
import com.github.lisama569.secretleasebrokerjava.store.CallerDirectory;
import com.github.lisama569.secretleasebrokerjava.tests.support.TestConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 并发场景：同一租约的并发续租、并发吊销不得出现计数错乱或吊销丢失/重复成功。
 */
@SpringBootTest
@Import(TestConfig.class)
class LeaseConcurrencyTest {

    private static final Logger log = LoggerFactory.getLogger(LeaseConcurrencyTest.class);

    @Autowired LeaseService leaseService;
    @Autowired AdminService adminService;
    @Autowired CallerDirectory callers;
    @Autowired AuditLog auditLog;

    @BeforeEach
    void seed() {
        callers.register(new Caller("alice", Map.of("role", "payments", "env", "prod")));
        adminService.replacePolicy(List.of(
                new PolicyRule("allow-renew", 100, Effect.ALLOW, Action.RENEW,
                        "secret/payments/", Map.of("role", "payments", "env", "prod")),
                new PolicyRule("allow-issue", 100, Effect.ALLOW, Action.ISSUE,
                        "secret/payments/", Map.of("role", "payments", "env", "prod"))));
    }

    @Test
    @DisplayName("N 个线程并发续租同一租约：成功次数恰好等于最终 renewCount")
    void concurrentRenewsNoLostUpdate() throws Exception {
        Lease lease = leaseService.issue("alice", "secret/payments/db", 600_000L);
        int threads = 64;
        ExecutorService pool = Executors.newFixedThreadPool(16);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        AtomicInteger success = new AtomicInteger();
        AtomicInteger failures = new AtomicInteger();

        for (int i = 0; i < threads; i++) {
            pool.submit(() -> {
                try {
                    start.await();
                    leaseService.renew(lease.id(), 10_000L);
                    success.incrementAndGet();
                } catch (ServiceException ex) {
                    failures.incrementAndGet();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            });
        }
        start.countDown();
        assertTrue(done.await(30, TimeUnit.SECONDS), "并发续租未在超时内完成");
        pool.shutdown();

        Lease finalLease = leaseService.getLease(lease.id());
        log.info("[并发续租] 成功={} 失败={} 最终 renewCount={} 状态={}",
                success.get(), failures.get(), finalLease.renewCount(), finalLease.status());
        assertEquals(0, failures.get(), "租约未到期/未吊销，不应有续租失败");
        assertEquals(threads, success.get());
        assertEquals(threads, finalLease.renewCount(), "每次成功续租必须恰好计数一次");
    }

    @Test
    @DisplayName("并发续租与并发吊销交错：吊销恰好成功一次，其余续租全部确定失败且无计数错乱")
    void concurrentRenewAndRevoke() throws Exception {
        Lease lease = leaseService.issue("alice", "secret/payments/db", 600_000L);
        int renewThreads = 48;
        int revokeThreads = 8;
        int total = renewThreads + revokeThreads;
        ExecutorService pool = Executors.newFixedThreadPool(20);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(total);
        AtomicInteger renewOk = new AtomicInteger();
        AtomicInteger renewRejected = new AtomicInteger();
        AtomicInteger revokeOk = new AtomicInteger();
        AtomicInteger revokeDuplicate = new AtomicInteger();

        for (int i = 0; i < renewThreads; i++) {
            pool.submit(() -> {
                try {
                    start.await();
                    leaseService.renew(lease.id(), 10_000L);
                    renewOk.incrementAndGet();
                } catch (ServiceException ex) {
                    assertEquals(ErrorCode.LEASE_NOT_ACTIVE, ex.getCode(),
                            "吊销后续租只能得到 LEASE_NOT_ACTIVE");
                    renewRejected.incrementAndGet();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            });
        }
        for (int i = 0; i < revokeThreads; i++) {
            pool.submit(() -> {
                try {
                    start.await();
                    leaseService.revoke(lease.id());
                    revokeOk.incrementAndGet();
                } catch (ServiceException ex) {
                    assertEquals(ErrorCode.LEASE_ALREADY_REVOKED, ex.getCode());
                    revokeDuplicate.incrementAndGet();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            });
        }
        start.countDown();
        assertTrue(done.await(30, TimeUnit.SECONDS));
        pool.shutdown();

        Lease finalLease = leaseService.getLease(lease.id());
        log.info("[并发续租+吊销] 续租成功={} 续租被拒={} 吊销成功={} 重复吊销={} 最终状态={} renewCount={}",
                renewOk.get(), renewRejected.get(), revokeOk.get(), revokeDuplicate.get(),
                finalLease.status(), finalLease.renewCount());
        assertEquals(1, revokeOk.get(), "吊销必须恰好成功一次，不能丢失也不能重复");
        assertEquals(revokeThreads - 1, revokeDuplicate.get());
        assertEquals(LeaseStatus.REVOKED, finalLease.status());
        assertEquals(renewOk.get(), finalLease.renewCount(),
                "成功续租次数必须与 renewCount 一致，无丢更新");
        assertEquals(renewThreads - renewOk.get(), renewRejected.get());
    }
}
