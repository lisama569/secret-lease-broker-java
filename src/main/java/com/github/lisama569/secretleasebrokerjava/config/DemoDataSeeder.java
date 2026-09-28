package com.github.lisama569.secretleasebrokerjava.config;

import com.github.lisama569.secretleasebrokerjava.model.Action;
import com.github.lisama569.secretleasebrokerjava.model.Caller;
import com.github.lisama569.secretleasebrokerjava.model.Effect;
import com.github.lisama569.secretleasebrokerjava.model.PolicyRule;
import com.github.lisama569.secretleasebrokerjava.service.AdminService;
import com.github.lisama569.secretleasebrokerjava.store.CallerDirectory;
import com.github.lisama569.secretleasebrokerjava.store.SecretStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.ApplicationArguments;
import org.springframework.context.annotation.Configuration;

import java.util.List;
import java.util.Map;

/**
 * 本地演示数据（仅进程内模拟，无真实凭据）：
 * - caller alice（role=payments, env=prod）：可对 secret/payments/ 申请与续租；
 * - caller bob（role=readonly, env=prod）：无任何 ALLOW 规则，默认拒绝；
 * - 一条 DENY 规则演示冲突优先级（数值更小者胜）。
 * 可通过 lease.demo-seed=false 关闭。
 */
@Configuration
public class DemoDataSeeder implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(DemoDataSeeder.class);

    private final LeaseProperties leaseProperties;
    private final CallerDirectory callers;
    private final SecretStore secrets;
    private final AdminService adminService;

    public DemoDataSeeder(LeaseProperties leaseProperties, CallerDirectory callers,
                          SecretStore secrets, AdminService adminService) {
        this.leaseProperties = leaseProperties;
        this.callers = callers;
        this.secrets = secrets;
        this.adminService = adminService;
    }

    @Override
    public void run(ApplicationArguments args) {
        // 设置系统属性 -Dlease.demo-seed=false 可关闭演示数据
        if (Boolean.getBoolean("lease.demo-seed.disabled")) {
            return;
        }
        callers.register(new Caller("alice",
                Map.of("role", "payments", "env", "prod", "team", "checkout")));
        callers.register(new Caller("bob",
                Map.of("role", "readonly", "env", "prod", "team", "audit")));

        secrets.createIfAbsent("secret/payments/db");

        PolicyRule allowIssue = new PolicyRule("allow-payments-issue", 100, Effect.ALLOW,
                Action.ISSUE, "secret/payments/",
                Map.of("role", "payments", "env", "prod"));
        PolicyRule allowRenew = new PolicyRule("allow-payments-renew", 100, Effect.ALLOW,
                Action.RENEW, "secret/payments/",
                Map.of("role", "payments", "env", "prod"));
        // 高优先级 DENY：演示即使存在 ALLOW，更具体/更高优先级的 DENY 也会胜出
        PolicyRule denyCheckoutIssue = new PolicyRule("deny-checkout-issue-demo", 10, Effect.DENY,
                Action.ISSUE, "secret/payments/",
                Map.of("role", "payments", "env", "prod", "team", "NEVER_MATCHES"));
        long version = adminService.replacePolicy(
                List.of(denyCheckoutIssue, allowIssue, allowRenew));
        log.info("演示数据已播种：callers=alice,bob；密钥=secret/payments/db；策略版本={}", version);
    }
}
