package com.github.lisama569.secretleasebrokerjava.store;

import java.util.Map;

/**
 * 某个密钥路径的当前视图：当前版本号、各版本内容与上次轮换时间。
 * record 本身不可变；轮换通过整体替换该快照完成。
 */
public record SecretState(String path,
                          long currentVersion,
                          Map<Long, String> versions,
                          long lastRotatedAtMillis) {

    public String valueAt(long version) {
        return versions.get(version);
    }
}
