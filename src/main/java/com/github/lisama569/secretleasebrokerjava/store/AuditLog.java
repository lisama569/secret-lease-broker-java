package com.github.lisama569.secretleasebrokerjava.store;

import com.github.lisama569.secretleasebrokerjava.model.AuditEvent;
import com.github.lisama569.secretleasebrokerjava.model.AuditEventType;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.stereotype.Component;

/**
 * 追加式审计日志：序号由 AtomicLong 全局单调递增，
 * 存储用 CopyOnWriteArrayList 保证遍历快照稳定。
 */
@Component
public class AuditLog {

    private final AtomicLong sequenceGenerator = new AtomicLong(0);
    private final CopyOnWriteArrayList<AuditEvent> events = new CopyOnWriteArrayList<>();

    /** 清空审计记录（仅用于本地演示与测试隔离）。 */
    public void clear() {
        events.clear();
    }

    public long nextSequence() {
        return sequenceGenerator.incrementAndGet();
    }

    public AuditEvent append(AuditEventType type,
                             String callerId,
                             String path,
                             String leaseId,
                             String result,
                             String detail,
                             long nowMillis) {
        AuditEvent event = new AuditEvent(
                nextSequence(), nowMillis, type, callerId, path, leaseId, result, detail);
        events.add(event);
        return event;
    }

    public List<AuditEvent> findAll() {
        return List.copyOf(events);
    }

    public List<AuditEvent> findByLeaseId(String leaseId) {
        return events.stream()
                .filter(e -> leaseId.equals(e.leaseId()))
                .toList();
    }
}
