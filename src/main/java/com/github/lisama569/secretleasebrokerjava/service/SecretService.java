package com.github.lisama569.secretleasebrokerjava.service;

import com.github.lisama569.secretleasebrokerjava.error.BrokerException;
import com.github.lisama569.secretleasebrokerjava.error.ErrorCode;
import com.github.lisama569.secretleasebrokerjava.model.AccessAction;
import com.github.lisama569.secretleasebrokerjava.model.AuditEventType;
import com.github.lisama569.secretleasebrokerjava.model.Caller;
import com.github.lisama569.secretleasebrokerjava.model.PolicyDecision;
import com.github.lisama569.secretleasebrokerjava.model.RotationMode;
import com.github.lisama569.secretleasebrokerjava.store.AuditLog;
import com.github.lisama569.secretleasebrokerjava.store.CallerStore;
import com.github.lisama569.secretleasebrokerjava.store.SecretState;
import com.github.lisama569.secretleasebrokerjava.store.SecretStore;
import com.github.lisama569.secretleasebrokerjava.time.TimeSource;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 密钥轮换与凭租约读取。
 *
 * 轮换语义（明确且稳定）：
 *  - SOFT：仅产生新版本，旧租约继续绑定各自签发时的 secretVersion，读到的始终是旧版本值，
 *    直到旧租约自然到期；新签发的租约绑定当前新版本。
 *  - HARD：在同一轮换操作中，先落新版本，再原子吊销该路径下所有有效租约；
 *    旧租约随后读会得到确定的 LEASE_REVOKED。
 */
@Service
public class SecretService {

    private static final Logger log = LoggerFactory.getLogger(SecretService.class);

    private final SecretStore secretStore;
    private final LeaseService leaseService;
    private final CallerStore callerStore;
    private final PolicyEngine policyEngine;
    private final AuditLog auditLog;
    private final TimeSource timeSource;

    public SecretService(SecretStore secretStore,
                         LeaseService leaseService,
                         CallerStore callerStore,
                         PolicyEngine policyEngine,
                         AuditLog auditLog,
                         TimeSource timeSource) {
        this.secretStore = secretStore;
        this.leaseService = leaseService;
        this.callerStore = callerStore;
        this.policyEngine = policyEngine;
        this.auditLog = auditLog;
        this.timeSource = timeSource;
    }

    public record SecretAccess(String path, String value, long version, UUID leaseId) {
    }

    public SecretAccess readWithLease(String callerId, UUID leaseId) {
        long now = timeSource.nowMillis();
        // 归属 + READ 策略 + 存活校验全部在租约服务内完成，失败原因可区分。
        var lease = leaseService.requireActiveLease(callerId, leaseId, AccessAction.READ);

        SecretState state = secretStore.find(lease.path())
                .orElseThrow(() -> new BrokerException(ErrorCode.SECRET_NOT_FOUND,
                        "secret no longer exists: " + lease.path()));
        String value = state.valueAt(lease.secretVersion());
        if (value == null) {
            // 正常不会发生：模拟后端保留全部历史版本。
            throw new BrokerException(ErrorCode.INTERNAL_ERROR,
                    "secret version " + lease.secretVersion() + " missing for " + lease.path());
        }
        auditLog.append(AuditEventType.SECRET_READ, callerId, lease.path(),
                leaseId.toString(), "SUCCESS",
                "read secretVersion=" + lease.secretVersion()
                        + " (current=" + state.currentVersion() + ")", now);
        log.info("[secret] read caller={} lease={} path={} secretVersion={} current={}",
                callerId, leaseId, lease.path(), lease.secretVersion(), state.currentVersion());
        return new SecretAccess(lease.path(), value, lease.secretVersion(), leaseId);
    }

    public record RotationResult(String path, long newVersion, RotationMode mode, int revokedLeaseCount) {
    }

    public RotationResult rotate(String callerId, String path, String newValue, RotationMode mode) {
        long now = timeSource.nowMillis();
        if (path == null || path.isBlank()) {
            throw new BrokerException(ErrorCode.PATH_BLANK, "path must not be blank");
        }
        if (newValue == null || newValue.isEmpty()) {
            throw new BrokerException(ErrorCode.INVALID_PARAM, "new secret value must not be empty");
        }
        if (mode == null) {
            throw new BrokerException(ErrorCode.ROTATION_MODE_INVALID,
                    "mode must be one of SOFT/HARD");
        }
        Caller caller = callerStore.find(callerId)
                .orElseThrow(() -> new BrokerException(ErrorCode.UNKNOWN_CALLER,
                        "unknown caller: " + callerId));

        PolicyDecision decision = policyEngine.decide(caller, path, AccessAction.ROTATE);
        if (!decision.allowed()) {
            auditLog.append(AuditEventType.SECRET_ROTATE, callerId, path, null, "DENIED",
                    "policy denied by rule " + decision.ruleId(), now);
            throw new BrokerException(ErrorCode.POLICY_DENIED,
                    "ROTATE not allowed: " + decision.reason());
        }

        SecretState state = secretStore.rotate(path, newValue, mode, now);

        int revoked = 0;
        if (mode == RotationMode.HARD) {
            // 新版本先落库，再吊销；此后任何旧租约的读都会得到 LEASE_REVOKED，
            // 任何并发续租都会在 CAS 重读后得到确定的“已吊销/已过期”结论。
            revoked = leaseService.revokeAllActiveForPath(
                    path, "hard rotation to version " + state.currentVersion(), now);
        }
        auditLog.append(AuditEventType.SECRET_ROTATE, callerId, path, null, "SUCCESS",
                "rotated to version " + state.currentVersion()
                        + " mode=" + mode + " revokedLeases=" + revoked
                        + " policyRule=" + decision.ruleId(), now);
        log.info("[secret] rotate caller={} path={} newVersion={} mode={} revoked={}",
                callerId, path, state.currentVersion(), mode, revoked);
        return new RotationResult(path, state.currentVersion(), mode, revoked);
    }
}
