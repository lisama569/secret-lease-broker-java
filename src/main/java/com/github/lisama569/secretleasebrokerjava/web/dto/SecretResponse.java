package com.github.lisama569.secretleasebrokerjava.web.dto;

public record SecretResponse(String path, long version, String value, String leaseId) {
}
