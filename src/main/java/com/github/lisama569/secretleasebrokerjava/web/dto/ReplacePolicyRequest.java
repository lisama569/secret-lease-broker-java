package com.github.lisama569.secretleasebrokerjava.web.dto;

import java.util.List;

public record ReplacePolicyRequest(List<PolicyRuleRequest> rules) {
}
