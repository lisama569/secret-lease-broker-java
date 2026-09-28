package com.github.lisama569.secretleasebrokerjava.web.dto;

import java.util.List;

public record PolicySnapshotResponse(long policyVersion, List<PolicyRuleRequest> rules) {
}
