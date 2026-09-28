package com.github.lisama569.secretleasebrokerjava.web.dto;

import java.util.Map;

public record PolicyRuleRequest(String id,
                                String action,
                                String pathPattern,
                                Map<String, String> requiredAttributes,
                                String effect,
                                Integer priority) {
}
