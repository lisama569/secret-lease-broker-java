package com.github.lisama569.secretleasebrokerjava.policy;

import com.github.lisama569.secretleasebrokerjava.model.PolicyRule;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 进程内策略集存储。
 * 规则以不可变快照整体替换；版本号单调递增，判定引擎用它做缓存失效依据。
 */
@Component
public class InMemoryPolicyStore implements PolicyStore {

    private record Snapshot(long version, List<PolicyRule> rules) {
    }

    private final AtomicLong versions = new AtomicLong(0);

    private volatile Snapshot snapshot = new Snapshot(0, List.of());

    @Override
    public long replaceRules(List<PolicyRule> rules) {
        long newVersion = versions.incrementAndGet();
        snapshot = new Snapshot(newVersion, List.copyOf(rules));
        return newVersion;
    }

    @Override
    public List<PolicyRule> currentRules() {
        return snapshot.rules();
    }

    @Override
    public long version() {
        return snapshot.version();
    }

    /** 供引擎读取一次一致的 (版本, 规则)。 */
    Snapshot snapshot() {
        return snapshot;
    }
}
