package com.github.lisama569.secretleasebrokerjava.store;

import com.github.lisama569.secretleasebrokerjava.clock.TimeSource;
import com.github.lisama569.secretleasebrokerjava.error.ErrorCode;
import com.github.lisama569.secretleasebrokerjava.error.ServiceException;
import com.github.lisama569.secretleasebrokerjava.model.SecretVersion;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.security.SecureRandom;

/**
 * 进程内密钥后端：每个路径维护一条不可变版本链。
 * 同一路径上的创建/轮换按路径分段加锁串行化，保证版本号不跳号、不重复；
 * 不同路径互不阻塞。
 */
@Component
public class InMemorySecretStore implements SecretStore {

    private record VersionChain(TreeMap<Integer, SecretVersion> versions) {
    }

    private final ConcurrentHashMap<String, VersionChain> chains = new ConcurrentHashMap<>();
    private final SecureRandom random = new SecureRandom();
    private final TimeSource timeSource;

    public InMemorySecretStore(TimeSource timeSource) {
        this.timeSource = timeSource;
    }

    @Override
    public SecretVersion createIfAbsent(String path) {
        validatePath(path);
        VersionChain chain = chains.computeIfAbsent(path, p -> new VersionChain(new TreeMap<>()));
        synchronized (chain) {
            Map.Entry<Integer, SecretVersion> latest = chain.versions().lastEntry();
            if (latest != null) {
                return latest.getValue();
            }
            SecretVersion v1 = newVersion(path, 1);
            chain.versions().put(1, v1);
            return v1;
        }
    }

    @Override
    public Optional<SecretVersion> findLatest(String path) {
        VersionChain chain = chains.get(path);
        if (chain == null) {
            return Optional.empty();
        }
        synchronized (chain) {
            return Optional.ofNullable(chain.versions().lastEntry()).map(Map.Entry::getValue);
        }
    }

    @Override
    public Optional<SecretVersion> findVersion(String path, int version) {
        VersionChain chain = chains.get(path);
        if (chain == null) {
            return Optional.empty();
        }
        synchronized (chain) {
            return Optional.ofNullable(chain.versions().get(version));
        }
    }

    @Override
    public SecretVersion rotate(String path) {
        validatePath(path);
        VersionChain chain = chains.computeIfAbsent(path, p -> new VersionChain(new TreeMap<>()));
        synchronized (chain) {
            int next = chain.versions().isEmpty() ? 1 : chain.versions().lastKey() + 1;
            SecretVersion created = newVersion(path, next);
            chain.versions().put(next, created);
            return created;
        }
    }

    @Override
    public List<String> listPaths() {
        return new ArrayList<>(chains.keySet());
    }

    private SecretVersion newVersion(String path, int version) {
        // 模拟材料：固定前缀 + 随机串，明确不是真实凭据
        byte[] bytes = new byte[16];
        random.nextBytes(bytes);
        StringBuilder sb = new StringBuilder("simulated://").append(path).append('#').append(version).append(':');
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return new SecretVersion(path, version, sb.toString(), timeSource.nowMillis());
    }

    private static void validatePath(String path) {
        if (path == null || path.isBlank()) {
            throw new ServiceException(ErrorCode.INVALID_PARAMETER, "secretPath 不能为空");
        }
        if (!path.startsWith("secret/")) {
            throw new ServiceException(ErrorCode.INVALID_PARAMETER,
                    "secretPath 必须以 'secret/' 开头: " + path);
        }
    }
}
