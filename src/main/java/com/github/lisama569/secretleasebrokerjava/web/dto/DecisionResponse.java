package com.github.lisama569.secretleasebrokerjava.web.dto;

import java.util.List;

public record DecisionResponse(boolean allowed,
                               String ruleId,
                               String reason,
                               long policyVersion,
                               String callerId,
                               String path,
                               String action,
                               List<String> trace) {
}
