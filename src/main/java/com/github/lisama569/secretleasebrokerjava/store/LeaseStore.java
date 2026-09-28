package com.github.lisama569.secretleasebrokerjava.store;

import com.github.lisama569.secretleasebrokerjava.model.Lease;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.Function;
import org.springframework.stereotype.Component;

/**
 * 进程内租约存储。
 *
 * 并发语义：单租约的所有变更（续租/吊销）走 compareAndSwap——
 * 仅当存储中的 revision 与调用方持有的快照一致时才提交 updater 产物，
 * 否则返回 empty 让上层判定为并发冲突，从根上杜绝丢失更新与计数错乱。
 * 每次成功变更后 revision 单调递增。
 */
@Component
public class LeaseStore {

    private final ConcurrentMap<UUID, Lease> leases = new ConcurrentHashMap<>();

    /** 插入新租约；若 ID 已存在返回 false。 */
    /** 清空全部租约（仅用于本地演示与测试隔离）。 */
    public void clear() {
        leases.clear();
    }

    public boolean putIfAbsent(Lease lease) {
        return leases.putIfAbsent(lease.id(), lease) == null;
    }

    /** 基于期望 revision 的 CAS 更新；revision 不匹配返回 empty。 */
    public Optional<Lease> compareAndSwap(UUID id, long expectedRevision, Function<Lease, Lease> updater) {
        // compute 对单个 key 的重映射是原子执行的；内部再核对 revision 做乐观锁。
        final boolean[] committed = {false};
        final Lease[] outcome = new Lease[1];
        leases.compute(id, (uuid, current) -> {
            if (current == null || current.revision() != expectedRevision) {
                outcome[0] = current;
                return current; // 不提交
            }
            Lease updated = updater.apply(current);
            if (updated == null || updated.revision() != current.revision() + 1) {
                throw new IllegalStateException(
                        "updater must return lease with revision " + (current.revision() + 1));
            }
            committed[0] = true;
            outcome[0] = updated;
            return updated;
        });
        return committed[0] ? Optional.of(outcome[0]) : Optional.empty();
    }

    public Optional<Lease> find(UUID id) {
        return Optional.ofNullable(leases.get(id));
    }

    public List<Lease> findByPath(String path) {
        return leases.values().stream()
                .filter(l -> l.path().equals(path))
                .sorted(java.util.Comparator.comparingLong(Lease::issuedAtMillis))
                .toList();
    }

    /** 查找某调用方在指定路径上当前仍有效的租约。 */
    public Optional<Lease> findActiveByCallerAndPath(String callerId, String path, long nowMillis) {
        return leases.values().stream()
                .filter(l -> l.callerId().equals(callerId))
                .filter(l -> l.path().equals(path))
                .filter(l -> l.isActiveAt(nowMillis))
                .findFirst();
    }

    /** 按签发时携带的幂等键查找，用于请求重放返回同一租约。 */
    public Optional<Lease> findByIdempotencyKey(String callerId, String idempotencyKey) {
        if (idempotencyKey == null) {
            return Optional.empty();
        }
        return leases.values().stream()
                .filter(l -> l.callerId().equals(callerId))
                .filter(l -> idempotencyKey.equals(l.idempotencyKey()))
                .findFirst();
    }

    /** 某路径下所有处于 ACTIVE 状态的租约（是否已到期由调用方结合时间判定）。 */
    public List<Lease> findActiveByPath(String path, long nowMillis) {
        return leases.values().stream()
                .filter(l -> l.path().equals(path))
                .filter(l -> l.isActiveAt(nowMillis))
                .toList();
    }
}
