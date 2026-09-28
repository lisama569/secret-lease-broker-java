package com.github.lisama569.secretleasebrokerjava.store;

import com.github.lisama569.secretleasebrokerjava.model.Caller;

import java.util.Optional;

/**
 * 模拟调用方目录：进程内注册调用方身份与属性。
 */
public interface CallerDirectory {

    Caller register(Caller caller);

    Optional<Caller> find(String callerId);
}
