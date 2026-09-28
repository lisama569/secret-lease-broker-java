package com.github.lisama569.secretleasebrokerjava.web;

import com.github.lisama569.secretleasebrokerjava.error.ErrorCode;
import com.github.lisama569.secretleasebrokerjava.error.ServiceException;
import com.github.lisama569.secretleasebrokerjava.model.Action;
import com.github.lisama569.secretleasebrokerjava.model.Caller;
import com.github.lisama569.secretleasebrokerjava.model.Decision;
import com.github.lisama569.secretleasebrokerjava.model.PolicyRule;
import com.github.lisama569.secretleasebrokerjava.model.SecretVersion;
import com.github.lisama569.secretleasebrokerjava.policy.PolicyEngine;
import com.github.lisama569.secretleasebrokerjava.policy.PolicyStore;
import com.github.lisama569.secretleasebrokerjava.service.AdminService;
import com.github.lisama569.secretleasebrokerjava.service.AuditLog;
import com.github.lisama569.secretleasebrokerjava.store.CallerDirectory;
import com.github.lisama569.secretleasebrokerjava.web.dto.Dtos;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.util.List;
import java.util.Map;

/**
 * 管理面 REST 接口：密钥轮换、调用方注册、策略替换/查询、显式策略判定、审计查询。
 */
@RestController
@RequestMapping("/api/admin")
public class AdminController {

    private final AdminService adminService;
    private final CallerDirectory callerDirectory;
    private final PolicyStore policyStore;
    private final PolicyEngine policyEngine;
    private final AuditLog auditLog;

    public AdminController(AdminService adminService, CallerDirectory callerDirectory,
                           PolicyStore policyStore, PolicyEngine policyEngine, AuditLog auditLog) {
        this.adminService = adminService;
        this.callerDirectory = callerDirectory;
        this.policyStore = policyStore;
        this.policyEngine = policyEngine;
        this.auditLog = auditLog;
    }

    /** 路径含 '/'，作为请求参数传递更稳妥；也兼容 /secrets/{path} 形式。 */
    @PostMapping({"/secrets/rotate", "/secrets/{path}/rotate"})
    public ResponseEntity<Map<String, Object>> rotate(
            @PathVariable(required = false) String path,
            @RequestParam(value = "path", required = false) String pathParam) {
        String target = path != null ? path : pathParam;
        SecretVersion v = adminService.rotateSecret(target);
        return ResponseEntity.ok(Map.of(
                "secretPath", v.secretPath(),
                "version", v.version(),
                "createdAt", v.createdAt()));
    }

    @PostMapping("/callers")
    public ResponseEntity<Map<String, Object>> registerCaller(@RequestBody Dtos.CallerRequest request) {
        if (request == null || request.callerId() == null || request.callerId().isBlank()) {
            throw new ServiceException(ErrorCode.INVALID_PARAMETER, "callerId 不能为空");
        }
        Caller caller = callerDirectory.register(
                new Caller(request.callerId(),
                        request.attributes() == null ? Map.of() : request.attributes()));
        return ResponseEntity.created(URI.create("/api/admin/callers/" + caller.id()))
                .body(Map.of("callerId", caller.id(), "attributes", caller.attributes()));
    }

    @GetMapping("/callers/{callerId}")
    public Map<String, Object> getCaller(@PathVariable String callerId) {
        Caller caller = callerDirectory.find(callerId)
                .orElseThrow(() -> new ServiceException(ErrorCode.INVALID_PARAMETER,
                        "未知调用方: " + callerId));
        return Map.of("callerId", caller.id(), "attributes", caller.attributes());
    }

    @PostMapping("/policy")
    public ResponseEntity<Map<String, Object>> replacePolicy(
            @RequestBody Dtos.PolicyReplaceRequest request) {
        if (request == null || request.rules() == null) {
            throw new ServiceException(ErrorCode.INVALID_PARAMETER, "rules 不能为空");
        }
        List<PolicyRule> rules = request.rules().stream().map(Dtos.RuleBody::toRule).toList();
        long version = adminService.replacePolicy(rules);
        return ResponseEntity.ok(Map.of("policyVersion", version, "ruleCount", rules.size()));
    }

    @GetMapping("/policy")
    public Dtos.PolicyView getPolicy() {
        return new Dtos.PolicyView(policyStore.version(),
                policyStore.currentRules().stream().map(Dtos::fromRule).toList());
    }

    /** 显式策略判定：便于直接观察命中规则与依据。 */
    @PostMapping("/decisions")
    public Map<String, Object> decide(@RequestBody Map<String, String> body) {
        String callerId = body.get("callerId");
        String secretPath = body.get("secretPath");
        String actionText = body.get("action");
        if (callerId == null || secretPath == null || actionText == null) {
            throw new ServiceException(ErrorCode.INVALID_PARAMETER,
                    "callerId/secretPath/action 均必填");
        }
        Action action;
        try {
            action = Action.valueOf(actionText.trim().toUpperCase());
        } catch (IllegalArgumentException ex) {
            throw new ServiceException(ErrorCode.INVALID_PARAMETER,
                    "action 只能为 ISSUE 或 RENEW: " + actionText);
        }
        Caller caller = callerDirectory.find(callerId)
                .orElseThrow(() -> new ServiceException(ErrorCode.INVALID_PARAMETER,
                        "未知调用方: " + callerId));
        Decision d = policyEngine.evaluate(caller, secretPath, action);
        return Map.of(
                "allowed", d.allowed(),
                "action", d.action().name(),
                "callerId", d.callerId(),
                "secretPath", d.secretPath(),
                "matchedRuleId", d.matchedRuleId() == null ? "" : d.matchedRuleId(),
                "effect", d.effect() == null ? "" : d.effect().name(),
                "reason", d.reason(),
                "policyVersion", d.policyVersion());
    }

    @GetMapping("/audit")
    public List<Dtos.AuditEventView> recentAudit(@RequestParam(defaultValue = "50") int limit) {
        int safe = Math.max(1, Math.min(limit, 1000));
        return auditLog.recent(safe).stream()
                .map(e -> new Dtos.AuditEventView(e.sequence(), e.timestamp(), e.eventType(),
                        e.success(), e.callerId(), e.leaseId(), e.secretPath(), e.detail()))
                .toList();
    }

    @GetMapping("/audit/leases/{leaseId}")
    public List<Dtos.AuditEventView> auditForLease(@PathVariable String leaseId) {
        return auditLog.forLease(leaseId).stream()
                .map(e -> new Dtos.AuditEventView(e.sequence(), e.timestamp(), e.eventType(),
                        e.success(), e.callerId(), e.leaseId(), e.secretPath(), e.detail()))
                .toList();
    }
}
