package com.github.lisama569.secretleasebrokerjava.web.dto;

import java.util.Map;

public record RegisterCallerRequest(String callerId, Map<String, String> attributes) {
}
