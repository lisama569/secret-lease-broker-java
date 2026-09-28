package com.github.lisama569.secretleasebrokerjava.web;

import com.github.lisama569.secretleasebrokerjava.error.BrokerException;
import com.github.lisama569.secretleasebrokerjava.error.ErrorCode;
import com.github.lisama569.secretleasebrokerjava.model.AccessAction;
import com.github.lisama569.secretleasebrokerjava.model.AuditEventType;
import com.github.lisama569.secretleasebrokerjava.model.Caller;
import com.github.lisama569.secretleasebrokerjava.model.Lease;
import com.github.lisama569.secretleasebrokerjava.model.PolicyDecision;
import com.github.lisama569.secretleasebrokerjava.model.PolicyRule;
import com.github.lisama569.secretleasebrokerjava.model.RotationMode;
import com.github.lisama569.secretleasebrokerjava.service.LeaseService;
import com.github.lisama569.secretleasebrokerjava.service.PolicyEngine;
import com.github.lisama569.secretleasebrokerjava.service.SecretService;
import com.github.lisama569.secretleasebrokerjava.store.AuditLog;
import com.github.lisama569.secretleasebrokerjava.store.CallerStore;
import com.github.lisama569.secretleasebrokerjava.store.PolicyStore;
import com.github.lisama569.secretleasebrokerjava.time.TimeSource;
import com.github.lisama569.secretleasebrokerjava.web.dto.AuditEventResponse;
import com.github.lisama569.secretleasebrokerjava.web.dto.DecisionRequest;
import com.github.lisama569.secretleasebrokerjava.web.dto.DecisionResponse;
import com.github.lisama569.secretleasebrokerjava.web.dto.IssueLeaseRequest;
import com.github.lisama569.secretleasebrokerjava.web.dto.LeaseResponse;
import com.github.lisama569.secretleasebrokerjava.web.dto.PolicyReplaceResponse;
import com.github.lisama569.secretleasebrokerjava.web.dto.PolicyRuleRequest;
import com.github.lisama569.secretleasebrokerjava.web.dto.PolicySnapshotResponse;
import com.github.lisama569.secretleasebrokerjava.web.dto.RegisterCallerRequest;
import com.github.lisama569.secretleasebrokerjava.web.dto.RenewLeaseRequest;
import com.github.lisama569.secretleasebrokerjava.web.dto.ReplacePolicyRequest;
import com.github.lisama569.secretleasebrokerjava.web.dto.RevokeLeaseRequest;
import com.github.lisama569.secretleasebrokerjava.web.dto.RotateResponse;
import com.github.lisama569.secretleasebrokerjava.web.dto.RotateSecretRequest;
import com.github.lisama569.secretleasebrokerjava.web.dto.SecretResponse;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 租约、密钥、策略与审计的 HTTP 入口。
 */
@RestController
@RequestMapping("/api")
public class BrokerController {

    private final LeaseService leaseService;
    private final SecretService secretService;
    private final PolicyEngine policyEngine;
    private final PolicyStore policyStore;
    private final CallerStore callerStore;
    private final AuditLog auditLog;
    private final TimeSource timeSource;

    public BrokerController(LeaseService leaseService,
                            SecretService secretService,
                            PolicyEngine policyEngine,
                            PolicyStore policyStore,
                            CallerStore callerStore,
                            AuditLog auditLog,
                            TimeSource timeSource) {
        this.leaseService = leaseService;
        this.secretService = secretService;
        this.policyEngine = policyEngine;
        this.policyStore = policyStore;
        this.callerStore = callerStore;
        this.auditLog = auditLog;
        this.timeSource = timeSource;
    }

    // ---------------- 调用方管理（本地模拟身份源） ----------------

    @PostMapping("/callers")
    public String registerCaller(@RequestBody RegisterCallerRequest req) {
        if (req == null || req.callerId() == null || req.callerId().isBlank()) {
            throw new BrokerException(ErrorCode.INVALID_PARAM, "callerId must not be blank");
        }
        callerStore.register(new Caller(req.callerId(), req.attributes()));
        return "registered:" + req.callerId();
    }

    // ---------------- 租约生命周期 ----------------

    @PostMapping("/leases")
    public LeaseResponse issue(@RequestBody IssueLeaseRequest req) {
        if (req == null || req.callerId() == null || req.path() == null) {
            throw new BrokerException(ErrorCode.INVALID_PARAM, "callerId and path are required");
        }
        long ttl = req.ttlMillis() == null ? 60_000L : req.ttlMillis();
        Lease lease = leaseService.issue(req.callerId(), req.path(), ttl, req.idempotencyKey());
        return toLeaseResponse(lease);
    }

    @PostMapping("/leases/{leaseId}/renew")
    public LeaseResponse renew(@PathVariable String leaseId, @RequestBody RenewLeaseRequest req) {
        if (req == null || req.callerId() == null) {
            throw new BrokerException(ErrorCode.INVALID_PARAM, "callerId is required");
        }
        long ttl = req.ttlMillis() == null ? 60_000L : req.ttlMillis();
        Lease lease = leaseService.renew(req.callerId(), parseLeaseId(leaseId), ttl);
        return toLeaseResponse(lease);
    }

    @PostMapping("/leases/{leaseId}/revoke")
    public LeaseResponse revoke(@PathVariable String leaseId, @RequestBody RevokeLeaseRequest req) {
        if (req == null || req.callerId() == null) {
            throw new BrokerException(ErrorCode.INVALID_PARAM, "callerId is required");
        }
        String reason = req.reason() == null ? "user revoke" : req.reason();
        Lease lease = leaseService.revoke(req.callerId(), parseLeaseId(leaseId), reason);
        return toLeaseResponse(lease);
    }

    // ---------------- 密钥读取与轮换 ----------------

    @GetMapping("/secrets/{path}")
    public SecretResponse read(@PathVariable String path,
                               @RequestParam String callerId,
                               @RequestParam String leaseId) {
        var access = secretService.readWithLease(callerId, parseLeaseId(leaseId));
        return new SecretResponse(access.path(), access.version(), access.value(),
                access.leaseId().toString());
    }

    @PostMapping("/secrets/{path}/rotate")
    public RotateResponse rotate(@PathVariable String path, @RequestBody RotateSecretRequest req) {
        if (req == null || req.callerId() == null) {
            throw new BrokerException(ErrorCode.INVALID_PARAM, "callerId is required");
        }
        RotationMode mode = parseMode(req.mode());
        String value = req.value() == null ? "rotated-" + System.nanoTime() : req.value();
        var result = secretService.rotate(req.callerId(), path, value, mode);
        return new RotateResponse(result.path(), result.newVersion(),
                result.mode().name(), result.revokedLeaseCount());
    }

    // ---------------- 策略管理与判定 ----------------

    @PostMapping("/policy/replace")
    public PolicyReplaceResponse replacePolicy(@RequestBody ReplacePolicyRequest req) {
        if (req == null || req.rules() == null || req.rules().isEmpty()) {
            throw new BrokerException(ErrorCode.RULES_EMPTY, "at least one rule is required");
        }
        List<PolicyRule> rules = req.rules().stream().map(BrokerController::toRule).toList();
        HashSet<String> ids = new HashSet<>();
        for (PolicyRule r : rules) {
            if (!ids.add(r.id())) {
                throw new BrokerException(ErrorCode.RULE_INVALID, "duplicate rule id: " + r.id());
            }
        }
        var snapshot = policyStore.replace(rules);
        auditLog.append(AuditEventType.POLICY_REPLACE, "system", null, null, "SUCCESS",
                "policy replaced version=" + snapshot.version() + " ruleIds=" + ids,
                System.currentTimeMillis());
        return new PolicyReplaceResponse(snapshot.version(), rules.size(),
                rules.stream().map(PolicyRule::id).toList());
    }

    @GetMapping("/policy")
    public PolicySnapshotResponse getPolicy() {
        var snap = policyStore.get();
        return new PolicySnapshotResponse(snap.version(),
                snap.rules().stream().map(BrokerController::toRuleRequest).toList());
    }

    @PostMapping("/decide")
    public DecisionResponse decide(@RequestBody DecisionRequest req) {
        if (req == null || req.callerId() == null || req.path() == null || req.action() == null) {
            throw new BrokerException(ErrorCode.INVALID_PARAM, "callerId, path, action are required");
        }
        Caller caller = callerStore.find(req.callerId())
                .orElseThrow(() -> new BrokerException(ErrorCode.UNKNOWN_CALLER, req.callerId()));
        PolicyDecision decision = policyEngine.decide(caller, req.path(), parseAction(req.action()));
        return new DecisionResponse(decision.allowed(), decision.ruleId(), decision.reason(),
                decision.policyVersion(), decision.callerId(), decision.path(),
                decision.action().name(), decision.trace());
    }

    // ---------------- 审计 ----------------

    @GetMapping("/audit")
    public List<AuditEventResponse> audit(@RequestParam(required = false) String leaseId) {
        var events = (leaseId == null || leaseId.isBlank())
                ? auditLog.findAll()
                : auditLog.findByLeaseId(leaseId);
        return events.stream().map(e -> new AuditEventResponse(
                e.sequence(), e.timestampMillis(), e.type().name(), e.callerId(),
                e.path(), e.leaseId(), e.result(), e.detail())).toList();
    }

    // ---------------- 映射辅助 ----------------

    private static PolicyRule toRule(PolicyRuleRequest r) {
        if (r == null || r.id() == null || r.id().isBlank()) {
            throw new BrokerException(ErrorCode.RULE_INVALID, "rule id must not be blank");
        }
        AccessAction action = (r.action() == null || r.action().isBlank())
                ? null : parseAction(r.action());
        PolicyRule.Effect effect;
        try {
            effect = PolicyRule.Effect.valueOf(r.effect() == null ? "DENY" : r.effect().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new BrokerException(ErrorCode.RULE_INVALID, "effect must be ALLOW or DENY");
        }
        if (r.pathPattern() == null || r.pathPattern().isBlank()) {
            throw new BrokerException(ErrorCode.RULE_INVALID, "pathPattern must not be blank");
        }
        int priority = r.priority() == null ? 1000 : r.priority();
        return new PolicyRule(r.id(), action, r.pathPattern(), r.requiredAttributes(), effect, priority);
    }

    private static PolicyRuleRequest toRuleRequest(PolicyRule r) {
        return new PolicyRuleRequest(r.id(),
                r.action() == null ? null : r.action().name(),
                r.pathPattern(), r.requiredAttributes(), r.effect().name(), r.priority());
    }

    private static AccessAction parseAction(String action) {
        try {
            return AccessAction.valueOf(action.toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new BrokerException(ErrorCode.INVALID_PARAM,
                    "action must be one of ISSUE/RENEW/REVOKE/READ/ROTATE");
        }
    }

    private static RotationMode parseMode(String mode) {
        if (mode == null || mode.isBlank()) {
            return RotationMode.SOFT;
        }
        try {
            return RotationMode.valueOf(mode.toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new BrokerException(ErrorCode.ROTATION_MODE_INVALID, "mode must be SOFT or HARD");
        }
    }

    private static UUID parseLeaseId(String leaseId) {
        try {
            return UUID.fromString(leaseId);
        } catch (IllegalArgumentException e) {
            throw new BrokerException(ErrorCode.INVALID_PARAM, "malformed leaseId: " + leaseId);
        }
    }

    private LeaseResponse toLeaseResponse(Lease lease) {
        long ttl = Math.max(0L, lease.expiresAtMillis() - timeSource.nowMillis());
        return new LeaseResponse(
                lease.id().toString(), lease.callerId(), lease.path(), lease.secretVersion(),
                lease.issuedAtMillis(), lease.expiresAtMillis(), ttl, lease.status().name(),
                lease.renewCount(), lease.revokedAtMillis(), lease.revokedReason(), lease.revision());
    }
}
