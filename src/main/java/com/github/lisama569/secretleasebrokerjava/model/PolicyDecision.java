package com.github.lisama569.secretleasebrokerjava.model;

import java.util.List;

/**
 * 一次策略判定的结论及依据。
 * ruleId 为命中的规则 ID；默认拒绝时为 DEFAULT_DENY。
 * trace 为按优先级考察过的候选规则说明，用于解释判定过程。
 */
public record PolicyDecision(boolean allowed,
                             String ruleId,
                             String reason,
                             long policyVersion,
                             String callerId,
                             String path,
                             AccessAction action,
                             List<String> trace) {
}
