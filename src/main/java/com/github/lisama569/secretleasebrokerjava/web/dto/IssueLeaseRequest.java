package com.github.lisama569.secretleasebrokerjava.web.dto;

public record IssueLeaseRequest(String callerId, String path, Long ttlMillis, String idempotencyKey) {
}
