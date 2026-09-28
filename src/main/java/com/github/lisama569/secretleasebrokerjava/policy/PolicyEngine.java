package com.github.lisama569.secretleasebrokerjava.policy;

import com.github.lisama569.secretleasebrokerjava.clock.TimeSource;
import com.github.lisama569.secretleasebrokerjava.model.Action;
import com.github.lisama569.secretleasebrokerjava.model.Caller;
import com.github.lisama569.secretleasebrokerjava.model.Decision;
import com.github.lisama569.secretleasebrokerjava.model.Effect;
import com.github.lisama569.secretleasebrokerjava.model.PolicyRule;
import com.github.lisama569.secretleasebrokerjava.service.AuditLog;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 基于身份属性的策略判定引擎。
 *
 * 判定模型（默认拒绝）：
 * 1. 只在规则的 action 匹配（规则 action 为 null 表示通配）、secretPath 命中 pathPrefix、
 *    调用方满足全部属性等值约束时，规则才算命中；
 * 2. 命中规则按 (priority 升序, DENY 先于 ALLOW, ruleId 字典序) 排序，第一条决定结论 ——
 *    冲突时优先级唯一且确定，可复现；
 * 3. 没有任何规则命中即默认拒绝。
 *
 * 缓存：以策略版本为键。策略整体替换会产生新版本与新的缓存表，旧版本缓存整体废弃、
 * 不可能再影响新判定（"策略更新后旧判定缓存不继续生效"）。
 */
@Component
public class PolicyEngine {

    private static final Logger log = LoggerFactory.getLogger(PolicyEngine.class);

    private record CacheKey(Action action, String callerId, String secretPath, Map<String, String> attrs) {
    }

    private record VersionedCache(long version, ConcurrentHashMap<CacheKey, Decision> decisions) {
        VersionedCache(long version) {
            this(version, new ConcurrentHashMap<>());
        }
    }

    private static final Comparator<PolicyRule> RULE_ORDER = Comparator
            .comparingInt(PolicyRule::priority)
            .thenComparing(r -> r.effect() == Effect.DENY ? 0 : 1)
            .thenComparing(PolicyRule::ruleId);

    private final InMemoryPolicyStore policyStore;
    private final AuditLog auditLog;
    private final TimeSource timeSource;

    private volatile VersionedCache cache = new VersionedCache(0);

    public PolicyEngine(InMemoryPolicyStore policyStore, AuditLog auditLog, TimeSource timeSource) {
        this.policyStore = policyStore;
        this.auditLog = auditLog;
        this.timeSource = timeSource;
    }

    /**
     * 判定一次访问。纯只读操作：不抛业务异常，结论总是包含命中依据与原因。
     */
    public Decision evaluate(Caller caller, String secretPath, Action action) {
        long storeVersion = policyStore.version();
        VersionedCache current = cache;
        // 策略版本一旦变化，整个旧缓存表被丢弃，旧判定绝不会串到新版本
        if (current.version() != storeVersion) {
            synchronized (this) {
                if (cache.version() != storeVersion) {
                    cache = new VersionedCache(storeVersion);
                }
                current = cache;
            }
        }

        CacheKey key = new CacheKey(action, caller.id(), secretPath, caller.attributes());
        Decision decision = current.decisions().computeIfAbsent(key,
                k -> decide(policyStore.currentRules(), caller, secretPath, action, storeVersion));

        log.info("policy request: caller={} attrs={} path={} action={} policyVersion={} -> allowed={} rule={} reason={}",
                caller.id(), caller.attributes(), secretPath, action, decision.policyVersion(),
                decision.allowed(), decision.matchedRuleId(), decision.reason());
        if (!decision.allowed()) {
            auditLog.record("DENY", false, caller.id(), null, secretPath,
                    "action=" + action + " rule=" + decision.matchedRuleId()
                            + " policyVersion=" + decision.policyVersion() + " reason=" + decision.reason());
        }
        return decision;
    }

    private Decision decide(List<PolicyRule> rules, Caller caller, String secretPath,
                            Action action, long policyVersion) {
        List<PolicyRule> matched = rules.stream()
                .filter(rule -> actionMatches(rule, action))
                .filter(rule -> pathMatches(rule, secretPath))
                .filter(rule -> attributesMatch(rule, caller))
                .sorted(RULE_ORDER)
                .toList();

        if (matched.isEmpty()) {
            return new Decision(false, action, caller.id(), secretPath, null, null,
                    "默认拒绝：没有命中任何 ALLOW 规则", policyVersion, timeSource.nowMillis());
        }
        PolicyRule winner = matched.get(0);
        boolean allowed = winner.effect() == Effect.ALLOW;
        String reason = (allowed ? "命中最高优先级 ALLOW 规则 " : "命中更高优先级 DENY 规则 ")
                + winner.ruleId() + "（priority=" + winner.priority()
                + "，同级共命中 " + matched.size() + " 条，按 DENY 优先/ruleId 决胜）";
        return new Decision(allowed, action, caller.id(), secretPath, winner.ruleId(),
                winner.effect(), reason, policyVersion, timeSource.nowMillis());
    }

    private static boolean actionMatches(PolicyRule rule, Action action) {
        return rule.action() == null || rule.action() == action;
    }

    private static boolean pathMatches(PolicyRule rule, String secretPath) {
        String prefix = rule.pathPrefix();
        if (prefix == null || prefix.isEmpty()) {
            return true;
        }
        return secretPath.startsWith(prefix);
    }

    private static boolean attributesMatch(PolicyRule rule, Caller caller) {
        for (Map.Entry<String, String> required : rule.attributeConstraints().entrySet()) {
            String actual = caller.attributes().get(required.getKey());
            if (!required.getValue().equals(actual)) {
                return false;
            }
        }
        return true;
    }
}
