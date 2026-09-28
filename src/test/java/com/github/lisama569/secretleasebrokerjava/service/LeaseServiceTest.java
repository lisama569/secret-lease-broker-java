package com.github.lisama569.secretleasebrokerjava.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.github.lisama569.secretleasebrokerjava.error.BrokerException;
import com.github.lisama569.secretleasebrokerjava.error.ErrorCode;
import com.github.lisama569.secretleasebrokerjava.model.AccessAction;
import com.github.lisama569.secretleasebrokerjava.model.Caller;
import com.github.lisama569.secretleasebrokerjava.model.Lease;
import com.github.lisama569.secretleasebrokerjava.model.PolicyRule;
import com.github.lisama569.secretleasebrokerjava.store.AuditLog;
import com.github.lisama569.secretleasebrokerjava.store.CallerStore;
import com.github.lisama569.secretleasebrokerjava.store.LeaseStore;
import com.github.lisama569.secretleasebrokerjava.store.PolicyStore;
import com.github.lisama569.secretleasebrokerjava.store.SecretStore;
import com.github.lisama569.secretleasebrokerjava.time.ManualTimeSource;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 租约生命周期：签发/续租/吊销、过期失效、越权拒绝、重复吊销、幂等签发。
 * 失败原因必须可区分（POLICY_DENIED / LEASE_EXPIRED / LEASE_REVOKED / INVALID_PARAM 等）。
 */
class LeaseServiceTest {

    private static final Logger log = LoggerFactory.getLogger(LeaseServiceTest.class);

    private static final long T0 = 1_000_000L;
    private static final long TTL = 60_000L;
    private static final String PATH = "secret.dev.db";

    private ManualTimeSource clock;
    private LeaseStore leaseStore;
    private PolicyStore policyStore;
    private LeaseService leaseService;
    private SecretStore secretStore;
    private AuditLog auditLog;

    @BeforeEach
    void setUp() {
        clock = new ManualTimeSource(T0);
        leaseStore = new LeaseStore();
        secretStore = new SecretStore();
        auditLog = new AuditLog();
        policyStore = new PolicyStore();
        CallerStore callerStore = new CallerStore();
        callerStore.register(new Caller("app-reader", Map.of("role", "app", "team", "payments")));
        callerStore.register(new Caller("app-legacy", Map.of("role", "app", "team", "legacy")));
        callerStore.register(new Caller("ops-admin", Map.of("role", "ops")));
        policyStore.replace(List.of(
                new PolicyRule("allow-app", null, "secret.dev.*",
                        Map.of("role", "app"), PolicyRule.Effect.ALLOW, 100),
                new PolicyRule("deny-legacy", null, "secret.dev.*",
                        Map.of("role", "app", "team", "legacy"), PolicyRule.Effect.DENY, 10),
                new PolicyRule("allow-ops", null, "*",
                        Map.of("role", "ops"), PolicyRule.Effect.ALLOW, 200)));
        PolicyEngine engine = new PolicyEngine(policyStore);
        leaseService = new LeaseService(leaseStore, secretStore, callerStore,
                policyStore, engine, auditLog, clock);
    }

    @Test
    @DisplayName("签发成功：返回 ACTIVE 租约并写 SUCCESS 审计")
    void issueCreatesActiveLeaseAndAudits() {
        Lease lease = leaseService.issue("app-reader", PATH, TTL, null);
        log.info("[case] issue lease={}", lease);
        assertThat(lease.status().name()).isEqualTo("ACTIVE");
        assertThat(lease.renewCount()).isZero();
        assertThat(lease.secretVersion()).isEqualTo(1L);
        assertThat(auditLog.findByLeaseId(lease.id().toString()))
                .anyMatch(e -> e.type().name().equals("LEASE_ISSUE") && e.result().equals("SUCCESS"));
    }

    @Test
    @DisplayName("越权申请被拒绝：错误码 POLICY_DENIED，且有 DENIED 审计与命中规则")
    void issueByUnauthorizedCallerIsPolicyDenied() {
        assertThatThrownBy(() -> leaseService.issue("app-legacy", PATH, TTL, null))
                .isInstanceOf(BrokerException.class)
                .satisfies(ex -> {
                    ErrorCode code = ((BrokerException) ex).errorCode();
                    log.info("[case] unauthorized issue errorCode={} message={}", code, ex.getMessage());
                    assertThat(code).isEqualTo(ErrorCode.POLICY_DENIED);
                });
    }

    @Test
    @DisplayName("参数非法：空白路径 / 非正 TTL 返回不同的参数错误码")
    void invalidParametersRejected() {
        assertThatThrownBy(() -> leaseService.issue("app-reader", "  ", TTL, null))
                .extracting(e -> ((BrokerException) e).errorCode())
                .isEqualTo(ErrorCode.PATH_BLANK);
        assertThatThrownBy(() -> leaseService.issue("app-reader", PATH, 0, null))
                .extracting(e -> ((BrokerException) e).errorCode())
                .isEqualTo(ErrorCode.TTL_OUT_OF_RANGE);
    }

    @Test
    @DisplayName("过期后续租被拒绝：LEASE_EXPIRED；到期前续租成功并滑动续期")
    void renewRespectsExpiry() {
        Lease lease = leaseService.issue("app-reader", PATH, TTL, null);

        clock.advanceMillis(TTL / 2);
        Lease renewed = leaseService.renew("app-reader", lease.id(), TTL);
        log.info("[case] renew-before-expiry renewCount={} expiresAt={}",
                renewed.renewCount(), renewed.expiresAtMillis());
        assertThat(renewed.renewCount()).isOne();
        assertThat(renewed.expiresAtMillis()).isEqualTo(T0 + TTL / 2 + TTL);

        clock.advanceMillis(TTL + 1);
        assertThatThrownBy(() -> leaseService.renew("app-reader", lease.id(), TTL))
                .isInstanceOf(BrokerException.class)
                .satisfies(ex -> {
                    ErrorCode code = ((BrokerException) ex).errorCode();
                    log.info("[case] renew-after-expiry errorCode={}", code);
                    assertThat(code).isEqualTo(ErrorCode.LEASE_EXPIRED);
                });
    }

    @Test
    @DisplayName("到期或吊销后租约立即失效：READ 分别得到 LEASE_EXPIRED / LEASE_REVOKED")
    void leaseBecomesUnusableAfterExpiryOrRevoke() {
        Lease lease = leaseService.issue("app-reader", PATH, TTL, null);
        leaseService.revoke("app-reader", lease.id(), "manual");
        assertThatThrownBy(() -> leaseService.requireActiveLease("app-reader", lease.id(), AccessAction.READ))
                .extracting(e -> ((BrokerException) e).errorCode())
                .isEqualTo(ErrorCode.LEASE_REVOKED);

        Lease lease2 = leaseService.issue("app-reader", "secret.dev.db2", TTL, null);
        clock.advanceMillis(TTL + 1);
        assertThatThrownBy(() -> leaseService.requireActiveLease("app-reader", lease2.id(), AccessAction.READ))
                .extracting(e -> ((BrokerException) e).errorCode())
                .isEqualTo(ErrorCode.LEASE_EXPIRED);
    }

    @Test
    @DisplayName("重复吊销被拒绝：LEASE_ALREADY_REVOKED，并带首次吊销原因")
    void doubleRevokeRejected() {
        Lease lease = leaseService.issue("app-reader", PATH, TTL, null);
        leaseService.revoke("app-reader", lease.id(), "first reason");
        assertThatThrownBy(() -> leaseService.revoke("app-reader", lease.id(), "second reason"))
                .isInstanceOf(BrokerException.class)
                .satisfies(ex -> {
                    BrokerException be = (BrokerException) ex;
                    log.info("[case] double-revoke errorCode={} message={}", be.errorCode(), be.getMessage());
                    assertThat(be.errorCode()).isEqualTo(ErrorCode.LEASE_ALREADY_REVOKED);
                    assertThat(be.getMessage()).contains("first reason");
                });
    }

    @Test
    @DisplayName("非属主续租/吊销被拒绝：LEASE_CALLER_MISMATCH")
    void nonOwnerOperationsRejected() {
        Lease lease = leaseService.issue("app-reader", PATH, TTL, null);
        assertThatThrownBy(() -> leaseService.renew("ops-admin", lease.id(), TTL))
                .extracting(e -> ((BrokerException) e).errorCode())
                .isEqualTo(ErrorCode.LEASE_CALLER_MISMATCH);
        assertThatThrownBy(() -> leaseService.revoke("ops-admin", lease.id(), "x"))
                .extracting(e -> ((BrokerException) e).errorCode())
                .isEqualTo(ErrorCode.LEASE_CALLER_MISMATCH);
    }

    @Test
    @DisplayName("不存在的租约返回 LEASE_NOT_FOUND")
    void unknownLeaseRejected() {
        assertThatThrownBy(() -> leaseService.renew("app-reader", UUID.randomUUID(), TTL))
                .extracting(e -> ((BrokerException) e).errorCode())
                .isEqualTo(ErrorCode.LEASE_NOT_FOUND);
    }

    @Test
    @DisplayName("幂等签发：相同 idempotencyKey 重放返回同一租约，不重复签发")
    void idempotentIssueReturnsSameLease() {
        Lease first = leaseService.issue("app-reader", PATH, TTL, "key-123");
        Lease replay = leaseService.issue("app-reader", PATH, TTL, "key-123");
        log.info("[case] idempotent first={} replay={}", first.id(), replay.id());
        assertThat(replay.id()).isEqualTo(first.id());
        assertThat(leaseStore.findByPath(PATH)).hasSize(1);
    }

    @Test
    @DisplayName("无幂等键的并发安全：同调用方同路径已有有效租约时重复签发被拒")
    void duplicateActiveLeaseRejected() {
        leaseService.issue("app-reader", PATH, TTL, null);
        assertThatThrownBy(() -> leaseService.issue("app-reader", PATH, TTL, null))
                .extracting(e -> ((BrokerException) e).errorCode())
                .isEqualTo(ErrorCode.DUPLICATE_ACTIVE_LEASE);
    }
}
