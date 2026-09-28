package com.github.lisama569.secretleasebrokerjava.web.dto;

public record RotateResponse(String path, long newVersion, String mode, int revokedLeaseCount) {
}
