package com.github.lisama569.secretleasebrokerjava.model;

import java.util.Map;

/**
 * 一条策略规则。
 * action 为 null 表示匹配任意操作；requiredAttributes 为调用方必须全部具备的属性；
 * priority 数值越小优先级越高，同值时按规则 ID 字典序，保证冲突判定确定。
 */
public record PolicyRule(String id,
                         AccessAction action,
                         String pathPattern,
                         Map<String, String> requiredAttributes,
                         Effect effect,
                         int priority) {

    public enum Effect { ALLOW, DENY }

    public PolicyRule {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("rule id must not be blank");
        }
        if (pathPattern == null || pathPattern.isBlank()) {
            throw new IllegalArgumentException("pathPattern must not be blank");
        }
        if (effect == null) {
            throw new IllegalArgumentException("effect must not be null");
        }
        requiredAttributes = Map.copyOf(requiredAttributes == null ? Map.of() : requiredAttributes);
    }
}
