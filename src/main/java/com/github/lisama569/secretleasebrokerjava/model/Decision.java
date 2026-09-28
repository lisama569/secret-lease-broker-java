package com.github.lisama569.secretleasebrokerjava.model;

/**
 * 一次策略判定的完整结论：是否允许、命中依据、策略版本与判定时间。
 * 默认拒绝：allowed=false 且 matchedRuleId 为 null 时表示没有任何规则放行。
 *
 * @param allowed       是否放行
 * @param action        被判定的操作
 * @param callerId      调用方
 * @param secretPath    密钥路径
 * @param matchedRuleId 命中并决定结论的规则 id；默认拒绝时为 null
 * @param effect        命中规则的效果；默认拒绝时为 null
 * @param reason        人类可读的解释
 * @param policyVersion 判定时使用的策略版本（单调递增）
 * @param decidedAt     判定时间（epoch millis）
 */
public record Decision(boolean allowed,
                       Action action,
                       String callerId,
                       String secretPath,
                       String matchedRuleId,
                       Effect effect,
                       String reason,
                       long policyVersion,
                       long decidedAt) {
}
