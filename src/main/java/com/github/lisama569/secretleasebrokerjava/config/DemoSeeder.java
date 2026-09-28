package com.github.lisama569.secretleasebrokerjava.config;

import com.github.lisama569.secretleasebrokerjava.model.AccessAction;
import com.github.lisama569.secretleasebrokerjava.model.Caller;
import com.github.lisama569.secretleasebrokerjava.model.PolicyRule;
import com.github.lisama569.secretleasebrokerjava.store.CallerStore;
import com.github.lisama569.secretleasebrokerjava.store.PolicyStore;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * 启动时写入演示用调用方与策略，方便本地直接验证。
 *
 * 演示身份：
 *  app-reader   role=app     可申请/续租 dev 路径租约并读取
 *  app-legacy   role=app     仅旧属性集，用于演示策略收紧后旧判定缓存失效
 *  ops-admin    role=ops     可吊销、可轮换、可读 prod 路径
 *  outsider     role=guest   无任何匹配规则 -> 默认拒绝
 *
 * 策略规则中包含同路径不同优先级的 ALLOW/DENY，用于演示冲突时的确定优先级。
 */
@Component
public class DemoSeeder {

    private static final Logger log = LoggerFactory.getLogger(DemoSeeder.class);

    private final CallerStore callerStore;
    private final PolicyStore policyStore;

    public DemoSeeder(CallerStore callerStore, PolicyStore policyStore) {
        this.callerStore = callerStore;
        this.policyStore = policyStore;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void seed() {
        callerStore.register(new Caller("app-reader",
                Map.of("role", "app", "env", "dev", "team", "payments")));
        callerStore.register(new Caller("app-legacy",
                Map.of("role", "app", "env", "dev", "team", "legacy")));
        callerStore.register(new Caller("ops-admin",
                Map.of("role", "ops", "env", "prod")));
        callerStore.register(new Caller("outsider",
                Map.of("role", "guest")));

        List<PolicyRule> rules = List.of(
                // 低优先级（数值大）：app 角色可访问 secret.dev.**
                new PolicyRule("allow-app-dev-any",
                        null, "secret.dev.*",
                        Map.of("role", "app"),
                        PolicyRule.Effect.ALLOW, 100),
                // 高优先级（数值小）的显式 DENY：team=legacy 即使满足上面的条件也被拒绝，
                // 演示多条规则冲突时 (priority, ruleId) 确定序裁决。
                new PolicyRule("deny-legacy-team",
                        null, "secret.dev.*",
                        Map.of("role", "app", "team", "legacy"),
                        PolicyRule.Effect.DENY, 10),
                // ops 对全部路径的完整权限
                new PolicyRule("allow-ops-all",
                        null, "*",
                        Map.of("role", "ops"),
                        PolicyRule.Effect.ALLOW, 200),
                // app 对 prod 只能读（需要已有租约），申请/轮换均默认拒绝
                new PolicyRule("allow-app-prod-read",
                        AccessAction.READ, "secret.prod.*",
                        Map.of("role", "app"),
                        PolicyRule.Effect.ALLOW, 100)
        );
        policyStore.replace(rules);
        log.info("[demo] seeded callers=4, policyVersion={} rules={}",
                policyStore.get().version(), rules.stream().map(PolicyRule::id).toList());
    }
}
