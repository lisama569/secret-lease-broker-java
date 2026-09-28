package com.github.lisama569.secretleasebrokerjava.store;

import com.github.lisama569.secretleasebrokerjava.model.Lease;

import java.util.List;
import java.util.Optional;
import java.util.function.Function;

/**
 * 租约存储。并发语义：
 * - 同一租约上的更新必须原子串行化（按 id 的分段锁）；
 * - 不同租约之间互不阻塞；
 * - 变更函数返回 {@link Update}，携带新快照与是否发生状态转移，
 *   调用方据此区分"本次操作生效"与"维持原状（如重复吊销）"，不依赖时间巧合。
 */
public interface LeaseStore {

    /** mutate 的返回：newLease 为变更后的快照，changed 表示本次是否真的写入了不同快照。 */
    record Update(Lease newLease, boolean changed) {
    }

    /** 原子插入新租约；若 id 已存在则失败（返回 false），防止重复签发。 */
    boolean insert(Lease lease);

    /** 按 id 读取当前快照，不存在返回 empty。 */
    Optional<Lease> find(String leaseId);

    /**
     * 对指定租约原子地执行 "读-改-写"。
     * 变更函数返回 {@link Update}；{@code changed=false} 表示保持原快照（外层据此给出确定性拒绝）。
     * 租约不存在返回 empty。
     */
    Optional<Update> mutate(String leaseId, Function<Lease, Update> updater);

    /** 列出全部租约（管理/清扫/审计用途）。 */
    List<Lease> findAll();
}
