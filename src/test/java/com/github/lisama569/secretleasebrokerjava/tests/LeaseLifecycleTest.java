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
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 租约生命周期：签发、续租、过期、吊销，以及失败原因的可区分性。
 * 关键断言日志：请求、命中规则、最终判定。
 */
@SpringBootTest
@Import(TestConfig.class)
class LeaseLifecycleTest {

    private static final Logger log = LoggerFactory.getLogger(LeaseLifecycleTest.class);

    @Autowired LeaseService leaseService;
    @Autowired AdminService adminService;
    @Autowired CallerDirectory callers;
    @Autowired AuditLog auditLog;
    @Autowired
    MutableTimeSource clock;

    @BeforeEach
    void seed() {
        callers.register(new Caller("alice", Map.of("role", "payments", "env", "prod")));
        callers.register(new Caller("bob", Map.of("role", "readonly", "env", "prod")));
        adminService.replacePolicy(List.of(
                new PolicyRule("allow-payments-issue", 100, Effect.ALLOW, Action.ISSUE,
                        "secret/payments/", Map.of("role", "payments", "env", "prod")),
                new PolicyRule("allow-payments-renew", 100, Effect.ALLOW, Action.RENEW,
                        "secret/payments/", Map.of("role", "payments", "env", "prod"))));
    }

    @Test
    @DisplayName("正常签发与续租：renewCount 单调递增、到期时间按新 TTL 推进")
    void issueAndRenew() {
        Lease lease = leaseService.issue("alice", "secret/payments/db", 5_000L);
        log.info("[用例] issue -> lease={} status={} expiresAt={}", lease.id(), lease.status(),
                lease.expiresAt());
        assertEquals(LeaseStatus.ACTIVE, lease.status());
        assertEquals(0, lease.renewCount());

        clock.advance(1_000);
        Lease renewed = leaseService.renew(lease.id(), 7_000L);
        log.info("[用例] renew -> renewCount={} expiresAt={}", renewed.renewCount(),
                renewed.expiresAt());
        assertEquals(1, renewed.renewCount());
        assertEquals(clock.nowMillis() + 7_000L, renewed.expiresAt());
    }

    @Test
    @DisplayName("越权申请：无 ALLOW 命中 -> POLICY_DENIED，区别于其它失败")
    void unauthorizedIsPolicyDenied() {
        ServiceException ex = assertThrows(ServiceException.class,
                () -> leaseService.issue("bob", "secret/payments/db", 5_000L));
        log.warn("[用例] bob 越权申请被拒 errorCode={} message={}", ex.getCode(), ex.getMessage());
        assertEquals(ErrorCode.POLICY_DENIED, ex.getCode());
    }

    @Test
    @DisplayName("参数非法：未知调用方/TTL 越界 -> INVALID_PARAMETER")
    void invalidParameter() {
        ServiceException unknown = assertThrows(ServiceException.class,
                () -> leaseService.issue("ghost", "secret/payments/db", 5_000L));
        assertEquals(ErrorCode.INVALID_PARAMETER, unknown.getCode());

        ServiceException ttlTooBig = assertThrows(ServiceException.class,
                () -> leaseService.issue("alice", "secret/payments/db", 99_999_999L));
        log.warn("[用例] TTL 越界被拒 errorCode={}", ttlTooBig.getCode());
        assertEquals(ErrorCode.INVALID_PARAMETER, ttlTooBig.getCode());
    }

    @Test
    @DisplayName("过期续租被拒并落 EXPIRED，错误码 LEASE_NOT_ACTIVE")
    void renewAfterExpiry() {
        Lease lease = leaseService.issue("alice", "secret/payments/db", 1_000L);
        clock.advance(1_001);
        ServiceException ex = assertThrows(ServiceException.class,
                () -> leaseService.renew(lease.id(), 5_000L));
        log.warn("[用例] 过期续租被拒 errorCode={}", ex.getCode());
        assertEquals(ErrorCode.LEASE_NOT_ACTIVE, ex.getCode());
        assertEquals(LeaseStatus.EXPIRED, leaseService.getLease(lease.id()).status());
    }

    @Test
    @DisplayName("吊销立即失效；重复吊销 -> LEASE_ALREADY_REVOKED，可与其它原因区分")
    void revokeImmediatelyInvalidatesAndIsIdempotentReject() {
        Lease lease = leaseService.issue("alice", "secret/payments/db", 60_000L);
        Lease revoked = leaseService.revoke(lease.id());
        assertEquals(LeaseStatus.REVOKED, revoked.status());

        ServiceException duplicate = assertThrows(ServiceException.class,
                () -> leaseService.revoke(lease.id()));
        log.warn("[用例] 重复吊销被拒 errorCode={}", duplicate.getCode());
        assertEquals(ErrorCode.LEASE_ALREADY_REVOKED, duplicate.getCode());

        ServiceException renew = assertThrows(ServiceException.class,
                () -> leaseService.renew(lease.id(), 5_000L));
        assertEquals(ErrorCode.LEASE_NOT_ACTIVE, renew.getCode());
        assertTrue(auditLog.forLease(lease.id()).stream()
                .anyMatch(e -> e.eventType().equals("REVOKE_DUPLICATE")));
    }

    @Test
    @DisplayName("不存在的租约 -> LEASE_NOT_FOUND")
    void unknownLease() {
        ServiceException ex = assertThrows(ServiceException.class,
                () -> leaseService.getLease("does-not-exist"));
        assertEquals(ErrorCode.LEASE_NOT_FOUND, ex.getCode());
    }
}
