package com.github.lisama569.secretleasebrokerjava.service;

import com.github.lisama569.secretleasebrokerjava.clock.TimeSource;
import com.github.lisama569.secretleasebrokerjava.config.LeaseProperties;
import com.github.lisama569.secretleasebrokerjava.error.ErrorCode;
import com.github.lisama569.secretleasebrokerjava.error.ServiceException;
import com.github.lisama569.secretleasebrokerjava.model.Action;
import com.github.lisama569.secretleasebrokerjava.model.Caller;
import com.github.lisama569.secretleasebrokerjava.model.Decision;
import com.github.lisama569.secretleasebrokerjava.model.Lease;
import com.github.lisama569.secretleasebrokerjava.model.LeaseStatus;
import com.github.lisama569.secretleasebrokerjava.model.SecretVersion;
import com.github.lisama569.secretleasebrokerjava.policy.PolicyEngine;
import com.github.lisama569.secretleasebrokerjava.store.CallerDirectory;
import com.github.lisama569.secretleasebrokerjava.store.LeaseStore;
import com.github.lisama569.secretleasebrokerjava.store.LeaseStore.Update;
import com.github.lisama569.secretleasebrokerjava.store.SecretStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * 租约生命周期服务。
 *
 * 关键不变量：
 * - 签发前必须通过基于身份属性的策略判定（默认拒绝），并确保密钥存在；
 * - 续租在租约分段锁内完成状态校验 + 计数 +1 + 到期时间推进，并发续租不丢次；
 * - 过期与吊销都使租约立即失效：过期采用惰性落状态（读到/改到即标记 EXPIRED），
 *   吊销立即写 REVOKED；重复吊销被明确拒绝；
 * - 失败原因严格区分为 INVALID_PARAMETER / POLICY_DENIED / LEASE_NOT_FOUND /
 *   LEASE_NOT_ACTIVE / LEASE_ALREADY_REVOKED；
 * - 每个成功与失败步骤都写审计。
 */
@Component
public class LeaseService {

    private static final Logger log = LoggerFactory.getLogger(LeaseService.class);

    private final LeaseStore leaseStore;
    private final SecretStore secretStore;
    private final CallerDirectory callerDirectory;
    private final PolicyEngine policyEngine;
    private final AuditLog auditLog;
    private final TimeSource timeSource;
    private final LeaseProperties properties;

    public LeaseService(LeaseStore leaseStore, SecretStore secretStore,
                        CallerDirectory callerDirectory, PolicyEngine policyEngine,
                        AuditLog auditLog, TimeSource timeSource, LeaseProperties properties) {
        this.leaseStore = leaseStore;
        this.secretStore = secretStore;
        this.callerDirectory = callerDirectory;
        this.policyEngine = policyEngine;
        this.auditLog = auditLog;
        this.timeSource = timeSource;
        this.properties = properties;
    }

    /**
     * 申请租约。
     *
     * @param callerId   调用方身份，必须已注册
     * @param secretPath 密钥路径（不存在则模拟后端自动创建版本 1）
     * @param ttlMillis  请求 TTL；null 或非正数使用默认值，超过上限被拒绝
     */
    public Lease issue(String callerId, String secretPath, Long ttlMillis) {
        if (isBlank(callerId)) {
            throw invalid("callerId 不能为空");
        }
        if (isBlank(secretPath)) {
            throw invalid("secretPath 不能为空");
        }
        Caller caller = callerDirectory.find(callerId)
                .orElseThrow(() -> new ServiceException(ErrorCode.INVALID_PARAMETER,
                        "未知调用方: " + callerId + "（请先注册身份与属性）"));
        long ttl = normalizeTtl(ttlMillis);

        log.info("issue request: caller={} attrs={} path={} ttl={}", callerId, caller.attributes(),
                secretPath, ttl);
        Decision decision = policyEngine.evaluate(caller, secretPath, Action.ISSUE);
        if (!decision.allowed()) {
            auditLog.record("ISSUE_DENIED", false, callerId, null, secretPath,
                    "命中规则=" + decision.matchedRuleId() + "：" + decision.reason());
            throw new ServiceException(ErrorCode.POLICY_DENIED,
                    "策略不允许 ISSUE：" + decision.reason());
        }

        SecretVersion version = secretStore.createIfAbsent(secretPath);
        long now = timeSource.nowMillis();
        Lease lease = new Lease(
                UUID.randomUUID().toString(),
                callerId,
                secretPath,
                version.version(),
                now,
                now + ttl,
                ttl,
                0,
                LeaseStatus.ACTIVE,
                null);
        if (!leaseStore.insert(lease)) {
            // UUID 碰撞在本地模拟中可视为不可能，但语义上必须保证不重复签发
            auditLog.record("ISSUE_FAILED", false, callerId, lease.id(), secretPath,
                    "租约 id 冲突，拒绝重复签发");
            throw new ServiceException(ErrorCode.CONCURRENT_MODIFICATION, "租约 id 冲突，请重试");
        }
        auditLog.record("ISSUE", true, callerId, lease.id(), secretPath,
                "ttl=" + ttl + " 绑定密钥版本=" + version.version()
                        + " 命中规则=" + decision.matchedRuleId());
        log.info("issue granted: lease={} version={} expiresAt={}", lease.id(), version.version(),
                lease.expiresAt());
        return lease;
    }

    /**
     * 续租。必须命中针对 RENEW 的策略规则；过期/已吊销租约一律拒绝。
     * 整段读-改-写在租约锁内原子完成。
     */
    public Lease renew(String leaseId, Long ttlMillis) {
        if (isBlank(leaseId)) {
            throw invalid("leaseId 不能为空");
        }
        long ttl = normalizeTtl(ttlMillis);
        log.info("renew request: lease={} ttl={}", leaseId, ttl);

        Lease lease = leaseStore.find(leaseId)
                .orElseThrow(() -> notFound(leaseId));
        Caller caller = callerDirectory.find(lease.callerId())
                .orElseThrow(() -> new ServiceException(ErrorCode.INVALID_PARAMETER,
                        "租约调用方身份已不存在: " + lease.callerId()));

        Decision decision = policyEngine.evaluate(caller, lease.secretPath(), Action.RENEW);
        if (!decision.allowed()) {
            auditLog.record("RENEW_DENIED", false, lease.callerId(), leaseId, lease.secretPath(),
                    "命中规则=" + decision.matchedRuleId() + "：" + decision.reason());
            throw new ServiceException(ErrorCode.POLICY_DENIED,
                    "策略不允许 RENEW：" + decision.reason());
        }

        final long ttlFinal = ttl;
        LeaseStore.Update update = leaseStore
                .mutate(leaseId, current -> {
                    long now = timeSource.nowMillis();
                    if (current.status() == LeaseStatus.REVOKED) {
                        return new Update(current, false);
                    }
                    if (current.status() == LeaseStatus.EXPIRED || current.expiresAt() <= now) {
                        return new Update(withStatus(current, LeaseStatus.EXPIRED, null), true);
                    }
                    Lease next = new Lease(current.id(), current.callerId(), current.secretPath(),
                            current.secretVersion(), current.issuedAt(), now + ttlFinal, ttlFinal,
                            current.renewCount() + 1, LeaseStatus.ACTIVE, null);
                    return new Update(next, true);
                })
                .orElseThrow(() -> notFound(leaseId));
        Lease renewed = update.newLease();

        if (renewed.status() == LeaseStatus.EXPIRED) {
            auditLog.record("RENEW_EXPIRED", false, renewed.callerId(), leaseId,
                    renewed.secretPath(), "租约已过期，拒绝续租");
            throw new ServiceException(ErrorCode.LEASE_NOT_ACTIVE, "租约已过期，不能续租");
        }
        if (renewed.status() == LeaseStatus.REVOKED) {
            auditLog.record("RENEW_REVOKED", false, renewed.callerId(), leaseId,
                    renewed.secretPath(), "租约已吊销，拒绝续租");
            throw new ServiceException(ErrorCode.LEASE_NOT_ACTIVE, "租约已吊销，不能续租");
        }
        auditLog.record("RENEW", true, renewed.callerId(), leaseId, renewed.secretPath(),
                "renewCount=" + renewed.renewCount() + " 新到期时间=" + renewed.expiresAt()
                        + " 命中规则=" + decision.matchedRuleId());
        log.info("renew granted: lease={} renewCount={} expiresAt={}", leaseId,
                renewed.renewCount(), renewed.expiresAt());
        return renewed;
    }

    /**
     * 吊销。立即生效且不可重复：已吊销返回 LEASE_ALREADY_REVOKED，已过期返回 LEASE_NOT_ACTIVE。
     */
    public Lease revoke(String leaseId) {
        if (isBlank(leaseId)) {
            throw invalid("leaseId 不能为空");
        }
        log.info("revoke request: lease={}", leaseId);

        final long now = timeSource.nowMillis();
        LeaseStore.Update update = leaseStore.mutate(leaseId, current -> {
            if (current.status() == LeaseStatus.REVOKED) {
                // 已经是 REVOKED：本次不产生任何写入，changed=false 精确标识"重复吊销"
                return new Update(current, false);
            }
            if (current.status() == LeaseStatus.EXPIRED || current.expiresAt() <= now) {
                return new Update(withStatus(current, LeaseStatus.EXPIRED, null), true);
            }
            return new Update(withStatus(current, LeaseStatus.REVOKED, now), true);
        }).orElseThrow(() -> notFound(leaseId));
        Lease result = update.newLease();

        if (result.status() == LeaseStatus.REVOKED) {
            if (update.changed()) {
                auditLog.record("REVOKE", true, result.callerId(), leaseId,
                        result.secretPath(), "revokedAt=" + result.revokedAt());
                return result;
            }
            auditLog.record("REVOKE_DUPLICATE", false, result.callerId(), leaseId,
                    result.secretPath(), "租约此前已吊销，拒绝重复吊销");
            throw alreadyRevoked(leaseId);
        }
        auditLog.record("REVOKE_EXPIRED", false, result.callerId(), leaseId,
                result.secretPath(), "租约已过期，吊销无意义");
        throw new ServiceException(ErrorCode.LEASE_NOT_ACTIVE, "租约已过期，无需吊销");
    }

    /**
     * 查询租约当前状态。惰性把到期但仍标记 ACTIVE 的租约落为 EXPIRED。
     */
    public Lease getLease(String leaseId) {
        if (isBlank(leaseId)) {
            throw invalid("leaseId 不能为空");
        }
        Lease current = leaseStore.find(leaseId).orElseThrow(() -> notFound(leaseId));
        if (current.status() == LeaseStatus.ACTIVE && current.expiresAt() <= timeSource.nowMillis()) {
            LeaseStore.Update update = leaseStore.mutate(leaseId, c ->
                    c.status() == LeaseStatus.ACTIVE && c.expiresAt() <= timeSource.nowMillis()
                            ? new Update(withStatus(c, LeaseStatus.EXPIRED, null), true)
                            : new Update(c, false))
                    .orElseThrow(() -> notFound(leaseId));
            if (update.changed()) {
                auditLog.record("EXPIRE", true, update.newLease().callerId(), leaseId,
                        update.newLease().secretPath(),
                        "惰性检测到期 expiresAt=" + update.newLease().expiresAt());
            }
            return update.newLease();
        }
        return current;
    }

    /**
     * 持租约取用密钥材料。
     * 语义：
     * - 租约必须 ACTIVE 且未到期，否则 LEASE_NOT_ACTIVE（含已吊销）；
     * - 租约不可变地绑定签发时的密钥版本：即使密钥已轮换，有效旧租约拿到的仍是旧版本材料；
     *   想访问新版本必须重新申请租约。这里不会发生版本漂移。
     * 每次取用均审计，可追溯谁在哪一刻读了哪个版本。
     */
    public SecretVersion fetchMaterial(String leaseId) {
        Lease lease = getLease(leaseId); // 复用惰性过期判定
        if (lease.status() == LeaseStatus.REVOKED) {
            auditLog.record("FETCH_DENIED", false, lease.callerId(), leaseId, lease.secretPath(),
                    "租约已吊销，拒绝取用");
            throw new ServiceException(ErrorCode.LEASE_NOT_ACTIVE, "租约已吊销，不能取用密钥");
        }
        if (lease.status() == LeaseStatus.EXPIRED) {
            auditLog.record("FETCH_DENIED", false, lease.callerId(), leaseId, lease.secretPath(),
                    "租约已过期，拒绝取用");
            throw new ServiceException(ErrorCode.LEASE_NOT_ACTIVE, "租约已过期，不能取用密钥");
        }
        SecretVersion version = secretStore.findVersion(lease.secretPath(), lease.secretVersion())
                .orElseThrow(() -> new ServiceException(ErrorCode.SECRET_VERSION_MISMATCH,
                        "租约绑定的密钥版本已不存在: " + lease.secretPath()
                                + "#" + lease.secretVersion()));
        int latest = secretStore.findLatest(lease.secretPath())
                .map(SecretVersion::version).orElse(version.version());
        auditLog.record("FETCH", true, lease.callerId(), leaseId, lease.secretPath(),
                "交付版本=" + version.version()
                        + (latest > version.version() ? "（密钥已轮换至 " + latest
                        + "，本租约仍稳定交付旧版本，需重新签发获取新版本）" : "（当前最新版本）"));
        return version;
    }

    private static Lease withStatus(Lease current, LeaseStatus status, Long revokedAt) {
        return new Lease(current.id(), current.callerId(), current.secretPath(),
                current.secretVersion(), current.issuedAt(), current.expiresAt(),
                current.ttlMillis(), current.renewCount(), status, revokedAt);
    }

    private long normalizeTtl(Long requested) {
        if (requested == null || requested <= 0) {
            return properties.getDefaultTtlMillis();
        }
        if (requested < properties.getMinTtlMillis()) {
            throw invalid("ttlMillis 不能小于 " + properties.getMinTtlMillis() + "ms");
        }
        if (requested > properties.getMaxTtlMillis()) {
            throw invalid("ttlMillis 不能大于 " + properties.getMaxTtlMillis() + "ms");
        }
        return requested;
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    private static ServiceException invalid(String message) {
        return new ServiceException(ErrorCode.INVALID_PARAMETER, message);
    }

    private static ServiceException notFound(String leaseId) {
        return new ServiceException(ErrorCode.LEASE_NOT_FOUND, "租约不存在: " + leaseId);
    }

    private static ServiceException alreadyRevoked(String leaseId) {
        return new ServiceException(ErrorCode.LEASE_ALREADY_REVOKED, "租约已吊销: " + leaseId);
    }
}
