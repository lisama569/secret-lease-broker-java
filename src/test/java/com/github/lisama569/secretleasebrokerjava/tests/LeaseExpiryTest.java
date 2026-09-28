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
import com.github.lisama569.secretleasebrokerjava.service.LeaseService;
import com.github.lisama569.secretleasebrokerjava.service.LeaseSweeper;
import com.github.lisama569.secretleasebrokerjava.store.CallerDirectory;
import com.github.lisama569.secretleasebrokerjava.tests.support.MutableTimeSource;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 过期语义：到期即失效，惰性判定与清扫器都能把状态落为 EXPIRED。
 */
@SpringBootTest
@Import(TestConfig.class)
class LeaseExpiryTest {

    private static final Logger log = LoggerFactory.getLogger(LeaseExpiryTest.class);

    @Autowired LeaseService leaseService;
    @Autowired AdminService adminService;
    @Autowired CallerDirectory callers;
    @Autowired LeaseSweeper sweeper;
    @Autowired MutableTimeSource clock;

    private String path;

    @BeforeEach
    void seed() {
        path = "secret/expiry/db-" + System.nanoTime();
        callers.register(new Caller("alice", Map.of("role", "payments", "env", "prod")));
        adminService.replacePolicy(List.of(
                new PolicyRule("allow-issue", 100, Effect.ALLOW, Action.ISSUE,
                        "secret/expiry/", Map.of("role", "payments", "env", "prod")),
                new PolicyRule("allow-renew", 100, Effect.ALLOW, Action.RENEW,
                        "secret/expiry/", Map.of("role", "payments", "env", "prod"))));
    }

    @Test
    @DisplayName("惰性判定：时间越过 expiresAt 后，查询自动落 EXPIRED 且取用被拒")
    void lazyExpiry() {
        Lease lease = leaseService.issue("alice", path, 1_000L);
        clock.advance(1_001);
        Lease got = leaseService.getLease(lease.id());
        log.info("[过期-惰性] lease={} 状态={}", lease.id(), got.status());
        assertEquals(LeaseStatus.EXPIRED, got.status());

        ServiceException ex = assertThrows(ServiceException.class,
                () -> leaseService.fetchMaterial(lease.id()));
        assertEquals(ErrorCode.LEASE_NOT_ACTIVE, ex.getCode());
    }

    @Test
    @DisplayName("清扫器：到点扫描把租约落 EXPIRED，效果与惰性判定一致")
    void sweeperExpiry() {
        Lease lease = leaseService.issue("alice", path, 2_000L);
        assertEquals(LeaseStatus.ACTIVE, leaseService.getLease(lease.id()).status());
        clock.advance(2_001);
        sweeper.sweepExpired();
        log.info("[过期-清扫] lease={} 状态={}", lease.id(),
                leaseService.getLease(lease.id()).status());
        assertEquals(LeaseStatus.EXPIRED, leaseService.getLease(lease.id()).status());
    }

    @Test
    @DisplayName("未到期时时间前进不影响租约，续租可延长寿命")
    void renewBeforeExpiryKeepsAlive() {
        Lease lease = leaseService.issue("alice", path, 2_000L);
        clock.advance(1_500);
        Lease renewed = leaseService.renew(lease.id(), 2_000L);
        clock.advance(1_500); // 距首次到期已过，但续租后仍有效
        assertEquals(LeaseStatus.ACTIVE, leaseService.getLease(lease.id()).status());
        log.info("[过期-续租续命] renewCount={} expiresAt={}", renewed.renewCount(),
                renewed.expiresAt());
    }
}
