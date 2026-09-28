package com.github.lisama569.secretleasebrokerjava.web.dto;

public record RenewLeaseRequest(String callerId, Long ttlMillis) {
}
