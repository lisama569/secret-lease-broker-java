package com.github.lisama569.secretleasebrokerjava.model;

import java.util.Map;

/**
 * 一条策略规则。
 * 匹配条件：action 相等、密钥路径前缀匹配、调用方属性满足全部 attributeConstraints（等值匹配）。
 * 多条规则命中时按 priority 升序比较，数字越小优先级越高；同优先级拒绝（DENY）优先，
 * 仍相同则按 ruleId 字典序，保证冲突结论确定且可复现。
 *
 * @param ruleId               稳定规则标识，审计中作为命中依据
 * @param priority             优先级，数字越小越优先
 * @param effect               ALLOW / DENY
 * @param action               受控操作；null 表示同时适用于 ISSUE 与 RENEW
 * @param pathPrefix           密钥路径前缀，命中要求 secretPath 以该前缀开头
 * @param attributeConstraints 调用方属性必须全部满足的等值约束
 */
public record PolicyRule(String ruleId,
                         int priority,
                         Effect effect,
                         Action action,
                         String pathPrefix,
                         Map<String, String> attributeConstraints) {

    public PolicyRule {
        attributeConstraints = Map.copyOf(attributeConstraints);
    }
}
