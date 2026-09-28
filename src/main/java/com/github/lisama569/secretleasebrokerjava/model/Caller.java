package com.github.lisama569.secretleasebrokerjava.model;

import java.util.Map;

/**
 * 调用方身份及其用于策略判定的属性（如 role、env、team）。
 *
 * @param id         稳定的调用方标识
 * @param attributes 身份属性，键值均非空字符串
 */
public record Caller(String id, Map<String, String> attributes) {

    public Caller {
        attributes = Map.copyOf(attributes);
    }
}
