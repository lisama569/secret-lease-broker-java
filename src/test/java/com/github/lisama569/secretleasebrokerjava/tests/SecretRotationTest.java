package com.github.lisama569.secretleasebrokerjava.tests;

import com.github.lisama569.secretleasebrokerjava.error.ErrorCode;
import com.github.lisama569.secretleasebrokerjava.error.ServiceException;
import com.github.lisama569.secretleasebrokerjava.model.Action;
import com.github.lisama569.secretleasebrokerjava.model.Caller;
import com.github.lisama569.secretleasebrokerjava.model.Effect;
import com.github.lisama569.secretleasebrokerjava.model.Lease;
import com.github.lisama569.secretleasebrokerjava.model.PolicyRule;
import com.github.lisama569.secretleasebrokerjava.model.SecretVersion;
import com.github.lisama569.secretleasebrokerjava.service.AdminService;
import com.github.lisama569.secretleasebrokerjava.service.LeaseService;
import com.github.lisama569.secretleasebrokerjava.store.CallerDirectory;
import com.github.lisama569.secretleasebrokerjava.store.SecretStore;
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
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 轮换语义：旧租约绑定旧版本、新租约绑定新版本，互不漂移。
 */
@SpringBootTest
@Import(TestConfig.class)
class SecretRotationTest {

    private static final Logger log = LoggerFactory.getLogger(SecretRotationTest.class);

    @Autowired LeaseService leaseService;
    @Autowired AdminService adminService;
    @Autowired SecretStore secretStore;
    @Autowired CallerDirectory callers;
    @Autowired MutableTimeSource clock;

    /** 每个用例独立路径，彻底避免共享同一 Spring 上下文时的版本链相互污染。 */
    private String path;

    @BeforeEach
    void seed() {
        path = "secret/rotation/db-" + System.nanoTime();
        callers.register(new Caller("alice", Map.of("role", "payments", "env", "prod")));
        adminService.replacePolicy(List.of(
                new PolicyRule("allow-issue", 100, Effect.ALLOW, Action.ISSUE,
                        "secret/rotation/", Map.of("role", "payments", "env", "prod")),
                new PolicyRule("allow-renew", 100, Effect.ALLOW, Action.RENEW,
                        "secret/rotation/", Map.of("role", "payments", "env", "prod"))));
    }

    @Test
    @DisplayName("轮换后：旧 ACTIVE 租约仍稳定交付旧版本材料；新租约交付新版本")
    void oldLeaseStaysOnOldVersion() {
        Lease oldLease = leaseService.issue("alice", path, 600_000L);
        SecretVersion v1 = leaseService.fetchMaterial(oldLease.id());
        assertEquals(1, v1.version());

        SecretVersion rotated = adminService.rotateSecret(path);
        assertEquals(2, rotated.version());

        // 旧租约仍 ACTIVE，且拿到的仍是版本 1 的材料（版本不漂移）
        SecretVersion oldView = leaseService.fetchMaterial(oldLease.id());
        log.info("[轮换] 旧租约={} 交付版本={}（最新版本=2）", oldLease.id(), oldView.version());
        assertEquals(1, oldView.version());
        assertEquals(v1.material(), oldView.material());

        // 旧租约仍可续租，且续租不改绑定版本
        Lease renewed = leaseService.renew(oldLease.id(), 600_000L);
        assertEquals(1, renewed.secretVersion());
        assertEquals(1, leaseService.fetchMaterial(oldLease.id()).version());

        // 重新签发才能拿到新版本
        Lease newLease = leaseService.issue("alice", path, 600_000L);
        SecretVersion newView = leaseService.fetchMaterial(newLease.id());
        log.info("[轮换] 新租约={} 交付版本={}", newLease.id(), newView.version());
        assertEquals(2, newView.version());
        assertNotEquals(v1.material(), newView.material());
    }

    @Test
    @DisplayName("轮换不影响吊销与过期语义：旧租约被吊销后立即不可取用")
    void rotationDoesNotInterfereWithRevocation() {
        Lease lease = leaseService.issue("alice", path, 1_000L);
        adminService.rotateSecret(path);
        leaseService.revoke(lease.id());
        ServiceException ex = assertThrows(ServiceException.class,
                () -> leaseService.fetchMaterial(lease.id()));
        assertEquals(ErrorCode.LEASE_NOT_ACTIVE, ex.getCode());
    }

    @Test
    @DisplayName("多次轮换版本号严格单调递增且材料各不相同")
    void versionsAreMonotonicAndDistinct() {
        adminService.rotateSecret(path); // v1（路径尚未签发过）
        SecretVersion v2 = adminService.rotateSecret(path);
        SecretVersion v3 = adminService.rotateSecret(path);
        assertEquals(2, v2.version());
        assertEquals(3, v3.version());
        assertNotEquals(v2.material(), v3.material());
        assertEquals(3, secretStore.findLatest(path).orElseThrow().version());
    }
}
