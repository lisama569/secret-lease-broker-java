package com.github.lisama569.secretleasebrokerjava.store;

import com.github.lisama569.secretleasebrokerjava.error.BrokerException;
import com.github.lisama569.secretleasebrokerjava.error.ErrorCode;
import com.github.lisama569.secretleasebrokerjava.model.RotationMode;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import org.springframework.stereotype.Component;

/**
 * 进程内密钥后端模拟：按路径存放多版本密钥值，
 * 轮换采用每键原子 compute，保证并发轮换下版本号严格递增。
 */
@Component
public class SecretStore {

    private final ConcurrentMap<String, SecretState> secrets = new ConcurrentHashMap<>();

    /** 清空全部密钥（仅用于本地演示与测试隔离）。 */
    public void clear() {
        secrets.clear();
    }

    public SecretState createIfAbsent(String path, String initialValue, long nowMillis) {
        return secrets.computeIfAbsent(path, p -> {
            Map<Long, String> versions = new ConcurrentHashMap<>();
            versions.put(1L, initialValue);
            return new SecretState(p, 1L, Map.copyOf(versions), nowMillis);
        });
    }

    public SecretState rotate(String path, String newValue, RotationMode mode, long nowMillis) {
        SecretState updated = secrets.computeIfPresent(path, (p, old) -> {
            long nextVersion = old.currentVersion() + 1;
            Map<Long, String> versions = new ConcurrentHashMap<>(old.versions());
            versions.put(nextVersion, newValue);
            return new SecretState(p, nextVersion, Map.copyOf(versions), nowMillis);
        });
        if (updated == null) {
            throw new BrokerException(ErrorCode.SECRET_NOT_FOUND,
                    "secret path does not exist: " + path);
        }
        return updated;
    }

    public Optional<SecretState> find(String path) {
        return Optional.ofNullable(secrets.get(path));
    }
}
