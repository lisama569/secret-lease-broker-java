package com.github.lisama569.secretleasebrokerjava.store;

import com.github.lisama569.secretleasebrokerjava.model.Lease;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/**
 * 进程内租约存储。
 * 并发模型：
 * - 租约是不可变 record，变更 = 计算新快照后整体替换 map 引用；
 * - 互斥手段是按 leaseId 哈希映射到固定数量的锁条带（锁对象生命周期独立于值，
 *   不会因快照替换而更换），同一租约上的 "读-改-写" 严格串行，
 *   并发续租/吊销绝不会丢更新或导致 renewCount 错乱；不同租约落到不同条带互不阻塞；
 * - 插入用 putIfAbsent，重复 id 直接失败，防止重复签发。
 */
@Component
public class InMemoryLeaseStore implements LeaseStore {

    /** 锁条带数量：2 的幂，取模用位运算。 */
    private static final int STRIPES = 64;

    private final ConcurrentHashMap<String, Lease> leases = new ConcurrentHashMap<>();

    private final Object[] locks;

    public InMemoryLeaseStore() {
        this.locks = new Object[STRIPES];
        for (int i = 0; i < STRIPES; i++) {
            this.locks[i] = new Object();
        }
    }

    private Object lockOf(String leaseId) {
        return locks[(leaseId.hashCode() & 0x7fffffff) & (STRIPES - 1)];
    }

    @Override
    public boolean insert(Lease lease) {
        return leases.putIfAbsent(lease.id(), lease) == null;
    }

    @Override
    public Optional<Lease> find(String leaseId) {
        return Optional.ofNullable(leases.get(leaseId));
    }

    @Override
    public Optional<Update> mutate(String leaseId, Function<Lease, Update> updater) {
        synchronized (lockOf(leaseId)) {
            Lease current = leases.get(leaseId);
            if (current == null) {
                return Optional.empty();
            }
            Update update = updater.apply(current);
            if (update.changed()) {
                leases.put(leaseId, update.newLease());
            }
            return Optional.of(update);
        }
    }

    @Override
    public List<Lease> findAll() {
        return new ArrayList<>(leases.values());
    }
}
