package com.github.lisama569.secretleasebrokerjava.service;

import com.github.lisama569.secretleasebrokerjava.clock.TimeSource;
import com.github.lisama569.secretleasebrokerjava.model.AuditEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 进程内审计日志。
 * - {@link CopyOnWriteArrayList} 保存全部事件，{@link AtomicLong} 分配单调序号，
 *   并发下序号不重不乱，可据此精确复盘交错顺序；
 * - 每条事件同时以结构化文本写入 INFO 日志（关键单测可直接看到请求、命中规则与判定）。
 */
@Component
public class InMemoryAuditLog implements AuditLog {

    private static final Logger log = LoggerFactory.getLogger("AUDIT");

    private final CopyOnWriteArrayList<AuditEvent> events = new CopyOnWriteArrayList<>();
    private final AtomicLong sequence = new AtomicLong(0);
    private final TimeSource timeSource;

    public InMemoryAuditLog(TimeSource timeSource) {
        this.timeSource = timeSource;
    }

    @Override
    public AuditEvent record(String eventType, boolean success, String callerId,
                             String leaseId, String secretPath, String detail) {
        AuditEvent event = new AuditEvent(
                sequence.incrementAndGet(),
                timeSource.nowMillis(),
                eventType,
                success,
                callerId,
                leaseId,
                secretPath,
                detail);
        events.add(event);
        log.info("audit#{} [{}] success={} caller={} lease={} path={} detail={}",
                event.sequence(), event.eventType(), event.success(),
                event.callerId(), event.leaseId(), event.secretPath(), event.detail());
        return event;
    }

    @Override
    public List<AuditEvent> recent(int limit) {
        int size = events.size();
        int from = Math.max(0, size - limit);
        return events.subList(from, size).reversed();
    }

    @Override
    public List<AuditEvent> forLease(String leaseId) {
        return events.stream().filter(e -> leaseId.equals(e.leaseId())).toList();
    }
}
