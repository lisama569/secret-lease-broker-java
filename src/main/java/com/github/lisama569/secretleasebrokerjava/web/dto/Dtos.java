package com.github.lisama569.secretleasebrokerjava.web.dto;

import com.github.lisama569.secretleasebrokerjava.error.ErrorCode;
import com.github.lisama569.secretleasebrokerjava.error.ServiceException;
import com.github.lisama569.secretleasebrokerjava.model.Action;
import com.github.lisama569.secretleasebrokerjava.model.Effect;
import com.github.lisama569.secretleasebrokerjava.model.PolicyRule;

import java.util.List;
import java.util.Map;

/**
 * REST 层请求/响应载体与转换。
 */
public final class Dtos {

    private Dtos() {
    }

    public record IssueRequest(String callerId, String secretPath, Long ttlMillis) {
    }

    public record RenewRequest(Long ttlMillis) {
    }

    public record RevokeResponse(String leaseId, String status, long revokedAt) {
    }

    public record LeaseView(String leaseId, String callerId, String secretPath, int secretVersion,
                            long issuedAt, long expiresAt, int renewCount, String status) {
    }

    /** 取用密钥材料的响应：同时返回租约绑定版本与当前最新版本，直观展示轮换语义。 */
    public record MaterialResponse(String leaseId, String secretPath, int deliveredVersion,
                                   int latestVersion, String material) {
    }

    public record CallerRequest(String callerId, Map<String, String> attributes) {
    }

    public record RuleBody(String ruleId, Integer priority, Effect effect, String action,
                           String pathPrefix, Map<String, String> attributes) {

        public PolicyRule toRule() {
            if (ruleId == null || ruleId.isBlank()) {
                throw new ServiceException(ErrorCode.INVALID_PARAMETER, "ruleId 不能为空");
            }
            if (effect == null) {
                throw new ServiceException(ErrorCode.INVALID_PARAMETER,
                        "effect 不能为空（ALLOW/DENY）: " + ruleId);
            }
            Action parsed = null;
            if (action != null && !action.isBlank()) {
                try {
                    parsed = Action.valueOf(action.trim().toUpperCase());
                } catch (IllegalArgumentException ex) {
                    throw new ServiceException(ErrorCode.INVALID_PARAMETER,
                            "action 只能为 ISSUE 或 RENEW: " + action);
                }
            }
            int p = priority == null ? 100 : priority;
            return new PolicyRule(ruleId, p, effect, parsed, pathPrefix,
                    attributes == null ? Map.of() : attributes);
        }
    }

    public record PolicyReplaceRequest(List<RuleBody> rules) {
    }

    public record PolicyView(long version, List<RuleBody> rules) {
    }

    public record AuditEventView(long sequence, long timestamp, String eventType, boolean success,
                                 String callerId, String leaseId, String secretPath,
                                 String detail) {
    }

    public record ErrorResponse(String errorCode, String message) {
    }

    public static RuleBody fromRule(PolicyRule rule) {
        return new RuleBody(rule.ruleId(), rule.priority(), rule.effect(),
                rule.action() == null ? null : rule.action().name(),
                rule.pathPrefix(), rule.attributeConstraints());
    }
}
