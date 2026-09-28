package com.github.lisama569.secretleasebrokerjava.store;

import com.github.lisama569.secretleasebrokerjava.model.SecretVersion;

import java.util.List;
import java.util.Optional;

/**
 * 模拟密钥后端：进程内保存密钥路径与其全部不可变版本。
 */
public interface SecretStore {

    /** 若路径不存在则创建并写入版本 1；已存在则忽略。返回当前最新版本。 */
    SecretVersion createIfAbsent(String path);

    Optional<SecretVersion> findLatest(String path);

    Optional<SecretVersion> findVersion(String path, int version);

    /** 原子轮换：产生下一个版本并返回；路径不存在则先建版本 1。 */
    SecretVersion rotate(String path);

    List<String> listPaths();
}
