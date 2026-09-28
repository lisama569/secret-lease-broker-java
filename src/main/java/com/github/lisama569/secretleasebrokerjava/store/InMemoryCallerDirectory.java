package com.github.lisama569.secretleasebrokerjava.store;

import com.github.lisama569.secretleasebrokerjava.error.ErrorCode;
import com.github.lisama569.secretleasebrokerjava.error.ServiceException;
import com.github.lisama569.secretleasebrokerjava.model.Caller;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 进程内调用方目录。
 */
@Component
public class InMemoryCallerDirectory implements CallerDirectory {

    private final ConcurrentHashMap<String, Caller> callers = new ConcurrentHashMap<>();

    @Override
    public Caller register(Caller caller) {
        if (caller == null || isBlank(caller.id())) {
            throw new ServiceException(ErrorCode.INVALID_PARAMETER, "callerId 不能为空");
        }
        // 重复注册视为幂等更新（便于本地演示重置属性）
        callers.put(caller.id(), caller);
        return caller;
    }

    @Override
    public Optional<Caller> find(String callerId) {
        return Optional.ofNullable(callers.get(callerId));
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
