package com.github.lisama569.secretleasebrokerjava.tests;

import com.github.lisama569.secretleasebrokerjava.model.Action;
import com.github.lisama569.secretleasebrokerjava.model.Caller;
import com.github.lisama569.secretleasebrokerjava.model.Decision;
import com.github.lisama569.secretleasebrokerjava.model.Effect;
import com.github.lisama569.secretleasebrokerjava.model.PolicyRule;
import com.github.lisama569.secretleasebrokerjava.policy.PolicyEngine;
import com.github.lisama569.secretleasebrokerjava.service.AdminService;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 策略模型：默认拒绝、确定性冲突优先级、属性匹配、更新即缓存失效。
 */
@SpringBootTest
@Import(TestConfig.class)
class PolicyEngineTest {

    private static final Logger log = LoggerFactory.getLogger(PolicyEngineTest.class);

    @Autowired PolicyEngine engine;
    @Autowired AdminService adminService;
    @Autowired CallerDirectory callers;

    private final Caller alice = new Caller("alice",
            Map.of("role", "payments", "env", "prod", "team", "checkout"));
    private final Caller bob = new Caller("bob", Map.of("role", "billing", "env", "prod"));

    @BeforeEach
    void register() {
        callers.register(alice);
        callers.register(bob);
    }

    private Decision logAndReturn(Caller caller, String path, Action action) {
        Decision d = engine.evaluate(caller, path, action);
        log.info("[判定请求] caller={} attrs={} path={} action={} => allowed={} rule={} reason={}",
                caller.id(), caller.attributes(), path, action, d.allowed(),
                d.matchedRuleId(), d.reason());
        return d;
    }

    @Test
    @DisplayName("空策略默认拒绝，无命中规则")
    void defaultDeny() {
        adminService.replacePolicy(List.of());
        Decision d = logAndReturn(alice, "secret/payments/db", Action.ISSUE);
        assertFalse(d.allowed());
        assertNull(d.matchedRuleId());
    }

    @Test
    @DisplayName("显式 ALLOW 才放行，并返回命中规则 id 作为依据")
    void explicitAllowWins() {
        adminService.replacePolicy(List.of(
                new PolicyRule("allow-payments", 100, Effect.ALLOW, Action.ISSUE,
                        "secret/payments/", Map.of("role", "payments", "env", "prod"))));
        Decision yes = logAndReturn(alice, "secret/payments/db", Action.ISSUE);
        assertTrue(yes.allowed());
        assertEquals("allow-payments", yes.matchedRuleId());

        Decision no = logAndReturn(bob, "secret/payments/db", Action.ISSUE);
        assertFalse(no.allowed());
        assertNull(no.matchedRuleId(), "属性不满足任何规则，仍默认拒绝");
    }

    @Test
    @DisplayName("冲突时 priority 数值小的规则优先；同优先级 DENY 优先；再相同按 ruleId 决胜")
    void deterministicPrecedence() {
        adminService.replacePolicy(List.of(
                // 两条都命中 alice：priority 相同的 ALLOW 与 DENY，DENY 应胜出
                new PolicyRule("z-allow", 100, Effect.ALLOW, Action.ISSUE,
                        "secret/payments/", Map.of("role", "payments")),
                new PolicyRule("a-deny", 100, Effect.DENY, Action.ISSUE,
                        "secret/payments/", Map.of("role", "payments"))));
        Decision denied = logAndReturn(alice, "secret/payments/db", Action.ISSUE);
        assertFalse(denied.allowed());
        assertEquals("a-deny", denied.matchedRuleId());

        // 再加一条数值优先级更高（数字更小）的 ALLOW，它应压过同/低优先级 DENY
        adminService.replacePolicy(List.of(
                new PolicyRule("z-allow", 100, Effect.ALLOW, Action.ISSUE,
                        "secret/payments/", Map.of("role", "payments")),
                new PolicyRule("a-deny", 100, Effect.DENY, Action.ISSUE,
                        "secret/payments/", Map.of("role", "payments")),
                new PolicyRule("top-allow", 50, Effect.ALLOW, Action.ISSUE,
                        "secret/payments/", Map.of("role", "payments"))));
        Decision allowed = logAndReturn(alice, "secret/payments/db", Action.ISSUE);
        assertTrue(allowed.allowed());
        assertEquals("top-allow", allowed.matchedRuleId());
    }

    @Test
    @DisplayName("策略更新后旧判定缓存立即失效：同一请求的结论随新版本改变")
    void cacheInvalidatedOnPolicyUpdate() {
        adminService.replacePolicy(List.of(
                new PolicyRule("allow-payments", 100, Effect.ALLOW, Action.ISSUE,
                        "secret/payments/", Map.of("role", "payments"))));
        Decision before = logAndReturn(alice, "secret/payments/db", Action.ISSUE);
        assertTrue(before.allowed());
        long v1 = before.policyVersion();

        // 更新为仅 DENY（默认拒绝也可以，这里用显式 DENY 以拿到命中依据）
        adminService.replacePolicy(List.of(
                new PolicyRule("deny-all-payments", 10, Effect.DENY, Action.ISSUE,
                        "secret/payments/", Map.of())));
        Decision after = logAndReturn(alice, "secret/payments/db", Action.ISSUE);
        assertFalse(after.allowed(), "旧 ALLOW 缓存不得在策略更新后继续生效");
        assertEquals("deny-all-payments", after.matchedRuleId());
        assertTrue(after.policyVersion() > v1, "判定必须基于更新后的策略版本");
    }

    @Test
    @DisplayName("action 与路径前缀均参与匹配：ISSUE 允许不代表 RENEW 允许")
    void actionAndPathScoping() {
        adminService.replacePolicy(List.of(
                new PolicyRule("issue-only", 100, Effect.ALLOW, Action.ISSUE,
                        "secret/payments/", Map.of("role", "payments"))));
        assertTrue(logAndReturn(alice, "secret/payments/db", Action.ISSUE).allowed());
        Decision renew = logAndReturn(alice, "secret/payments/db", Action.RENEW);
        assertFalse(renew.allowed());
        Decision otherPath = logAndReturn(alice, "secret/other/x", Action.ISSUE);
        assertFalse(otherPath.allowed());
    }
}
