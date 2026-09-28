package com.github.lisama569.secretleasebrokerjava.web.dto;

import java.util.List;

public record PolicyReplaceResponse(long policyVersion, int ruleCount, List<String> ruleIds) {
}
