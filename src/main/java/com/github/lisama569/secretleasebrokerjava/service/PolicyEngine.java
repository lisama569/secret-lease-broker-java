package com.github.lisama569.secretleasebrokerjava.service;

import com.github.lisama569.secretleasebrokerjava.model.AccessAction;
import com.github.lisama569.secretleasebrokerjava.model.Caller;
import com.github.lisama569.secretleasebrokerjava.model.PolicyDecision;
import com.github.lisama569.secretleasebrokerjava.model.PolicyRule;
import com.github.lisama569.secretleasebrokerjava.store.PolicyStore;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 基于身份属性的策略判定。
 *
 * 规则模型：
 *  - action（null 表示任意操作）、pathPattern（支持 * 通配）、requiredAttributes（调用方必须全部具备）；
 *  - 默认拒绝：没有任何规则完全匹配时一律拒绝（DEFAULT_DENY）；
 *  - 冲突优先级：按 (priority 升序, ruleId 字典序) 排序，第一个完全匹配的规则生效，结论确定可复现。
 *
 * 缓存：判定结果以 (调用方, 路径, 操作, 属性摘要, 策略版本) 为键缓存；
 * 策略整体替换后版本号 +1，旧版本缓存立即不再被使用。
 */
@Service
public class PolicyEngine {

    private static final Logger log = LoggerFactory.getLogger(PolicyEngine.class);
    public static final String DEFAULT_DENY_RULE = "DEFAULT_DENY";

    private final PolicyStore policyStore;
    private final ConcurrentHashMap<CacheKey, PolicyDecision> cache = new ConcurrentHashMap<>();

    public PolicyEngine(PolicyStore policyStore) {
        this.policyStore = policyStore;
    }

    private record CacheKey(String callerId, String path, AccessAction action,
                            int attrHash, long policyVersion) {
    }

    public PolicyDecision decide(Caller caller, String path, AccessAction action) {
        PolicyStore.PolicySnapshot snapshot = policyStore.get();
        CacheKey key = new CacheKey(caller.id(), path, action, caller.attributes().hashCode(), snapshot.version());

        PolicyDecision cached = cache.get(key);
        if (cached != null) {
            log.info("[policy] request caller={} path={} action={} -> CACHE HIT allowed={} rule={} version={}",
                    caller.id(), path, action, cached.allowed(), cached.ruleId(), snapshot.version());
            return cached;
        }

        List<PolicyRule> ordered = snapshot.rules().stream()
                .sorted(Comparator.comparingInt(PolicyRule::priority)
                        .thenComparing(PolicyRule::id))
                .toList();

        List<String> trace = new ArrayList<>();
        PolicyDecision decision = null;

        for (PolicyRule rule : ordered) {
            boolean actionMatch = rule.action() == null || rule.action() == action;
            boolean pathMatch = matches(rule.pathPattern(), path);
            if (!actionMatch || !pathMatch) {
                continue; // 与本次请求无关的规则不进入判定依据
            }
            boolean attrMatch = attributesSatisfied(rule.requiredAttributes(), caller.attributes());
            if (attrMatch) {
                trace.add(String.format(
                        "rule=%s(priority=%d) 完全命中 action/path/attributes -> %s",
                        rule.id(), rule.priority(), rule.effect()));
                decision = toDecision(rule, snapshot.version(), caller, path, action, trace);
                break;
            }
            trace.add(String.format(
                    "rule=%s(priority=%d) action/path 命中但属性不满足 required=%s actual=%s -> 跳过",
                    rule.id(), rule.priority(), rule.requiredAttributes(), caller.attributes()));
        }

        if (decision == null) {
            trace.add("无任何规则完全匹配 -> 默认拒绝 (default-deny)");
            decision = new PolicyDecision(false, DEFAULT_DENY_RULE,
                    "no explicit allow rule matched; denied by default",
                    snapshot.version(), caller.id(), path, action, List.copyOf(trace));
        }

        cache.put(key, decision);
        log.info("[policy] request caller={} attrs={} path={} action={} version={} -> allowed={} rule={} reason={} trace={}",
                caller.id(), caller.attributes(), path, action, snapshot.version(),
                decision.allowed(), decision.ruleId(), decision.reason(), decision.trace());
        return decision;
    }

    private PolicyDecision toDecision(PolicyRule rule, long version, Caller caller,
                                      String path, AccessAction action, List<String> trace) {
        boolean allowed = rule.effect() == PolicyRule.Effect.ALLOW;
        String reason = allowed
                ? String.format("explicit ALLOW rule '%s' matched", rule.id())
                : String.format("explicit DENY rule '%s' matched", rule.id());
        return new PolicyDecision(allowed, rule.id(), reason, version,
                caller.id(), path, action, List.copyOf(trace));
    }

    static boolean attributesSatisfied(Map<String, String> required, Map<String, String> actual) {
        for (Map.Entry<String, String> e : required.entrySet()) {
            String actualValue = actual.get(e.getKey());
            if (actualValue == null || !actualValue.equals(e.getValue())) {
                return false;
            }
        }
        return true;
    }

    /**
     * 仅支持 * 通配符的轻量路径匹配：* 匹配任意（含空）字符序列。
     */
    static boolean matches(String pattern, String path) {
        StringBuilder regex = new StringBuilder("^");
        for (int i = 0; i < pattern.length(); i++) {
            char c = pattern.charAt(i);
            if (c == '*') {
                regex.append(".*");
            } else {
                regex.append(quoteLiteral(c));
            }
        }
        regex.append('$');
        return path.matches(regex.toString());
    }

    private static String quoteLiteral(char c) {
        if ("\\.[]{}()+-?^$|".indexOf(c) >= 0) {
            return "\\" + c;
        }
        return String.valueOf(c);
    }
}
