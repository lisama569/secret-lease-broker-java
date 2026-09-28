package com.github.lisama569.secretleasebrokerjava.service;

import com.github.lisama569.secretleasebrokerjava.model.AuditEvent;

import java.util.List;

/**
 * 审计日志：记录生命周期关键步骤，支持按租约检索以复盘并发交错。
 */
public interface AuditLog {

    /**
     * 追加一条审计事件（序号由实现原子分配）。
     *
     * @param eventType  事件类型
     * @param success    是否成功
     * @param callerId   调用方，可空
     * @param leaseId    租约 id，可空
     * @param secretPath 密钥路径，可空
     * @param detail     细节说明
     * @return 已落盘的不可变事件
     */
    AuditEvent record(String eventType, boolean success, String callerId,
                      String leaseId, String secretPath, String detail);

    /** 最近若干条（按时间倒序）。 */
    List<AuditEvent> recent(int limit);

    /** 与指定租约相关的全部事件（按序号升序）。 */
    List<AuditEvent> forLease(String leaseId);
}
