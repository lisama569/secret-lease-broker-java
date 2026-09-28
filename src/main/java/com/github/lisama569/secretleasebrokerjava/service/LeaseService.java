package com.github.lisama569.secretleasebrokerjava.service;

import com.github.lisama569.secretleasebrokerjava.error.BrokerException;
import com.github.lisama569.secretleasebrokerjava.error.ErrorCode;
import com.github.lisama569.secretleasebrokerjava.model.AccessAction;
import com.github.lisama569.secretleasebrokerjava.model.AuditEventType;
import com.github.lisama569.secretleasebrokerjava.model.Caller;
import com.github.lisama569.secretleasebrokerjava.model.Lease;
import com.github.lisama569.secretleasebrokerjava.model.LeaseStatus;
import com.github.lisama569.secretleasebrokerjava.model.PolicyDecision;
import com.github.lisama569.secretleasebrokerjava.store.AuditLog;
import com.github.lisama569.secretleasebrokerjava.store.CallerStore;
import com.github.lisama569.secretleasebrokerjava.store.LeaseStore;
import com.github.lisama569.secretleasebrokerjava.store.PolicyStore;
import com.github.lisama569.secretleasebrokerjava.store.SecretStore;
import com.github.lisama569.secretleasebrokerjava.time.TimeSource;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 租约生命周期管理。
 *
 * 并发设计：
 *  - 签发：按 (callerId,path) 分段加锁，锁内完成“幂等键重放检查 / 已有有效租约检查 / 插入”，
 *    杜绝同一调用方对同一路径并发重复签发；
 *  - 续租 / 吊销：基于 LeaseStore 的 revision CAS 自旋，冲突方重读最新快照后重新做状态判定，
 *    因此“与吊销并发的续租”要么先成功（随后吊销生效），要么收到确定的 LEASE_REVOKED，
 *    不会出现吊销丢失或 renewCount 错乱；
 *  - 每次状态迁移都写一条审计记录。
 */
@Service
public class LeaseService {

    private static final Logger log = LoggerFactory.getLogger(LeaseService.class);

    static final long MAX_TTL_MILLIS = 365L * 24 * 60 * 60 * 1000;
    private static final int MAX_CAS_ATTEMPTS = 1000;

    private final LeaseStore leaseStore;
    private final SecretStore secretStore;
    private final CallerStore callerStore;
    private final PolicyStore policyStore;
    private final PolicyEngine policyEngine;
    private final AuditLog auditLog;
    private final TimeSource timeSource;
    private final ConcurrentHashMap<String, Object> issueLocks = new ConcurrentHashMap<>();

    public LeaseService(LeaseStore leaseStore,
                        SecretStore secretStore,
                        CallerStore callerStore,
                        PolicyStore policyStore,
                        PolicyEngine policyEngine,
                        AuditLog auditLog,
                        TimeSource timeSource) {
        this.leaseStore = leaseStore;
        this.secretStore = secretStore;
        this.callerStore = callerStore;
        this.policyStore = policyStore;
        this.policyEngine = policyEngine;
        this.auditLog = auditLog;
        this.timeSource = timeSource;
    }

    public Lease issue(String callerId, String path, long ttlMillis, String idempotencyKey) {
        long now = timeSource.nowMillis();
        validatePath(path);
        validateTtl(ttlMillis);
        Caller caller = requireCaller(callerId);

        PolicyDecision decision = policyEngine.decide(caller, path, AccessAction.ISSUE);
        if (!decision.allowed()) {
            audit(AuditEventType.LEASE_ISSUE, callerId, path, null, "DENIED",
                    "policy denied by rule " + decision.ruleId(), now);
            throw new BrokerException(ErrorCode.POLICY_DENIED,
                    "ISSUE not allowed for caller='" + callerId + "' path='" + path
                            + "': " + decision.reason());
        }

        Object lock = issueLocks.computeIfAbsent(lockKey(callerId, path), k -> new Object());
        synchronized (lock) {
            // 幂等重放：相同 (caller, idempotencyKey) 直接返回原租约，不重复签发。
            if (idempotencyKey != null && !idempotencyKey.isBlank()) {
                Optional<Lease> replay = leaseStore.findByIdempotencyKey(callerId, idempotencyKey);
                if (replay.isPresent()) {
                    log.info("[lease] idempotent replay lease={} key={}", replay.get().id(), idempotencyKey);
                    return replay.get();
                }
            }

            Optional<Lease> existing = leaseStore.findActiveByCallerAndPath(callerId, path, now);
            if (existing.isPresent()) {
                throw new BrokerException(ErrorCode.DUPLICATE_ACTIVE_LEASE,
                        "caller '" + callerId + "' already holds active lease "
                                + existing.get().id() + " on path '" + path + "'");
            }

            // 密钥后端是模拟实现：不存在则惰性创建一份确定性的本地假值。
            var secret = secretStore.createIfAbsent(path, "local-secret(" + path + ")#1", now);

            Lease lease = new Lease(
                    UUID.randomUUID(), callerId, path, secret.currentVersion(),
                    now, now + ttlMillis, LeaseStatus.ACTIVE, 0, null, null,
                    idempotencyKey, 1L);
            if (!leaseStore.putIfAbsent(lease)) {
                throw new BrokerException(ErrorCode.CONCURRENT_MODIFICATION,
                        "lease id collision, please retry");
            }
            audit(AuditEventType.LEASE_ISSUE, callerId, path, lease.id().toString(), "SUCCESS",
                    "issued ttl=" + ttlMillis + "ms secretVersion=" + secret.currentVersion()
                            + " policyRule=" + decision.ruleId() + " policyVersion=" + decision.policyVersion(),
                    now);
            log.info("[lease] issued lease={} caller={} path={} ttl={}ms expiresAt={}",
                    lease.id(), callerId, path, ttlMillis, lease.expiresAtMillis());
            return lease;
        }
    }

    public Lease renew(String callerId, UUID leaseId, long ttlMillis) {
        long now = timeSource.nowMillis();
        validateTtl(ttlMillis);
        Lease lease = requireLease(leaseId);
        requireOwner(lease, callerId);

        // 先判活：死亡租约（过期/已吊销）的续租一律拒绝，结论与并发无关、确定可复现。
        requireUsable(lease, now, AccessAction.RENEW);

        Caller caller = requireCaller(callerId);
        PolicyDecision decision = policyEngine.decide(caller, lease.path(), AccessAction.RENEW);
        if (!decision.allowed()) {
            audit(AuditEventType.LEASE_RENEW, callerId, lease.path(), leaseId.toString(), "DENIED",
                    "policy denied by rule " + decision.ruleId(), now);
            throw new BrokerException(ErrorCode.POLICY_DENIED,
                    "RENEW not allowed: " + decision.reason());
        }

        for (int attempt = 1; attempt <= MAX_CAS_ATTEMPTS; attempt++) {
            Lease snapshot = leaseStore.find(leaseId).orElseThrow();
            requireUsable(snapshot, now, AccessAction.RENEW);
            final long baseRevision = snapshot.revision();
            Optional<Lease> swapped = leaseStore.compareAndSwap(leaseId, baseRevision, l ->
                    new Lease(l.id(), l.callerId(), l.path(), l.secretVersion(),
                            l.issuedAtMillis(), now + ttlMillis, LeaseStatus.ACTIVE,
                            l.renewCount() + 1, null, null, l.idempotencyKey(),
                            baseRevision + 1));
            if (swapped.isPresent()) {
                Lease renewed = swapped.get();
                audit(AuditEventType.LEASE_RENEW, callerId, lease.path(), leaseId.toString(), "SUCCESS",
                        "renewed renewCount=" + renewed.renewCount()
                                + " newExpiresAt=" + renewed.expiresAtMillis()
                                + " revision=" + renewed.revision()
                                + " policyRule=" + decision.ruleId(), now);
                log.info("[lease] renewed lease={} renewCount={} expiresAt={} revision={}",
                        leaseId, renewed.renewCount(), renewed.expiresAtMillis(), renewed.revision());
                return renewed;
            }
            // CAS 失败说明被并发续租/吊销抢先，自旋重读后重新判活与计数。
        }
        throw new BrokerException(ErrorCode.CONCURRENT_MODIFICATION,
                "too many concurrent renew attempts on lease " + leaseId);
    }

    public Lease revoke(String callerId, UUID leaseId, String reason) {
        long now = timeSource.nowMillis();
        Lease lease = requireLease(leaseId);
        requireOwner(lease, callerId);

        Caller caller = requireCaller(callerId);
        PolicyDecision decision = policyEngine.decide(caller, lease.path(), AccessAction.REVOKE);
        if (!decision.allowed()) {
            audit(AuditEventType.LEASE_REVOKE, callerId, lease.path(), leaseId.toString(), "DENIED",
                    "policy denied by rule " + decision.ruleId(), now);
            throw new BrokerException(ErrorCode.POLICY_DENIED,
                    "REVOKE not allowed: " + decision.reason());
        }

        Lease result = doRevoke(lease, callerId, reason, now, "user");
        log.info("[lease] revoked lease={} by={} reason={} revision={}",
                leaseId, callerId, reason, result.revision());
        return result;
    }

    /**
     * 系统侧强制吊销（HARD 轮换调用），不做调用方策略校验，返回实际吊销条数。
     */
    public int revokeAllActiveForPath(String path, String reason, long nowMillis) {
        List<Lease> actives = leaseStore.findActiveByPath(path, nowMillis);
        int count = 0;
        for (Lease lease : actives) {
            Optional<Lease> current = leaseStore.find(lease.id());
            if (current.isEmpty() || !current.get().isActiveAt(nowMillis)) {
                continue;
            }
            doRevoke(current.get(), "system", reason, nowMillis, "rotation");
            count++;
        }
        return count;
    }

    private Lease doRevoke(Lease lease, String actor, String reason, long now, String trigger) {
        UUID id = lease.id();
        for (int attempt = 1; attempt <= MAX_CAS_ATTEMPTS; attempt++) {
            Lease snapshot = leaseStore.find(id).orElseThrow();
            if (snapshot.status() == LeaseStatus.REVOKED) {
                // 重复吊销：明确拒绝并指出首次吊销原因，便于调用方区分。
                throw new BrokerException(ErrorCode.LEASE_ALREADY_REVOKED,
                        "lease " + id + " already revoked at " + snapshot.revokedAtMillis()
                                + " reason='" + snapshot.revokedReason() + "'");
            }
            if (!snapshot.isActiveAt(now)) {
                throw new BrokerException(ErrorCode.LEASE_EXPIRED,
                        "lease " + id + " expired at " + snapshot.expiresAtMillis()
                                + ", revoke rejected");
            }
            final long baseRevision = snapshot.revision();
            Optional<Lease> swapped = leaseStore.compareAndSwap(id, baseRevision, l ->
                    new Lease(l.id(), l.callerId(), l.path(), l.secretVersion(),
                            l.issuedAtMillis(), l.expiresAtMillis(), LeaseStatus.REVOKED,
                            l.renewCount(), now, reason, l.idempotencyKey(), baseRevision + 1));
            if (swapped.isPresent()) {
                audit(AuditEventType.LEASE_REVOKE, actor, lease.path(), id.toString(), "SUCCESS",
                        "revoked trigger=" + trigger + " reason='" + reason
                                + "' revision=" + (baseRevision + 1), now);
                return swapped.get();
            }
            // 与并发续租/吊销冲突：重读后要么成功，要么得到确定的“已吊销/已过期”结论。
        }
        throw new BrokerException(ErrorCode.CONCURRENT_MODIFICATION,
                "too many concurrent revoke attempts on lease " + id);
    }

    /**
     * 凭租约访问（READ）前的统一校验：存在 -> 归属 -> 策略 -> 存活。
     */
    public Lease requireActiveLease(String callerId, UUID leaseId, AccessAction action) {
        long now = timeSource.nowMillis();
        Lease lease = requireLease(leaseId);
        if (!lease.callerId().equals(callerId)) {
            throw new BrokerException(ErrorCode.LEASE_CALLER_MISMATCH,
                    "lease " + leaseId + " belongs to another caller");
        }
        Caller caller = requireCaller(callerId);
        PolicyDecision decision = policyEngine.decide(caller, lease.path(), action);
        if (!decision.allowed()) {
            audit(AuditEventType.POLICY_DECIDE, callerId, lease.path(), leaseId.toString(), "DENIED",
                    action + " denied by rule " + decision.ruleId(), now);
            throw new BrokerException(ErrorCode.POLICY_DENIED,
                    action + " not allowed: " + decision.reason());
        }
        requireUsable(lease, now, action);
        return lease;
    }

    public List<Lease> listByPath(String path) {
        return leaseStore.findByPath(path);
    }

    // ----------------------------- 内部校验 -----------------------------

    private void requireOwner(Lease lease, String callerId) {
        if (!lease.callerId().equals(callerId)) {
            throw new BrokerException(ErrorCode.LEASE_CALLER_MISMATCH,
                    "lease " + lease.id() + " belongs to another caller");
        }
    }

    private Lease requireLease(UUID leaseId) {
        if (leaseId == null) {
            throw new BrokerException(ErrorCode.INVALID_PARAM, "leaseId must not be null");
        }
        return leaseStore.find(leaseId)
                .orElseThrow(() -> new BrokerException(ErrorCode.LEASE_NOT_FOUND,
                        "lease not found: " + leaseId));
    }

    private Caller requireCaller(String callerId) {
        if (callerId == null || callerId.isBlank()) {
            throw new BrokerException(ErrorCode.INVALID_PARAM, "callerId must not be blank");
        }
        return callerStore.find(callerId)
                .orElseThrow(() -> new BrokerException(ErrorCode.UNKNOWN_CALLER,
                        "unknown caller: " + callerId));
    }

    private void validatePath(String path) {
        if (path == null || path.isBlank()) {
            throw new BrokerException(ErrorCode.PATH_BLANK, "path must not be blank");
        }
    }

    private void validateTtl(long ttlMillis) {
        if (ttlMillis <= 0 || ttlMillis > MAX_TTL_MILLIS) {
            throw new BrokerException(ErrorCode.TTL_OUT_OF_RANGE,
                    "ttlMillis must be within (0, " + MAX_TTL_MILLIS + "], got " + ttlMillis);
        }
    }

    private void requireUsable(Lease lease, long now, AccessAction action) {
        if (lease.status() == LeaseStatus.REVOKED) {
            throw new BrokerException(ErrorCode.LEASE_REVOKED,
                    action + " rejected: lease " + lease.id() + " was revoked at "
                            + lease.revokedAtMillis());
        }
        if (now >= lease.expiresAtMillis()) {
            throw new BrokerException(ErrorCode.LEASE_EXPIRED,
                    action + " rejected: lease " + lease.id() + " expired at "
                            + lease.expiresAtMillis() + " (now=" + now + ")");
        }
    }

    private void audit(AuditEventType type, String callerId, String path,
                       String leaseId, String result, String detail, long now) {
        auditLog.append(type, callerId, path, leaseId, result, detail, now);
    }

    private static String lockKey(String callerId, String path) {
        return callerId + "|" + path;
    }
}
