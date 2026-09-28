package com.github.lisama569.secretleasebrokerjava.store;

import com.github.lisama569.secretleasebrokerjava.model.Caller;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import org.springframework.stereotype.Component;

/**
 * 进程内调用方注册表，模拟外部身份源。
 */
@Component
public class CallerStore {

    private final ConcurrentMap<String, Caller> callers = new ConcurrentHashMap<>();

    /** 清空调用方注册表（仅用于本地演示与测试隔离）。 */
    public void clear() {
        callers.clear();
    }

    public void register(Caller caller) {
        callers.put(caller.id(), caller);
    }

    public Optional<Caller> find(String id) {
        if (id == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(callers.get(id));
    }
}
