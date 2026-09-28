package com.github.lisama569.secretleasebrokerjava.store;

import com.github.lisama569.secretleasebrokerjava.model.PolicyRule;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.stereotype.Component;

/**
 * 策略快照 + 单调版本号。replace 为原子操作，任一时刻读到的都是一份完整快照；
 * 每次整体替换版本号 +1，策略引擎据此让旧版本的判定缓存立即失效。
 */
@Component
public class PolicyStore {

    public record PolicySnapshot(long version, List<PolicyRule> rules) {
    }

    private static final PolicySnapshot EMPTY = new PolicySnapshot(0L, List.of());

    private final AtomicReference<PolicySnapshot> snapshot = new AtomicReference<>(EMPTY);

    public PolicySnapshot get() {
        return snapshot.get();
    }

    public PolicySnapshot replace(List<PolicyRule> rules) {
        PolicySnapshot next = new PolicySnapshot(
                snapshot.get().version() + 1,
                List.copyOf(rules));
        snapshot.set(next);
        return next;
    }

    /** 恢复到空策略（仅用于本地演示与测试隔离）。 */
    public PolicySnapshot reset() {
        snapshot.set(EMPTY);
        return EMPTY;
    }
}
