package com.github.lisama569.secretleasebrokerjava.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.github.lisama569.secretleasebrokerjava.model.AccessAction;
import com.github.lisama569.secretleasebrokerjava.model.Caller;
import com.github.lisama569.secretleasebrokerjava.model.PolicyDecision;
import com.github.lisama569.secretleasebrokerjava.model.PolicyRule;
import com.github.lisama569.secretleasebrokerjava.store.PolicyStore;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 策略模型关键性质：默认拒绝、冲突优先级确定、属性匹配、缓存随策略版本失效。
 */
class PolicyEngineTest {

    private static final Logger log = LoggerFactory.getLogger(PolicyEngineTest.class);

    private final PolicyStore store = new PolicyStore();
    private final PolicyEngine engine = new PolicyEngine(store);

    private final Caller appReader = new Caller("app-reader",
            Map.of("role", "app", "team", "payments"));
    private final Caller appLegacy = new Caller("app-legacy",
            Map.of("role", "app", "team", "legacy"));
    private final Caller guest = new Caller("outsider", Map.of("role", "guest"));

    private void installAppPolicy() {
        store.replace(List.of(
                new PolicyRule("allow-app-dev", null, "secret.dev.*",
                        Map.of("role", "app"), PolicyRule.Effect.ALLOW, 100),
                new PolicyRule("deny-legacy", null, "secret.dev.*",
                        Map.of("role", "app", "team", "legacy"), PolicyRule.Effect.DENY, 10),
                new PolicyRule("allow-ops", null, "*",
                        Map.of("role", "ops"), PolicyRule.Effect.ALLOW, 200)));
    }

    @Test
    @DisplayName("默认拒绝：无任何规则匹配时拒绝并标记 DEFAULT_DENY")
    void defaultDenyWhenNoRuleMatches() {
        store.replace(List.of());
        PolicyDecision d = engine.decide(guest, "secret.dev.db", AccessAction.ISSUE);
        log.info("[case] default-deny decision={}", d);
        assertThat(d.allowed()).isFalse();
        assertThat(d.ruleId()).isEqualTo(PolicyEngine.DEFAULT_DENY_RULE);
        assertThat(d.trace()).anyMatch(t -> t.contains("默认拒绝"));
    }

    @Test
    @DisplayName("显式 ALLOW：属性满足时放行并给出命中规则")
    void explicitAllowWithRuleEvidence() {
        installAppPolicy();
        PolicyDecision d = engine.decide(appReader, "secret.dev.db", AccessAction.ISSUE);
        log.info("[case] allow request caller={} path={} action={} decision rule={} allowed={} trace={}",
                appReader.id(), "secret.dev.db", AccessAction.ISSUE, d.ruleId(), d.allowed(), d.trace());
        assertThat(d.allowed()).isTrue();
        assertThat(d.ruleId()).isEqualTo("allow-app-dev");
        assertThat(d.policyVersion()).isOne();
    }

    @Test
    @DisplayName("冲突优先级：priority 数值小的 DENY 先于 ALLOW 生效")
    void denyRuleWithHigherPriorityWins() {
        installAppPolicy();
        PolicyDecision d = engine.decide(appLegacy, "secret.dev.db", AccessAction.READ);
        log.info("[case] conflict request caller={} attrs={} -> rule={} allowed={} trace={}",
                appLegacy.id(), appLegacy.attributes(), d.ruleId(), d.allowed(), d.trace());
        assertThat(d.allowed()).isFalse();
        assertThat(d.ruleId()).isEqualTo("deny-legacy");
        // 依据中应记录被高优先级 DENY 命中
        assertThat(d.trace()).anyMatch(t -> t.contains("deny-legacy") && t.contains("DENY"));
    }

    @Test
    @DisplayName("同优先级时按 ruleId 字典序裁决，结论确定可复现")
    void samePriorityResolvedByRuleId() {
        store.replace(List.of(
                new PolicyRule("rule-b", AccessAction.ISSUE, "p/*",
                        Map.of(), PolicyRule.Effect.ALLOW, 5),
                new PolicyRule("rule-a", AccessAction.ISSUE, "p/*",
                        Map.of(), PolicyRule.Effect.DENY, 5)));
        PolicyDecision d1 = engine.decide(appReader, "p/x", AccessAction.ISSUE);
        PolicyDecision d2 = engine.decide(appReader, "p/x", AccessAction.ISSUE);
        log.info("[case] same-priority rule={} trace={}", d1.ruleId(), d1.trace());
        assertThat(d1.ruleId()).isEqualTo("rule-a");
        assertThat(d1.allowed()).isFalse();
        assertThat(d2.ruleId()).isEqualTo(d1.ruleId());
    }

    @Test
    @DisplayName("缓存命中后策略更新：旧版本缓存不再生效，按新版本重新判定")
    void cacheInvalidatedAfterPolicyUpdate() {
        installAppPolicy();
        PolicyDecision before = engine.decide(appReader, "secret.dev.db", AccessAction.ISSUE);
        assertThat(before.allowed()).isTrue();

        // 收紧策略：移除 allow-app-dev，再判定必须立即被默认拒绝
        store.replace(List.of(
                new PolicyRule("deny-legacy", null, "secret.dev.*",
                        Map.of("role", "app", "team", "legacy"), PolicyRule.Effect.DENY, 10)));
        PolicyDecision after = engine.decide(appReader, "secret.dev.db", AccessAction.ISSUE);
        log.info("[case] policy-update beforeRule={} afterRule={} afterAllowed={} version={}",
                before.ruleId(), after.ruleId(), after.allowed(), after.policyVersion());
        assertThat(after.allowed()).isFalse();
        assertThat(after.ruleId()).isEqualTo(PolicyEngine.DEFAULT_DENY_RULE);
        assertThat(after.policyVersion()).isGreaterThan(before.policyVersion());
    }

    @Test
    @DisplayName("action 精确匹配：仅放行 READ 时 ISSUE 被默认拒绝")
    void actionSpecificRuleDoesNotMatchOtherActions() {
        store.replace(List.of(
                new PolicyRule("read-only", AccessAction.READ, "secret.prod.*",
                        Map.of("role", "app"), PolicyRule.Effect.ALLOW, 1)));
        PolicyDecision read = engine.decide(appReader, "secret.prod.x", AccessAction.READ);
        PolicyDecision issue = engine.decide(appReader, "secret.prod.x", AccessAction.ISSUE);
        log.info("[case] action-specific readRule={} issueRule={}", read.ruleId(), issue.ruleId());
        assertThat(read.allowed()).isTrue();
        assertThat(issue.allowed()).isFalse();
        assertThat(issue.ruleId()).isEqualTo(PolicyEngine.DEFAULT_DENY_RULE);
    }
}
