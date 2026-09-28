package com.github.lisama569.secretleasebrokerjava.model;

import java.util.Map;

/**
 * 调用方身份：身份 ID + 身份属性（用于策略属性匹配）。
 */
public record Caller(String id, Map<String, String> attributes) {

    public Caller {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("caller id must not be blank");
        }
        attributes = Map.copyOf(attributes == null ? Map.of() : attributes);
    }
}
