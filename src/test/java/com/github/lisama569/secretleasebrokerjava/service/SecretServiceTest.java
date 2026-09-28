package com.github.lisama569.secretleasebrokerjava.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.github.lisama569.secretleasebrokerjava.error.BrokerException;
import com.github.lisama569.secretleasebrokerjava.error.ErrorCode;
import com.github.lisama569.secretleasebrokerjava.model.Caller;
import com.github.lisama569.secretleasebrokerjava.model.Lease;
import com.github.lisama569.secretleasebrokerjava.model.PolicyRule;
import com.github.lisama569.secretleasebrokerjava.model.RotationMode;
import com.github.lisama569.secretleasebrokerjava.store.AuditLog;
import com.github.lisama569.secretleasebrokerjava.store.CallerStore;
import com.github.lisama569.secretleasebrokerjava.store.LeaseStore;
import com.github.lisama569.secretleasebrokerjava.store.PolicyStore;
import com.github.lisama569.secretleasebrokerjava.store.SecretStore;
import com.github.lisama569.secretleasebrokerjava.time.ManualTimeSource;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 轮换语义：
 * SOFT - 旧租约稳定读取签发时的旧版本，新租约读取新版本；
 * HARD - 轮换后旧租约立即失效（LEASE_REVOKED），重新签发才能读到新版本。
 */
class SecretServiceTest {

    private static final Logger log = LoggerFactory.getLogger(SecretServiceTest.class);
    private static final long T0 = 5_000_000L;
    private static final long TTL = 60_000L;
    private static final String PATH = "secret.dev.db";

    private ManualTimeSource clock;
    private LeaseService leaseService;
    private SecretService secretService;
    private LeaseStore leaseStore;
    private SecretStore secretStore;

    @BeforeEach
    void setUp() {
        clock = new ManualTimeSource(T0);
        leaseStore = new LeaseStore();
        secretStore = new SecretStore();
        AuditLog auditLog = new AuditLog();
        PolicyStore policyStore = new PolicyStore();
        CallerStore callerStore = new CallerStore();
        callerStore.register(new Caller("app-reader", Map.of("role", "app")));
        callerStore.register(new Caller("ops-admin", Map.of("role", "ops")));
        callerStore.register(new Caller("guest", Map.of("role", "guest")));
        policyStore.replace(List.of(
                new PolicyRule("allow-app", null, "secret.dev.*",
                        Map.of("role", "app"), PolicyRule.Effect.ALLOW, 100),
                new PolicyRule("allow-ops", null, "*",
                        Map.of("role", "ops"), PolicyRule.Effect.ALLOW, 200)));
        PolicyEngine engine = new PolicyEngine(policyStore);
        leaseService = new LeaseService(leaseStore, secretStore, callerStore,
                policyStore, engine, auditLog, clock);
        secretService = new SecretService(secretStore, leaseService, callerStore,
                engine, auditLog, clock);
    }

    @Test
    @DisplayName("SOFT 轮换：旧租约继续读旧版本，新租约读新版本")
    void softRotationKeepsOldLeaseBoundToOldVersion() {
        Lease oldLease = leaseService.issue("app-reader", PATH, TTL, null);
        var before = secretService.readWithLease("app-reader", oldLease.id());
        log.info("[case] soft-before version={} value={}", before.version(), before.value());
        assertThat(before.version()).isOne();

        var rotation = secretService.rotate("ops-admin", PATH, "v2-secret", RotationMode.SOFT);
        log.info("[case] soft-rotate newVersion={} revoked={}", rotation.newVersion(),
                rotation.revokedLeaseCount());
        assertThat(rotation.newVersion()).isEqualTo(2L);
        assertThat(rotation.revokedLeaseCount()).isZero();

        var oldRead = secretService.readWithLease("app-reader", oldLease.id());
        log.info("[case] soft-after-old-lease version={}", oldRead.version());
        assertThat(oldRead.version()).isOne();
        assertThat(oldRead.value()).contains("#1");
    }

    @Test
    @DisplayName("HARD 轮换：旧租约立即 LEASE_REVOKED；重新签发后读到新版本")
    void hardRotationRevokesOldLeases() {
        Lease oldLease = leaseService.issue("app-reader", PATH, TTL, null);
        var rotation = secretService.rotate("ops-admin", PATH, "v2-secret", RotationMode.HARD);
        log.info("[case] hard-rotate newVersion={} revoked={}", rotation.newVersion(),
                rotation.revokedLeaseCount());
        assertThat(rotation.newVersion()).isEqualTo(2L);
        assertThat(rotation.revokedLeaseCount()).isOne();

        assertThatThrownBy(() -> secretService.readWithLease("app-reader", oldLease.id()))
                .isInstanceOf(BrokerException.class)
                .satisfies(ex -> {
                    ErrorCode code = ((BrokerException) ex).errorCode();
                    log.info("[case] hard-after-old-read errorCode={}", code);
                    assertThat(code).isEqualTo(ErrorCode.LEASE_REVOKED);
                });

        Lease newLease = leaseService.issue("app-reader", PATH, TTL, "post-rotation");
        var read = secretService.readWithLease("app-reader", newLease.id());
        log.info("[case] hard-new-lease version={} value={}", read.version(), read.value());
        assertThat(read.version()).isEqualTo(2L);
        assertThat(read.value()).isEqualTo("v2-secret");
    }

    @Test
    @DisplayName("越权轮换被拒绝：guest 无 ROTATE 权限 -> POLICY_DENIED")
    void rotateByUnauthorizedCallerDenied() {
        leaseService.issue("app-reader", PATH, TTL, null);
        assertThatThrownBy(() -> secretService.rotate("guest", PATH, "x", RotationMode.SOFT))
                .extracting(e -> ((BrokerException) e).errorCode())
                .isEqualTo(ErrorCode.POLICY_DENIED);
    }

    @Test
    @DisplayName("读取不存在的密钥路径（无租约场景之外）给出 SECRET_NOT_FOUND 的前置校验")
    void rotateUnknownPathRejected() {
        assertThatThrownBy(() -> secretService.rotate("ops-admin", "secret/nope", "v", RotationMode.SOFT))
                .extracting(e -> ((BrokerException) e).errorCode())
                .isEqualTo(ErrorCode.SECRET_NOT_FOUND);
    }
}
