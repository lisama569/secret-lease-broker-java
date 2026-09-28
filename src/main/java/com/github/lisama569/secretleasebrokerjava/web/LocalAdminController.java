package com.github.lisama569.secretleasebrokerjava.web;

import com.github.lisama569.secretleasebrokerjava.config.DemoSeeder;
import com.github.lisama569.secretleasebrokerjava.store.AuditLog;
import com.github.lisama569.secretleasebrokerjava.store.CallerStore;
import com.github.lisama569.secretleasebrokerjava.store.LeaseStore;
import com.github.lisama569.secretleasebrokerjava.store.PolicyStore;
import com.github.lisama569.secretleasebrokerjava.store.SecretStore;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 仅用于本地运行与测试隔离的管理端点：清空全部进程内状态并重新播种演示数据。
 * 不涉及任何外部系统。
 */
@RestController
@RequestMapping("/api/local")
public class LocalAdminController {

    private final CallerStore callerStore;
    private final PolicyStore policyStore;
    private final LeaseStore leaseStore;
    private final SecretStore secretStore;
    private final AuditLog auditLog;
    private final DemoSeeder demoSeeder;

    public LocalAdminController(CallerStore callerStore,
                                PolicyStore policyStore,
                                LeaseStore leaseStore,
                                SecretStore secretStore,
                                AuditLog auditLog,
                                DemoSeeder demoSeeder) {
        this.callerStore = callerStore;
        this.policyStore = policyStore;
        this.leaseStore = leaseStore;
        this.secretStore = secretStore;
        this.auditLog = auditLog;
        this.demoSeeder = demoSeeder;
    }

    @PostMapping("/reset")
    public String reset() {
        leaseStore.clear();
        secretStore.clear();
        auditLog.clear();
        callerStore.clear();
        policyStore.reset();
        demoSeeder.seed();
        return "reset-and-reseeded";
    }
}
