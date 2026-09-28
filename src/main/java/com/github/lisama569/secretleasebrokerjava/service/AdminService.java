package com.github.lisama569.secretleasebrokerjava.service;

import com.github.lisama569.secretleasebrokerjava.error.ErrorCode;
import com.github.lisama569.secretleasebrokerjava.error.ServiceException;
import com.github.lisama569.secretleasebrokerjava.model.Effect;
import com.github.lisama569.secretleasebrokerjava.model.PolicyRule;
import com.github.lisama569.secretleasebrokerjava.model.SecretVersion;
import com.github.lisama569.secretleasebrokerjava.policy.PolicyStore;
import com.github.lisama569.secretleasebrokerjava.store.SecretStore;
import org.springframework.stereotype.Component;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 管理面服务：密钥轮换与策略整体替换。
 *
 * 轮换语义（稳定、可解释）：
 * - rotate 原子地为路径产生下一个不可变版本；
 * - 不修改、不废止任何存量租约：旧 ACTIVE 租约继续绑定旧版本直到自然到期或被吊销；
 * - 因此轮换不会"误伤"已有授权，但旧租约永远拿不到新版本材料——调用方必须在策略允许下
 *   重新签发，才能访问新版本。
 */
@Component
public class AdminService {

    private final SecretStore secretStore;
    private final PolicyStore policyStore;
    private final AuditLog auditLog;

    public AdminService(SecretStore secretStore, PolicyStore policyStore, AuditLog auditLog) {
        this.secretStore = secretStore;
        this.policyStore = policyStore;
        this.auditLog = auditLog;
    }

    /**
     * 轮换密钥；路径不存在则创建版本 1。返回新版本。
     */
    public SecretVersion rotateSecret(String secretPath) {
        if (secretPath == null || secretPath.isBlank()) {
            throw new ServiceException(ErrorCode.INVALID_PARAMETER, "secretPath 不能为空");
        }
        SecretVersion created = secretStore.rotate(secretPath);
        auditLog.record("ROTATE", true, null, null, secretPath,
                "新版本=" + created.version() + "（存量租约继续绑定各自版本，不自动迁移）");
        return created;
    }

    /**
     * 整体替换策略规则并返回新版本号。版本号单调递增，判定引擎据此整体作废旧缓存。
     */
    public long replacePolicy(List<PolicyRule> rules) {
        validate(rules);
        long version = policyStore.replaceRules(rules);
        auditLog.record("POLICY_UPDATE", true, null, null, null,
                "新版本=" + version + " 规则数=" + rules.size()
                        + "；旧版本判定缓存整体失效，默认拒绝，仅显式 ALLOW 放行");
        return version;
    }

    private void validate(List<PolicyRule> rules) {
        if (rules == null) {
            throw new ServiceException(ErrorCode.INVALID_PARAMETER, "rules 不能为 null");
        }
        Set<String> ids = new HashSet<>();
        for (PolicyRule rule : rules) {
            if (rule == null || rule.ruleId() == null || rule.ruleId().isBlank()) {
                throw new ServiceException(ErrorCode.INVALID_PARAMETER, "规则缺少 ruleId");
            }
            if (!ids.add(rule.ruleId())) {
                throw new ServiceException(ErrorCode.INVALID_PARAMETER,
                        "ruleId 重复: " + rule.ruleId());
            }
            if (rule.effect() != Effect.ALLOW && rule.effect() != Effect.DENY) {
                throw new ServiceException(ErrorCode.INVALID_PARAMETER,
                        "规则 effect 必须为 ALLOW/DENY: " + rule.ruleId());
            }
            if (rule.pathPrefix() != null && rule.pathPrefix().isBlank()) {
                throw new ServiceException(ErrorCode.INVALID_PARAMETER,
                        "pathPrefix 不能为空串（null 表示通配）: " + rule.ruleId());
            }
        }
    }
}
